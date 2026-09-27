package gy.pig.spark

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import spark_token.BroadcastTransactionRequest
import spark_token.FinalTokenTransaction
import spark_token.SignatureWithIndex
import spark_token.TokenMintInput
import java.math.BigInteger

/**
 * V3 token transactions against the operator stand-in: what the wallet broadcasts, how it signs,
 * and which answers it accepts. The partial and final transaction hashes themselves are checked
 * against the operators' vectors in [ProtoHashTests]. Port of the Swift SDK's
 * `TokenTransactionV3Tests`.
 */
class TokenTransactionV3Tests {
    private val token = TokenOutputLockTests.TOKEN
    private val receiver = SparkAddress.encode(TokenHashVectorTests.key(25), SparkNetwork.REGTEST)

    /**
     * Runs [body] on a wallet holding one 100-token output, against a stand-in that finalizes
     * broadcasts, altered by [tamper].
     */
    private fun <T> withFinalizingOperator(tamper: (FinalTokenTransaction.Builder) -> Unit = {}, body: suspend (SparkWallet, FakeOperatorState) -> T,): T =
        runBlocking {
            val state = FakeOperatorState { false }
            state.broadcast = FakeOperatorState.Broadcast.Finalize(tamper)
            withFakeOperator(state) { wallet ->
                state.setTokenOutputs(TokenOutputLockTests.walletOutputs(wallet, amounts = listOf(100)))
                body(wallet, state)
            }
        }

    private fun onlyBroadcast(state: FakeOperatorState): BroadcastTransactionRequest {
        val broadcasts = state.broadcasts
        assertEquals(1, broadcasts.size)
        return broadcasts.single().request
    }

    /**
     * The owner signatures are the wallet's, over the protohash of the partial transaction, in
     * `single_signature` rather than the deprecated field.
     */
    private fun expectSigned(request: BroadcastTransactionRequest, wallet: SparkWallet, inputs: Int) {
        val identity = hex(wallet.identityPublicKeyHex)
        val hash = ProtoHash.hash(request.partialTokenTransaction)
        assertEquals(identity.toByteString(), request.identityPublicKey)
        assertEquals((0 until inputs).toList(), request.tokenTransactionOwnerSignaturesList.map { it.inputIndex })
        for (signature in request.tokenTransactionOwnerSignaturesList) {
            assertFalse(signature.hasSignature())
            assertEquals(SignatureWithIndex.AuthoritySignaturesCase.SINGLE_SIGNATURE, signature.authoritySignaturesCase)
            val keyed = signature.singleSignature
            assertEquals(identity.toByteString(), keyed.publicKey)
            assertTrue(TransferLeafVerifier.verifyECDSA(keyed.signature.toByteArray(), hash, identity))
        }
    }

    private fun finalHash(request: BroadcastTransactionRequest): String = ProtoHash.hash(FakeOperator.finalize(request.partialTokenTransaction)).toHexString()

    @Test(timeout = 60_000)
    fun aTransferBroadcastsOneSignedV3TransactionAndReturnsTheFinalTransactionsHash() = withFinalizingOperator { wallet, state ->
        val hash = wallet.transferTokens(token, BigInteger.valueOf(40), receiver)
        val request = onlyBroadcast(state)
        val partial = request.partialTokenTransaction
        assertEquals(finalHash(request), hash)
        assertTrue(state.startedTransactions.isEmpty())

        assertEquals(3, partial.version)
        val metadata = partial.tokenTransactionMetadata
        assertEquals(180L, metadata.validityDurationSeconds)
        assertEquals(spark.Spark.Network.REGTEST, metadata.network)
        assertEquals(wallet.collectOperatorIdentityPublicKeys(), metadata.sparkOperatorIdentityPublicKeysList)
        assertEquals(0, metadata.clientCreatedTimestamp.nanos % 1_000)
        assertTrue(metadata.invoiceAttachmentsList.isEmpty())
        assertEquals(listOf(0), partial.transferInput.outputsToSpendList.map { it.prevTokenTransactionVout })

        val identity = hex(wallet.identityPublicKeyHex).toByteString()
        assertEquals(listOf(TokenHashVectorTests.key(25).toByteString(), identity), partial.partialTokenOutputsList.map { it.ownerPublicKey })
        assertEquals(listOf(BigInteger.valueOf(40), BigInteger.valueOf(60)), partial.partialTokenOutputsList.map { decodeUInt128(it.tokenAmount) })
        for (output in partial.partialTokenOutputsList) {
            assertEquals(TokenOutputLockTests.TOKEN_IDENTIFIER.toByteString(), output.tokenIdentifier)
            assertEquals(10_000L, output.withdrawBondSats)
            assertEquals(1_000L, output.withdrawRelativeBlockLocktime)
        }
        expectSigned(request, wallet, inputs = 1)
    }

    @Test(timeout = 60_000)
    fun aBurnPaysTheBurnKeySignedForTheOutputItSpends() = withFinalizingOperator { wallet, state ->
        wallet.burnTokens(token, BigInteger.valueOf(100))
        val request = onlyBroadcast(state)
        assertEquals(listOf(bytes(0x02, 33).toByteString()), request.partialTokenTransaction.partialTokenOutputsList.map { it.ownerPublicKey })
        expectSigned(request, wallet, inputs = 1)
    }

    @Test(timeout = 60_000)
    fun aMintPaysTheIssuerSignedByTheIssuer() = withFinalizingOperator { wallet, state ->
        val hash = wallet.mintTokens(token, BigInteger.valueOf(5))
        val request = onlyBroadcast(state)
        val partial = request.partialTokenTransaction
        val identity = hex(wallet.identityPublicKeyHex).toByteString()
        assertEquals(finalHash(request), hash)
        assertEquals(identity, partial.mintInput.issuerPublicKey)
        assertEquals(TokenOutputLockTests.TOKEN_IDENTIFIER.toByteString(), partial.mintInput.tokenIdentifier)
        assertEquals(listOf(identity), partial.partialTokenOutputsList.map { it.ownerPublicKey })
        assertEquals(listOf(BigInteger.valueOf(5)), partial.partialTokenOutputsList.map { decodeUInt128(it.tokenAmount) })
        expectSigned(request, wallet, inputs = 1)
    }

    @Test(timeout = 60_000)
    fun aCreateCarriesTheTokensParametersAndReturnsTheIdentifierTheOperatorsReport() = withFinalizingOperator { wallet, state ->
        val result = wallet.createToken(
            tokenName = "Piggy",
            tokenTicker = "PIG",
            decimals = 2u,
            maxSupply = BigInteger.valueOf(1_000),
            isFreezable = false,
        )
        val request = onlyBroadcast(state)
        val create = request.partialTokenTransaction.createInput
        assertEquals("Piggy", create.tokenName)
        assertEquals("PIG", create.tokenTicker)
        assertEquals(2, create.decimals)
        assertEquals(BigInteger.valueOf(1_000), decodeUInt128(create.maxSupply))
        assertFalse(create.hasCreationEntityPublicKey())
        assertTrue(request.partialTokenTransaction.partialTokenOutputsList.isEmpty())
        assertEquals(encodeBech32mTokenIdentifier(FakeOperator.CREATED_TOKEN_IDENTIFIER, SparkNetwork.REGTEST), result.tokenIdentifier)
        assertEquals(finalHash(request), result.transactionHash)
        expectSigned(request, wallet, inputs = 1)
    }

    private val tamperings: List<Pair<String, (FinalTokenTransaction.Builder) -> Unit>> = listOf(
        "output owner redirected" to { final -> final.editOutput(0) { ownerPublicKey = TokenHashVectorTests.key(99).toByteString() } },
        "output amount changed" to { final -> final.editOutput(0) { tokenAmount = encodeUInt128(BigInteger.valueOf(41)).toByteString() } },
        "withdraw bond changed" to { final -> final.editOutput(1) { withdrawBondSats = 1 } },
        "revocation commitment missing" to { final ->
            final.setFinalTokenOutputs(1, final.getFinalTokenOutputs(1).toBuilder().clearRevocationCommitment())
        },
        "output dropped" to { final -> final.removeFinalTokenOutputs(final.finalTokenOutputsCount - 1) },
        "input changed" to { final ->
            val input = final.transferInput.toBuilder()
            input.setOutputsToSpend(0, input.getOutputsToSpend(0).toBuilder().setPrevTokenTransactionVout(7))
            final.setTransferInput(input)
        },
        "metadata changed" to { final -> final.setTokenTransactionMetadata(final.tokenTransactionMetadata.toBuilder().setValidityDurationSeconds(300)) },
        "version changed" to { final -> final.version = 2 },
        "type changed" to { final -> final.setMintInput(TokenMintInput.getDefaultInstance()) },
    )

    private fun FinalTokenTransaction.Builder.editOutput(index: Int, edit: spark_token.PartialTokenOutput.Builder.() -> Unit) {
        val output = getFinalTokenOutputs(index)
        setFinalTokenOutputs(index, output.toBuilder().setPartialTokenOutput(output.partialTokenOutput.toBuilder().apply(edit)))
    }

    @Test(timeout = 60_000)
    fun aFinalTransactionThatIsNotTheOneSignedIsRefused() {
        assertEquals(9, tamperings.size)
        for ((label, tamper) in tamperings) {
            withFinalizingOperator(tamper) { wallet, _ ->
                val error = expectSparkErrorSuspending(label) { wallet.transferTokens(token, BigInteger.valueOf(40), receiver) }
                assertTrue("$label: $error", error is SparkError.UntrustedResponse)
            }
        }
    }

    @Test(timeout = 60_000)
    fun v2IsStillUsedWhenConfigured() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state, configure = { it.copy(tokenTransactionVersion = TokenTransactionVersion.V2) }) { wallet ->
            state.setTokenOutputs(TokenOutputLockTests.walletOutputs(wallet, amounts = listOf(100)))
            expectGrpcFailure { TokenOutputLockTests.send(wallet) }
            assertEquals(1, state.startedTransactions.size)
            assertEquals(2, state.startedTransactions.single().transaction.version)
            assertTrue(state.broadcasts.isEmpty())
        }
    }
}
