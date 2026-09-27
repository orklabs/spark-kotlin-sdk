package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import spark.Spark
import spark_token.FinalTokenTransaction
import spark_token.PartialTokenTransaction
import spark_token.TokenCreateInput
import spark_token.TokenMintInput
import spark_token.TokenTransaction
import spark_token.TokenTransferInput

/**
 * Checks that the "final" token transaction the coordinator returns from `start_transaction` is
 * the transaction the wallet submitted, plus only the server-set fields it is allowed to add
 * (output ids, revocation commitments, withdraw bond and locktime, expiry), and that its keyshare
 * info names the configured operators. Mirrors the reference SDK's `validateTokenTransaction`.
 * Runs before the wallet signs the final hash for every operator, so a coordinator cannot
 * redirect or resize token outputs.
 */
internal object TokenTransactionValidator {

    class Expectations(
        /** Operator identity keys the wallet placed in the partial transaction. */
        val operatorIdentityPublicKeys: List<ByteString>,
        /** Operator identifiers from the wallet configuration. */
        val operatorIdentifiers: Set<String>,
        val threshold: UInt,
        val withdrawBondSats: ULong,
        val withdrawRelativeBlockLocktime: ULong,
    )

    private fun fail(what: String): Nothing = throw SparkError.UntrustedResponse("final token transaction rejected: $what")

    fun validate(final: TokenTransaction, partial: TokenTransaction, keyshareInfo: Spark.SigningKeyshare?, expectations: Expectations) {
        if (final.version != partial.version) fail("version changed")
        if (final.network != partial.network) fail("network changed")
        if (final.invoiceAttachmentsList != partial.invoiceAttachmentsList) fail("invoice attachments changed")
        // To the millisecond, the precision the transaction hash covers.
        if (!final.hasClientCreatedTimestamp() ||
            !partial.hasClientCreatedTimestamp() ||
            milliseconds(final.clientCreatedTimestamp) != milliseconds(partial.clientCreatedTimestamp)
        ) {
            fail("client created timestamp changed")
        }

        val expectedKeys = expectations.operatorIdentityPublicKeys.toSet()
        if (final.sparkOperatorIdentityPublicKeysList.toSet() != expectedKeys ||
            final.sparkOperatorIdentityPublicKeysList.size != expectations.operatorIdentityPublicKeys.size ||
            partial.sparkOperatorIdentityPublicKeysList.toSet() != expectedKeys
        ) {
            fail("operator identity public keys changed")
        }

        validateInputs(final, partial)
        validateOutputs(final, partial, expectations)
        // The operators always send it, and the reference SDK refuses an answer without it.
        if (keyshareInfo == null) fail("keyshare info missing")
        validateKeyshare(keyshareInfo, expectations)
    }

    /**
     * Checks that the final transaction the coordinator answers `broadcast_transaction` with is
     * the V3 partial transaction the wallet signed, plus only what the operators add: a
     * revocation commitment per output, and a create's creation entity key. The wallet signs
     * only the partial transaction, whose hash already binds the inputs, outputs and amounts;
     * this makes sure the hash the SDK reports is of that transaction. Fields are compared by
     * their protohash, which is what the transaction's hash covers.
     */
    fun validateV3(final: FinalTokenTransaction, partial: PartialTokenTransaction) {
        if (final.version != partial.version) fail("version changed")
        if (!final.hasTokenTransactionMetadata() ||
            !ProtoHash.hash(final.tokenTransactionMetadata).contentEquals(ProtoHash.hash(partial.tokenTransactionMetadata))
        ) {
            fail("metadata changed")
        }
        if (final.hasExecuteBefore() != partial.hasExecuteBefore() ||
            (partial.hasExecuteBefore() && final.executeBefore != partial.executeBefore)
        ) {
            fail("execute-before changed")
        }
        validateInputsV3(final, partial)
        if (final.finalTokenOutputsCount != partial.partialTokenOutputsCount) {
            fail("output count changed (${final.finalTokenOutputsCount} vs ${partial.partialTokenOutputsCount})")
        }
        for (index in 0 until partial.partialTokenOutputsCount) {
            val answered = final.getFinalTokenOutputs(index)
            if (!answered.hasPartialTokenOutput() ||
                !ProtoHash.hash(answered.partialTokenOutput).contentEquals(ProtoHash.hash(partial.getPartialTokenOutputs(index)))
            ) {
                fail("output $index changed")
            }
            if (answered.revocationCommitment.size() != 33) fail("output $index has no revocation commitment")
        }
    }

    private fun validateInputsV3(final: FinalTokenTransaction, partial: PartialTokenTransaction) {
        // Both oneofs number mint 3, transfer 4 and create 5.
        if (final.tokenInputsCase.number != partial.tokenInputsCase.number) fail("transaction type changed or missing")
        when (partial.tokenInputsCase) {
            PartialTokenTransaction.TokenInputsCase.TRANSFER_INPUT ->
                if (!ProtoHash.hash(final.transferInput).contentEquals(ProtoHash.hash(partial.transferInput))) fail("inputs changed")
            PartialTokenTransaction.TokenInputsCase.MINT_INPUT ->
                if (!ProtoHash.hash(final.mintInput).contentEquals(ProtoHash.hash(partial.mintInput))) fail("mint input changed")
            PartialTokenTransaction.TokenInputsCase.CREATE_INPUT -> {
                // The creation entity key is set by the operators.
                val answered = final.createInput.toBuilder().clearCreationEntityPublicKey().build()
                if (!ProtoHash.hash(answered).contentEquals(ProtoHash.hash(partial.createInput))) fail("create input changed")
            }
            else -> fail("transaction type changed or missing")
        }
    }

    private fun milliseconds(timestamp: Timestamp): Long = timestamp.seconds * 1_000 + timestamp.nanos / 1_000_000

    private fun validateInputs(final: TokenTransaction, partial: TokenTransaction) {
        val finalCase = final.tokenInputsCase
        if (finalCase != partial.tokenInputsCase) fail("transaction type changed or missing")
        when (finalCase) {
            TokenTransaction.TokenInputsCase.MINT_INPUT -> validateMintInput(final.mintInput, partial.mintInput)
            TokenTransaction.TokenInputsCase.CREATE_INPUT -> validateCreateInput(final.createInput, partial.createInput)
            TokenTransaction.TokenInputsCase.TRANSFER_INPUT -> validateTransferInput(final.transferInput, partial.transferInput)
            else -> fail("transaction type changed or missing")
        }
    }

    private fun validateMintInput(f: TokenMintInput, p: TokenMintInput) {
        if (f.issuerPublicKey.isEmpty || f.issuerPublicKey != p.issuerPublicKey) fail("mint issuer changed")
        if (!f.hasTokenIdentifier() || !p.hasTokenIdentifier() || f.tokenIdentifier != p.tokenIdentifier) {
            fail("mint token identifier changed")
        }
    }

    private fun validateCreateInput(f: TokenCreateInput, p: TokenCreateInput) {
        if (f.issuerPublicKey.isEmpty || f.issuerPublicKey != p.issuerPublicKey) fail("create issuer changed")
        if (f.tokenName != p.tokenName ||
            f.tokenTicker != p.tokenTicker ||
            f.decimals != p.decimals ||
            f.maxSupply != p.maxSupply ||
            f.isFreezable != p.isFreezable
        ) {
            fail("token creation parameters changed")
        }
        if (f.hasExtraMetadata() != p.hasExtraMetadata() || f.extraMetadata != p.extraMetadata) {
            fail("token extra metadata changed")
        }
    }

    private fun validateTransferInput(final: TokenTransferInput, partial: TokenTransferInput) {
        val f = final.outputsToSpendList
        val p = partial.outputsToSpendList
        if (f.size != p.size || p.isEmpty()) fail("outputs to spend count changed")
        for (index in p.indices) {
            if (f[index].prevTokenTransactionHash != p[index].prevTokenTransactionHash ||
                f[index].prevTokenTransactionVout != p[index].prevTokenTransactionVout
            ) {
                fail("input $index changed")
            }
        }
    }

    private fun validateOutputs(final: TokenTransaction, partial: TokenTransaction, expectations: Expectations) {
        if (final.tokenOutputsCount != partial.tokenOutputsCount) {
            fail("output count changed (${final.tokenOutputsCount} vs ${partial.tokenOutputsCount})")
        }
        for (index in 0 until partial.tokenOutputsCount) {
            val fo = final.getTokenOutputs(index)
            val po = partial.getTokenOutputs(index)
            if (fo.ownerPublicKey != po.ownerPublicKey) fail("output $index owner changed")
            if (fo.tokenAmount != po.tokenAmount) fail("output $index amount changed")
            if (po.hasTokenIdentifier() && (!fo.hasTokenIdentifier() || fo.tokenIdentifier != po.tokenIdentifier)) {
                fail("output $index token identifier changed")
            }
            if (fo.hasTokenPublicKey() && po.hasTokenPublicKey() && fo.tokenPublicKey != po.tokenPublicKey) {
                fail("output $index token public key changed")
            }
            // uint64 on the wire: compare unsigned.
            if (fo.hasWithdrawBondSats() && fo.withdrawBondSats.toULong() != expectations.withdrawBondSats) {
                fail(
                    "output $index withdraw bond ${fo.withdrawBondSats.toULong()} differs from expected ${expectations.withdrawBondSats}",
                )
            }
            if (fo.hasWithdrawRelativeBlockLocktime() &&
                fo.withdrawRelativeBlockLocktime.toULong() != expectations.withdrawRelativeBlockLocktime
            ) {
                fail(
                    "output $index withdraw locktime ${fo.withdrawRelativeBlockLocktime.toULong()} differs from expected " +
                        "${expectations.withdrawRelativeBlockLocktime}",
                )
            }
        }
    }

    private fun validateKeyshare(keyshareInfo: Spark.SigningKeyshare, expectations: Expectations) {
        val threshold = keyshareInfo.threshold.toUInt()
        if (threshold != expectations.threshold) {
            fail("keyshare threshold $threshold differs from expected ${expectations.threshold}")
        }
        val owners = keyshareInfo.ownerIdentifiersList
        if (owners.size != expectations.operatorIdentifiers.size) {
            fail("keyshare operator count ${owners.size} differs from configured ${expectations.operatorIdentifiers.size}")
        }
        if (owners.toSet().size != owners.size) fail("duplicate keyshare owner identifiers")
        for (identifier in owners) {
            if (identifier !in expectations.operatorIdentifiers) fail("keyshare owner $identifier is not a configured operator")
        }
    }
}
