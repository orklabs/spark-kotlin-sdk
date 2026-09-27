package gy.pig.spark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import spark_token.OutputWithPreviousTransactionData
import spark_token.PartialTokenTransaction
import spark_token.TokenOutput
import spark_token.TokenOutputStatus
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which token outputs a send may pick, as the reference SDK's `TokenOutputManager` decides it:
 * only AVAILABLE ones, and none another send from the wallet has picked in the last 30 s. Port of
 * the Swift SDK's `TokenOutputLockTests`.
 */
class TokenOutputLockTests {
    companion object {
        val TOKEN_IDENTIFIER: ByteArray = bytes(0x01, 32)

        fun output(
            vout: Int,
            amount: Long = 100,
            status: TokenOutputStatus = TokenOutputStatus.TOKEN_OUTPUT_STATUS_AVAILABLE,
            owner: ByteArray = bytes(0x02, 33),
        ): OutputWithPreviousTransactionData = OutputWithPreviousTransactionData.newBuilder()
            .setOutput(
                TokenOutput.newBuilder()
                    .setOwnerPublicKey(owner.toByteString())
                    .setTokenIdentifier(TOKEN_IDENTIFIER.toByteString())
                    .setTokenAmount(encodeUInt128(BigInteger.valueOf(amount)).toByteString())
                    .setStatus(status),
            )
            .setPreviousTransactionHash(bytes(0xAA, 32).toByteString())
            .setPreviousTransactionVout(vout)
            .build()

        fun keys(outputs: List<OutputWithPreviousTransactionData>): List<String> = outputs.map { TokenOutputLocks.key(it) }

        /** Outputs of [amounts], owned by [wallet], with [statuses] (AVAILABLE when not given). */
        fun walletOutputs(wallet: SparkWallet, amounts: List<Long>, statuses: List<TokenOutputStatus> = emptyList(),): List<OutputWithPreviousTransactionData> =
            amounts.mapIndexed { index, amount ->
                output(
                    vout = index,
                    amount = amount,
                    status = statuses.getOrElse(index) { TokenOutputStatus.TOKEN_OUTPUT_STATUS_AVAILABLE },
                    owner = hex(wallet.identityPublicKeyHex),
                )
            }

        val TOKEN: Bech32mTokenIdentifier get() = encodeBech32mTokenIdentifier(TOKEN_IDENTIFIER, SparkNetwork.REGTEST)

        /** Sends 100 of the test token from [wallet] to itself. */
        suspend fun send(wallet: SparkWallet) {
            wallet.transferTokens(TOKEN, BigInteger.valueOf(100), wallet.getSparkAddress())
        }
    }

    @Test
    fun onlyAvailableOutputsAreOfferedAndThePickedOnesAreNotOfferedAgain() {
        val locks = TokenOutputLocks()
        val outputs = listOf(output(0), output(1, status = TokenOutputStatus.TOKEN_OUTPUT_STATUS_PENDING_OUTBOUND), output(2))
        assertEquals(keys(listOf(outputs[0])), keys(locks.acquire(outputs) { it.take(1) }))
        assertEquals(keys(listOf(outputs[2])), keys(locks.acquire(outputs) { it }))
        assertTrue(locks.acquire(outputs) { it }.isEmpty())
    }

    @Test
    fun aLockEndsAfterItsExpiry() {
        var now = 0L
        val locks = TokenOutputLocks(expiry = 100.milliseconds) { now }
        val outputs = listOf(output(0))
        assertEquals(1, locks.acquire(outputs) { it }.size)
        now += 99.milliseconds.inWholeNanoseconds
        assertTrue(locks.acquire(outputs) { it }.isEmpty())
        now += 1.milliseconds.inWholeNanoseconds
        assertEquals(1, locks.acquire(outputs) { it }.size)
    }

    @Test
    fun theDefaultExpiryIsTheReferenceSdks30Seconds() {
        assertEquals(30_000L, TokenOutputLocks().expiry.inWholeMilliseconds)
    }

    @Test
    fun anOutputTheOperatorsReportPendingLosesItsLockAndCanBePickedOnceAvailableAgain() {
        val locks = TokenOutputLocks()
        assertEquals(1, locks.acquire(listOf(output(0))) { it }.size)
        assertTrue(locks.acquire(listOf(output(0, status = TokenOutputStatus.TOKEN_OUTPUT_STATUS_PENDING_OUTBOUND))) { it }.isEmpty())
        // The pending transaction expired: the operators report the output AVAILABLE again.
        assertEquals(1, locks.acquire(listOf(output(0))) { it }.size)
    }

    @Test
    fun aPickThatFailsLocksNothing() {
        val locks = TokenOutputLocks()
        val outputs = listOf(output(0))
        expectSparkError {
            locks.acquire(outputs) { selectTokenOutputs(it, BigInteger.valueOf(500), TokenOutputSelectionStrategy.SMALL_FIRST) }
        }
        assertEquals(1, locks.acquire(outputs) { it }.size)
    }

    @Test
    fun concurrentPicksNeverShareAnOutput() = runBlocking {
        val locks = TokenOutputLocks()
        val outputs = (0 until 50).map { output(it) }
        val picked = (0 until 50)
            .map { async(Dispatchers.Default) { keys(locks.acquire(outputs) { it.take(1) }) } }
            .awaitAll()
            .flatten()
        assertEquals(50, picked.size)
        assertEquals(50, picked.toSet().size)
    }

    // Sends against the operator stand-in, whose `start_transaction` and `broadcast_transaction`
    // record the outputs each transaction spends and then refuse it.

    @Test(timeout = 60_000)
    fun aSendSkipsOutputsTheOperatorsReportPending() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            val outputs = walletOutputs(
                wallet,
                amounts = listOf(100, 100),
                statuses = listOf(TokenOutputStatus.TOKEN_OUTPUT_STATUS_PENDING_OUTBOUND, TokenOutputStatus.TOKEN_OUTPUT_STATUS_AVAILABLE),
            )
            state.setTokenOutputs(outputs)
            expectGrpcFailure { send(wallet) }
            assertEquals(listOf(keys(listOf(outputs[1]))), state.startedSpends)
        }
    }

    @Test(timeout = 60_000)
    fun aSendDoesNotPickTheOutputsOfOneThatMayStillBeInFlight() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            val outputs = walletOutputs(wallet, amounts = listOf(100, 100))
            state.setTokenOutputs(outputs)
            // The first send is refused, but the operators may hold it: its output stays locked.
            expectGrpcFailure { send(wallet) }
            expectGrpcFailure { wallet.burnTokens(TOKEN, BigInteger.valueOf(100)) }
            assertEquals(listOf(keys(listOf(outputs[0])), keys(listOf(outputs[1]))), state.startedSpends)
            // Nothing left to pick: refused before anything reaches the operators.
            expectSparkErrorSuspending { send(wallet) }
            assertEquals(2, state.startedSpends.size)
        }
    }

    @Test(timeout = 60_000)
    fun concurrentSendsSpendDifferentOutputs() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            state.setTokenOutputs(walletOutputs(wallet, amounts = listOf(100, 100)))
            (0 until 2).map { async(Dispatchers.Default) { runCatching { send(wallet) } } }.awaitAll()
            val spends = state.startedSpends
            assertEquals(2, spends.size)
            assertEquals(2, spends.flatten().toSet().size)
        }
    }
}

/** Retries of a transfer sent with an idempotency key. Port of the Swift SDK's `TokenTransferIdempotencyTests`. */
class TokenTransferIdempotencyTests {
    @Test(timeout = 60_000)
    fun aRetryWithTheKeyResendsTheFirstTransactionUnchanged() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            state.setTokenOutputs(TokenOutputLockTests.walletOutputs(wallet, amounts = listOf(100, 100)))
            repeat(2) {
                expectGrpcFailure {
                    wallet.transferTokens(TokenOutputLockTests.TOKEN, BigInteger.valueOf(100), wallet.getSparkAddress(), idempotencyKey = "retry-1")
                }
            }
            val broadcasts = state.broadcasts
            assertEquals(2, broadcasts.size)
            assertEquals(listOf("retry-1", "retry-1"), broadcasts.map { it.idempotencyKey })
            assertEquals(broadcasts.first().request.partialTokenTransaction, broadcasts.last().request.partialTokenTransaction)
        }
    }

    @Test(timeout = 60_000)
    fun aKeyUsedForAnotherTransferIsRefusedBeforeAnythingIsSent() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            state.setTokenOutputs(TokenOutputLockTests.walletOutputs(wallet, amounts = listOf(100, 100)))
            val address = wallet.getSparkAddress()
            suspend fun send(amount: Long) {
                wallet.transferTokens(TokenOutputLockTests.TOKEN, BigInteger.valueOf(amount), address, idempotencyKey = "k")
            }
            expectGrpcFailure { send(100) }
            val error = expectSparkErrorSuspending { send(50) }
            assertTrue(error.toString(), error is SparkError.InvalidArgument)
            assertEquals(1, state.broadcasts.size)
        }
    }

    @Test
    fun theOldestKeysAreForgottenBeyondTheCapacity() {
        val attempts = TokenTransferAttempts()
        val request = TokenTransferAttempts.Request(
            tokenIdentifier = bytes(0, 32).toByteString(),
            amount = BigInteger.ONE,
            receiverIdentityPublicKey = bytes(0, 33).toByteString(),
        )
        val attempt = TokenTransferAttempts.Attempt(
            request = request,
            transaction = TokenTransactionDraft.V3(PartialTokenTransaction.getDefaultInstance()),
            spentOutputs = emptyList(),
        )
        for (index in 0..TokenTransferAttempts.CAPACITY) {
            attempts.remember(attempt, "key-$index")
        }
        assertNull(attempts.attempt("key-0"))
        assertNotNull(attempts.attempt("key-1"))
        assertNotNull(attempts.attempt("key-${TokenTransferAttempts.CAPACITY}"))
    }
}
