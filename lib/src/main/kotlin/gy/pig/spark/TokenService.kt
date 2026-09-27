package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import spark_token.*
import java.math.BigInteger
import java.text.Normalizer

private const val QUERY_TOKEN_OUTPUTS_PAGE_SIZE = 100
private const val MAX_TOKEN_OUTPUTS_TX = 500

// MARK: - Public Token API

/**
 * Transfer tokens to a receiver's Spark address. A Spark invoice is refused with
 * [SparkError.InvalidAddress], as in `send(receiverSparkAddress, amountSats)`.
 *
 * @param idempotencyKey Makes retries safe. A retry with the same key, on the same wallet,
 *   resends the transaction the first call built, so the transfer is made at most once: a retry
 *   after it went through returns its hash again, and a retry after it failed completes it if it
 *   can still be sent, else fails again. A key used for another token, amount or receiver is
 *   refused with [SparkError.InvalidArgument]. The wallet remembers the last 1,000 keys; use a
 *   new key for a new transfer.
 */
public suspend fun SparkWallet.transferTokens(
    tokenIdentifier: Bech32mTokenIdentifier,
    tokenAmount: BigInteger,
    receiverSparkAddress: String,
    strategy: TokenOutputSelectionStrategy = TokenOutputSelectionStrategy.SMALL_FIRST,
    idempotencyKey: String? = null,
): String {
    val (rawTokenId, _) = decodeBech32mTokenIdentifier(tokenIdentifier, config.network)
    // The receiver's identity key, from a Spark address for this network (a Spark invoice is
    // refused), before any output is fetched.
    val receiverData = SparkAddress.decode(receiverSparkAddress, config.network)
    val request = TokenTransferAttempts.Request(
        tokenIdentifier = ByteString.copyFrom(rawTokenId),
        amount = tokenAmount,
        receiverIdentityPublicKey = ByteString.copyFrom(receiverData),
    )

    val earlier = idempotencyKey?.let { tokenTransferAttempts.attempt(it) }
    val attempt = if (earlier != null) {
        if (earlier.request != request) {
            throw SparkError.InvalidArgument("idempotency key $idempotencyKey was used for a different token transfer")
        }
        earlier
    } else {
        newTokenTransfer(request, tokenIdentifier, strategy).also { attempt ->
            idempotencyKey?.let { tokenTransferAttempts.remember(attempt, it) }
        }
    }

    return sendTokenTransaction(attempt.transaction, spentOutputs = attempt.spentOutputs, idempotencyKey = idempotencyKey).transactionHash
}

/** Picks outputs for [request] and builds its transaction, with change back to the wallet. */
private suspend fun SparkWallet.newTokenTransfer(
    request: TokenTransferAttempts.Request,
    tokenIdentifier: Bech32mTokenIdentifier,
    strategy: TokenOutputSelectionStrategy,
): TokenTransferAttempts.Attempt {
    val outputs = fetchTokenOutputs(tokenIdentifiers = listOf(request.tokenIdentifier.toByteArray()))
    if (outputs.isEmpty()) {
        throw SparkError.InsufficientTokenBalance(token = tokenIdentifier, need = "${request.amount}", have = "0")
    }

    // Only available outputs no other send from this wallet has picked (see TokenOutputLocks).
    val selected = tokenOutputLocks.acquire(outputs) { selectTokenOutputs(it, request.amount, strategy) }

    val receiver = TokenOutputSpec(owner = request.receiverIdentityPublicKey, tokenIdentifier = request.tokenIdentifier, amount = request.amount)
    val draft = transferDraft(
        spent = selected,
        outputs = transferOutputs(spent = selected, receivers = listOf(receiver), changeOwner = ByteString.copyFrom(signer.identityPublicKey)),
    )
    return TokenTransferAttempts.Attempt(request = request, transaction = draft, spentOutputs = selected)
}

public suspend fun SparkWallet.getTokenBalances(): List<TokenBalance> {
    val outputs = fetchTokenOutputs()

    val balancesByToken = mutableMapOf<ByteString, Pair<BigInteger, BigInteger>>()
    for (output in outputs) {
        val tokenId = output.output.tokenIdentifier
        val amount = decodeUInt128(output.output.tokenAmount)
        val (owned, available) = balancesByToken[tokenId] ?: (BigInteger.ZERO to BigInteger.ZERO)
        balancesByToken[tokenId] = (owned + amount) to (if (TokenOutputLocks.isAvailable(output)) available + amount else available)
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

/**
 * Create a new token on Spark. The caller's identity key becomes the issuer.
 *
 * The parameters are checked as the operators check them: the name 3–20 and the ticker 3–6
 * UTF-8 bytes, both in Unicode normalization form C; decimals up to 255; extra metadata up to
 * 1024 bytes. A token that breaks them is refused with [SparkError.TokenValidationFailed].
 */
public suspend fun SparkWallet.createToken(
    tokenName: String,
    tokenTicker: String,
    decimals: UInt,
    maxSupply: BigInteger = BigInteger.ZERO,
    isFreezable: Boolean,
    extraMetadata: ByteArray? = null,
): TokenCreationResult {
    validateTokenParameters(tokenName, tokenTicker, decimals, extraMetadata)

    val createInput = TokenCreateInput.newBuilder()
        .setIssuerPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setTokenName(tokenName)
        .setTokenTicker(tokenTicker)
        .setDecimals(decimals.toInt())
        .setMaxSupply(ByteString.copyFrom(encodeUInt128(maxSupply)))
        .setIsFreezable(isFreezable)
        .apply {
            extraMetadata?.let { setExtraMetadata(ByteString.copyFrom(it)) }
        }
        .build()

    val sent = sendTokenTransaction(createDraft(createInput))
    val bech32TokenId = sent.tokenIdentifier?.let { encodeBech32mTokenIdentifier(it, config.network) }
    return TokenCreationResult(transactionHash = sent.transactionHash, tokenIdentifier = bech32TokenId)
}

/**
 * The operators' rules for a new token (`TokenMetadata.ValidatePartial`), also the reference
 * SDK's: the name 3–20 and the ticker 3–6 UTF-8 bytes, both in Unicode normalization form C;
 * decimals up to 255; extra metadata up to 1024 bytes. The operators refuse a token that breaks
 * them with INTERNAL, which reaches the wallet as "Something went wrong.", so each rule is
 * checked here to say which one.
 */
internal fun validateTokenParameters(tokenName: String, tokenTicker: String, decimals: UInt, extraMetadata: ByteArray?) {
    fun refuse(message: String): Nothing = throw SparkError.TokenValidationFailed(message)
    if (!Normalizer.isNormalized(tokenName, Normalizer.Form.NFC)) refuse("Token name must be NFC-normalized UTF-8")
    if (!Normalizer.isNormalized(tokenTicker, Normalizer.Form.NFC)) refuse("Token ticker must be NFC-normalized UTF-8")
    val nameBytes = tokenName.toByteArray(Charsets.UTF_8).size
    if (nameBytes !in 3..20) refuse("Token name must be 3-20 UTF-8 bytes, not $nameBytes")
    val tickerBytes = tokenTicker.toByteArray(Charsets.UTF_8).size
    if (tickerBytes !in 3..6) refuse("Token ticker must be 3-6 UTF-8 bytes, not $tickerBytes")
    if (decimals > 255u) refuse("Decimals must be <= 255")
    if (extraMetadata != null && extraMetadata.size > 1024) refuse("Extra metadata must be <= 1024 bytes")
}

/**
 * Mint additional tokens for an existing token. Caller must be the token issuer.
 *
 * @param idempotencyKey Sent with the transaction, so the operators answer a retry with the key
 *   from their idempotency records rather than minting again.
 */
public suspend fun SparkWallet.mintTokens(tokenIdentifier: Bech32mTokenIdentifier, tokenAmount: BigInteger, idempotencyKey: String? = null,): String {
    if (tokenAmount.signum() <= 0) throw SparkError.TokenValidationFailed("Mint amount must be greater than 0")

    val (rawTokenId, _) = decodeBech32mTokenIdentifier(tokenIdentifier, config.network)
    return sendTokenTransaction(mintDraft(rawTokenId, tokenAmount), idempotencyKey = idempotencyKey).transactionHash
}

/** Burn tokens by transferring them to a dead address. */
public suspend fun SparkWallet.burnTokens(
    tokenIdentifier: Bech32mTokenIdentifier,
    tokenAmount: BigInteger,
    strategy: TokenOutputSelectionStrategy = TokenOutputSelectionStrategy.SMALL_FIRST,
): String {
    val burnPubKey = ByteArray(33).apply { fill(0x02) }
    val (rawTokenId, _) = decodeBech32mTokenIdentifier(tokenIdentifier, config.network)

    val outputs = fetchTokenOutputs(tokenIdentifiers = listOf(rawTokenId))
    if (outputs.isEmpty()) {
        throw SparkError.InsufficientTokenBalance(token = tokenIdentifier, need = "$tokenAmount", have = "0")
    }

    val selected = tokenOutputLocks.acquire(outputs) { selectTokenOutputs(it, tokenAmount, strategy) }

    val burn = TokenOutputSpec(owner = ByteString.copyFrom(burnPubKey), tokenIdentifier = ByteString.copyFrom(rawTokenId), amount = tokenAmount)
    val draft = transferDraft(
        spent = selected,
        outputs = transferOutputs(spent = selected, receivers = listOf(burn), changeOwner = ByteString.copyFrom(signer.identityPublicKey)),
    )
    return sendTokenTransaction(draft, spentOutputs = selected).transactionHash
}

// MARK: - Token Output Selection

internal fun selectTokenOutputs(
    outputs: List<OutputWithPreviousTransactionData>,
    amount: BigInteger,
    strategy: TokenOutputSelectionStrategy,
): List<OutputWithPreviousTransactionData> {
    if (amount.signum() <= 0) throw SparkError.TokenValidationFailed("Token amount must be greater than 0")

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

// MARK: - Internal: V2 Broadcast (Two-Phase: start + commit)

internal suspend fun SparkWallet.broadcastTokenTransactionV2(
    tokenTransaction: TokenTransaction,
    signingPublicKeys: List<ByteArray>?,
    idempotencyKey: String? = null,
): SentTokenTransaction {
    val stub = getTokenStubWithIdempotency(idempotencyKey)

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
    val commitResponse = getTokenStub().commitTransaction(commitRequest)

    val tokenId = if (commitResponse.hasTokenIdentifier()) commitResponse.tokenIdentifier.toByteArray() else null
    return SentTokenTransaction(transactionHash = finalHash.toHexString(), tokenIdentifier = tokenId)
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
                if (!key.contentEquals(signer.identityPublicKey)) {
                    throw SparkError.TokenValidationFailed("Cannot sign with unknown key: ${key.toHexString()}")
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

/** Token identifiers per metadata query: the operators' `MaxTokenMetadataFilterValues`. */
internal const val TOKEN_METADATA_BATCH_SIZE = 500

/**
 * Metadata of [tokenIdentifiers], asked for at most [TOKEN_METADATA_BATCH_SIZE] at a time: the
 * operators refuse larger filters, and anyone can send a wallet tokens of as many kinds as they
 * like.
 */
internal suspend fun SparkWallet.fetchTokenMetadata(tokenIdentifiers: List<ByteArray>,): Map<ByteString, TokenMetadataInfo> {
    if (tokenIdentifiers.isEmpty()) return emptyMap()

    val stub = getTokenStub()
    val metadata = tokenIdentifiers.chunked(TOKEN_METADATA_BATCH_SIZE).flatMap { batch ->
        val request = QueryTokenMetadataRequest.newBuilder()
            .addAllTokenIdentifiers(batch.map { ByteString.copyFrom(it) })
            .build()
        stub.queryTokenMetadata(request).tokenMetadataList
    }

    return metadata.associate { meta ->
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
internal fun SparkWallet.currentTimestamp(): Timestamp {
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
