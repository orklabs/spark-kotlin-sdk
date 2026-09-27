package gy.pig.spark

import com.google.protobuf.ByteString
import okhttp3.RequestBody.Companion.toRequestBody
import spark.Spark
import uniffi.spark_frost.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

public suspend fun SparkWallet.getDepositAddress(): DepositAddress {
    val stub = getCoordinatorStub()

    // A one-time deposit address is keyed to a fresh leaf: derive its signing
    // key pair from a new UUID and register that key (not the identity key) so
    // the resulting P2TR address can later be claimed via deriveLeafSigningKey.
    // Mirrors spark-swift-sdk DepositService.getDepositAddress().
    val leafId = UUID.randomUUID().toString().lowercase()
    val (_, leafPublicKey) = signer.deriveLeafSigningKeyPair(leafId)

    val request = Spark.GenerateDepositAddressRequest.newBuilder()
        .setNetwork(config.network.toProto())
        .setSigningPublicKey(ByteString.copyFrom(leafPublicKey))
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setLeafId(leafId)
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .build()

    val response = stub.generateDepositAddress(request)
    val addr = response.depositAddress

    return DepositAddress(
        address = addr.address,
        leafId = leafId,
        userPublicKey = leafPublicKey,
        verifyingKey = addr.verifyingKey.toByteArray(),
    )
}

public suspend fun SparkWallet.getStaticDepositAddress(): StaticDepositAddress {
    val stub = getCoordinatorStub()

    val request = Spark.GenerateStaticDepositAddressRequest.newBuilder()
        .setNetwork(config.network.toProto())
        .setSigningPublicKey(ByteString.copyFrom(signer.depositPublicKey))
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .build()

    val response = stub.generateStaticDepositAddress(request)

    return StaticDepositAddress(
        address = response.depositAddress.address,
        verifyingKey = response.depositAddress.verifyingKey.toByteArray(),
    )
}

public suspend fun SparkWallet.queryUnusedDepositAddresses(limit: Int = 100, offset: Int = 0,): List<UnusedDepositAddress> {
    val stub = getCoordinatorStub()

    val request = Spark.QueryUnusedDepositAddressesRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .setLimit(limit.toLong())
        .setOffset(offset.toLong())
        .build()

    val response = stub.queryUnusedDepositAddresses(request)

    return response.depositAddressesList.map { deposit ->
        UnusedDepositAddress(
            address = deposit.depositAddress,
            leafId = deposit.leafId,
            userSigningPublicKey = deposit.userSigningPublicKey.toByteArray(),
            verifyingPublicKey = deposit.verifyingPublicKey.toByteArray(),
        )
    }
}

public suspend fun SparkWallet.getDepositFeeEstimate(transactionId: String, outputIndex: UInt = 0u,): DepositFeeEstimate {
    val result = sspClient.executeRaw(
        query = GraphQLQueries.STATIC_DEPOSIT_QUOTE,
        variables = mapOf(
            "transaction_id" to transactionId,
            "output_index" to outputIndex.toInt(),
            "network" to config.network.networkGraphQL,
        ),
    )

    // Strict like Swift's `as? Int64` / `as? String`: the credit amount is signed back to the SSP.
    val quote = result.optJSONObject("static_deposit_quote")
    val creditAmountSats = quote?.let { wholeNonNegativeLong(it.opt("credit_amount_sats")) }
    val quoteSignature = quote?.stringOrNull("signature")
    if (creditAmountSats == null || quoteSignature == null) {
        throw SparkError.InvalidResponse("Invalid static deposit quote response")
    }
    return DepositFeeEstimate(creditAmountSats = creditAmountSats, quoteSignature = quoteSignature)
}

public suspend fun SparkWallet.claimStaticDeposit(transactionId: String, outputIndex: UInt = 0u,): String {
    val feeEstimate = getDepositFeeEstimate(transactionId, outputIndex)

    // Build signing payload matching Swift SDK
    val staticSecretKey = signer.deriveStaticDepositKey(0)
    val depositSecretKeyHex = staticSecretKey.toHexString()

    val payload = java.io.ByteArrayOutputStream()
    payload.write("claim_static_deposit".toByteArray(Charsets.UTF_8))
    payload.write(config.network.networkGraphQL.lowercase().toByteArray(Charsets.UTF_8))
    payload.write(transactionId.toByteArray(Charsets.UTF_8))
    payload.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(outputIndex.toInt()).array())
    payload.write(0) // requestType = Fixed
    payload.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(feeEstimate.creditAmountSats).array())
    val sigBytes = feeEstimate.quoteSignature.hexToBytesOrNull() ?: feeEstimate.quoteSignature.toByteArray(Charsets.UTF_8)
    payload.write(sigBytes)

    val payloadHash = sha256(payload.toByteArray())
    val signature = signer.signWithIdentityKey(payloadHash)

    val result = sspClient.executeRaw(
        query = GraphQLMutations.CLAIM_STATIC_DEPOSIT,
        variables = mapOf(
            "transaction_id" to transactionId,
            "output_index" to outputIndex.toInt(),
            "network" to config.network.networkGraphQL,
            "request_type" to "FIXED_AMOUNT",
            "credit_amount_sats" to feeEstimate.creditAmountSats,
            "deposit_secret_key" to depositSecretKeyHex,
            "signature" to signature.toHexString(),
            "quote_signature" to feeEstimate.quoteSignature,
        ),
    )

    return result.getJSONObject("claim_static_deposit").getString("transfer_id")
}

public suspend fun SparkWallet.queryStaticDepositAddresses(): List<StaticDepositAddress> {
    val stub = getCoordinatorStub()

    val request = Spark.QueryStaticDepositAddressesRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .build()

    val response = stub.queryStaticDepositAddresses(request)

    return response.depositAddressesList.map { deposit ->
        StaticDepositAddress(
            address = deposit.depositAddress,
            verifyingKey = deposit.verifyingPublicKey.toByteArray(),
        )
    }
}

public data class DepositUtxo(val txid: String, val vout: UInt,)

public suspend fun SparkWallet.getUtxosForDepositAddress(address: String, excludeClaimed: Boolean = true,): List<DepositUtxo> {
    val stub = getCoordinatorStub()

    val request = Spark.GetUtxosForAddressRequest.newBuilder()
        .setAddress(address)
        .setNetwork(config.network.toProto())
        .setExcludeClaimed(excludeClaimed)
        .build()

    val response = stub.getUtxosForAddress(request)

    return response.utxosList.map { utxo ->
        DepositUtxo(
            txid = utxo.txid.toByteArray().toHexString(),
            vout = utxo.vout.toUInt(),
        )
    }
}

public suspend fun SparkWallet.claimStaticDepositWithMaxFee(transactionId: String, maxFee: Long, outputIndex: UInt = 0u,): String? {
    val quote = getDepositFeeEstimate(transactionId, outputIndex)

    val rawTx = fetchRawTransaction(transactionId)
    val output = parseTxOutput(rawTx, outputIndex)
    val totalAmount = reportedSats(output.value)
    val fee = totalAmount - quote.creditAmountSats

    if (fee > maxFee) return null

    return claimStaticDeposit(transactionId, outputIndex)
}

private const val INITIAL_ROOT_NODE_SEQUENCE: UInt = 0u
private const val INITIAL_REFUND_SEQUENCE: UInt = 2000u

/**
 * Claim an on-chain deposit to a one-time deposit address after it has been confirmed.
 *
 * The transaction's outputs are matched against the wallet's unused deposit addresses, so the
 * claim is built for the leaf that actually received the funds.
 *
 * @param txID The on-chain transaction ID (display hex).
 * @param vout The output index. Pass `null` (the default) to locate the output that pays one
 *   of this wallet's deposit addresses; an explicit index must pay one of them.
 */
public suspend fun SparkWallet.claimDeposit(txID: String, vout: UInt? = null) {
    val txidBytes = txidBytesFromDisplayHex(txID)
    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    // Fetch raw tx from the block explorer and make sure it is the transaction we asked for.
    val rawTx = fetchRawTransaction(txID)
    if (!RawTransaction.parse(rawTx, context = "deposit tx").txid.contentEquals(txidBytes)) {
        throw SparkError.UntrustedResponse("block explorer returned a transaction that does not hash to $txID")
    }

    // Query unused deposit addresses and find the output that pays one of them
    val candidates = queryUnusedDepositAddresses().filter { it.leafId.isNotEmpty() }
    val match = DepositMatcher.match(
        rawTx = rawTx,
        candidateAddresses = candidates.map { it.address },
        requestedVout = vout,
        network = config.network,
    )
    val depositInfo = candidates.firstOrNull { it.address == match.address }
        ?: throw SparkError.InvalidResponse("No unused deposit address found. Generate one first with getDepositAddress().")
    val outputIndex = match.vout

    val leafId = depositInfo.leafId
    val verifyingKey = depositInfo.verifyingPublicKey

    val signingKey = signer.deriveLeafSigningKey(leafId)
    val signingPubKey = getPublicKeyBytes(signingKey, true)

    // Create root node transaction pair
    val rootNodeTx = constructNodeTxPair(
        parentTx = rawTx,
        vout = outputIndex,
        address = depositInfo.address,
        sequence = INITIAL_ROOT_NODE_SEQUENCE,
        directSequence = SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

    // Create initial timelock refund txs
    val refundTrio = constructRefundTxTrio(
        cpfpNodeTx = rootNodeTx.cpfp.tx,
        directNodeTx = null,
        vout = 0u,
        receivingPubkey = signingPubKey,
        network = networkStr,
        sequence = INITIAL_REFUND_SEQUENCE,
        directSequence = INITIAL_REFUND_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

    // Get signing commitments (3: root, cpfpRefund, directFromCpfpRefund)
    val commitmentsReq = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .setNodeIdCount(1)
        .build()
    val commitmentsResp = stub.getSigningCommitments(commitmentsReq)
    val allCommitments = commitmentsResp.signingCommitmentsList
    if (allCommitments.size < 3) {
        throw SparkError.InvalidResponse("Got ${allCommitments.size} signing commitments, need 3")
    }

    val rootJob = FrostSigningHelper.buildSigningJob(
        leafID = leafId,
        signingKey = signingKey,
        verifyingKey = verifyingKey,
        rawTx = rootNodeTx.cpfp.tx,
        sighash = rootNodeTx.cpfp.sighash,
        soCommitments = allCommitments[0].signingNonceCommitmentsMap,
    )
    val refundJob = FrostSigningHelper.buildSigningJob(
        leafID = leafId,
        signingKey = signingKey,
        verifyingKey = verifyingKey,
        rawTx = refundTrio.cpfpRefund.tx,
        sighash = refundTrio.cpfpRefund.sighash,
        soCommitments = allCommitments[1].signingNonceCommitmentsMap,
    )
    val directFromCpfpRefundJob = FrostSigningHelper.buildSigningJob(
        leafID = leafId,
        signingKey = signingKey,
        verifyingKey = verifyingKey,
        rawTx = refundTrio.directFromCpfpRefund.tx,
        sighash = refundTrio.directFromCpfpRefund.sighash,
        soCommitments = allCommitments[2].signingNonceCommitmentsMap,
    )

    val utxo = Spark.UTXO.newBuilder()
        .setRawTx(ByteString.copyFrom(rawTx))
        .setVout(outputIndex.toInt())
        .setNetwork(config.network.toProto())
        .setTxid(ByteString.copyFrom(txidBytes))
        .build()

    val finalizeReq = Spark.FinalizeDepositTreeCreationRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setOnChainUtxo(utxo)
        .setRootTxSigningJob(rootJob)
        .setRefundTxSigningJob(refundJob)
        .setDirectFromCpfpRefundTxSigningJob(directFromCpfpRefundJob)
        .build()

    stub.finalizeDepositTreeCreation(finalizeReq)
}

public suspend fun SparkWallet.refundStaticDeposit(
    depositTransactionId: String,
    outputIndex: UInt = 0u,
    destinationAddress: String,
    satsPerVbyte: Long,
): String {
    require(satsPerVbyte <= 150) { "satsPerVbyte must be <= 150" }

    val estimatedVbytes = 194L
    val fee = satsPerVbyte * estimatedVbytes
    require(fee >= 194) { "Fee must be at least 194 sats" }

    val stub = getCoordinatorStub()

    // Fetch deposit tx to know the output value
    val rawDepositTx = fetchRawTransaction(depositTransactionId)
    val depositOutput = parseTxOutput(rawDepositTx, outputIndex)
    val creditAmountSats = reportedSats(depositOutput.value) - fee
    require(creditAmountSats > 0) { "Fee too large, credit amount must be > 0" }

    // Build spend tx
    val spendTx = constructSpendTx(
        depositTxId = depositTransactionId,
        outputIndex = outputIndex,
        destinationAddress = destinationAddress,
        amountSats = creditAmountSats.toULong(),
        network = config.network,
    )

    // Compute sighash
    val sighash = computeMultiInputSighashUniffi(
        tx = spendTx,
        inputIndex = 0u,
        prevOutScripts = listOf(depositOutput.scriptPubKey),
        prevOutValues = listOf(depositOutput.value),
    )

    val staticKey = signer.deriveStaticDepositKey(0)
    val staticPubKey = getPublicKeyBytes(staticKey, true)
    val networkStr = config.network.networkGraphQL.lowercase()

    // Build signing payload
    val payload = java.io.ByteArrayOutputStream()
    payload.write("claim_static_deposit".toByteArray(Charsets.UTF_8))
    payload.write(networkStr.toByteArray(Charsets.UTF_8))
    payload.write(depositTransactionId.toByteArray(Charsets.UTF_8))
    payload.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(outputIndex.toInt()).array())
    payload.write(2) // requestType = Refund
    payload.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(creditAmountSats).array())
    payload.write(sighash.toHexString().toByteArray(Charsets.UTF_8))
    val payloadHash = sha256(payload.toByteArray())
    val userSignature = signer.signWithIdentityKey(payloadHash)

    // FROST nonce
    val keyPackage = KeyPackage(secretKey = staticKey, publicKey = staticPubKey, verifyingKey = staticPubKey)
    val nonceResult = frostNonce(keyPackage)

    val signingJob = FrostSigningHelper.buildUnsignedJob(
        signingPublicKey = staticPubKey,
        rawTx = spendTx,
        hidingNonce = nonceResult.commitment.hiding,
        bindingNonce = nonceResult.commitment.binding,
    )

    val txidBytes = txidBytesFromDisplayHex(depositTransactionId)
    val utxo = Spark.UTXO.newBuilder()
        .setTxid(ByteString.copyFrom(txidBytes))
        .setVout(outputIndex.toInt())
        .setNetwork(config.network.toProto())
        .build()

    val refundReq = Spark.InitiateStaticDepositUtxoRefundRequest.newBuilder()
        .setOnChainUtxo(utxo)
        .setRefundTxSigningJob(signingJob)
        .setUserSignature(ByteString.copyFrom(userSignature))
        .build()

    val refundResp = stub.initiateStaticDepositUtxoRefund(refundReq)

    val signingResult = refundResp.refundTxSigningResult
    val verifyingKey = refundResp.depositAddress.verifyingPublicKey.toByteArray()

    // Sign FROST and aggregate
    val realKeyPackage = KeyPackage(secretKey = staticKey, publicKey = staticPubKey, verifyingKey = verifyingKey)
    val nativeCommitments = signingResult.signingNonceCommitmentsMap.mapValues { (_, proto) ->
        SigningCommitment(hiding = proto.hiding.toByteArray(), binding = proto.binding.toByteArray())
    }

    val selfSignature = signFrost(
        msg = sighash,
        keyPackage = realKeyPackage,
        nonce = nonceResult.nonce,
        selfCommitment = nonceResult.commitment,
        statechainCommitments = nativeCommitments,
        adaptorPublicKey = null,
    )

    val soSignatures = signingResult.signatureSharesMap.mapValues { it.value.toByteArray() }
    val soPublicKeys = signingResult.publicKeysMap.mapValues { it.value.toByteArray() }

    val aggregatedSig = aggregateFrost(
        msg = sighash, statechainCommitments = nativeCommitments,
        selfCommitment = nonceResult.commitment,
        statechainSignatures = soSignatures, selfSignature = selfSignature,
        statechainPublicKeys = soPublicKeys, selfPublicKey = staticPubKey,
        verifyingKey = verifyingKey, adaptorPublicKey = null,
    )

    // Add witness to spend tx
    val signedTx = addWitnessToTx(spendTx, aggregatedSig)
    return signedTx.toHexString()
}

public suspend fun SparkWallet.refundAndBroadcastStaticDeposit(
    depositTransactionId: String,
    outputIndex: UInt = 0u,
    destinationAddress: String,
    satsPerVbyte: Long,
): String {
    val txHex = refundStaticDeposit(
        depositTransactionId = depositTransactionId,
        outputIndex = outputIndex,
        destinationAddress = destinationAddress,
        satsPerVbyte = satsPerVbyte,
    )
    return broadcastTransaction(txHex)
}

public suspend fun SparkWallet.broadcastTransaction(txHex: String): String {
    val baseURL = when (config.network) {
        SparkNetwork.MAINNET -> "https://mempool.space/api"
        SparkNetwork.REGTEST -> "http://localhost:3000"
    }

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder()
        .url("$baseURL/tx")
        .post(txHex.toRequestBody(null))
        .build()

    val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        client.newCall(request).execute()
    }

    if (!response.isSuccessful) {
        val body = response.body?.string() ?: ""
        throw SparkError.InvalidResponse("Failed to broadcast tx: $body")
    }

    return response.body?.string()?.trim() ?: ""
}

// ── Internal helpers ──

internal suspend fun SparkWallet.fetchRawTransaction(txID: String): ByteArray {
    val baseURL = when (config.network) {
        SparkNetwork.MAINNET -> "https://mempool.space/api"
        SparkNetwork.REGTEST -> "http://localhost:3000"
    }

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder().url("$baseURL/tx/$txID/hex").build()

    val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        client.newCall(request).execute()
    }

    if (!response.isSuccessful) {
        throw SparkError.InvalidResponse("Failed to fetch raw transaction $txID")
    }

    val hexString = response.body?.string()?.trim()
        ?: throw SparkError.InvalidResponse("Empty response for transaction $txID")
    return hexString.hexToBytesOrNull() ?: throw SparkError.InvalidResponse("Invalid hex in raw transaction response")
}

/** Parse a display-order (big-endian hex) txid into the internal byte order used on the wire. */
internal fun txidBytesFromDisplayHex(hex: String): ByteArray {
    val bytes = if (hex.length == 64) hex.hexToBytesOrNull() else null
    return bytes?.reversedArray() ?: throw SparkError.InvalidResponse("Invalid transaction id: $hex")
}

/**
 * Build a simple 1-input 1-output spend transaction (version 3, witness serialisation with an
 * empty witness; the signature is attached by [addWitnessToTx]).
 */
internal fun constructSpendTx(depositTxId: String, outputIndex: UInt, destinationAddress: String, amountSats: ULong, network: SparkNetwork,): ByteArray {
    val scriptPubKey = BitcoinAddress.scriptPubKey(destinationAddress, network)
    val tx = RawTransaction(
        version = 3u,
        inputs = listOf(RawTransaction.Input(previousTxid = txidBytesFromDisplayHex(depositTxId), previousIndex = outputIndex)),
        outputs = listOf(RawTransaction.Output(value = amountSats, scriptPubKey = scriptPubKey)),
        locktime = 0u,
        hasWitnessSerialization = true,
    )
    return tx.serialized(includeWitness = true)
}

/** Attach a single-item witness (a schnorr signature) to the first input of a segwit tx. */
internal fun addWitnessToTx(rawTx: ByteArray, witness: ByteArray): ByteArray {
    val tx = RawTransaction.parse(rawTx, context = "spend tx")
    val first = tx.inputs.firstOrNull() ?: throw SparkError.MalformedTransaction("spend tx has no inputs")
    val inputs = listOf(first.copy(witness = listOf(witness))) + tx.inputs.drop(1)
    return tx.copy(inputs = inputs, hasWitnessSerialization = true).serialized(includeWitness = true)
}
