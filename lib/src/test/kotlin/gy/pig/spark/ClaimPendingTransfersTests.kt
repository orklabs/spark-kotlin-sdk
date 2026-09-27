package gy.pig.spark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark

/** One pending transfer that cannot be claimed must not leave the others unclaimed. */
class ClaimPendingTransfersTests {

    private fun transfer(id: String): Spark.Transfer = Spark.Transfer.newBuilder().setId(id).build()

    @Test
    fun aFailingTransferInTheMiddleDoesNotBlockTheOnesAfterIt() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = claimEach(listOf(transfer("a"), transfer("unsigned"), transfer("c"))) { t ->
            attempted.add(t.id)
            if (t.id == "unsigned") {
                // What the verifier throws for an inbound leaf without a valid sender signature.
                throw SparkError.UntrustedResponse("sender signature on leaf l1 in transfer unsigned is missing or invalid")
            }
        }
        assertEquals(listOf("a", "unsigned", "c"), attempted)
        assertEquals(3, result.pending)
        assertEquals(2, result.claimed)
        assertEquals(listOf("unsigned"), result.failures.map { it.transferId })
        assertTrue(result.failures.single().error is SparkError.UntrustedResponse)
    }

    @Test
    fun claimAllRethrowsTheFirstFailureOnlyAfterEveryTransferWasAttempted() {
        val first = SparkError.UntrustedResponse("first")
        val second = SparkError.InvalidResponse("second")
        val claim = SparkTransferClaim(
            pending = 4,
            claimed = 2,
            failures = listOf(SparkTransferClaimFailure("x", first), SparkTransferClaimFailure("y", second)),
        )
        val thrown = assertThrows(SparkError.UntrustedResponse::class.java) { claim.claimedOrThrow() }
        assertSame(first, thrown)
        assertArrayEquals(arrayOf<Throwable>(second), thrown.suppressed)

        assertEquals(3, SparkTransferClaim(pending = 3, claimed = 3, failures = emptyList()).claimedOrThrow())
        assertEquals(0, SparkTransferClaim(pending = 0, claimed = 0, failures = emptyList()).claimedOrThrow())
    }

    @Test
    fun cancellationStopsTheLoopAndIsNeverRecordedAsAFailedClaim() {
        val attempted = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                claimEach(listOf(transfer("a"), transfer("b"), transfer("c"))) { t ->
                    attempted.add(t.id)
                    if (t.id == "b") throw CancellationException("caller went away")
                }
            }
        }
        assertEquals(listOf("a", "b"), attempted)
    }
}
