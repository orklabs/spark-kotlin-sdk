package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import io.grpc.Status
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import spark.Spark
import uniffi.spark_frost.*
import java.util.UUID

private const val HTLC_TIMELOCK_OFFSET: UInt = 70u
private const val DIRECT_HTLC_TIMELOCK_OFFSET: UInt = 85u
private const val LIGHTNING_HTLC_SEQUENCE: UInt = 2160u

/**
 * Create a BOLT-11 invoice through the SSP.
 *
 * The invoice the SSP returns is verified before any preimage share leaves the device: it must
 * carry our payment hash, the requested amount (none for [amountSats] `0`) and the wallet's
 * network, otherwise [SparkError.UntrustedResponse] is thrown.
 *
 * @param amountSats Invoice amount; `0` for an amountless invoice. Must not be negative.
 * @param memo Optional description, at most 639 UTF-8 bytes.
 * @param expirySecs Optional expiry; must be positive when given.
 */
public suspend fun SparkWallet.createLightningInvoice(amountSats: Long, memo: String? = null, expirySecs: Int? = null,): LightningInvoice {
    validateInvoiceRequest(amountSats, memo, expirySecs)
    val preimage = randomSecretKeyBytes()
    val paymentHash = sha256(preimage)
    val paymentHashHex = paymentHash.toHexString()

    val variables = mutableMapOf<String, Any>(
        "network" to config.network.networkGraphQL,
        "amount_sats" to amountSats,
        "payment_hash" to paymentHashHex,
    )
    if (expirySecs != null) variables["expiry_secs"] = expirySecs
    if (memo != null) variables["memo"] = memo

    val response = sspClient.executeRaw(
        query = GraphQLMutations.REQUEST_LIGHTNING_RECEIVE,
        variables = variables,
    )

    val invoice = response.optJSONObject("request_lightning_receive")
        ?.optJSONObject("request")
        ?.optJSONObject("invoice")
    val encodedInvoice = invoice?.stringOrNull("encoded_invoice")
    val expiresAtStr = invoice?.stringOrNull("expires_at")
    if (invoice == null || encodedInvoice == null || expiresAtStr == null) {
        throw SparkError.InvalidResponse("Invalid lightning receive response")
    }

    // The invoice we hand out must be the one we asked for: our payment hash, our amount,
    // our network. Checked before any preimage share leaves the device.
    val decodedInvoice = LightningValidator.verifyCreatedInvoice(
        encodedInvoice = encodedInvoice,
        reportedPaymentHashHex = invoice.stringOrNull("payment_hash"),
        expectedPaymentHash = paymentHash,
        expectedAmountSats = amountSats,
        network = config.network,
    )

    // Split the preimage and store one encrypted share with each operator.
    val shares = splitSecretWithProofsUniffi(preimage, config.signingThreshold, config.signingOperators.size.toUInt())
    val storeRequest = storePreimageShareRequest(
        paymentHash = paymentHash,
        shares = shares,
        encodedInvoice = encodedInvoice,
        identityPublicKey = signer.identityPublicKey,
        config = config,
    )
    getCoordinatorStub().storePreimageShareV2(storeRequest)

    return LightningInvoice(
        paymentRequest = encodedInvoice,
        paymentHash = paymentHashHex,
        amountSats = amountSats,
        expiresAt = parseISODateOrNull(expiresAtStr) ?: decodedInvoice.expiresAt,
    )
}

/** Reject invoice parameters the SSP would refuse or mis-handle, before anything is sent. */
internal fun validateInvoiceRequest(amountSats: Long, memo: String?, expirySecs: Int?) {
    if (amountSats < 0) {
        throw SparkError.InvalidArgument("amountSats must not be negative, got $amountSats")
    }
    if (expirySecs != null && expirySecs <= 0) {
        throw SparkError.InvalidArgument("expirySecs must be positive, got $expirySecs")
    }
    if (memo != null && memo.toByteArray(Charsets.UTF_8).size > 639) {
        throw SparkError.InvalidArgument("memo must be at most 639 bytes")
    }
}

/**
 * Pay a Lightning invoice via single-call `initiate_preimage_swap_v3` with a TransferPackage
 * (matching the JS reference SDK approach).
 *
 * Once the coordinator is asked to lock the leaves, the send runs to completion even if the
 * calling coroutine is cancelled: either the SSP request id is returned or
 * [SparkError.LightningSendIncomplete] carries the transfer id, so a locked transfer is never
 * left behind without it. The same error reports a preimage swap whose outcome is unknown (a
 * connection lost after the request went out, a deadline, an internal error).
 *
 * @param paymentRequest BOLT-11 invoice. Must be for the wallet's network
 *   ([SparkError.InvalidInvoice] otherwise) and carry a payment secret. It is sent on trimmed and
 *   in lower case.
 * @param maxFeeSats Highest routing fee the caller accepts. The SSP's fee estimate is fetched
 *   first and the payment is refused with [SparkError.FeeExceedsLimit] if it is higher; the
 *   estimate is offered as is, so `maxFeeSats = estimate` always goes through.
 * @param amountSats Amount to pay for an amountless invoice. Must be omitted (or equal) for an
 *   invoice that carries an amount.
 * @param idempotencyKey Optional key for deduplication. If the same key is used for multiple
 *   calls, the server returns the same result instead of creating duplicates. Without one the
 *   transfer id keys the preimage swap.
 * @param transferId Optional UUID to make the whole send resumable. On
 *   [SparkError.LightningSendIncomplete] call again with the same id (and the same invoice,
 *   amount and [idempotencyKey]): when the coordinator already holds that transfer, no leaf is
 *   selected or locked again — the held transfer must pay this invoice's payment hash with at
 *   most [maxFeeSats] on top — and the SSP is asked to pay from it. The SSP answers a repeated
 *   request for a transfer with the request it already has, so a send that went through returns
 *   its request id instead of paying twice.
 * @return The SSP lightning send request id.
 */
public suspend fun SparkWallet.payLightningInvoice(
    paymentRequest: String,
    maxFeeSats: Long,
    amountSats: Long? = null,
    idempotencyKey: String? = null,
    transferId: String? = null,
): String {
    val payment = LightningPayment(paymentRequest, maxFeeSats, amountSats, idempotencyKey, config.network)
    val resumeTransferId = LightningValidator.normalizeTransferId(transferId)

    // Resuming a send the coordinator already holds: its leaves are locked for this payment, so
    // selecting leaves again would come up short (or swap for nothing) and a second swap would be
    // refused. Check what it holds and have the SSP pay from that.
    if (resumeTransferId != null) {
        val held = heldLightningSend(resumeTransferId)
        if (held != null) {
            LightningValidator.verifyHeldSend(
                held,
                transferId = resumeTransferId,
                payment = payment,
                identityPublicKey = signer.identityPublicKey,
                sspIdentityPublicKey = config.requireSspIdentityPublicKey(),
            )
            return requestLightningSend(payment, resumeTransferId)
        }
    }
    val sendTransferId = resumeTransferId ?: UUID.randomUUID().toString().lowercase()
    val swapRequest = prepareLightningSend(payment, sendTransferId)

    // Never start locking leaves for a caller that is already cancelled. From here on the send
    // runs to completion regardless: the coordinator may lock the leaves as soon as it sees the
    // request, and the LightningSendIncomplete(transferId) contract needs the transfer id to
    // reach the caller. Both calls are bounded (60 s RPC deadline, OkHttp timeouts).
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        val transfer = submitPreimageSwap(swapRequest, preimageSwapIdempotencyKey(payment.idempotencyKey, sendTransferId))
        requestLightningSend(payment, transfer.id)
    }
}

/**
 * The Lightning send this wallet started under [transferId], as the coordinator holds it — its
 * HTLC (preimage request) with the transfer — or `null` when the coordinator holds none.
 */
internal suspend fun SparkWallet.heldLightningSend(transferId: String): Spark.PreimageRequestWithTransfer? {
    val request = Spark.QueryHtlcRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .addTransferIds(transferId)
        .setMatchRole(Spark.PreimageRequestRole.PREIMAGE_REQUEST_ROLE_SENDER)
        .setLimit(1)
        .build()
    return getCoordinatorStub().queryHtlc(request).preimageRequestsList.firstOrNull()
}

/**
 * Steps 1–3 of a Lightning send, before anything is submitted: quote the fee against the cap,
 * select leaves for amount + fee (swapping if needed), and build the `initiate_preimage_swap_v3`
 * request that hands them to the coordinator as an HTLC transfer to the SSP.
 */
internal suspend fun SparkWallet.prepareLightningSend(payment: LightningPayment, transferId: String): Spark.InitiatePreimageSwapRequest {
    val feeEstimate = getLightningSendFeeEstimate(encodedInvoice = payment.encodedInvoice, amountSats = payment.amountlessInvoiceAmountSats)
    val feeSats = LightningValidator.sendFeeSats(estimate = feeEstimate, maxFeeSats = payment.maxFeeSats)
    // Both are non-negative, so the sum can only overflow past Long.MAX_VALUE.
    if (payment.amountSats > Long.MAX_VALUE - feeSats) {
        throw SparkError.InvalidArgument("amount plus fee overflows")
    }

    val stub = getCoordinatorStub()
    // Select leaves covering invoice amount + fee (with swap if needed)
    val selectedLeaves = selectLeavesWithSwap(payment.amountSats + feeSats)
    val soOperators = stub.getSigningOperatorList(Empty.getDefaultInstance()).signingOperatorsMap

    return buildPreimageSwapRequest(
        stub = stub,
        paymentRequest = payment.encodedInvoice,
        paymentHash = payment.invoice.paymentHash,
        invoiceAmountSats = payment.amountSats,
        feeSats = feeSats,
        transferID = transferId,
        selectedLeaves = selectedLeaves,
        soOperators = soOperators,
    )
}

/**
 * Hand a Lightning send's preimage swap to the coordinator. A failure after which the coordinator
 * may still have committed the swap — leaves locked under the transfer id — surfaces as
 * [SparkError.LightningSendIncomplete] with that id, so the caller can resume instead of losing
 * track of the leaves until the transfer expires.
 */
internal suspend fun SparkWallet.submitPreimageSwap(request: Spark.InitiatePreimageSwapRequest, idempotencyKey: String): Spark.Transfer {
    val stub = getCoordinatorStubWithIdempotency(idempotencyKey)
    return try {
        stub.initiatePreimageSwapV3(request).transfer
    } catch (e: kotlin.Exception) {
        // Spelled out: `uniffi.spark_frost.*` brings its own `Exception` (FROST errors only).
        if (!preimageSwapMayHaveCommitted(e)) throw e
        throw SparkError.LightningSendIncomplete(
            transferId = request.transferRequest.transferId,
            reason = "the preimage swap's outcome is unknown: $e",
        )
    }
}

/**
 * Whether a failed `initiate_preimage_swap_v3` may still have been committed by the coordinator.
 * The statuses the operators give a request they refused before committing — validation,
 * authentication, a leaf or resource that is not available, a lock conflict — rule it out.
 * Anything else (a connection lost after the request went out, a deadline, a cancellation, an
 * internal or unknown error) does not.
 */
internal fun preimageSwapMayHaveCommitted(error: Throwable): Boolean {
    val status = error.grpcStatus ?: return true
    return status.code !in PREIMAGE_SWAP_REFUSALS
}

private val PREIMAGE_SWAP_REFUSALS = setOf(
    Status.Code.INVALID_ARGUMENT,
    Status.Code.FAILED_PRECONDITION,
    Status.Code.OUT_OF_RANGE,
    Status.Code.NOT_FOUND,
    Status.Code.ALREADY_EXISTS,
    Status.Code.PERMISSION_DENIED,
    Status.Code.UNAUTHENTICATED,
    Status.Code.RESOURCE_EXHAUSTED,
    Status.Code.ABORTED,
    Status.Code.UNIMPLEMENTED,
)

/**
 * The coordinator idempotency key of a Lightning send's preimage swap: the caller's key, else the
 * transfer id — never none. The coordinator answers a repeated key with the transfer it already
 * committed instead of running the swap again, so a transport retry of a swap whose answer was
 * lost, or a retry after [SparkError.LightningSendIncomplete], gets that transfer rather than a
 * duplicate-transfer rejection. The reference SDK always sends one (`idempotencyKey: transferId`).
 */
internal fun preimageSwapIdempotencyKey(idempotencyKey: String?, transferId: String): String = idempotencyKey ?: transferId

/**
 * Key tweaks and user-signed HTLC refunds in a TransferPackage, wrapped in the
 * `initiate_preimage_swap_v3` request that hands [selectedLeaves] to the SSP.
 */
private suspend fun SparkWallet.buildPreimageSwapRequest(
    stub: spark.SparkServiceGrpcKt.SparkServiceCoroutineStub,
    paymentRequest: String,
    paymentHash: ByteArray,
    invoiceAmountSats: Long,
    feeSats: Long,
    transferID: String,
    selectedLeaves: List<SparkLeaf>,
    soOperators: Map<String, Spark.SigningOperatorInfo>,
): Spark.InitiatePreimageSwapRequest {
    // receiverIdentityPubkey = SSP identity public key (matching JS SDK)
    val receiverPubKey = config.requireSspIdentityPublicKey()

    // Key tweaks plus user-signed HTLC refunds, in one TransferPackage
    val transferPackage = buildHtlcTransferPackage(
        stub = stub,
        transferID = transferID,
        selectedLeaves = selectedLeaves,
        paymentHash = paymentHash,
        receiverPubKey = receiverPubKey,
        soOperators = soOperators,
        networkStr = config.network.networkString,
    )

    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        // 16 days from now (matching JS SDK)
        .setExpiryTime(Timestamp.newBuilder().setSeconds((System.currentTimeMillis() / 1000) + 16 * 24 * 60 * 60))
        .setTransferPackage(transferPackage)
        .build()
    return preimageSwapRequest(
        paymentHash = paymentHash,
        invoiceAmountSats = invoiceAmountSats,
        bolt11Invoice = paymentRequest,
        feeSats = feeSats,
        transferRequest = transferRequest,
    )
}

/**
 * The `initiate_preimage_swap_v3` request of a Lightning send: the HTLC transfer to the SSP in
 * `transfer_request`, whose receiver the top-level receiver must equal. Only `transfer_request`:
 * the operators build the swap from it alone, and the legacy `transfer` field — plain, non-HTLC
 * refunds signed over to the SSP — is reserved in the current protocol; the reference SDK stopped
 * sending it in 0.9.0.
 */
internal fun preimageSwapRequest(
    paymentHash: ByteArray,
    invoiceAmountSats: Long,
    bolt11Invoice: String,
    feeSats: Long,
    transferRequest: Spark.StartTransferRequest,
): Spark.InitiatePreimageSwapRequest = Spark.InitiatePreimageSwapRequest.newBuilder()
    .setPaymentHash(ByteString.copyFrom(paymentHash))
    .setReason(Spark.InitiatePreimageSwapRequest.Reason.REASON_SEND)
    .setReceiverIdentityPublicKey(transferRequest.receiverIdentityPublicKey)
    .setFeeSats(feeSats)
    .setInvoiceAmount(
        Spark.InvoiceAmount.newBuilder()
            .setValueSats(invoiceAmountSats)
            .setInvoiceAmountProof(Spark.InvoiceAmountProof.newBuilder().setBolt11Invoice(bolt11Invoice)),
    )
    .setTransferRequest(transferRequest)
    .build()

/**
 * The `store_preimage_share_v2` request of a Lightning receive: each operator's share of the
 * preimage, ECIES-encrypted to its configured identity key. An operator validates the share at
 * its own index (`Index + 1`, which its identifier encodes), so each gets the share with that
 * index whatever the order of the configuration — the reference SDK's `shares[operator.id]`. No
 * `user_signature`: the current protocol reserves that field and the operators never read it
 * (reference SDK 0.6.5).
 */
internal fun storePreimageShareRequest(
    paymentHash: ByteArray,
    shares: List<VerifiableSecretShareResult>,
    encodedInvoice: String,
    identityPublicKey: ByteArray,
    config: SparkConfig,
): Spark.StorePreimageShareV2Request {
    val request = Spark.StorePreimageShareV2Request.newBuilder()
        .setPaymentHash(ByteString.copyFrom(paymentHash))
        .setThreshold(config.signingThreshold.toInt())
        .setInvoiceString(encodedInvoice)
        .setUserIdentityPublicKey(ByteString.copyFrom(identityPublicKey))
    for (soConfig in config.signingOperators) {
        val index = operatorShareIndex(soConfig.identifier)
        val share = shares.firstOrNull { it.index == index }
            ?: throw SparkError.InvalidArgument("no preimage share for operator ${soConfig.identifier}")
        val secretShareProto = Spark.SecretShare.newBuilder()
            .setSecretShare(ByteString.copyFrom(share.share))
            .addAllProofs(share.proofs.map { ByteString.copyFrom(it) })
            .build()
        val identityPubKey = soConfig.identityPublicKeyHex.hexToBytesOrNull()
        if (identityPubKey == null || identityPubKey.isEmpty()) {
            throw SparkError.InvalidArgument("operator ${soConfig.identifier} has no identity public key configured")
        }
        request.putEncryptedPreimageShares(soConfig.identifier, ByteString.copyFrom(encryptEcies(secretShareProto.toByteArray(), identityPubKey)))
    }
    return request.build()
}

/**
 * The secret-share index an operator validates its share at: its identifier, a 32-byte big-endian
 * number equal to its index + 1. `null` for anything else.
 */
internal fun operatorShareIndex(identifier: String): UInt? {
    if (identifier.length != 64 || !identifier.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return identifier.toUIntOrNull(16)?.takeIf { it > 0u }
}

/**
 * Steps 1-2 of a lightning send: the key tweaks handing the leaves to the SSP and the
 * user-signed HTLC refunds (cpfp, direct, directFromCpfp), packaged for the coordinator.
 */
private suspend fun SparkWallet.buildHtlcTransferPackage(
    stub: spark.SparkServiceGrpcKt.SparkServiceCoroutineStub,
    transferID: String,
    selectedLeaves: List<SparkLeaf>,
    paymentHash: ByteArray,
    receiverPubKey: ByteArray,
    soOperators: Map<String, Spark.SigningOperatorInfo>,
    networkStr: String,
): Spark.TransferPackage {
    val (_, tweakPackage) = KeyTweakHelper.buildSendPackage(
        transferID = transferID,
        leaves = selectedLeaves,
        receiverPubKey = receiverPubKey,
        signer = signer,
        soOperators = soOperators,
        signingOperatorConfigs = config.signingOperators,
        threshold = config.signingThreshold,
    )

    val htlcCommitmentsReq = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .addAllNodeIds(selectedLeaves.map { it.id })
        .build()
    val htlcCommitments = stub.getSigningCommitments(htlcCommitmentsReq).signingCommitmentsList
    if (htlcCommitments.size < 3 * selectedLeaves.size) {
        throw SparkError.InvalidResponse("Got ${htlcCommitments.size} signing commitments, need ${3 * selectedLeaves.size}")
    }

    val htlcJobs = buildHtlcSigningJobs(
        selectedLeaves = selectedLeaves,
        paymentHash = paymentHash,
        receiverPubKey = receiverPubKey,
        // sender identity public key (for HTLC seqlock destination)
        senderIdentityPubKey = signer.identityPublicKey,
        htlcCommitments = htlcCommitments,
        networkStr = networkStr,
    )

    val transferPackageBuilder = Spark.TransferPackage.newBuilder()
        .setUserSignature(ByteString.copyFrom(tweakPackage.signature))
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToSend(htlcJobs.cpfp)
        .addAllDirectLeavesToSend(htlcJobs.direct)
        .addAllDirectFromCpfpLeavesToSend(htlcJobs.directFromCpfp)
    for ((soID, cipher) in tweakPackage.keyTweakPackage) {
        transferPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }
    return transferPackageBuilder.build()
}

/**
 * Variables of the SSP's `request_lightning_send`. `amount_sats` is set for an amountless invoice
 * only — the SSP schema says it "should ONLY be set when the invoice amount is zero", and without
 * it the SSP cannot pay one (reference SDK, CHANGELOG 0.7.6). The SSP accepts either
 * `idempotency_key` or `user_outbound_transfer_external_id`, not both.
 */
internal fun lightningSendVariables(
    encodedInvoice: String,
    amountlessInvoiceAmountSats: Long?,
    idempotencyKey: String?,
    transferId: String,
): Map<String, Any> {
    val variables = mutableMapOf<String, Any>("encoded_invoice" to encodedInvoice)
    if (amountlessInvoiceAmountSats != null) variables["amount_sats"] = amountlessInvoiceAmountSats
    if (idempotencyKey != null) {
        variables["idempotency_key"] = idempotencyKey
    } else {
        variables["user_outbound_transfer_external_id"] = transferId
    }
    return variables
}

/**
 * Step 4 of a Lightning send: ask the SSP to pay the invoice from the transfer the coordinator
 * holds. The leaves are locked for that transfer by now, so any failure surfaces its id for the
 * app to resume (same `transferId`) or reconcile via the SSP; see [completeLightningSend].
 */
private suspend fun SparkWallet.requestLightningSend(payment: LightningPayment, transferId: String): String {
    val variables = lightningSendVariables(
        encodedInvoice = payment.encodedInvoice,
        amountlessInvoiceAmountSats = payment.amountlessInvoiceAmountSats,
        idempotencyKey = payment.idempotencyKey,
        transferId = transferId,
    )
    return completeLightningSend(transferId) {
        sspClient.executeRaw(query = GraphQLMutations.REQUEST_LIGHTNING_SEND, variables = variables)
    }
}

/**
 * Run the SSP step of a lightning send whose leaves the coordinator already holds under
 * [transferId], to completion: it is not interrupted when the calling coroutine is cancelled,
 * and any failure — including a response without a request id — is surfaced as
 * [SparkError.LightningSendIncomplete] carrying the transfer id, so the app can resume (same
 * `transferId`) or reconcile via the SSP. Swift reports a cancelled request the same way.
 */
internal suspend fun completeLightningSend(transferId: String, request: suspend () -> JSONObject): String = withContext(NonCancellable) {
    val sspResponse = try {
        request()
    } catch (e: kotlin.Exception) {
        // Spelled out: `uniffi.spark_frost.*` brings its own `Exception` (FROST errors only), which
        // made 0.2.1 let SSP and transport failures escape without the transfer id. Includes
        // CancellationException: nothing inside NonCancellable is cancelled by the caller, so one
        // can only come from the request itself — and it must not lose the id either.
        throw SparkError.LightningSendIncomplete(transferId = transferId, reason = e.message ?: e.toString())
    }
    sspResponse.optJSONObject("request_lightning_send")
        ?.optJSONObject("request")
        ?.stringOrNull("id")
        ?: throw SparkError.LightningSendIncomplete(
            transferId = transferId,
            reason = "invalid lightning send response from the SSP",
        )
}

/** HTLC refund signing jobs for a lightning send, one set (cpfp, direct, directFromCpfp) per leaf. */
private class HtlcSigningJobs(
    val cpfp: List<Spark.UserSignedTxSigningJob>,
    val direct: List<Spark.UserSignedTxSigningJob>,
    val directFromCpfp: List<Spark.UserSignedTxSigningJob>,
)

private fun SparkWallet.buildHtlcSigningJobs(
    selectedLeaves: List<SparkLeaf>,
    paymentHash: ByteArray,
    receiverPubKey: ByteArray,
    senderIdentityPubKey: ByteArray,
    htlcCommitments: List<Spark.RequestedSigningCommitments>,
    networkStr: String,
): HtlcSigningJobs {
    val htlcCpfpJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val htlcDirectJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val htlcDirectFromCpfpJobs = mutableListOf<Spark.UserSignedTxSigningJob>()

    for (i in selectedLeaves.indices) {
        val leaf = selectedLeaves[i]
        val node = leaf.node ?: throw SparkError.InvalidResponse("Leaf ${leaf.id} missing node data")
        val signingKey = signer.deriveLeafSigningKey(leaf.id)
        val verifyingKey = node.verifyingPublicKey.toByteArray()

        val cpfpComm = htlcCommitments[i].signingNonceCommitmentsMap
        val directComm = htlcCommitments[i + selectedLeaves.size].signingNonceCommitmentsMap
        val directFromCpfpComm = htlcCommitments[i + 2 * selectedLeaves.size].signingNonceCommitmentsMap

        val (htlcNextSequence, htlcDirectSequence) = htlcSequences(node.refundTx.toByteArray())

        // CPFP HTLC refund (no fee applied)
        val cpfpHtlc = constructHtlcTransaction(
            nodeTx = node.nodeTx.toByteArray(), vout = 0u,
            sequence = htlcNextSequence, paymentHash = paymentHash,
            hashlockPubkey = receiverPubKey, seqlockPubkey = senderIdentityPubKey,
            htlcSequence = LIGHTNING_HTLC_SEQUENCE,
            applyFee = false, feeSats = 0uL, network = networkStr,
        )
        htlcCpfpJobs.add(
            FrostSigningHelper.buildSigningJob(
                leafID = leaf.id,
                signingKey = signingKey,
                verifyingKey = verifyingKey,
                rawTx = cpfpHtlc.tx,
                sighash = cpfpHtlc.sighash,
                soCommitments = cpfpComm,
            )
        )

        // Direct HTLC refund (if directTx exists)
        if (!node.directTx.isEmpty) {
            val directHtlc = constructHtlcTransaction(
                nodeTx = node.directTx.toByteArray(), vout = 0u,
                sequence = htlcDirectSequence, paymentHash = paymentHash,
                hashlockPubkey = receiverPubKey, seqlockPubkey = senderIdentityPubKey,
                htlcSequence = LIGHTNING_HTLC_SEQUENCE,
                applyFee = true, feeSats = SPARK_DEFAULT_FEE_SATS.toULong(), network = networkStr,
            )
            htlcDirectJobs.add(
                FrostSigningHelper.buildSigningJob(
                    leafID = leaf.id,
                    signingKey = signingKey,
                    verifyingKey = verifyingKey,
                    rawTx = directHtlc.tx,
                    sighash = directHtlc.sighash,
                    soCommitments = directComm,
                )
            )
        }

        // DirectFromCpfp HTLC refund (always, from cpfp node tx)
        val directFromCpfpHtlc = constructHtlcTransaction(
            nodeTx = node.nodeTx.toByteArray(), vout = 0u,
            sequence = htlcDirectSequence, paymentHash = paymentHash,
            hashlockPubkey = receiverPubKey, seqlockPubkey = senderIdentityPubKey,
            htlcSequence = LIGHTNING_HTLC_SEQUENCE,
            applyFee = true, feeSats = SPARK_DEFAULT_FEE_SATS.toULong(), network = networkStr,
        )
        htlcDirectFromCpfpJobs.add(
            FrostSigningHelper.buildSigningJob(
                leafID = leaf.id,
                signingKey = signingKey,
                verifyingKey = verifyingKey,
                rawTx = directFromCpfpHtlc.tx,
                sighash = directFromCpfpHtlc.sighash,
                soCommitments = directFromCpfpComm,
            )
        )
    }
    return HtlcSigningJobs(cpfp = htlcCpfpJobs, direct = htlcDirectJobs, directFromCpfp = htlcDirectFromCpfpJobs)
}

/**
 * Sequences of a Lightning send's HTLC refunds: the current refund timelock minus 100, plus 70
 * for the CPFP HTLC and 85 for the direct ones — the reference SDK's
 * `getNextHTLCTransactionSequence`, and what the operators rebuild (refund sequence − 30 and − 15,
 * `lightning_handler.go`). Unlike transfer refunds these are NOT rounded down to the interval.
 * Spend paths only select leaves [isSpendable] allows, which keeps a leaf the operators would
 * refuse to let the receiver claim (rounded timelock at the floor) out.
 */
internal fun htlcSequences(refundTxData: ByteArray): Pair<UInt, UInt> {
    val rawSequence = parseSequenceFromRawTx(refundTxData)
    val currentTimelock = rawSequence and 0xFFFFu
    if (currentTimelock <= SPARK_TIME_LOCK_INTERVAL.toUInt()) {
        throw SparkError.LeafTimelockExhausted(
            "Leaf timelock exhausted ($currentTimelock <= $SPARK_TIME_LOCK_INTERVAL); needs renewal before it can pay",
        )
    }
    val nextTimelock = currentTimelock - SPARK_TIME_LOCK_INTERVAL.toUInt()
    val bit30 = rawSequence and (1u shl 30)
    return (bit30 or (nextTimelock + HTLC_TIMELOCK_OFFSET)) to (bit30 or (nextTimelock + DIRECT_HTLC_TIMELOCK_OFFSET))
}

/** Fee estimate in sats (rounded up) for an outbound lightning payment. */
public suspend fun SparkWallet.getLightningSendFeeEstimate(encodedInvoice: String, amountSats: Long? = null,): Long {
    val variables = mutableMapOf<String, Any>("encoded_invoice" to encodedInvoice)
    if (amountSats != null) variables["amount_sats"] = amountSats

    val response = sspClient.executeRaw(
        query = GraphQLQueries.LIGHTNING_SEND_FEE_ESTIMATE,
        variables = variables,
    )
    return lightningFeeEstimateSats(response)
}

/**
 * The SSP's lightning fee estimate in sats, in the unit the SSP reports it in (the reference SDK
 * switches on it too): SATOSHI as is, MILLISATOSHI rounded up; any other unit, or a value that is
 * not a whole non-negative number, is refused.
 */
internal fun lightningFeeEstimateSats(response: JSONObject): Long {
    val estimate = response.optJSONObject("lightning_send_fee_estimate")
        ?: throw SparkError.InvalidResponse("Invalid fee estimate response")
    return SspCurrencyAmount.sats(estimate.optJSONObject("fee_estimate"), field = "lightning fee estimate")
}
