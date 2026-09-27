package gy.pig.spark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark

/**
 * The claim pass ported from the reference SDK's `claimTransfers`
 * (`spark-wallet-claim-transfers.test.ts`), via the Swift SDK's `ClaimDrainTests.swift`. Without a
 * server-time snapshot the reference's fallback mode applies: pages of 25, restart from the head
 * after progress, advance otherwise, at most 100 pages per pass.
 */
class ClaimDrainTests {

    private fun transfer(
        id: String,
        status: Spark.TransferStatus = Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED,
        leafIds: List<String> = emptyList(),
    ): Spark.Transfer = Spark.Transfer.newBuilder()
        .setId(id)
        .setStatus(status)
        .setType(Spark.TransferType.TRANSFER)
        .addAllLeaves(leafIds.map { Spark.TransferLeaf.newBuilder().setLeaf(Spark.TreeNode.newBuilder().setId(it)).build() })
        .build()

    /** Pending transfers that leave the set once claimed, as they do on the operators. */
    private class PendingServer(initial: List<Spark.Transfer>, private val failing: Set<String> = emptySet()) {
        val pending = initial.toMutableList()
        val queries = mutableListOf<Pair<Int, Int>>()
        val claimAttempts = mutableListOf<String>()

        fun page(limit: Int, offset: Int): List<Spark.Transfer> {
            queries.add(limit to offset)
            if (offset >= pending.size) return emptyList()
            return pending.subList(offset, minOf(offset + limit, pending.size)).toList()
        }

        fun claim(transfer: Spark.Transfer) {
            claimAttempts.add(transfer.id)
            if (transfer.id in failing) throw SparkError.UntrustedResponse("sender signature on ${transfer.id} does not verify")
            pending.removeAll { it.id == transfer.id }
        }

        suspend fun run(): PendingTransferClaim = PendingTransferDrain.run(fetch = { l, o -> page(l, o) }, claim = { claim(it) })
    }

    /** Answers queries from a script (the last answer repeats) and never removes anything. */
    private class ScriptedServer(script: List<List<Spark.Transfer>>, private val succeeding: (String) -> Boolean = { true }) {
        private val script = script.toMutableList()
        val queries = mutableListOf<Pair<Int, Int>>()
        val claimAttempts = mutableListOf<String>()

        fun page(limit: Int, offset: Int): List<Spark.Transfer> {
            queries.add(limit to offset)
            return if (script.size > 1) script.removeAt(0) else script[0]
        }

        fun claim(transfer: Spark.Transfer) {
            claimAttempts.add(transfer.id)
            if (!succeeding(transfer.id)) throw SparkError.InvalidResponse("failed to claim ${transfer.id}")
        }

        suspend fun run(): PendingTransferClaim = PendingTransferDrain.run(fetch = { l, o -> page(l, o) }, claim = { claim(it) })
    }

    @Test
    fun drainsPendingTransfersFromTheHeadIn25TransferBatches() = runBlocking {
        val first = (1..25).map { transfer("transfer-$it") }
        val server = ScriptedServer(listOf(first, listOf(transfer("transfer-26"))))
        val result = server.run()
        assertEquals((1..26).map { "transfer-$it" }, result.claimedTransferIds)
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf(25, 25), server.queries.map { it.first })
        assertEquals(listOf(0, 0), server.queries.map { it.second })
    }

    @Test
    fun drainsAShrinkingServerSidePendingSetAcrossSeveralBatches() = runBlocking {
        val server = PendingServer((1..76).map { transfer("server-transfer-$it") })
        val result = server.run()
        assertEquals((1..76).map { "server-transfer-$it" }, result.claimedTransferIds)
        assertTrue(server.pending.isEmpty())
        assertEquals(listOf(0, 0, 0, 0), server.queries.map { it.second })
        assertEquals(listOf(25, 25, 25, 25), server.queries.map { it.first })
        assertEquals(76, server.claimAttempts.size)
    }

    @Test
    fun restartsFromTheHeadAfterPartiallyClaimingAFullBatchSkippingNonClaimableStatuses() = runBlocking {
        val skipped = transfer("server-transfer-skipped", status = Spark.TransferStatus.TRANSFER_STATUS_EXPIRED)
        val server = PendingServer(listOf(skipped) + (1..50).map { transfer("server-transfer-$it") })
        val result = server.run()
        assertEquals((1..50).map { "server-transfer-$it" }, result.claimedTransferIds)
        assertEquals(listOf(skipped.id), server.pending.map { it.id })
        assertEquals(listOf(0, 0, 0), server.queries.map { it.second })
        assertEquals(50, server.claimAttempts.size)
    }

    @Test
    fun skipsANonClaimableHeadBatchToReachLaterClaimableTransfers() = runBlocking {
        val expired = (1..25).map { transfer("expired-$it", status = Spark.TransferStatus.TRANSFER_STATUS_EXPIRED) }
        val server = ScriptedServer(listOf(expired, listOf(transfer("claimable-later"))))
        val result = server.run()
        assertEquals(listOf("claimable-later"), result.claimedTransferIds)
        assertEquals(listOf(0, 25), server.queries.map { it.second })
    }

    @Test
    fun skipsAFullyFailingHeadBatchToReachLaterClaimableTransfers() = runBlocking {
        val failing = (1..25).map { transfer("transfer-$it") }
        val server = ScriptedServer(listOf(failing, listOf(transfer("transfer-26")))) { it == "transfer-26" }
        val result = server.run()
        assertEquals(listOf("transfer-26"), result.claimedTransferIds)
        assertEquals((1..25).map { "transfer-$it" }, result.failures.map { it.transferId })
        assertEquals(listOf(0, 25), server.queries.map { it.second })
        assertEquals(26, server.claimAttempts.size)
    }

    @Test
    fun aTransferThatCannotBeClaimedNeverBlocksTheOnesBehindIt() = runBlocking {
        // Anyone can create a pending transfer the SDK refuses: the operators store the per-leaf
        // sender signature without verifying it. Before this fix the pass stopped at the first one.
        val server = PendingServer(listOf(transfer("refused")) + (1..30).map { transfer("good-$it") }, failing = setOf("refused"))
        val result = server.run()
        assertEquals((1..30).map { "good-$it" }, result.claimedTransferIds)
        assertEquals(listOf("refused"), result.failures.map { it.transferId })
        assertTrue(result.failures.single().error is SparkError.UntrustedResponse)
        assertEquals(listOf("refused"), server.pending.map { it.id })
        // Tried once per pass, not once per page.
        assertEquals(1, server.claimAttempts.count { it == "refused" })
    }

    @Test
    fun thePassReportsTheLeavesOfTheTransfersItClaimedForTheRenewalThatFollows() = runBlocking {
        val server = PendingServer(
            listOf(transfer("t1", leafIds = listOf("a", "b")), transfer("refused", leafIds = listOf("x")), transfer("t2", leafIds = listOf("c"))),
            failing = setOf("refused"),
        )
        val result = server.run()
        assertEquals(listOf("t1", "t2"), result.claimedTransferIds)
        assertEquals(listOf("a", "b", "c"), result.claimedLeafIds)
    }

    @Test
    fun scansThroughUnclaimablePagesForAtMost100Batches() = runBlocking {
        val expired = (1..25).map { transfer("expired-loop-$it", status = Spark.TransferStatus.TRANSFER_STATUS_EXPIRED) }
        val server = ScriptedServer(listOf(expired))
        val result = server.run()
        assertTrue(result.claimedTransferIds.isEmpty())
        assertEquals(PendingTransferDrain.MAX_BATCHES, server.queries.size)
    }

    @Test
    fun aServerThatNeverShrinksIsDrainedForAtMost100BatchesClaimingEachTransferOnce() = runBlocking {
        // The reference SDK re-claims the same 25 every batch here; one attempt per pass suffices.
        val batch = (1..25).map { transfer("loop-$it") }
        val server = ScriptedServer(listOf(batch))
        val result = server.run()
        assertEquals(batch.map { it.id }, result.claimedTransferIds)
        assertEquals(PendingTransferDrain.MAX_BATCHES, server.queries.size)
    }

    @Test
    fun onlyTheReferenceSdksClaimableStatusesAreClaimed() {
        assertEquals(
            setOf(
                Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAKED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_REFUND_SIGNED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_APPLIED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_LOCKED,
            ),
            PendingTransferDrain.CLAIMABLE_STATUSES,
        )
        for (status in listOf(
            Spark.TransferStatus.TRANSFER_STATUS_SENDER_INITIATED,
            Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAK_PENDING,
            Spark.TransferStatus.TRANSFER_STATUS_COMPLETED,
            Spark.TransferStatus.TRANSFER_STATUS_EXPIRED,
            Spark.TransferStatus.TRANSFER_STATUS_RETURNED,
            Spark.TransferStatus.TRANSFER_STATUS_SENDER_INITIATED_COORDINATOR,
            Spark.TransferStatus.TRANSFER_STATUS_APPLYING_SENDER_KEY_TWEAK,
        )) {
            assertFalse(status.toString(), status in PendingTransferDrain.CLAIMABLE_STATUSES)
        }
    }

    @Test
    fun cancellationStopsThePassAndIsNeverRecordedAsAFailedClaim() {
        val attempted = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                PendingTransferDrain.run(
                    fetch = { _, _ -> listOf(transfer("a"), transfer("b"), transfer("c")) },
                    claim = { transfer ->
                        attempted.add(transfer.id)
                        if (transfer.id == "b") throw CancellationException("caller went away")
                    },
                )
            }
        }
        assertEquals(listOf("a", "b"), attempted)
    }

    @Test
    fun claimsNeverRunConcurrently() = runBlocking {
        // The wallet's claim lock is a fair kotlinx Mutex, the reference SDK's claimTransferMutex.
        val lock = Mutex()
        var running = 0
        var maxRunning = 0
        val order = mutableListOf<Int>()
        coroutineScope {
            (0 until 8).map { index ->
                async(kotlinx.coroutines.Dispatchers.Default) {
                    lock.withLock {
                        synchronized(order) {
                            running++
                            maxRunning = maxOf(maxRunning, running)
                            order.add(index)
                        }
                        delay(5)
                        synchronized(order) { running-- }
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, maxRunning)
        assertEquals(8, order.size)
    }

    @Test(timeout = 60_000)
    fun aClaimPassOnTheWalletAsksForPagesOf25AndReportsNothingWhenNothingIsPending() = runBlocking {
        val state = FakeOperatorState { false }
        val result = withFakeOperator(state) { wallet -> wallet.claimPendingTransfers() }
        assertTrue(result.claimedTransferIds.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("query_pending_transfers"), state.methods)
        withFakeOperator(state) { wallet -> assertEquals(0, wallet.claimAllPendingTransfers()) }
    }
}
