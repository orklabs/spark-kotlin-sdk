package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import kotlinx.coroutines.CancellationException
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
 * @param paymentRequest BOLT-11 invoice. Must be for the wallet's network
 *   ([SparkError.InvalidInvoice] otherwise).
 * @param maxFeeSats Highest routing fee the caller accepts. The SSP's fee estimate is fetched
 *   first and the payment is refused with [SparkError.FeeExceedsLimit] if it is higher.
 * @param amountSats Amount to pay for an amountless invoice. Must be omitted (or equal) for an
 *   invoice that carries an amount.
 * @param idempotencyKey Optional key for deduplication. If the same key is used for multiple
 *   calls, the server returns the same result instead of creating duplicates.
 * @param transferId Optional UUID to make the whole send resumable. On
 *   [SparkError.LightningSendIncomplete] call again with the same id: the coordinator returns
 *   the transfer it already holds instead of locking more leaves.
 * @return The SSP lightning send request id.
 */
public suspend fun SparkWallet.payLightningInvoice(
    paymentRequest: String,
    maxFeeSats: Long,
    amountSats: Long? = null,
    idempotencyKey: String? = null,
    transferId: String? = null,
): String {
    val payment = prepareLightningPayment(paymentRequest, maxFeeSats, amountSats, transferId)
    val paymentHash = payment.invoice.paymentHash
    val invoiceAmountSats = payment.amountSats
    val feeSats = payment.feeSats
    val resumeTransferId = payment.resumeTransferId

    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    // Select leaves covering invoice amount + fee (with swap if needed)
    val selectedLeaves = selectLeavesWithSwap(payment.totalNeeded)
    val leafIDs = selectedLeaves.map { it.id }

    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val soOperators = soListResponse.signingOperatorsMap

    // receiverIdentityPubkey = SSP identity public key (matching JS SDK)
    val receiverPubKey = config.sspIdentityPublicKey
    val transferID = resumeTransferId ?: UUID.randomUUID().toString().lowercase()

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

    // Step 3: Signing commitments and regular cpfp refunds for the swap transfer field
    val swapCommitmentsReq = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .addAllNodeIds(leafIDs)
        .build()
    val swapCommitments = stub.getSigningCommitments(swapCommitmentsReq).signingCommitmentsList
    if (swapCommitments.size < selectedLeaves.size) {
        throw SparkError.InvalidResponse("Got ${swapCommitments.size} signing commitments, need ${selectedLeaves.size}")
    }
    val swapCpfpJobs = buildSwapRefundJobs(
        selectedLeaves = selectedLeaves,
        receiverPubKey = receiverPubKey,
        swapCommitments = swapCommitments,
        networkStr = networkStr,
    )

    // Step 4: initiate_preimage_swap_v3
    val invoiceAmount = Spark.InvoiceAmount.newBuilder()
        .setValueSats(invoiceAmountSats)
        .setInvoiceAmountProof(Spark.InvoiceAmountProof.newBuilder().setBolt11Invoice(paymentRequest).build())
        .build()

    // transfer field (field 4): only cpfp regular refund jobs (direct/directFromCpfp undefined when transferRequest exists)
    val transferField = Spark.StartUserSignedTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
        .addAllLeavesToSend(swapCpfpJobs)
        .build()

    // transferRequest field (field 7): full StartTransferRequest with HTLC TransferPackage
    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
        .setTransferPackage(transferPackage)
        .build()

    val swapRequest = Spark.InitiatePreimageSwapRequest.newBuilder()
        .setPaymentHash(ByteString.copyFrom(paymentHash))
        .setReason(Spark.InitiatePreimageSwapRequest.Reason.REASON_SEND)
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setFeeSats(feeSats)
        .setInvoiceAmount(invoiceAmount)
        .setTransfer(transferField)
        .setTransferRequest(transferRequest)
        .build()

    // A caller-supplied transfer id doubles as the coordinator idempotency key, so a retry
    // after a partial failure resumes the existing swap instead of starting a second one.
    val coordinatorIdempotencyKey = idempotencyKey ?: resumeTransferId
    val swapStub = if (coordinatorIdempotencyKey != null) getCoordinatorStubWithIdempotency(coordinatorIdempotencyKey) else stub
    val swapResponse = swapStub.initiatePreimageSwapV3(swapRequest)

    // Step 5: SSP call with transfer external ID.
    // SSP accepts either idempotency_key or user_outbound_transfer_external_id, not both.
    // When an idempotency key is provided, use it; otherwise use the transfer external ID.
    val sspVariables = mutableMapOf<String, Any>(
        "encoded_invoice" to paymentRequest,
    )
    if (idempotencyKey != null) {
        sspVariables["idempotency_key"] = idempotencyKey
    } else {
        sspVariables["user_outbound_transfer_external_id"] = swapResponse.transfer.id
    }

    return requestLightningSend(sspVariables, transferId = swapResponse.transfer.id)
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

/** A lightning payment that passed every client-side check, ready to lock leaves for. */
private class LightningPayment(val invoice: Bolt11Invoice, val amountSats: Long, val feeSats: Long, val totalNeeded: Long, val resumeTransferId: String?,)

/**
 * Everything `payLightningInvoice` checks before a leaf is touched: the fee cap, the invoice's
 * checksum, network and amount, the resume id, and the SSP's fee estimate against the cap.
 */
private suspend fun SparkWallet.prepareLightningPayment(paymentRequest: String, maxFeeSats: Long, amountSats: Long?, transferId: String?,): LightningPayment {
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
    val resumeTransferId = LightningValidator.normalizeTransferId(transferId)

    // Get fee estimate from SSP and refuse anything above the caller's cap.
    val feeEstimate = getLightningSendFeeEstimate(
        encodedInvoice = paymentRequest,
        amountSats = if (invoice.amountMsat == null) invoiceAmountSats else null,
    )
    val feeSats = maxOf(feeEstimate, 1L)
    if (feeSats > maxFeeSats) {
        throw SparkError.FeeExceedsLimit(feeSats = feeSats, maxFeeSats = maxFeeSats)
    }
    // Both are positive, so the sum can only overflow past Long.MAX_VALUE.
    if (invoiceAmountSats > Long.MAX_VALUE - feeSats) {
        throw SparkError.InvalidArgument("amount plus fee overflows")
    }
    return LightningPayment(
        invoice = invoice,
        amountSats = invoiceAmountSats,
        feeSats = feeSats,
        totalNeeded = invoiceAmountSats + feeSats,
        resumeTransferId = resumeTransferId,
    )
}

/**
 * The SSP half of a lightning send. By now the coordinator holds the leaves for [transferId]:
 * a failure is surfaced as [SparkError.LightningSendIncomplete] so the app can resume (same
 * `transferId`) or reconcile via the SSP.
 */
private suspend fun SparkWallet.requestLightningSend(variables: Map<String, Any>, transferId: String): String {
    val sspResponse = try {
        sspClient.executeRaw(
            query = GraphQLMutations.REQUEST_LIGHTNING_SEND,
            variables = variables,
        )
    } catch (e: CancellationException) {
        // Unlike Swift (which reports any error here), cancellation is never swallowed in Kotlin:
        // it must reach the caller's scope. The transfer can still be reconciled via the SSP.
        throw e
    } catch (e: Exception) {
        throw SparkError.LightningSendIncomplete(transferId = transferId, reason = e.message ?: e.toString())
    }

    return sspResponse.optJSONObject("request_lightning_send")
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

        val (cpfpSeq, _) = computeNextSequences(node.refundTx.toByteArray())
        val bit30 = cpfpSeq and (1u shl 30)
        val nextTimelock = cpfpSeq and 0xFFFFu

        // HTLC sequences (matching JS SDK getNextHTLCTransactionSequence)
        val htlcNextSequence = bit30 or (nextTimelock + HTLC_TIMELOCK_OFFSET)
        val htlcDirectSequence = bit30 or (nextTimelock + DIRECT_HTLC_TIMELOCK_OFFSET)

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

/** Regular cpfp refund signing jobs for the swap transfer field of a lightning send. */
private fun SparkWallet.buildSwapRefundJobs(
    selectedLeaves: List<SparkLeaf>,
    receiverPubKey: ByteArray,
    swapCommitments: List<Spark.RequestedSigningCommitments>,
    networkStr: String,
): List<Spark.UserSignedTxSigningJob> = selectedLeaves.mapIndexed { i, leaf ->
    val node = leaf.node ?: throw SparkError.InvalidResponse("Leaf ${leaf.id} missing node data")
    val signingKey = signer.deriveLeafSigningKey(leaf.id)
    val (nextSequence, _) = computeNextSequences(node.refundTx.toByteArray())
    val cpfpRefund = constructRefundTx(
        tx = node.nodeTx.toByteArray(),
        vout = 0u,
        pubkey = receiverPubKey,
        network = networkStr,
        sequence = nextSequence,
    )
    FrostSigningHelper.buildSigningJob(
        leafID = leaf.id,
        signingKey = signingKey,
        verifyingKey = node.verifyingPublicKey.toByteArray(),
        rawTx = cpfpRefund.tx,
        sighash = cpfpRefund.sighash,
        soCommitments = swapCommitments[i].signingNonceCommitmentsMap,
    )
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
 * The SSP's lightning fee estimate, in whole sats rounded up. `original_value` is millisatoshi
 * and must be a whole, non-negative number; a value whose rounding would overflow is refused
 * instead of wrapping to a negative fee (which `payLightningInvoice` would then raise to 1 sat).
 */
internal fun lightningFeeEstimateSats(response: JSONObject): Long {
    val msat = response.optJSONObject("lightning_send_fee_estimate")
        ?.optJSONObject("fee_estimate")
        ?.let { wholeNonNegativeLong(it.opt("original_value")) }
        ?: throw SparkError.InvalidResponse("Invalid fee estimate response")
    return try {
        Math.addExact(msat, 999L) / 1000
    } catch (_: ArithmeticException) {
        throw SparkError.UntrustedResponse("SSP fee estimate of $msat msat is out of range")
    }
}
