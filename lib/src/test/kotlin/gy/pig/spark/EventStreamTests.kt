package gy.pig.spark

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How operator stream messages become [SparkEvent]s, following the reference SDK's
 * `handleStreamEvent`. Ported from the Swift SDK's `EventStreamTests.swift`.
 */
class EventStreamTests {
    private val wallet = byteArrayOf(0x02) + bytes(0x11, 32)
    private val other = byteArrayOf(0x03) + bytes(0x22, 32)

    private fun transferMessage(
        receiver: Boolean,
        type: Spark.TransferType,
        from: ByteArray = other,
        to: ByteArray = wallet,
    ): Spark.SubscribeToEventsResponse {
        val event = Spark.TransferEvent.newBuilder().setTransfer(
            Spark.Transfer.newBuilder()
                .setId("0199a8f0-0000-7000-8000-000000000001")
                .setType(type)
                .setStatus(Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED)
                .setSenderIdentityPublicKey(from.toByteString())
                .setReceiverIdentityPublicKey(to.toByteString())
                .setTotalValue(10),
        ).build()
        val message = Spark.SubscribeToEventsResponse.newBuilder()
        if (receiver) message.receiverTransfer = event else message.senderTransfer = event
        return message.build()
    }

    private fun depositMessage(status: String): Spark.SubscribeToEventsResponse = Spark.SubscribeToEventsResponse.newBuilder()
        .setDeposit(Spark.DepositEvent.newBuilder().setDeposit(Spark.TreeNode.newBuilder().setTreeId("tree").setStatus(status)))
        .build()

    @Test
    fun aPaymentToTheWalletIsReportedItsOwnSwapsCounterTransfersAndSelfTransfersAreNot() {
        for (type in listOf(Spark.TransferType.TRANSFER, Spark.TransferType.PREIMAGE_SWAP, Spark.TransferType.UTXO_SWAP)) {
            val event = mapEvent(transferMessage(receiver = true, type = type))
            assertTrue("$type", event is SparkEvent.TransferReceived)
            assertEquals(10L, (event as SparkEvent.TransferReceived).transfer.totalValueSats)
        }
        for (type in listOf(Spark.TransferType.COUNTER_SWAP, Spark.TransferType.COUNTER_SWAP_V3)) {
            assertNull("$type", mapEvent(transferMessage(receiver = true, type = type)))
        }
        assertNull(mapEvent(transferMessage(receiver = true, type = Spark.TransferType.TRANSFER, from = wallet, to = wallet)))
    }

    @Test
    fun outgoingTransfersAreReportedWithTheirStatusSwapsIncluded() {
        for (type in listOf(Spark.TransferType.TRANSFER, Spark.TransferType.PRIMARY_SWAP_V3)) {
            val event = mapEvent(transferMessage(receiver = false, type = type, from = wallet, to = other))
            assertTrue("$type", event is SparkEvent.TransferSent)
            assertEquals(Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED.toString(), (event as SparkEvent.TransferSent).transfer.status)
        }
    }

    @Test
    fun aDepositIsReportedOnceItsLeafIsAvailableConnectionEventsPassThrough() {
        assertEquals(SparkEvent.DepositConfirmed("tree"), mapEvent(depositMessage("AVAILABLE")))
        assertNull(mapEvent(depositMessage("CREATING")))
        val connected = Spark.SubscribeToEventsResponse.newBuilder().setConnected(Spark.ConnectedEvent.getDefaultInstance()).build()
        assertEquals(SparkEvent.Connected, mapEvent(connected))
        assertNull(mapEvent(Spark.SubscribeToEventsResponse.getDefaultInstance()))
        val heartbeat = Spark.SubscribeToEventsResponse.newBuilder().setHeartbeat(Spark.HeartbeatEvent.getDefaultInstance()).build()
        assertNull(mapEvent(heartbeat))
    }
}

/**
 * The event stream's connection handling against a local operator stand-in, whose subscription
 * sends `connected` and then ends (or stays silent).
 */
class EventStreamConnectionTests {
    internal companion object {
        /** Events up to and including the [count]-th [SparkEvent.Connected]; stops collecting there. */
        suspend fun eventsUntilConnection(flow: Flow<SparkEvent>, count: Int): List<SparkEvent> {
            var connections = 0
            return flow.transformWhile { event ->
                emit(event)
                !(event is SparkEvent.Connected && ++connections == count)
            }.toList()
        }
    }

    @Test
    fun attemptsBackOffFrom1SecondDoublingTo15AsTheReferenceSdksStreamDoes() {
        assertEquals(listOf(1, 1, 2, 4, 8, 15, 15, 15).map { it.seconds }, (0..7).map { eventStreamBackoff(it) })
    }

    @Test(timeout = 60_000)
    fun anEndedSubscriptionIsResumedAndEveryConnectionClaimsThePendingTransfers() = runBlocking {
        val state = FakeOperatorState { false }
        val events = withFakeOperator(state) { wallet -> eventsUntilConnection(wallet.subscribeToEvents(), count = 2) }
        assertEquals(3, events.size)
        assertEquals(SparkEvent.Connected, events[0])
        val reconnecting = events[1] as SparkEvent.Reconnecting
        assertEquals(1, reconnecting.attempt)
        assertEquals(1.seconds, reconnecting.retryIn)
        assertTrue(reconnecting.reason, reconnecting.reason.contains("ended"))
        assertEquals(SparkEvent.Connected, events[2])
        assertEquals(listOf("subscribe_to_events", "query_pending_transfers", "subscribe_to_events"), state.methods.take(3))
    }

    @Test(timeout = 60_000)
    fun closingTheWalletEndsItsEventStreamsAndRefusesNewOnesUntilStart() = runBlocking<Unit> {
        val state = FakeOperatorState { false }
        state.subscription = FakeOperatorState.Subscription.SILENCE
        withFakeOperator(state) { wallet ->
            coroutineScope {
                val collected = async { wallet.subscribeToEvents().toList() }
                launch {
                    // Close once the stream is up.
                    while (!state.methods.contains("subscribe_to_events")) delay(10)
                    delay(100)
                    wallet.close()
                }
                val events = withTimeoutOrNull(10_000) { collected.await() }
                // The collection ended normally after close(), without an exception.
                assertEquals(listOf(SparkEvent.Connected), events)
            }
            expectSparkErrorSuspending { wallet.subscribeToEvents() }
        }
    }

    @Test(timeout = 60_000)
    fun startAfterCloseAcceptsEventStreamsAgainAsAHostAppCyclesTheWalletAroundBackgrounding() = runBlocking<Unit> {
        val state = FakeOperatorState { false }
        state.subscription = FakeOperatorState.Subscription.SILENCE
        withFakeOperator(state) { wallet ->
            coroutineScope {
                val first = async { wallet.subscribeToEvents().toList() }
                while (!state.methods.contains("subscribe_to_events")) delay(10)
                delay(100)
                wallet.close()
                // The stream close() ended stays ended.
                assertEquals(listOf(SparkEvent.Connected), withTimeoutOrNull(10_000) { first.await() })
            }
            wallet.start()
            assertEquals(SparkEvent.Connected, withTimeoutOrNull(10_000) { wallet.subscribeToEvents().first() })
        }
    }

    @Test(timeout = 60_000)
    fun aSubscriptionThatGoesSilentAfterSendingHeartbeatsIsDroppedAndResubscribed() = runBlocking {
        val state = FakeOperatorState { false }
        state.subscription = FakeOperatorState.Subscription.HEARTBEAT_THEN_SILENCE
        val events = withFakeOperator(state) { wallet ->
            eventsUntilConnection(wallet.subscribeToEvents(heartbeatTimeout = 300.milliseconds), count = 2)
        }
        assertEquals(3, events.size)
        assertEquals(SparkEvent.Connected, events[0])
        val reconnecting = events[1] as SparkEvent.Reconnecting
        assertEquals(1, reconnecting.attempt)
        assertTrue(reconnecting.reason, reconnecting.reason.contains("heartbeat"))
        assertEquals(SparkEvent.Connected, events[2])
    }

    @Test(timeout = 60_000)
    fun aQuietSubscriptionThatNeverSentAHeartbeatIsKept() = runBlocking {
        val state = FakeOperatorState { false }
        state.subscription = FakeOperatorState.Subscription.SILENCE
        val events = mutableListOf<SparkEvent>()
        withFakeOperator(state) { wallet ->
            withTimeoutOrNull(1_000) {
                wallet.subscribeToEvents(heartbeatTimeout = 200.milliseconds).collect { events.add(it) }
            }
        }
        assertEquals(listOf(SparkEvent.Connected), events)
    }

    @Test
    fun theWatchdogArmsOnAHeartbeatAndPausesWhileAnEventIsHandled() = runBlocking {
        suspend fun fires(activity: EventStreamActivity, withinMs: Long): Boolean = withTimeoutOrNull(withinMs) { activity.silence(100.milliseconds) } != null

        val unarmed = EventStreamActivity()
        unarmed.received(heartbeat = false)
        unarmed.handled()
        assertFalse(fires(unarmed, withinMs = 400))

        val armed = EventStreamActivity()
        armed.received(heartbeat = true)
        armed.handled()
        assertTrue(fires(armed, withinMs = 5_000))

        val busy = EventStreamActivity()
        busy.received(heartbeat = true)
        assertFalse(fires(busy, withinMs = 400))
    }

    @Test(timeout = 60_000)
    fun aRejectedSubscriptionIsReissuedWithAFreshToken() = runBlocking {
        // Unlike grpc-swift, where the stream reconnects, the Kotlin interceptor re-issues the
        // subscription itself: the first thing the collector sees is the connection.
        val state = FakeOperatorState(rejection = FakeOperatorState.Rejection.BEFORE_HEADERS) { it == "session-1" }
        val first = withFakeOperator(state) { wallet -> wallet.subscribeToEvents().first() }
        assertEquals(SparkEvent.Connected, first)
        assertEquals(listOf("session-1", "session-2"), state.issuedTokens)
        assertEquals(listOf("subscribe_to_events Bearer session-1", "subscribe_to_events Bearer session-2"), state.calls.take(2))
    }
}
