package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import spark.Spark
import uniffi.spark_frost.*
import java.util.UUID

private const val HTLC_TIMELOCK_OFFSET: UInt = 70u
private const val DIRECT_HTLC_TIMELOCK_OFFSET: UInt = 85u
private const val LIGHTNING_HTLC_SEQUENCE: UInt = 2160u

suspend fun SparkWallet.createLightningInvoice(amountSats: Long, memo: String? = null, expirySecs: Int? = null,): LightningInvoice {
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

    val invoice = response.getJSONObject("request_lightning_receive")
        .getJSONObject("request")
        .getJSONObject("invoice")
    val encodedInvoice = invoice.getString("encoded_invoice")
    val expiresAtStr = invoice.getString("expires_at")

    // Split preimage and store encrypted shares with SOs
    val soConfigs = config.signingOperators
    val numOperators = soConfigs.size.toUInt()
    val threshold = (numOperators + 2u) / 2u

    val shares = splitSecretWithProofsUniffi(preimage, threshold, numOperators)

    val stub = getCoordinatorStub()

    @Suppress("DEPRECATION")
    val storeRequestBuilder = Spark.StorePreimageShareV2Request.newBuilder()
        .setPaymentHash(ByteString.copyFrom(paymentHash))
        .setThreshold(threshold.toInt())
        .setInvoiceString(encodedInvoice)
        .setUserIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))

    for (i in soConfigs.indices) {
        val soConfig = soConfigs[i]
        val share = shares[i]

        val secretShareProto = Spark.SecretShare.newBuilder()
            .setSecretShare(ByteString.copyFrom(share.share))
        for (proof in share.proofs) {
            secretShareProto.addProofs(ByteString.copyFrom(proof))
        }

        val shareBytes = secretShareProto.build().toByteArray()
        val identityPubKey = soConfig.identityPublicKeyHex.hexToByteArray()
        val encrypted = encryptEcies(shareBytes, identityPubKey)
        storeRequestBuilder.putEncryptedPreimageShares(soConfig.identifier, ByteString.copyFrom(encrypted))
    }

    // V2 request has user_signature reserved (removed) — no signing needed

    stub.storePreimageShareV2(storeRequestBuilder.build())

    val expiresAt = parseISODate(expiresAtStr)

    return LightningInvoice(
        paymentRequest = encodedInvoice,
        paymentHash = paymentHashHex,
        amountSats = amountSats,
        expiresAt = expiresAt,
    )
}

suspend fun SparkWallet.payLightningInvoice(paymentRequest: String, amountSats: Long? = null, idempotencyKey: String? = null,): String {
    val invoiceInfo = decodeBolt11PaymentHash(paymentRequest)
    val paymentHash = invoiceInfo.first
    val invoiceAmountSats = amountSats ?: invoiceInfo.second
        ?: throw SparkError.InvalidResponse("Invoice has no amount and amountSats not provided")

    val feeEstimate = getLightningSendFeeEstimate(
        encodedInvoice = paymentRequest,
        amountSats = if (invoiceInfo.second == null) amountSats else null,
    )
    val feeSats = maxOf(feeEstimate, 1L).toULong()

    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    val totalNeeded = invoiceAmountSats + feeSats.toLong()
    val selectedLeaves = selectLeavesWithSwap(totalNeeded)
    val leafIDs = selectedLeaves.map { it.id }

    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val soOperators = soListResponse.signingOperatorsMap

    val receiverPubKey = config.sspIdentityPublicKey
    val senderIdentityPubKey = signer.identityPublicKey
    val transferID = UUID.randomUUID().toString().lowercase()

    val expiryTime = Timestamp.newBuilder()
        .setSeconds((System.currentTimeMillis() / 1000) + 16 * 24 * 60 * 60)
        .build()

    // Step 1: Key tweaks for TransferPackage
    val (_, tweakPackage) = KeyTweakHelper.buildSendPackage(
        transferID = transferID,
        leaves = selectedLeaves,
        receiverPubKey = receiverPubKey,
        signer = signer,
        soOperators = soOperators,
        signingOperatorConfigs = config.signingOperators,
    )

    // Step 2: Signing commitments for HTLC refunds
    val htlcCommitmentsReq = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .addAllNodeIds(leafIDs)
        .build()
    val htlcCommitmentsResp = stub.getSigningCommitments(htlcCommitmentsReq)
    val htlcCommitments = htlcCommitmentsResp.signingCommitmentsList

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

        val htlcNextSequence = bit30 or (nextTimelock + HTLC_TIMELOCK_OFFSET)
        val htlcDirectSequence = bit30 or (nextTimelock + DIRECT_HTLC_TIMELOCK_OFFSET)

        // CPFP HTLC
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

        // Direct HTLC (if directTx exists)
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

        // DirectFromCpfp HTLC
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

    // Build TransferPackage
    val transferPackageBuilder = Spark.TransferPackage.newBuilder()
        .setUserSignature(ByteString.copyFrom(tweakPackage.signature))
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToSend(htlcCpfpJobs)
        .addAllDirectLeavesToSend(htlcDirectJobs)
        .addAllDirectFromCpfpLeavesToSend(htlcDirectFromCpfpJobs)
    for ((soID, cipher) in tweakPackage.keyTweakPackage) {
        transferPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }

    // Step 3: Signing commitments for swap transfer (regular refunds)
    val swapCommitmentsReq = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .addAllNodeIds(leafIDs)
        .build()
    val swapCommitmentsResp = stub.getSigningCommitments(swapCommitmentsReq)
    val swapCommitments = swapCommitmentsResp.signingCommitmentsList

    val swapCpfpJobs = mutableListOf<Spark.UserSignedTxSigningJob>()

    for (i in selectedLeaves.indices) {
        val leaf = selectedLeaves[i]
        val node = leaf.node!!
        val signingKey = signer.deriveLeafSigningKey(leaf.id)
        val verifyingKey = node.verifyingPublicKey.toByteArray()
        val cpfpComm = swapCommitments[i].signingNonceCommitmentsMap

        val (nextSequence, _) = computeNextSequences(node.refundTx.toByteArray())

        val cpfpRefund = constructRefundTx(
            tx = node.nodeTx.toByteArray(),
            vout = 0u,
            pubkey = receiverPubKey,
            network = networkStr,
            sequence = nextSequence,
        )
        swapCpfpJobs.add(
            FrostSigningHelper.buildSigningJob(
                leafID = leaf.id,
                signingKey = signingKey,
                verifyingKey = verifyingKey,
                rawTx = cpfpRefund.tx,
                sighash = cpfpRefund.sighash,
                soCommitments = cpfpComm,
            )
        )
    }

    // Step 4: initiate_preimage_swap_v3
    val invoiceAmountProto = Spark.InvoiceAmountProof.newBuilder()
        .setBolt11Invoice(paymentRequest)
        .build()
    val invoiceAmount = Spark.InvoiceAmount.newBuilder()
        .setValueSats(invoiceAmountSats.toULong().toLong())
        .setInvoiceAmountProof(invoiceAmountProto)
        .build()

    val transferField = Spark.StartUserSignedTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
        .addAllLeavesToSend(swapCpfpJobs)
        .build()

    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
        .setTransferPackage(transferPackageBuilder.build())
        .build()

    val swapRequest = Spark.InitiatePreimageSwapRequest.newBuilder()
        .setPaymentHash(ByteString.copyFrom(paymentHash))
        .setReason(Spark.InitiatePreimageSwapRequest.Reason.REASON_SEND)
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setFeeSats(feeSats.toLong())
        .setInvoiceAmount(invoiceAmount)
        .setTransfer(transferField)
        .setTransferRequest(transferRequest)
        .build()

    // Attach the idempotency key to the coordinator swap call (mirrors Swift's
    // metadataWithIdempotencyKey) so a retried pay can't re-initiate the swap.
    val swapStub = if (idempotencyKey != null) getCoordinatorStubWithIdempotency(idempotencyKey) else stub
    val swapResponse = swapStub.initiatePreimageSwapV3(swapRequest)

    // Step 5: SSP call with transfer external ID
    val sspVariables = mutableMapOf<String, Any>(
        "encoded_invoice" to paymentRequest,
    )
    if (idempotencyKey != null) {
        sspVariables["idempotency_key"] = idempotencyKey
    } else {
        sspVariables["user_outbound_transfer_external_id"] = swapResponse.transfer.id
    }
    val sspResponse = sspClient.executeRaw(
        query = GraphQLMutations.REQUEST_LIGHTNING_SEND,
        variables = sspVariables,
    )

    return sspResponse.getJSONObject("request_lightning_send")
        .getJSONObject("request")
        .getString("id")
}

suspend fun SparkWallet.getLightningSendFeeEstimate(encodedInvoice: String, amountSats: Long? = null,): Long {
    val variables = mutableMapOf<String, Any>("encoded_invoice" to encodedInvoice)
    if (amountSats != null) variables["amount_sats"] = amountSats

    val response = sspClient.executeRaw(
        query = GraphQLQueries.LIGHTNING_SEND_FEE_ESTIMATE,
        variables = variables,
    )

    val originalValue = response.getJSONObject("lightning_send_fee_estimate")
        .getJSONObject("fee_estimate")
        .getLong("original_value")

    // originalValue is in millisats, convert to sats (ceiling)
    return (originalValue + 999) / 1000
}

/** Decode BOLT11 invoice to extract payment hash and optional amount. */
fun decodeBolt11PaymentHash(invoice: String): Pair<ByteArray, Long?> {
    val lower = invoice.lowercase()
    require(lower.startsWith("lnbc") || lower.startsWith("lntb") || lower.startsWith("lnbcrt")) {
        "Not a valid BOLT11 invoice"
    }

    val separatorIndex = lower.lastIndexOf('1')
    require(separatorIndex >= 0) { "Invalid BOLT11 format" }

    val hrp = lower.substring(0, separatorIndex)
    val dataStr = lower.substring(separatorIndex + 1)
    require(dataStr.length > 6) { "BOLT11 data too short" }
    val dataWithoutChecksum = dataStr.dropLast(6)

    val bech32Chars = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    val bech32Values = bech32Chars.withIndex().associate { (i, c) -> c to i }

    val values = dataWithoutChecksum.map { ch ->
        bech32Values[ch] ?: throw SparkError.InvalidResponse("Invalid bech32 character: $ch")
    }

    require(values.size > 7) { "BOLT11 data too short for timestamp" }

    var pos = 7 // skip timestamp
    var paymentHash: ByteArray? = null

    while (pos + 3 <= values.size) {
        val fieldType = values[pos]
        val dataLength = values[pos + 1] * 32 + values[pos + 2]
        pos += 3
        if (pos + dataLength > values.size) break

        if (fieldType == 1) { // payment hash (p)
            paymentHash = Bech32.convertBits(
                values.subList(pos, pos + dataLength),
                fromBits = 5,
                toBits = 8,
                pad = false,
            )?.let { ints -> ByteArray(ints.size) { ints[it].toByte() } }
        }
        pos += dataLength
    }

    require(paymentHash != null && paymentHash.size == 32) { "Could not extract payment hash" }

    // Parse amount from HRP
    val amountPart = when {
        hrp.startsWith("lnbcrt") -> hrp.drop(6)
        hrp.startsWith("lnbc") -> hrp.drop(4)
        hrp.startsWith("lntb") -> hrp.drop(4)
        else -> ""
    }

    var amountSats: Long? = null
    if (amountPart.isNotEmpty()) {
        val multiplierChar = amountPart.last()
        val numberStr = amountPart.dropLast(1)
        val number = numberStr.toLongOrNull()
        if (number != null) {
            amountSats = when (multiplierChar) {
                'm' -> number * 100_000L
                'u' -> number * 100L
                'n' -> (number + 9) / 10
                'p' -> (number + 9999) / 10000
                else -> amountPart.toLongOrNull()?.let { it * 100_000_000L }
            }
        }
    }

    return paymentHash to amountSats
}
