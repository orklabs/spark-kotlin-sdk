package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import spark_token.*
import java.math.BigInteger

private const val QUERY_TOKEN_OUTPUTS_PAGE_SIZE = 100
private const val MAX_TOKEN_OUTPUTS_TX = 500

// MARK: - Public Token API

public suspend fun SparkWallet.transferTokens(
    tokenIdentifier: Bech32mTokenIdentifier,
    tokenAmount: BigInteger,
    receiverSparkAddress: String,
    strategy: TokenOutputSelectionStrategy = TokenOutputSelectionStrategy.SMALL_FIRST,
    idempotencyKey: String? = null,
): String {
    val (rawTokenId, _) = decodeBech32mTokenIdentifier(tokenIdentifier, config.network)

    val outputs = fetchTokenOutputs(tokenIdentifiers = listOf(rawTokenId))
    if (outputs.isEmpty()) {
        throw SparkError.InsufficientTokenBalance(token = tokenIdentifier, need = "$tokenAmount", have = "0")
    }

    val selected = selectTokenOutputs(outputs, tokenAmount, strategy)
    // Decode receiver's Spark address (must be for this network) to get their identity public key
    val receiverData = SparkAddress.decode(receiverSparkAddress, config.network)

    val tx = buildTransferTokenTransaction(
        selectedOutputs = selected,
        receiverOutputs = listOf(Triple(receiverData, rawTokenId, tokenAmount)),
        changeOwnerPubKey = signer.identityPublicKey,
    )

    return broadcastTokenTransactionV2(
        tokenTransaction = tx,
        signingPublicKeys = selected.map { it.output.ownerPublicKey.toByteArray() },
        revocationCommitments = selected.mapNotNull {
            if (it.output.hasRevocationCommitment()) it.output.revocationCommitment.toByteArray() else null
        },
        idempotencyKey = idempotencyKey,
    )
}

public suspend fun SparkWallet.getTokenBalances(): List<TokenBalance> {
    val outputs = fetchTokenOutputs()

    val balancesByToken = mutableMapOf<ByteString, Pair<BigInteger, BigInteger>>()
    for (output in outputs) {
        val tokenId = output.output.tokenIdentifier
        val amount = decodeUInt128(output.output.tokenAmount)
        val (owned, available) = balancesByToken[tokenId] ?: (BigInteger.ZERO to BigInteger.ZERO)
        val isAvailable = !output.output.hasStatus() ||
            output.output.status == TokenOutputStatus.TOKEN_OUTPUT_STATUS_AVAILABLE
        balancesByToken[tokenId] = (owned + amount) to (if (isAvailable) available + amount else available)
    }

    val tokenIds = balancesByToken.keys.map { it.toByteArray() }
    val metadataMap = fetchTokenMetadata(tokenIds)

    return balancesByToken.mapNotNull { (tokenId, entry) ->
        val meta = metadataMap[tokenId] ?: return@mapNotNull null
        TokenBalance(
            tokenMetadata = meta,
            ownedBalance = entry.first,
            availableToSendBalance = entry.second,
        )
    }
}

public suspend fun SparkWallet.getTokenOutputs(tokenIdentifier: Bech32mTokenIdentifier? = null,): List<TokenOutputInfo> {
    val rawTokenIds = tokenIdentifier?.let {
        val (rawId, _) = decodeBech32mTokenIdentifier(it, config.network)
        listOf(rawId)
    }

    val outputs = fetchTokenOutputs(tokenIdentifiers = rawTokenIds)
    return outputs.map { protoOutput ->
        val o = protoOutput.output
        TokenOutputInfo(
            id = o.id.ifEmpty { null },
            ownerPublicKey = o.ownerPublicKey.toByteArray(),
            tokenIdentifier = o.tokenIdentifier.toByteArray(),
            tokenAmount = decodeUInt128(o.tokenAmount),
            previousTransactionHash = protoOutput.previousTransactionHash.toByteArray(),
            previousTransactionVout = protoOutput.previousTransactionVout.toUInt(),
            status = if (o.hasStatus()) o.status.toString() else "AVAILABLE",
        )
    }
}

public suspend fun SparkWallet.queryTokenMetadata(
    tokenIdentifiers: List<Bech32mTokenIdentifier>? = null,
    issuerPublicKeys: List<ByteArray>? = null,
): List<TokenMetadataInfo> {
    val stub = getTokenStub()

    val request = QueryTokenMetadataRequest.newBuilder().apply {
        tokenIdentifiers?.forEach { id ->
            val (rawId, _) = decodeBech32mTokenIdentifier(id, config.network)
            addTokenIdentifiers(ByteString.copyFrom(rawId))
        }
        issuerPublicKeys?.forEach { key ->
            addIssuerPublicKeys(ByteString.copyFrom(key))
        }
    }.build()

    val response = stub.queryTokenMetadata(request)
    return response.tokenMetadataList.map { meta ->
        val bech32Id = encodeBech32mTokenIdentifier(meta.tokenIdentifier.toByteArray(), config.network)
        TokenMetadataInfo(
            tokenIdentifier = bech32Id,
            rawTokenIdentifier = meta.tokenIdentifier.toByteArray(),
            issuerPublicKey = meta.issuerPublicKey.toByteArray(),
            tokenName = meta.tokenName,
            tokenTicker = meta.tokenTicker,
            decimals = meta.decimals.toUInt(),
            maxSupply = meta.maxSupply.toByteArray(),
            isFreezable = meta.isFreezable,
            extraMetadata = if (meta.hasExtraMetadata()) meta.extraMetadata.toByteArray() else null,
        )
    }
}

// MARK: - Token Issuance

public suspend fun SparkWallet.createToken(
    tokenName: String,
    tokenTicker: String,
    decimals: UInt,
    maxSupply: BigInteger = BigInteger.ZERO,
    isFreezable: Boolean,
    extraMetadata: ByteArray? = null,
): TokenCreationResult {
    val nameBytes = tokenName.toByteArray(Charsets.UTF_8)
    require(nameBytes.isNotEmpty() && nameBytes.size <= 20) { "Token name must be 1-20 UTF-8 bytes" }
    val tickerBytes = tokenTicker.toByteArray(Charsets.UTF_8)
    require(tickerBytes.isNotEmpty() && tickerBytes.size <= 6) { "Token ticker must be 1-6 UTF-8 bytes" }
    require(decimals <= 255u) { "Decimals must be <= 255" }
    extraMetadata?.let { require(it.size <= 1024) { "Extra metadata must be <= 1024 bytes" } }

    val issuerPubKey = signer.identityPublicKey

    val createInput = TokenCreateInput.newBuilder()
        .setIssuerPublicKey(ByteString.copyFrom(issuerPubKey))
        .setTokenName(tokenName)
        .setTokenTicker(tokenTicker)
        .setDecimals(decimals.toInt())
        .setMaxSupply(ByteString.copyFrom(encodeUInt128(maxSupply)))
        .setIsFreezable(isFreezable)
        .apply {
            extraMetadata?.let { setExtraMetadata(ByteString.copyFrom(it)) }
        }
        .build()

    val tx = TokenTransaction.newBuilder()
        .setVersion(2)
        .setNetwork(config.network.toProto())
        .setCreateInput(createInput)
        .addAllSparkOperatorIdentityPublicKeys(collectOperatorIdentityPublicKeys())
        .setClientCreatedTimestamp(currentTimestamp())
        .build()

    val (txHash, tokenId) = broadcastTokenTransactionV2Detailed(
        tokenTransaction = tx,
        signingPublicKeys = null,
        revocationCommitments = null,
    )

    val bech32TokenId = tokenId?.let { encodeBech32mTokenIdentifier(it, config.network) }
    return TokenCreationResult(transactionHash = txHash, tokenIdentifier = bech32TokenId)
}

public suspend fun SparkWallet.mintTokens(tokenIdentifier: Bech32mTokenIdentifier, tokenAmount: BigInteger, idempotencyKey: String? = null,): String {
    require(tokenAmount > BigInteger.ZERO) { "Mint amount must be greater than 0" }

    val (rawTokenId, _) = decodeBech32mTokenIdentifier(tokenIdentifier, config.network)
    val issuerPubKey = signer.identityPublicKey

    val mintInput = TokenMintInput.newBuilder()
        .setIssuerPublicKey(ByteString.copyFrom(issuerPubKey))
        .setTokenIdentifier(ByteString.copyFrom(rawTokenId))
        .build()

    val mintOutput = TokenOutput.newBuilder()
        .setOwnerPublicKey(ByteString.copyFrom(issuerPubKey))
        .setTokenIdentifier(ByteString.copyFrom(rawTokenId))
        .setTokenAmount(ByteString.copyFrom(encodeUInt128(tokenAmount)))
        .build()

    val tx = TokenTransaction.newBuilder()
        .setVersion(2)
        .setNetwork(config.network.toProto())
        .setMintInput(mintInput)
        .addTokenOutputs(mintOutput)
        .addAllSparkOperatorIdentityPublicKeys(collectOperatorIdentityPublicKeys())
        .setClientCreatedTimestamp(currentTimestamp())
        .build()

    return broadcastTokenTransactionV2(
        tokenTransaction = tx,
        signingPublicKeys = null,
        revocationCommitments = null,
        idempotencyKey = idempotencyKey,
    )
}

public suspend fun SparkWallet.burnTokens(
    tokenIdentifier: Bech32mTokenIdentifier,
    tokenAmount: BigInteger,
    strategy: TokenOutputSelectionStrategy = TokenOutputSelectionStrategy.SMALL_FIRST,
): String {
    val burnPubKey = ByteArray(33).apply { this[0] = 0x02 }
    val (rawTokenId, _) = decodeBech32mTokenIdentifier(tokenIdentifier, config.network)

    val outputs = fetchTokenOutputs(tokenIdentifiers = listOf(rawTokenId))
    if (outputs.isEmpty()) {
        throw SparkError.InsufficientTokenBalance(token = tokenIdentifier, need = "$tokenAmount", have = "0")
    }

    val selected = selectTokenOutputs(outputs, tokenAmount, strategy)

    val tx = buildTransferTokenTransaction(
        selectedOutputs = selected,
        receiverOutputs = listOf(Triple(burnPubKey, rawTokenId, tokenAmount)),
        changeOwnerPubKey = signer.identityPublicKey,
    )

    return broadcastTokenTransactionV2(
        tokenTransaction = tx,
        signingPublicKeys = selected.map { it.output.ownerPublicKey.toByteArray() },
        revocationCommitments = selected.mapNotNull {
            if (it.output.hasRevocationCommitment()) it.output.revocationCommitment.toByteArray() else null
        },
    )
}

// MARK: - Token Output Selection

internal fun selectTokenOutputs(
    outputs: List<OutputWithPreviousTransactionData>,
    amount: BigInteger,
    strategy: TokenOutputSelectionStrategy,
): List<OutputWithPreviousTransactionData> {
    require(amount > BigInteger.ZERO) { "Token amount must be greater than 0" }

    val totalAvailable = outputs.fold(BigInteger.ZERO) { acc, o -> acc + decodeUInt128(o.output.tokenAmount) }
    if (totalAvailable < amount) {
        throw SparkError.InsufficientTokenBalance(token = "token", need = "$amount", have = "$totalAvailable")
    }

    // Check for exact match
    outputs.firstOrNull { decodeUInt128(it.output.tokenAmount) == amount }?.let { return listOf(it) }

    return when (strategy) {
        TokenOutputSelectionStrategy.SMALL_FIRST -> {
            val sorted = outputs.sortedBy { decodeUInt128(it.output.tokenAmount) }
            var sum = BigInteger.ZERO
            var count = 0
            for (output in sorted) {
                sum += decodeUInt128(output.output.tokenAmount)
                count++
                if (sum >= amount) return sorted.take(count)
                if (count >= MAX_TOKEN_OUTPUTS_TX) break
            }

            val selected = sorted.take(minOf(count, MAX_TOKEN_OUTPUTS_TX)).toMutableList()
            val remaining = sorted.drop(minOf(count, MAX_TOKEN_OUTPUTS_TX)).reversed()
            var smallSum = selected.fold(BigInteger.ZERO) { acc, o -> acc + decodeUInt128(o.output.tokenAmount) }

            for (largeOutput in remaining) {
                if (smallSum >= amount) break
                if (selected.isEmpty()) break
                val smallest = selected.removeAt(0)
                smallSum = smallSum - decodeUInt128(smallest.output.tokenAmount) + decodeUInt128(largeOutput.output.tokenAmount)
                selected.add(largeOutput)
            }

            if (smallSum < amount) {
                throw SparkError.InsufficientTokenBalance(token = "token", need = "$amount", have = "$smallSum")
            }
            selected
        }

        TokenOutputSelectionStrategy.LARGE_FIRST -> {
            val sorted = outputs.sortedByDescending { decodeUInt128(it.output.tokenAmount) }
            val selected = mutableListOf<OutputWithPreviousTransactionData>()
            var remaining = amount
            for (output in sorted) {
                if (remaining.signum() == 0) break
                if (selected.size >= MAX_TOKEN_OUTPUTS_TX) break
                selected.add(output)
                val a = decodeUInt128(output.output.tokenAmount)
                remaining = if (a >= remaining) BigInteger.ZERO else remaining - a
            }
            if (remaining > BigInteger.ZERO) {
                throw SparkError.InsufficientTokenBalance(token = "token", need = "$amount", have = "${amount - remaining}")
            }
            selected
        }
    }
}

// MARK: - Internal: Build V2 Transfer Transaction

private fun SparkWallet.buildTransferTokenTransaction(
    selectedOutputs: List<OutputWithPreviousTransactionData>,
    receiverOutputs: List<Triple<ByteArray, ByteArray, BigInteger>>, // (receiverPubKey, rawTokenIdentifier, tokenAmount)
    changeOwnerPubKey: ByteArray,
): TokenTransaction {
    val sorted = selectedOutputs.sortedBy { it.previousTransactionVout }

    val availableByToken = mutableMapOf<ByteString, BigInteger>()
    for (output in sorted) {
        val key = output.output.tokenIdentifier
        availableByToken[key] = (availableByToken[key] ?: BigInteger.ZERO) + decodeUInt128(output.output.tokenAmount)
    }

    val requestedByToken = mutableMapOf<ByteString, BigInteger>()
    for ((_, rawTokenId, amount) in receiverOutputs) {
        val key = ByteString.copyFrom(rawTokenId)
        requestedByToken[key] = (requestedByToken[key] ?: BigInteger.ZERO) + amount
    }

    val tokenOutputs = mutableListOf<TokenOutput>()

    // Receiver outputs
    for ((receiverPubKey, rawTokenId, amount) in receiverOutputs) {
        tokenOutputs.add(
            TokenOutput.newBuilder()
                .setOwnerPublicKey(ByteString.copyFrom(receiverPubKey))
                .setTokenIdentifier(ByteString.copyFrom(rawTokenId))
                .setTokenAmount(ByteString.copyFrom(encodeUInt128(amount)))
                .build()
        )
    }

    // Change outputs
    for ((tokenId, availableAmount) in availableByToken) {
        val requestedAmount = requestedByToken[tokenId] ?: BigInteger.ZERO
        if (availableAmount > requestedAmount) {
            tokenOutputs.add(
                TokenOutput.newBuilder()
                    .setOwnerPublicKey(ByteString.copyFrom(changeOwnerPubKey))
                    .setTokenIdentifier(tokenId)
                    .setTokenAmount(ByteString.copyFrom(encodeUInt128(availableAmount - requestedAmount)))
                    .build()
            )
        }
    }

    val transferInput = TokenTransferInput.newBuilder()
        .addAllOutputsToSpend(
            sorted.map { output ->
                TokenOutputToSpend.newBuilder()
                    .setPrevTokenTransactionHash(output.previousTransactionHash)
                    .setPrevTokenTransactionVout(output.previousTransactionVout)
                    .build()
            }
        )
        .build()

    return TokenTransaction.newBuilder()
        .setVersion(2)
        .setNetwork(config.network.toProto())
        .setTransferInput(transferInput)
        .addAllTokenOutputs(tokenOutputs)
        .addAllSparkOperatorIdentityPublicKeys(collectOperatorIdentityPublicKeys())
        .setClientCreatedTimestamp(currentTimestamp())
        .build()
}

// MARK: - Internal: V2 Broadcast (Two-Phase: start + commit)

private suspend fun SparkWallet.broadcastTokenTransactionV2(
    tokenTransaction: TokenTransaction,
    signingPublicKeys: List<ByteArray>?,
    revocationCommitments: List<ByteArray>?,
    idempotencyKey: String? = null,
): String {
    val (txHash, _) = broadcastTokenTransactionV2Detailed(
        tokenTransaction = tokenTransaction,
        signingPublicKeys = signingPublicKeys,
        revocationCommitments = revocationCommitments,
        idempotencyKey = idempotencyKey,
    )
    return txHash
}

private suspend fun SparkWallet.broadcastTokenTransactionV2Detailed(
    tokenTransaction: TokenTransaction,
    signingPublicKeys: List<ByteArray>?,
    revocationCommitments: List<ByteArray>?,
    idempotencyKey: String? = null,
): Pair<String, ByteArray?> {
    val stub = if (idempotencyKey != null) {
        getTokenStubWithIdempotency(idempotencyKey)
    } else {
        getTokenStub()
    }

    // Phase 1: Hash partial transaction and sign
    val partialHash = hashTokenTransactionV2(tokenTransaction, partialHash = true)
    val ownerSignatures = buildOwnerSignatures(tokenTransaction, partialHash, signingPublicKeys)

    val startRequest = StartTransactionRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setPartialTokenTransaction(tokenTransaction)
        .addAllPartialTokenTransactionOwnerSignatures(ownerSignatures)
        .setValidityDurationSeconds(60)
        .build()

    val startResponse = stub.startTransaction(startRequest)

    if (!startResponse.hasFinalTokenTransaction()) {
        throw SparkError.InvalidResponse("Missing final token transaction in start response")
    }

    val finalTx = startResponse.finalTokenTransaction

    // The coordinator may only add server-set fields; anything else is refused before the
    // wallet signs the final hash for each operator.
    TokenTransactionValidator.validate(
        final = finalTx,
        partial = tokenTransaction,
        keyshareInfo = if (startResponse.hasKeyshareInfo()) startResponse.keyshareInfo else null,
        expectations = TokenTransactionValidator.Expectations(
            operatorIdentityPublicKeys = collectOperatorIdentityPublicKeys(),
            operatorIdentifiers = config.signingOperators.map { it.identifier }.toSet(),
            threshold = config.signingThreshold,
            withdrawBondSats = config.expectedWithdrawBondSats,
            withdrawRelativeBlockLocktime = config.expectedWithdrawRelativeBlockLocktime,
        ),
    )

    // Phase 2: Hash final transaction and create per-operator signatures
    val finalHash = hashTokenTransactionV2(finalTx, partialHash = false)
    val operatorSignatures = buildOperatorSignatures(finalTx, finalHash)

    val commitRequest = CommitTransactionRequest.newBuilder()
        .setFinalTokenTransaction(finalTx)
        .setFinalTokenTransactionHash(ByteString.copyFrom(finalHash))
        .addAllInputTtxoSignaturesPerOperator(operatorSignatures)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .build()

    // Use a fresh stub without idempotency for commit phase
    val commitStub = getTokenStub()
    val commitResponse = commitStub.commitTransaction(commitRequest)

    val tokenId = if (commitResponse.hasTokenIdentifier()) commitResponse.tokenIdentifier.toByteArray() else null
    return finalHash.toHexString() to tokenId
}

// MARK: - Internal: Signature Helpers

private fun SparkWallet.buildOwnerSignatures(tx: TokenTransaction, hash: ByteArray, signingPublicKeys: List<ByteArray>?,): List<SignatureWithIndex> {
    val signatures = mutableListOf<SignatureWithIndex>()

    when (tx.tokenInputsCase) {
        TokenTransaction.TokenInputsCase.MINT_INPUT,
        TokenTransaction.TokenInputsCase.CREATE_INPUT -> {
            val sig = signer.signWithIdentityKey(hash)
            signatures.add(
                SignatureWithIndex.newBuilder()
                    .setSignature(ByteString.copyFrom(sig))
                    .setInputIndex(0)
                    .build()
            )
        }

        TokenTransaction.TokenInputsCase.TRANSFER_INPUT -> {
            val keys = signingPublicKeys
                ?: throw SparkError.TokenValidationFailed("Missing signing public keys for transfer")
            for ((i, key) in keys.withIndex()) {
                require(key.contentEquals(signer.identityPublicKey)) {
                    "Cannot sign with unknown key: ${key.toHexString()}"
                }
                val sig = signer.signWithIdentityKey(hash)
                signatures.add(
                    SignatureWithIndex.newBuilder()
                        .setSignature(ByteString.copyFrom(sig))
                        .setInputIndex(i)
                        .build()
                )
            }
        }

        else -> throw SparkError.InvalidResponse("Unknown token input type")
    }

    return signatures
}

private fun SparkWallet.buildOperatorSignatures(tx: TokenTransaction, finalHash: ByteArray,): List<InputTtxoSignaturesPerOperator> {
    val result = mutableListOf<InputTtxoSignaturesPerOperator>()

    for (operatorConfig in config.signingOperators) {
        val operatorPubKey = operatorConfig.identityPublicKeyHex.hexToByteArray()
        if (operatorPubKey.isEmpty()) continue

        val payloadHash = hashOperatorSpecificPayload(
            finalTokenTransactionHash = finalHash,
            operatorIdentityPublicKey = operatorPubKey,
        )

        val ttxoSignatures = mutableListOf<SignatureWithIndex>()

        when (tx.tokenInputsCase) {
            TokenTransaction.TokenInputsCase.MINT_INPUT,
            TokenTransaction.TokenInputsCase.CREATE_INPUT -> {
                val sig = signer.signWithIdentityKey(payloadHash)
                ttxoSignatures.add(
                    SignatureWithIndex.newBuilder()
                        .setSignature(ByteString.copyFrom(sig))
                        .setInputIndex(0)
                        .build()
                )
            }

            TokenTransaction.TokenInputsCase.TRANSFER_INPUT -> {
                val transferInput = tx.transferInput
                for (i in 0 until transferInput.outputsToSpendCount) {
                    val sig = signer.signWithIdentityKey(payloadHash)
                    ttxoSignatures.add(
                        SignatureWithIndex.newBuilder()
                            .setSignature(ByteString.copyFrom(sig))
                            .setInputIndex(i)
                            .build()
                    )
                }
            }

            else -> throw SparkError.InvalidResponse("Unknown token input type")
        }

        result.add(
            InputTtxoSignaturesPerOperator.newBuilder()
                .addAllTtxoSignatures(ttxoSignatures)
                .setOperatorIdentityPublicKey(ByteString.copyFrom(operatorPubKey))
                .build()
        )
    }

    return result
}

// MARK: - Internal: Fetch Token Outputs

internal suspend fun SparkWallet.fetchTokenOutputs(tokenIdentifiers: List<ByteArray>? = null,): List<OutputWithPreviousTransactionData> {
    val stub = getTokenStub()
    val allOutputs = mutableListOf<OutputWithPreviousTransactionData>()
    var cursor: String? = null

    do {
        val request = QueryTokenOutputsRequest.newBuilder()
            .addOwnerPublicKeys(ByteString.copyFrom(signer.identityPublicKey))
            .setNetwork(config.network.toProto())
            .apply {
                tokenIdentifiers?.forEach { id ->
                    addTokenIdentifiers(ByteString.copyFrom(id))
                }
                val pageReq = spark.Spark.PageRequest.newBuilder()
                    .setPageSize(QUERY_TOKEN_OUTPUTS_PAGE_SIZE)
                    .setDirection(spark.Spark.Direction.NEXT)
                cursor?.let { pageReq.setCursor(it) }
                setPageRequest(pageReq.build())
            }
            .build()

        val response = stub.queryTokenOutputs(request)
        allOutputs.addAll(response.outputsWithPreviousTransactionDataList)

        cursor = if (response.hasPageResponse() && response.pageResponse.nextCursor.isNotEmpty()) {
            response.pageResponse.nextCursor
        } else {
            null
        }
    } while (cursor != null)

    return allOutputs
}

// MARK: - Internal: Fetch Token Metadata

internal suspend fun SparkWallet.fetchTokenMetadata(tokenIdentifiers: List<ByteArray>,): Map<ByteString, TokenMetadataInfo> {
    if (tokenIdentifiers.isEmpty()) return emptyMap()

    val stub = getTokenStub()
    val request = QueryTokenMetadataRequest.newBuilder()
        .addAllTokenIdentifiers(tokenIdentifiers.map { ByteString.copyFrom(it) })
        .build()

    val response = stub.queryTokenMetadata(request)

    return response.tokenMetadataList.associate { meta ->
        val bech32Id = encodeBech32mTokenIdentifier(meta.tokenIdentifier.toByteArray(), config.network)
        meta.tokenIdentifier to TokenMetadataInfo(
            tokenIdentifier = bech32Id,
            rawTokenIdentifier = meta.tokenIdentifier.toByteArray(),
            issuerPublicKey = meta.issuerPublicKey.toByteArray(),
            tokenName = meta.tokenName,
            tokenTicker = meta.tokenTicker,
            decimals = meta.decimals.toUInt(),
            maxSupply = meta.maxSupply.toByteArray(),
            isFreezable = meta.isFreezable,
            extraMetadata = if (meta.hasExtraMetadata()) meta.extraMetadata.toByteArray() else null,
        )
    }
}

// MARK: - Internal: Helpers

/**
 * Now on the operators' clock: they refuse a client timestamp outside the transaction's validity
 * window measured on theirs (the reference SDK stamps server time too).
 */
private fun SparkWallet.currentTimestamp(): Timestamp {
    val now = serverClock.nowMillis()
    val seconds = now / 1000
    val nanos = ((now % 1000) * 1_000_000).toInt()
    // Truncate nanos to microsecond precision (matching Swift)
    val truncatedNanos = (nanos / 1000) * 1000
    return Timestamp.newBuilder()
        .setSeconds(seconds)
        .setNanos(truncatedNanos)
        .build()
}

internal fun SparkWallet.collectOperatorIdentityPublicKeys(): List<ByteString> {
    return config.signingOperators
        .map { it.identityPublicKeyHex.hexToByteArray() }
        .filter { it.isNotEmpty() }
        .sortedWith(
            Comparator { a, b ->
                for (i in 0 until minOf(a.size, b.size)) {
                    val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                    if (cmp != 0) return@Comparator cmp
                }
                a.size - b.size
            }
        )
        .map { ByteString.copyFrom(it) }
}
