package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
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

    // Split preimage and store encrypted shares with SOs using config-based identifiers/keys
    val soConfigs = config.signingOperators
    val numOperators = soConfigs.size.toUInt()
    val threshold = config.signingThreshold

    val shares = splitSecretWithProofsUniffi(preimage, threshold, numOperators)

    val stub = getCoordinatorStub()

    @Suppress("DEPRECATION")
    val storeRequestBuilder = Spark.StorePreimageShareV2Request.newBuilder()
        .setPaymentHash(ByteString.copyFrom(paymentHash))
        .setThreshold(threshold.toInt())
        .setInvoiceString(encodedInvoice)
        .setUserIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))

    // Match shares to operators by array index, encrypt to each SO's identity key
    for (i in soConfigs.indices) {
        val soConfig = soConfigs[i]
        val share = shares[i]

        val secretShareProto = Spark.SecretShare.newBuilder()
            .setSecretShare(ByteString.copyFrom(share.share))
        for (proof in share.proofs) {
            secretShareProto.addProofs(ByteString.copyFrom(proof))
        }

        val shareBytes = secretShareProto.build().toByteArray()
        val identityPubKey = soConfig.identityPublicKeyHex.hexToBytesOrNull()
        if (identityPubKey == null || identityPubKey.isEmpty()) {
            throw SparkError.InvalidArgument("operator ${soConfig.identifier} has no identity public key configured")
        }
        val encrypted = encryptEcies(shareBytes, identityPubKey)
        storeRequestBuilder.putEncryptedPreimageShares(soConfig.identifier, ByteString.copyFrom(encrypted))
    }

    // V2 request has user_signature reserved (removed) — no signing needed

    stub.storePreimageShareV2(storeRequestBuilder.build())

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
 * left behind without it.
 *
 * @param paymentRequest BOLT-11 invoice. Must be for the wallet's network
 *   ([SparkError.InvalidInvoice] otherwise).
 * @param maxFeeSats Highest routing fee the caller accepts. The SSP's fee estimate is fetched
 *   first and the payment is refused with [SparkError.FeeExceedsLimit] if it is higher.
 * @param amountSats Amount to pay for an amountless invoice. Must be omitted (or equal) for an
 *   invoice that carries an amount.
 * @param idempotencyKey Optional key for deduplication. If the same key is used for multiple
 *   calls, the server returns the same result instead of creating duplicates.
 * @param transferId Optional UUID to make the whole send resumable. On
 *   [SparkError.LightningSendIncomplete] call again with the same id: when the coordinator
 *   already holds a transfer with this id — ours, to the SSP, covering the invoice amount plus a
 *   fee within [maxFeeSats] — the SDK goes straight back to the SSP without selecting or locking
 *   any other leaves.
 * @return The SSP lightning send request id.
 */
public suspend fun SparkWallet.payLightningInvoice(
    paymentRequest: String,
    maxFeeSats: Long,
    amountSats: Long? = null,
    idempotencyKey: String? = null,
    transferId: String? = null,
): String {
    val payment = checkLightningPayment(paymentRequest, maxFeeSats, amountSats, transferId)
    val resumeTransferId = payment.resumeTransferId

    // Resuming: a transfer the coordinator already holds under this id is the one an earlier call
    // locked. Its leaves are TRANSFER_LOCKED, so selecting again would fail or swap other leaves.
    if (resumeTransferId != null) {
        val existing = queryTransferByIdOrNull(resumeTransferId)
        if (canResumeLightningSend(existing, signer.identityPublicKey, config.requireSspIdentityPublicKey(), payment.amountSats, maxFeeSats)) {
            return requestLightningSend(lightningSendVariables(paymentRequest, idempotencyKey, resumeTransferId), resumeTransferId)
        }
    }

    val feeSats = lightningSendFee(paymentRequest, payment, maxFeeSats)
    val stub = getCoordinatorStub()

    // Select leaves covering invoice amount + fee (with swap if needed)
    val selectedLeaves = selectLeavesWithSwap(payment.amountSats + feeSats)
    val soOperators = stub.getSigningOperatorList(Empty.getDefaultInstance()).signingOperatorsMap
    val transferID = resumeTransferId ?: UUID.randomUUID().toString().lowercase()

    val swapRequest = buildPreimageSwapRequest(
        stub = stub,
        paymentRequest = paymentRequest,
        paymentHash = payment.invoice.paymentHash,
        invoiceAmountSats = payment.amountSats,
        feeSats = feeSats,
        transferID = transferID,
        selectedLeaves = selectedLeaves,
        soOperators = soOperators,
    )

    // A caller-supplied transfer id doubles as the coordinator idempotency key, so a retry
    // after a partial failure resumes the existing swap instead of starting a second one.
    val coordinatorIdempotencyKey = idempotencyKey ?: resumeTransferId
    val swapStub = if (coordinatorIdempotencyKey != null) getCoordinatorStubWithIdempotency(coordinatorIdempotencyKey) else stub

    // Never start locking leaves for a caller that is already cancelled. From here on the send
    // runs to completion regardless: the coordinator may lock the leaves as soon as it sees the
    // request, and Swift's lightningSendIncomplete(transferId) contract needs the transfer id to
    // reach the caller. Both calls are bounded (60 s RPC deadline, OkHttp timeouts).
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        val swapResponse = swapStub.initiatePreimageSwapV3(swapRequest)
        requestLightningSend(lightningSendVariables(paymentRequest, idempotencyKey, swapResponse.transfer.id), swapResponse.transfer.id)
    }
}

/**
 * Steps 1-3 of a lightning send, before anything is submitted: key tweaks and user-signed HTLC
 * refunds in a TransferPackage, wrapped in the `initiate_preimage_swap_v3` request.
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
    val networkStr = config.network.networkString
    // receiverIdentityPubkey = SSP identity public key (matching JS SDK)
    val receiverPubKey = config.requireSspIdentityPublicKey()

    // Single shared expiry time — 16 days from now (matching JS SDK)
    val expiryTime = Timestamp.newBuilder()
        .setSeconds((System.currentTimeMillis() / 1000) + 16 * 24 * 60 * 60)
        .build()

    // Steps 1-2: key tweaks plus user-signed HTLC refunds, in one TransferPackage
    val transferPackage = buildHtlcTransferPackage(
        stub = stub,
        transferID = transferID,
        selectedLeaves = selectedLeaves,
        paymentHash = paymentHash,
        receiverPubKey = receiverPubKey,
        soOperators = soOperators,
        networkStr = networkStr,
    )

    // Step 3: the initiate_preimage_swap_v3 request
    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
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

/** A lightning payment that passed the client-side checks that need no network call. */
private class CheckedLightningPayment(val invoice: Bolt11Invoice, val amountSats: Long, val resumeTransferId: String?)

/** The fee cap, the invoice's checksum, network and amount, and the resume id — checked before any call is made. */
private fun SparkWallet.checkLightningPayment(paymentRequest: String, maxFeeSats: Long, amountSats: Long?, transferId: String?): CheckedLightningPayment {
    if (maxFeeSats < 0) {
        throw SparkError.InvalidArgument("maxFeeSats must not be negative, got $maxFeeSats")
    }
    val invoice = Bolt11Invoice.decode(paymentRequest)
    if (!invoice.belongsTo(config.network)) {
        throw SparkError.InvalidInvoice("invoice is for ${invoice.network.label}, wallet is on ${config.network.networkString}")
    }
    val invoiceAmountSats = LightningValidator.resolvePaymentAmountSats(
        invoiceAmountMsat = invoice.amountMsat,
        requestedAmountSats = amountSats,
    )
    return CheckedLightningPayment(invoice, invoiceAmountSats, LightningValidator.normalizeTransferId(transferId))
}

/** The routing fee for a new send: the SSP's estimate (at least 1 sat), refused above the caller's cap. */
private suspend fun SparkWallet.lightningSendFee(paymentRequest: String, payment: CheckedLightningPayment, maxFeeSats: Long): Long {
    val feeEstimate = getLightningSendFeeEstimate(
        encodedInvoice = paymentRequest,
        amountSats = if (payment.invoice.amountMsat == null) payment.amountSats else null,
    )
    val feeSats = maxOf(feeEstimate, 1L)
    if (feeSats > maxFeeSats) {
        throw SparkError.FeeExceedsLimit(feeSats = feeSats, maxFeeSats = maxFeeSats)
    }
    // Both are positive, so the sum can only overflow past Long.MAX_VALUE.
    if (payment.amountSats > Long.MAX_VALUE - feeSats) {
        throw SparkError.InvalidArgument("amount plus fee overflows")
    }
    return feeSats
}

/**
 * Whether [existing] — what the coordinator holds under a caller's resume `transferId` — is a
 * lightning send this wallet already started for this payment, so the SSP step can be retried
 * without touching any leaf. `null` (nothing under that id yet) means a new send.
 *
 * A transfer that is not an outgoing preimage swap from this wallet to the SSP, whose leaves were
 * returned, or whose value does not cover the invoice amount plus a fee within [maxFeeSats] is
 * refused: resuming it could only pay the wrong thing, and starting over under the same id would
 * hand the coordinator's idempotency key to another request.
 */
internal fun canResumeLightningSend(
    existing: Spark.Transfer?,
    ownIdentityPublicKey: ByteArray,
    sspIdentityPublicKey: ByteArray,
    invoiceAmountSats: Long,
    maxFeeSats: Long,
): Boolean {
    if (existing == null) return false
    val id = existing.id
    if (!existing.senderIdentityPublicKey.toByteArray().contentEquals(ownIdentityPublicKey) ||
        !existing.receiverIdentityPublicKey.toByteArray().contentEquals(sspIdentityPublicKey) ||
        existing.type != Spark.TransferType.PREIMAGE_SWAP
    ) {
        throw SparkError.InvalidArgument("transfer $id is not a lightning payment from this wallet; use a new transferId")
    }
    if (existing.status == Spark.TransferStatus.TRANSFER_STATUS_EXPIRED || existing.status == Spark.TransferStatus.TRANSFER_STATUS_RETURNED) {
        throw SparkError.InvalidArgument("transfer $id is ${existing.status} and its leaves were returned; start a new payment with a new transferId")
    }
    // total_value is uint64: anything that reads as negative is far beyond any real payment.
    val total = existing.totalValue
    if (total < invoiceAmountSats) {
        throw SparkError.InvalidArgument("transfer $id holds $total sats, less than the invoice amount of $invoiceAmountSats sats")
    }
    val feeSats = total - invoiceAmountSats
    if (feeSats > maxFeeSats) {
        throw SparkError.FeeExceedsLimit(feeSats = feeSats, maxFeeSats = maxFeeSats)
    }
    return true
}

/** The `request_lightning_send` variables: the SSP takes either an idempotency key or the transfer's external id, not both. */
internal fun lightningSendVariables(paymentRequest: String, idempotencyKey: String?, transferId: String): Map<String, Any> = if (idempotencyKey != null) {
    mapOf("encoded_invoice" to paymentRequest, "idempotency_key" to idempotencyKey)
} else {
    mapOf("encoded_invoice" to paymentRequest, "user_outbound_transfer_external_id" to transferId)
}

/** The SSP half of a lightning send: `request_lightning_send` for [transferId], see [completeLightningSend]. */
private suspend fun SparkWallet.requestLightningSend(variables: Map<String, Any>, transferId: String): String = completeLightningSend(transferId) {
    sspClient.executeRaw(query = GraphQLMutations.REQUEST_LIGHTNING_SEND, variables = variables)
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
