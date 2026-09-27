package gy.pig.spark

import com.google.protobuf.ByteString
import okhttp3.RequestBody.Companion.toRequestBody
import spark.Spark
import uniffi.spark_frost.*
import java.util.UUID

/**
 * Generate a one-time deposit address. After sending BTC on-chain, call [claimDeposit].
 *
 * The address is verified before it is returned, as the reference SDK does: the operators' proof
 * of possession, every operator's signature over it but the coordinator's, and that it pays the
 * reported verifying key ([SparkError.UntrustedResponse] otherwise).
 */
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
    DepositAddressVerifier.verify(
        addr,
        userSigningPublicKey = leafPublicKey,
        identityPublicKey = signer.identityPublicKey,
        isStatic = false,
        config = config,
    )

    return DepositAddress(
        address = addr.address,
        leafId = leafId,
        userPublicKey = leafPublicKey,
        verifyingKey = addr.verifyingKey.toByteArray(),
    )
}

/**
 * Generate a static (reusable) deposit address, or get the one the operators already hold for
 * this wallet, for the static-deposit key (index 0) as the Swift and reference SDKs request it.
 *
 * The address is verified before it is returned: the operators' proof of possession, every
 * operator's signature over it (the coordinator's included), and that it pays the reported
 * verifying key ([SparkError.UntrustedResponse] otherwise).
 */
public suspend fun SparkWallet.getStaticDepositAddress(): StaticDepositAddress {
    val stub = getCoordinatorStub()
    val staticPubKey = getPublicKeyBytes(signer.deriveStaticDepositKey(0), true)

    val request = Spark.GenerateStaticDepositAddressRequest.newBuilder()
        .setNetwork(config.network.toProto())
        .setSigningPublicKey(ByteString.copyFrom(staticPubKey))
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .build()

    val deposit = stub.generateStaticDepositAddress(request).depositAddress
    try {
        DepositAddressVerifier.verify(
            deposit,
            userSigningPublicKey = staticPubKey,
            identityPublicKey = signer.identityPublicKey,
            isStatic = true,
            config = config,
        )
    } catch (e: SparkError.UntrustedResponse) {
        throw legacyStaticAddressError(deposit) ?: e
    }
    return StaticDepositAddress(address = deposit.address, verifyingKey = deposit.verifyingKey.toByteArray())
}

/**
 * spark-kotlin-sdk up to 0.2.2 requested the static deposit address for the account's deposit key
 * (`2'`) instead of the static-deposit key (`3'/0'`) the claims and refunds sign with. The
 * operators keep returning an address made that way, which the SDK cannot claim or refund: say so
 * instead of reporting a bad proof.
 */
private fun SparkWallet.legacyStaticAddressError(deposit: Spark.Address): SparkError? {
    val legacy = try {
        DepositAddressVerifier.verify(deposit, signer.depositPublicKey, signer.identityPublicKey, isStatic = true, config = config)
        true
    } catch (_: SparkError) {
        false
    }
    if (!legacy) return null
    return SparkError.InvalidResponse(
        "static deposit address ${deposit.address} was created by spark-kotlin-sdk 0.2.x for the deposit key instead of the " +
            "static-deposit key; this SDK cannot claim or refund deposits to it",
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

/**
 * Get the SSP's quote for claiming a static deposit (how much will be credited after fees).
 * Without [outputIndex] the quote is for the output that pays this wallet's static deposit
 * address. The txid may be in any case.
 */
public suspend fun SparkWallet.getDepositFeeEstimate(transactionId: String, outputIndex: UInt? = null): DepositFeeEstimate {
    val outpoint = DepositOutpoint(transactionId, staticDepositVout(transactionId, outputIndex))
    val result = sspClient.executeRaw(
        query = GraphQLQueries.STATIC_DEPOSIT_QUOTE,
        variables = mapOf(
            "transaction_id" to outpoint.txid,
            "output_index" to outpoint.vout.toInt(),
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

/**
 * Claim a static deposit for whatever credit the SSP quotes, unchecked. Without [outputIndex] the
 * output that pays this wallet's static deposit address is claimed.
 *
 * @return The Spark transfer id of the claim.
 */
@Deprecated(
    message = "Signs whatever credit the SSP quotes. Use claimStaticDepositWithMaxFee, or claimStaticDeposit(transactionId, outputIndex, quote) " +
        "with a quote you checked.",
)
public suspend fun SparkWallet.claimStaticDeposit(transactionId: String, outputIndex: UInt? = null): String {
    val vout = staticDepositVout(transactionId, outputIndex)
    val quote = getDepositFeeEstimate(transactionId, vout)
    return claimStaticDeposit(transactionId, vout, quote)
}

/**
 * Claim a static deposit for exactly the credit of [quote] — the SSP-signed quote
 * [getDepositFeeEstimate] returned for this output — as the reference SDK's `claimStaticDeposit`
 * does: the wallet signs a fixed-amount claim for that credit and the SSP's quote signature, so
 * the SSP cannot credit less. Without [outputIndex] the output that pays this wallet's static
 * deposit address is claimed. The txid may be in any case.
 *
 * @return The Spark transfer id of the claim.
 * @throws SparkError.InvalidArgument for a quote crediting nothing or a malformed txid.
 * @throws SparkError.InvalidResponse when the quote's signature is not hex.
 */
public suspend fun SparkWallet.claimStaticDeposit(transactionId: String, outputIndex: UInt? = null, quote: DepositFeeEstimate): String {
    if (quote.creditAmountSats <= 0) {
        throw SparkError.InvalidArgument("the quote credits ${quote.creditAmountSats} sats; nothing to claim")
    }
    val quoteSignature = quote.quoteSignature.hexToBytesOrNull()?.takeIf { it.isNotEmpty() }
        ?: throw SparkError.InvalidResponse("the SSP's quote signature is not hex")
    val outpoint = DepositOutpoint(transactionId, staticDepositVout(transactionId, outputIndex))
    val statement = staticDepositStatement(
        outpoint,
        network = config.network,
        requestType = StaticDepositRequestType.FIXED,
        creditAmountSats = quote.creditAmountSats.toULong(),
        authorization = quoteSignature,
    )
    val signature = signer.signWithIdentityKey(sha256(statement))
    val staticSecretKey = signer.deriveStaticDepositKey(0)

    val result = sspClient.executeRaw(
        query = GraphQLMutations.CLAIM_STATIC_DEPOSIT,
        variables = mapOf(
            "transaction_id" to outpoint.txid,
            "output_index" to outpoint.vout.toInt(),
            "network" to config.network.networkGraphQL,
            "request_type" to "FIXED_AMOUNT",
            "credit_amount_sats" to quote.creditAmountSats,
            "deposit_secret_key" to staticSecretKey.toHexString(),
            "signature" to signature.toHexString(),
            "quote_signature" to quote.quoteSignature,
        ),
    )
    return result.optJSONObject("claim_static_deposit")?.stringOrNull("transfer_id")
        ?: throw SparkError.InvalidResponse("No transfer_id in claim response")
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

/**
 * Claim a static deposit, but only if the fee is at or below [maxFee] sats: the SSP's quote is
 * checked against the deposit's value (from a transaction that hashes to the txid) and then
 * claimed exactly, as the reference SDK does. Without [outputIndex] the output that pays this
 * wallet's static deposit address is claimed.
 *
 * @return The Spark transfer id of the claim, or `null` if the fee exceeds [maxFee].
 */
public suspend fun SparkWallet.claimStaticDepositWithMaxFee(transactionId: String, maxFee: Long, outputIndex: UInt? = null): String? {
    val depositTx = fetchDepositTransaction(transactionId)
    val vout = staticDepositVout(transactionId, outputIndex, depositTx)
    val outpoint = DepositOutpoint(transactionId, vout)
    val depositSats = reportedSats(depositTx.output(vout).value)

    val quote = getDepositFeeEstimate(outpoint.txid, vout)
    if (staticDepositFee(depositSats, quote) > maxFee) return null
    return claimStaticDeposit(outpoint.txid, vout, quote)
}

/** What the SSP keeps of a deposit under [quote]. */
internal fun staticDepositFee(depositSats: Long, quote: DepositFeeEstimate): Long = depositSats - quote.creditAmountSats

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

/**
 * Refund a static deposit back on-chain. Without [outputIndex] the output that pays this wallet's
 * static deposit address is refunded. The txid may be in any case.
 *
 * @param destinationAddress Bitcoin address to send the refund to (wallet's network).
 * @param satsPerVbyte Fee rate, at most 150.
 * @return The signed transaction hex, ready for broadcast.
 */
public suspend fun SparkWallet.refundStaticDeposit(
    depositTransactionId: String,
    outputIndex: UInt? = null,
    destinationAddress: String,
    satsPerVbyte: Long,
): String {
    if (satsPerVbyte > 150) throw SparkError.InvalidArgument("satsPerVbyte must be <= 150")
    // Estimated vbytes for a 1-input 1-output P2TR transaction.
    val estimatedVbytes = 194L
    val fee = satsPerVbyte * estimatedVbytes
    if (fee < 194) throw SparkError.InvalidArgument("Fee must be at least 194 sats")

    // The deposit output, from a transaction that hashes to the txid.
    val depositTx = fetchDepositTransaction(depositTransactionId)
    val outpoint = DepositOutpoint(depositTransactionId, staticDepositVout(depositTransactionId, outputIndex, depositTx))
    val depositOutput = depositTx.output(outpoint.vout)
    val creditAmountSats = reportedSats(depositOutput.value) - fee
    if (creditAmountSats <= 0) throw SparkError.InvalidArgument("Fee too large, credit amount must be > 0")

    // Build spend tx: 1 input (deposit utxo), 1 output (destination)
    val spendTx = constructSpendTx(
        spending = outpoint,
        destinationAddress = destinationAddress,
        amountSats = creditAmountSats.toULong(),
        network = config.network,
    )

    // Compute sighash for the spend tx
    val sighash = computeMultiInputSighashUniffi(
        tx = spendTx,
        inputIndex = 0u,
        prevOutScripts = listOf(depositOutput.scriptPubKey),
        prevOutValues = listOf(depositOutput.value),
    )

    val stub = getCoordinatorStub()
    val staticKey = signer.deriveStaticDepositKey(0)
    val staticPubKey = getPublicKeyBytes(staticKey, true)

    // Authorize the refund: the statement ends with the spend transaction's raw sighash.
    val statement = staticDepositStatement(
        outpoint,
        network = config.network,
        requestType = StaticDepositRequestType.REFUND,
        creditAmountSats = creditAmountSats.toULong(),
        authorization = sighash,
    )
    val userSignature = signer.signWithIdentityKey(sha256(statement))

    // FROST nonce
    val keyPackage = KeyPackage(secretKey = staticKey, publicKey = staticPubKey, verifyingKey = staticPubKey)
    val nonceResult = frostNonce(keyPackage)

    val signingJob = FrostSigningHelper.buildUnsignedJob(
        signingPublicKey = staticPubKey,
        rawTx = spendTx,
        hidingNonce = nonceResult.commitment.hiding,
        bindingNonce = nonceResult.commitment.binding,
    )

    val refundReq = Spark.InitiateStaticDepositUtxoRefundRequest.newBuilder()
        .setOnChainUtxo(outpoint.utxo(config.network.toProto()))
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
    return addWitnessToTx(spendTx, aggregatedSig).toHexString()
}

/** Refund a static deposit and broadcast it. Returns the txid. */
public suspend fun SparkWallet.refundAndBroadcastStaticDeposit(
    depositTransactionId: String,
    outputIndex: UInt? = null,
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

/**
 * Fetch raw transaction bytes from the block explorer. Throws [SparkError.InvalidArgument] for a
 * txid that is not 64 hex characters, and [SparkError.InvalidResponse] for a reply that is not
 * hex, before or instead of trusting either.
 */
internal suspend fun SparkWallet.fetchRawTransaction(txID: String): ByteArray {
    val txid = DepositOutpoint.normalizedTxid(txID)
    val baseURL = when (config.network) {
        SparkNetwork.MAINNET -> "https://mempool.space/api"
        SparkNetwork.REGTEST -> "http://localhost:3000"
    }

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder().url("$baseURL/tx/$txid/hex").build()

    val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SparkError.InvalidResponse("Failed to fetch raw transaction $txid")
            response.body?.string()
        }
    }
    return body?.trim()?.hexToBytesOrNull()?.takeIf { it.isNotEmpty() }
        ?: throw SparkError.InvalidResponse("Invalid hex in raw transaction response")
}

/** A deposit transaction from the block explorer, checked to hash to [txid]. */
internal suspend fun SparkWallet.fetchDepositTransaction(txid: String): RawTransaction {
    val normalized = DepositOutpoint.normalizedTxid(txid)
    val tx = RawTransaction.parse(fetchRawTransaction(normalized), context = "deposit tx")
    if (tx.txidHex != normalized) {
        throw SparkError.UntrustedResponse("block explorer returned a transaction that does not hash to $normalized")
    }
    return tx
}

/**
 * [outputIndex], or else the output of deposit [txid] that pays this wallet's static deposit
 * address, as the reference SDK's `getDepositTransactionVout` finds it.
 */
internal suspend fun SparkWallet.staticDepositVout(txid: String, outputIndex: UInt?, transaction: RawTransaction? = null): UInt {
    if (outputIndex != null) return outputIndex
    val tx = transaction ?: fetchDepositTransaction(txid)
    return staticDepositVout(tx, queryStaticDepositAddresses().map { it.address }, config.network)
}

/** The first output of [tx] paying one of [addresses]. */
internal fun staticDepositVout(tx: RawTransaction, addresses: List<String>, network: SparkNetwork): UInt {
    val scripts = addresses.mapNotNull {
        try {
            BitcoinAddress.scriptPubKey(it, network)
        } catch (_: SparkError) {
            null
        }
    }
    val index = tx.outputs.indexOfFirst { output -> scripts.any { it.contentEquals(output.scriptPubKey) } }
    if (index < 0) {
        throw SparkError.InvalidArgument("transaction ${tx.txidHex} does not pay this wallet's static deposit address")
    }
    return index.toUInt()
}

/** Parse a display-order (big-endian hex) txid into the internal byte order used on the wire. */
internal fun txidBytesFromDisplayHex(hex: String): ByteArray {
    val bytes = if (hex.length == 64) hex.hexToBytesOrNull() else null
    return bytes?.reversedArray() ?: throw SparkError.InvalidResponse("Invalid transaction id: $hex")
}

/**
 * The unsigned 1-input 1-output transaction spending a static deposit: version 3, final sequence,
 * locktime 0, in the non-witness serialisation. The operators rebuild exactly that and compare it
 * byte for byte (`validateStaticDepositSingleInputTx`), and the reference SDK sends
 * `tx.toBytes()`; the signature is attached afterwards by [addWitnessToTx].
 */
internal fun constructSpendTx(spending: DepositOutpoint, destinationAddress: String, amountSats: ULong, network: SparkNetwork): ByteArray {
    val scriptPubKey = BitcoinAddress.scriptPubKey(destinationAddress, network)
    val tx = RawTransaction(
        version = 3u,
        inputs = listOf(RawTransaction.Input(previousTxid = spending.internalOrderTxid, previousIndex = spending.vout)),
        outputs = listOf(RawTransaction.Output(value = amountSats, scriptPubKey = scriptPubKey)),
        locktime = 0u,
        hasWitnessSerialization = false,
    )
    return tx.serialized(includeWitness = false)
}

/** Attach a single-item witness (a schnorr signature) to the first input of a segwit tx. */
internal fun addWitnessToTx(rawTx: ByteArray, witness: ByteArray): ByteArray {
    val tx = RawTransaction.parse(rawTx, context = "spend tx")
    val first = tx.inputs.firstOrNull() ?: throw SparkError.MalformedTransaction("spend tx has no inputs")
    val inputs = listOf(first.copy(witness = listOf(witness))) + tx.inputs.drop(1)
    return tx.copy(inputs = inputs, hasWitnessSerialization = true).serialized(includeWitness = true)
}
