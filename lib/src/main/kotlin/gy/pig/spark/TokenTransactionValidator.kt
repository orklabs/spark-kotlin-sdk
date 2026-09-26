package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark
import spark_token.TokenCreateInput
import spark_token.TokenMintInput
import spark_token.TokenTransaction
import spark_token.TokenTransferInput

/**
 * Checks that the "final" token transaction the coordinator returns from `start_transaction` is
 * the transaction the wallet submitted, plus only the server-set fields it is allowed to add
 * (output ids, revocation commitments, withdraw bond and locktime, expiry). Mirrors the reference
 * SDK's `validateTokenTransaction`. Runs before the wallet signs the final hash for every
 * operator, so a coordinator cannot redirect or resize token outputs.
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

        val expectedKeys = expectations.operatorIdentityPublicKeys.toSet()
        if (final.sparkOperatorIdentityPublicKeysList.toSet() != expectedKeys ||
            final.sparkOperatorIdentityPublicKeysList.size != expectations.operatorIdentityPublicKeys.size ||
            partial.sparkOperatorIdentityPublicKeysList.toSet() != expectedKeys
        ) {
            fail("operator identity public keys changed")
        }

        validateInputs(final, partial)
        validateOutputs(final, partial, expectations)
        if (keyshareInfo != null) validateKeyshare(keyshareInfo, expectations)
    }

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
