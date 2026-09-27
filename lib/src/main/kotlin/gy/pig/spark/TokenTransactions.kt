package gy.pig.spark

import com.google.protobuf.ByteString
import spark_multisig.Multisig
import spark_token.BroadcastTransactionRequest
import spark_token.OutputWithPreviousTransactionData
import spark_token.PartialTokenOutput
import spark_token.PartialTokenTransaction
import spark_token.SignatureWithIndex
import spark_token.TokenCreateInput
import spark_token.TokenMintInput
import spark_token.TokenOutput
import spark_token.TokenOutputToSpend
import spark_token.TokenTransaction
import spark_token.TokenTransactionMetadata
import spark_token.TokenTransferInput
import java.math.BigInteger

/**
 * A token transaction the wallet has built and not yet sent, in the configured version
 * ([SparkConfig.tokenTransactionVersion]).
 */
internal sealed class TokenTransactionDraft {
    /** Sent with `start_transaction` and `commit_transaction`. */
    class V2(val transaction: TokenTransaction) : TokenTransactionDraft()

    /** Sent with `broadcast_transaction`. */
    class V3(val partial: PartialTokenTransaction) : TokenTransactionDraft()
}

/** An output a token transaction creates. */
internal data class TokenOutputSpec(val owner: ByteString, val tokenIdentifier: ByteString, val amount: BigInteger)

/** A transaction's final hash, hex, and for a create the new token's identifier. */
internal class SentTokenTransaction(val transactionHash: String, val tokenIdentifier: ByteArray?)

/**
 * How long the operators may take to carry out a V3 token transaction: the reference SDK's
 * default (the operators accept 1 to 300 seconds).
 */
internal const val TOKEN_VALIDITY_DURATION_SECONDS = 180L

// MARK: - Building

/**
 * The outputs of a transfer spending [spent] to [receivers]: the receivers' outputs, then change
 * to [changeOwner] for each token spent beyond what they are paid.
 */
internal fun transferOutputs(
    spent: List<OutputWithPreviousTransactionData>,
    receivers: List<TokenOutputSpec>,
    changeOwner: ByteString,
): List<TokenOutputSpec> {
    // Tokens in the order they are first spent.
    val change = LinkedHashMap<ByteString, BigInteger>()
    for (output in spent) {
        val token = output.output.tokenIdentifier
        change[token] = (change[token] ?: BigInteger.ZERO) + decodeUInt128(output.output.tokenAmount)
    }
    for (receiver in receivers) {
        val available = change[receiver.tokenIdentifier] ?: BigInteger.ZERO
        change[receiver.tokenIdentifier] = available - available.min(receiver.amount)
    }
    return receivers + change.mapNotNull { (token, amount) ->
        if (amount.signum() > 0) TokenOutputSpec(owner = changeOwner, tokenIdentifier = token, amount = amount) else null
    }
}

/** The outputs a transfer spends, in vout order: the order of its inputs and owner signatures. */
private fun inVoutOrder(spent: List<OutputWithPreviousTransactionData>): List<OutputWithPreviousTransactionData> =
    spent.sortedBy { it.previousTransactionVout.toUInt() }

/** A transfer spending [spent], in vout order, to [outputs]. */
internal fun SparkWallet.transferDraft(spent: List<OutputWithPreviousTransactionData>, outputs: List<TokenOutputSpec>): TokenTransactionDraft {
    val input = TokenTransferInput.newBuilder()
        .addAllOutputsToSpend(
            inVoutOrder(spent).map { output ->
                TokenOutputToSpend.newBuilder()
                    .setPrevTokenTransactionHash(output.previousTransactionHash)
                    .setPrevTokenTransactionVout(output.previousTransactionVout)
                    .build()
            },
        )
        .build()
    return draft(DraftInputs.Transfer(input), outputs)
}

/** A mint of [amount] of [tokenIdentifier] to the issuer, this wallet. */
internal fun SparkWallet.mintDraft(tokenIdentifier: ByteArray, amount: BigInteger): TokenTransactionDraft {
    val issuer = ByteString.copyFrom(signer.identityPublicKey)
    val token = ByteString.copyFrom(tokenIdentifier)
    val input = TokenMintInput.newBuilder().setIssuerPublicKey(issuer).setTokenIdentifier(token).build()
    return draft(DraftInputs.Mint(input), listOf(TokenOutputSpec(owner = issuer, tokenIdentifier = token, amount = amount)))
}

/** The creation of a token this wallet issues. */
internal fun SparkWallet.createDraft(input: TokenCreateInput): TokenTransactionDraft = draft(DraftInputs.Create(input), emptyList())

private sealed class DraftInputs {
    class Transfer(val input: TokenTransferInput) : DraftInputs()

    class Mint(val input: TokenMintInput) : DraftInputs()

    class Create(val input: TokenCreateInput) : DraftInputs()
}

private fun SparkWallet.draft(inputs: DraftInputs, outputs: List<TokenOutputSpec>): TokenTransactionDraft = when (config.tokenTransactionVersion) {
    TokenTransactionVersion.V2 -> {
        val transaction = TokenTransaction.newBuilder()
            .setVersion(2)
            .setNetwork(config.network.toProto())
        when (inputs) {
            is DraftInputs.Transfer -> transaction.setTransferInput(inputs.input)
            is DraftInputs.Mint -> transaction.setMintInput(inputs.input)
            is DraftInputs.Create -> transaction.setCreateInput(inputs.input)
        }
        // The coordinator adds the withdraw bond and locktime to V2 outputs.
        for (spec in outputs) {
            transaction.addTokenOutputs(
                TokenOutput.newBuilder()
                    .setOwnerPublicKey(spec.owner)
                    .setTokenIdentifier(spec.tokenIdentifier)
                    .setTokenAmount(ByteString.copyFrom(encodeUInt128(spec.amount))),
            )
        }
        transaction
            .addAllSparkOperatorIdentityPublicKeys(collectOperatorIdentityPublicKeys())
            .setClientCreatedTimestamp(currentTimestamp())
        TokenTransactionDraft.V2(transaction.build())
    }
    TokenTransactionVersion.V3 -> {
        val metadata = TokenTransactionMetadata.newBuilder()
            // Strictly ascending, as the operators require of V3 transactions.
            .addAllSparkOperatorIdentityPublicKeys(collectOperatorIdentityPublicKeys())
            .setNetwork(config.network.toProto())
            .setClientCreatedTimestamp(currentTimestamp())
            .setValidityDurationSeconds(TOKEN_VALIDITY_DURATION_SECONDS)
        val partial = PartialTokenTransaction.newBuilder()
            .setVersion(3)
            .setTokenTransactionMetadata(metadata)
        when (inputs) {
            is DraftInputs.Transfer -> partial.setTransferInput(inputs.input)
            is DraftInputs.Mint -> partial.setMintInput(inputs.input)
            is DraftInputs.Create -> partial.setCreateInput(inputs.input)
        }
        // V3 outputs carry the withdraw bond and locktime, which must equal the network's.
        for (spec in outputs) {
            partial.addPartialTokenOutputs(
                PartialTokenOutput.newBuilder()
                    .setOwnerPublicKey(spec.owner)
                    .setWithdrawBondSats(config.expectedWithdrawBondSats.toLong())
                    .setWithdrawRelativeBlockLocktime(config.expectedWithdrawRelativeBlockLocktime.toLong())
                    .setTokenIdentifier(spec.tokenIdentifier)
                    .setTokenAmount(ByteString.copyFrom(encodeUInt128(spec.amount))),
            )
        }
        TokenTransactionDraft.V3(partial.build())
    }
}

// MARK: - Sending

/**
 * Sends [draft], signing for the [spentOutputs] of a transfer (or as the issuer of a mint or
 * create), and returns the final transaction's hash and, for a create, the token's identifier.
 */
internal suspend fun SparkWallet.sendTokenTransaction(
    draft: TokenTransactionDraft,
    spentOutputs: List<OutputWithPreviousTransactionData> = emptyList(),
    idempotencyKey: String? = null,
): SentTokenTransaction {
    val owners = inVoutOrder(spentOutputs).map { it.output.ownerPublicKey.toByteArray() }
    return when (draft) {
        is TokenTransactionDraft.V2 -> broadcastTokenTransactionV2(
            tokenTransaction = draft.transaction,
            signingPublicKeys = if (draft.transaction.hasTransferInput()) owners else null,
            idempotencyKey = idempotencyKey,
        )
        is TokenTransactionDraft.V3 -> broadcastTokenTransactionV3(draft.partial, spentOutputOwners = owners, idempotencyKey = idempotencyKey)
    }
}

/**
 * V3: one `broadcast_transaction`, signed over the protohash of [partial], which binds its
 * inputs, outputs and amounts; the operators then build, sign and commit the final transaction,
 * which is checked to be [partial] before its hash is returned.
 */
private suspend fun SparkWallet.broadcastTokenTransactionV3(
    partial: PartialTokenTransaction,
    spentOutputOwners: List<ByteArray>,
    idempotencyKey: String?,
): SentTokenTransaction {
    val request = BroadcastTransactionRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setPartialTokenTransaction(partial)
        .addAllTokenTransactionOwnerSignatures(ownerSignaturesV3(partial, ProtoHash.hash(partial), spentOutputOwners))
        .build()

    val response = getTokenStubWithIdempotency(idempotencyKey).broadcastTransaction(request)
    if (!response.hasFinalTokenTransaction()) {
        throw SparkError.InvalidResponse("Missing final token transaction in broadcast response")
    }
    TokenTransactionValidator.validateV3(response.finalTokenTransaction, partial)
    return SentTokenTransaction(
        transactionHash = ProtoHash.hash(response.finalTokenTransaction).toHexString(),
        tokenIdentifier = if (response.hasTokenIdentifier()) response.tokenIdentifier.toByteArray() else null,
    )
}

/**
 * One signature per input of a transfer, by the owner of the output it spends, or one by the
 * issuer for a mint or create; in `single_signature`, as the reference SDK sends them.
 */
private fun SparkWallet.ownerSignaturesV3(partial: PartialTokenTransaction, hash: ByteArray, spentOutputOwners: List<ByteArray>,): List<SignatureWithIndex> {
    val keys = when (partial.tokenInputsCase) {
        PartialTokenTransaction.TokenInputsCase.TRANSFER_INPUT -> {
            if (spentOutputOwners.size != partial.transferInput.outputsToSpendCount) {
                throw SparkError.TokenValidationFailed("Missing signing keys for the outputs to spend")
            }
            spentOutputOwners
        }
        PartialTokenTransaction.TokenInputsCase.MINT_INPUT,
        PartialTokenTransaction.TokenInputsCase.CREATE_INPUT,
        -> listOf(signer.identityPublicKey)
        else -> throw SparkError.TokenValidationFailed("Token transaction has no inputs")
    }
    return keys.mapIndexed { index, key ->
        if (!key.contentEquals(signer.identityPublicKey)) {
            throw SparkError.TokenValidationFailed("Cannot sign with unknown key: ${key.toHexString()}")
        }
        val keyed = Multisig.KeyedSignature.newBuilder()
            .setPublicKey(ByteString.copyFrom(key))
            .setSignature(ByteString.copyFrom(signer.signWithIdentityKey(hash)))
        SignatureWithIndex.newBuilder()
            .setInputIndex(index)
            .setSingleSignature(keyed)
            .build()
    }
}
