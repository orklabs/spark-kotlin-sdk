package gy.pig.spark

import io.grpc.Status
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The official SDK's auth middleware drops a token the operator rejects, authenticates again and
 * re-issues the call; the reference SDK also shares one authentication among concurrent callers
 * and retries whole challenge exchanges. These tests run the wallet's real transport stack
 * against a local operator stand-in. Ported from the Swift SDK's `AuthRetryTests.swift`.
 */
class AuthRetryTests {

    private fun unauthenticatedCode(error: Throwable): Status.Code? = error.grpcStatus?.code

    @Test(timeout = 60_000)
    fun aTokenTheOperatorRejectsBeforeRespondingIsDroppedAndTheCallReissuedWithAFreshOne() = runBlocking {
        val state = FakeOperatorState(rejection = FakeOperatorState.Rejection.BEFORE_HEADERS) { it == "session-1" }
        val leaves = withFakeOperator(state) { it.getLeaves() }
        assertTrue(leaves.isEmpty())
        assertEquals(listOf("session-1", "session-2"), state.issuedTokens)
        assertEquals(listOf("query_nodes Bearer session-1", "query_nodes Bearer session-2"), state.calls)
    }

    /**
     * Unlike grpc-swift, which fails a call rejected after its response headers, the Kotlin
     * interceptor holds headers back until the first message, so such a call is re-issued too:
     * the operators only answer UNAUTHENTICATED before any handler ran.
     */
    @Test(timeout = 60_000)
    fun aRejectionSentAfterTheResponseHeadersIsReissuedWithAFreshToken() = runBlocking {
        val state = FakeOperatorState(rejection = FakeOperatorState.Rejection.AFTER_HEADERS) { it == "session-1" }
        withFakeOperator(state) { wallet ->
            wallet.getLeaves()
            // The fresh token stays cached: the next call authenticates no more.
            wallet.getLeaves()
        }
        assertEquals(listOf("session-1", "session-2"), state.issuedTokens)
        assertEquals(
            listOf("query_nodes Bearer session-1", "query_nodes Bearer session-2", "query_nodes Bearer session-2"),
            state.calls,
        )
    }

    @Test(timeout = 60_000)
    fun aTokenThatIsNeverAcceptedFailsAfterTheRetryPolicysAttempts() = runBlocking {
        val state = FakeOperatorState(rejection = FakeOperatorState.Rejection.BEFORE_HEADERS) { true }
        withFakeOperator(state) { wallet ->
            val error = runCatching { wallet.getLeaves() }.exceptionOrNull()
            assertEquals(Status.Code.UNAUTHENTICATED, error?.let(::unauthenticatedCode))
        }
        assertEquals(listOf("session-1", "session-2", "session-3"), state.issuedTokens)
        assertEquals(AuthRetryInterceptor.MAX_ATTEMPTS, state.calls.size)
    }

    @Test(timeout = 60_000)
    fun concurrentCallsShareOneAuthentication() = runBlocking {
        val state = FakeOperatorState { false }
        state.verifyDelayMillis = 300
        withFakeOperator(state) { wallet ->
            coroutineScope {
                (0 until 5).map { async { wallet.getLeaves() } }.awaitAll()
            }
        }
        assertEquals(1, state.challengesIssued)
        assertEquals(listOf("session-1"), state.issuedTokens)
        assertEquals(5, state.calls.size)
    }

    @Test(timeout = 60_000)
    fun anExpiredOrAlreadyUsedChallengeIsReplacedByAFreshOneOtherRefusalsEndAuthentication() = runBlocking {
        val state = FakeOperatorState { false }
        state.failNextVerifications(
            listOf(
                Status.FAILED_PRECONDITION.withDescription("challenge validation failed: expired: challenge expired 3 seconds ago"),
                Status.FAILED_PRECONDITION.withDescription("challenge validation failed: challenge reused: nonce already used"),
            ),
        )
        withFakeOperator(state) { it.getLeaves() }
        assertEquals(3, state.challengesIssued)
        assertEquals(listOf("session-1"), state.issuedTokens)

        val refused = FakeOperatorState { false }
        refused.failNextVerifications(
            listOf(Status.FAILED_PRECONDITION.withDescription("signature verification failed under both ECDSA and Schnorr")),
        )
        withFakeOperator(refused) { wallet ->
            val error = runCatching { wallet.getLeaves() }.exceptionOrNull()
            assertEquals(Status.Code.FAILED_PRECONDITION, error?.grpcStatus?.code)
        }
        assertEquals(1, refused.challengesIssued)
        assertTrue(refused.issuedTokens.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aConnectionFailureDuringTheExchangeIsRetriedAfterAPause() = runBlocking {
        val state = FakeOperatorState { false }
        state.failNextVerifications(listOf(Status.UNAVAILABLE.withDescription("connection reset")))
        withFakeOperator(state) { it.getLeaves() }
        assertEquals(2, state.challengesIssued)
        assertEquals(listOf("session-1"), state.issuedTokens)
    }

    @Test
    fun onlyTheRejectedTokenIsInvalidated() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            val first = wallet.authenticator.getToken(wallet.connectionManager, wallet.config.coordinatorAddress, wallet.signer)
            // A late rejection of some older token leaves the current one cached.
            wallet.authenticator.invalidate(wallet.config.coordinatorAddress, wallet.signer, "session-0")
            assertEquals(first, wallet.authenticator.getToken(wallet.connectionManager, wallet.config.coordinatorAddress, wallet.signer))
            wallet.authenticator.invalidate(wallet.config.coordinatorAddress, wallet.signer, first)
            assertEquals("session-2", wallet.authenticator.getToken(wallet.connectionManager, wallet.config.coordinatorAddress, wallet.signer))
        }
        assertEquals(listOf("session-1", "session-2"), state.issuedTokens)
    }
}
