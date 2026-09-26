package gy.pig.spark

import com.google.protobuf.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import spark.Spark
import spark_token.InvoiceAttachment
import spark_token.TokenCreateInput
import spark_token.TokenMintInput
import spark_token.TokenOutput
import spark_token.TokenOutputToSpend
import spark_token.TokenTransaction
import spark_token.TokenTransferInput
import java.math.BigInteger

/**
 * The coordinator's final token transaction must equal the partial one apart from server-set
 * fields. Ported from the Swift SDK's `TokenTransactionValidationTests.swift`.
 */
class TokenTransactionValidationTests {

    private val owner = byteArrayOf(0x02) + bytes(0x11, 32)
    private val receiver = byteArrayOf(0x03) + bytes(0x22, 32)
    private val tokenId = bytes(0x33, 32)
    private val operatorKeys = listOf(
        byteArrayOf(0x02) + bytes(0xA1, 32),
        byteArrayOf(0x02) + bytes(0xA2, 32),
        byteArrayOf(0x03) + bytes(0xA3, 32),
    ).map { it.toByteString() }
    private val identifiers = setOf("op1", "op2", "op3")

    private val expectations = TokenTransactionValidator.Expectations(
        operatorIdentityPublicKeys = operatorKeys,
        operatorIdentifiers = identifiers,
        threshold = 2u,
        withdrawBondSats = 10_000uL,
        withdrawRelativeBlockLocktime = 1_000uL,
    )

    private fun amount(value: Long) = encodeUInt128(BigInteger.valueOf(value)).toByteString()

    private fun timestamp(seconds: Long): Timestamp = Timestamp.newBuilder().setSeconds(seconds).build()

    private fun output(owner: ByteArray, amount: Long): TokenOutput = TokenOutput.newBuilder()
        .setOwnerPublicKey(owner.toByteString())
        .setTokenIdentifier(tokenId.toByteString())
        .setTokenAmount(amount(amount))
        .build()

    private fun partialTransfer(): TokenTransaction {
        val input = TokenTransferInput.newBuilder().addAllOutputsToSpend(
            (0 until 2).map { i ->
                TokenOutputToSpend.newBuilder()
                    .setPrevTokenTransactionHash(bytes(0x50 + i, 32).toByteString())
                    .setPrevTokenTransactionVout(i)
                    .build()
            },
        )
        return TokenTransaction.newBuilder()
            .setVersion(2)
            .setNetwork(Spark.Network.MAINNET)
            .setTransferInput(input)
            .addTokenOutputs(output(receiver, 700))
            .addTokenOutputs(output(owner, 300))
            .addAllSparkOperatorIdentityPublicKeys(operatorKeys)
            .setClientCreatedTimestamp(timestamp(1_700_000_000))
            .build()
    }

    /** What an honest coordinator returns: the partial transaction plus server-set fields. */
    private fun finalize(partial: TokenTransaction): TokenTransaction {
        val builder = partial.toBuilder()
        for (i in 0 until builder.tokenOutputsCount) {
            builder.setTokenOutputs(
                i,
                builder.getTokenOutputs(i).toBuilder()
                    .setId("output-$i")
                    .setRevocationCommitment((byteArrayOf(0x02) + bytes(0x60 + i, 32)).toByteString())
                    .setWithdrawBondSats(10_000)
                    .setWithdrawRelativeBlockLocktime(1_000)
                    .build(),
            )
        }
        return builder.setExpiryTime(timestamp(1_700_000_060)).build()
    }

    private fun keyshare(owners: List<String> = listOf("op1", "op2", "op3"), threshold: Int = 2): Spark.SigningKeyshare =
        Spark.SigningKeyshare.newBuilder().addAllOwnerIdentifiers(owners).setThreshold(threshold).build()

    @Test
    fun anHonestFinalTransactionPassesWithAndWithoutKeyshareInfo() {
        val partial = partialTransfer()
        val final = finalize(partial)
        TokenTransactionValidator.validate(final, partial, keyshareInfo = null, expectations = expectations)
        TokenTransactionValidator.validate(final, partial, keyshareInfo = keyshare(), expectations = expectations)

        // Mint and create transactions too.
        val mintInput = TokenMintInput.newBuilder()
            .setIssuerPublicKey(owner.toByteString())
            .setTokenIdentifier(tokenId.toByteString())
            .build()
        val mint = partial.toBuilder()
            .setMintInput(mintInput)
            .clearTokenOutputs()
            .addTokenOutputs(output(owner, 1_000))
            .build()
        TokenTransactionValidator.validate(finalize(mint), mint, keyshareInfo = null, expectations = expectations)

        val createInput = TokenCreateInput.newBuilder()
            .setIssuerPublicKey(owner.toByteString())
            .setTokenName("Acme")
            .setTokenTicker("ACME")
            .setDecimals(6)
            .setMaxSupply(amount(1_000_000))
            .setIsFreezable(false)
            .build()
        val create = partial.toBuilder().setCreateInput(createInput).clearTokenOutputs().build()
        val finalized = finalize(create)
        val finalCreate = finalized.toBuilder()
            .setCreateInput(
                finalized.createInput.toBuilder()
                    .setCreationEntityPublicKey((byteArrayOf(0x02) + bytes(0x77, 32)).toByteString()), // server-set
            )
            .build()
        TokenTransactionValidator.validate(finalCreate, create, keyshareInfo = null, expectations = expectations)
    }

    @Test
    fun everyTamperedFieldIsRefused() {
        val partial = partialTransfer()
        val honest = finalize(partial)

        fun expectRejected(label: String, mutate: TokenTransaction.Builder.() -> Unit) {
            val final = honest.toBuilder().apply(mutate).build()
            val error = expectSparkError(label) {
                TokenTransactionValidator.validate(final, partial, keyshareInfo = null, expectations = expectations)
            }
            assertEquals(label, SparkError.UntrustedResponse::class, error::class)
        }

        fun TokenTransaction.Builder.editOutput(index: Int, edit: TokenOutput.Builder.() -> Unit) {
            setTokenOutputs(index, getTokenOutputs(index).toBuilder().apply(edit).build())
        }

        expectRejected("output owner redirected") { editOutput(0) { ownerPublicKey = (byteArrayOf(0x02) + bytes(0xEE, 32)).toByteString() } }
        expectRejected("output amount changed") { editOutput(0) { tokenAmount = amount(701) } }
        expectRejected("change amount changed") { editOutput(1) { tokenAmount = amount(299) } }
        expectRejected("output token id changed") { editOutput(1) { tokenIdentifier = bytes(0x44, 32).toByteString() } }
        expectRejected("output removed") { removeTokenOutputs(tokenOutputsCount - 1) }
        expectRejected("output added") { addTokenOutputs(output(receiver, 1)) }
        expectRejected("withdraw bond lowered") { editOutput(0) { withdrawBondSats = 1 } }
        expectRejected("withdraw locktime lowered") { editOutput(0) { withdrawRelativeBlockLocktime = 10 } }
        expectRejected("network changed") { network = Spark.Network.REGTEST }
        expectRejected("version changed") { version = 3 }
        expectRejected("operator key swapped") {
            setSparkOperatorIdentityPublicKeys(0, (byteArrayOf(0x02) + bytes(0xBB, 32)).toByteString())
        }
        expectRejected("operator key dropped") {
            val kept = sparkOperatorIdentityPublicKeysList.dropLast(1)
            clearSparkOperatorIdentityPublicKeys().addAllSparkOperatorIdentityPublicKeys(kept)
        }
        expectRejected("operator key added") { addSparkOperatorIdentityPublicKeys((byteArrayOf(0x02) + bytes(0xBB, 32)).toByteString()) }
        expectRejected("input replaced") {
            val input = transferInput.toBuilder()
            input.setOutputsToSpend(0, input.getOutputsToSpend(0).toBuilder().setPrevTokenTransactionVout(9).build())
            setTransferInput(input)
        }
        expectRejected("input added") {
            setTransferInput(transferInput.toBuilder().addOutputsToSpend(transferInput.getOutputsToSpend(0)))
        }
        expectRejected("type changed") {
            setMintInput(TokenMintInput.newBuilder().setIssuerPublicKey(owner.toByteString()).setTokenIdentifier(tokenId.toByteString()))
        }
        expectRejected("invoice attachment added") {
            addInvoiceAttachments(InvoiceAttachment.newBuilder().setSparkInvoice("spark1..."))
        }
    }

    @Test
    fun keyshareInfoMustNameTheConfiguredOperatorsWithTheExpectedThreshold() {
        val partial = partialTransfer()
        val final = finalize(partial)
        for ((label, info) in listOf(
            "wrong threshold" to keyshare(threshold = 1),
            "too few operators" to keyshare(listOf("op1", "op2")),
            "unknown operator" to keyshare(listOf("op1", "op2", "evil")),
            "duplicate operator" to keyshare(listOf("op1", "op1", "op2")),
        )) {
            expectSparkError(label) { TokenTransactionValidator.validate(final, partial, keyshareInfo = info, expectations = expectations) }
        }
    }

    @Test
    fun configDerivesTheReferenceThresholdAndTokenExpectations() {
        assertEquals(2u, SparkConfig.defaultThreshold(operatorCount = 3))
        assertEquals(3u, SparkConfig.defaultThreshold(operatorCount = 5))
        assertEquals(2u, SparkConfig.defaultThreshold(operatorCount = 1))
        val config = SparkConfig(network = SparkNetwork.MAINNET)
        assertEquals(2u, config.signingThreshold)
        assertEquals(10_000uL, config.expectedWithdrawBondSats)
        assertEquals(1_000uL, config.expectedWithdrawRelativeBlockLocktime)
        assertEquals(3u, SparkConfig(network = SparkNetwork.MAINNET, signingThreshold = 3u).signingThreshold)
    }
}
