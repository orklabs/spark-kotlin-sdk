package gy.pig.spark

import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import spark.Spark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The wallet's running event streams, so that [SparkWallet.close] can stop them. */
internal class EventStreamRegistry {
    private val jobs = mutableSetOf<Job>()
    private var closed = false

    val isClosed: Boolean @Synchronized get() = closed

    /** Registers a stream's job; false once the wallet is closed. */
    @Synchronized
    fun register(job: Job): Boolean {
        if (closed) return false
        jobs.add(job)
        return true
    }

    @Synchronized
    fun finished(job: Job) {
        jobs.remove(job)
    }

    /** Stops every running stream and refuses new ones. */
    fun close() {
        val running = synchronized(this) {
            closed = true
            jobs.toList().also { jobs.clear() }
        }
        running.forEach { it.cancel() }
    }
}

/**
 * One subscription's activity, for the heartbeat watchdog. As in the reference SDK the watchdog
 * arms on the first heartbeat — a coordinator that sends none never times out — and pauses while
 * an event is being handled, since handling one can claim a transfer.
 *
 * @param monotonicNanos the monotonic clock, [System.nanoTime] outside tests.
 */
internal class EventStreamActivity(private val monotonicNanos: () -> Long = System::nanoTime) {
    private var isConnected = false
    private var heartbeats = false
    private var handling = false
    private var lastSeenNanos = monotonicNanos()

    val connected: Boolean @Synchronized get() = isConnected

    @Synchronized
    fun markConnected() {
        isConnected = true
    }

    /** A message arrived: the watchdog pauses until [handled]; a heartbeat arms it. */
    @Synchronized
    fun received(heartbeat: Boolean) {
        handling = true
        if (heartbeat) heartbeats = true
    }

    /** The message is handled: the silence starts counting again. */
    @Synchronized
    fun handled() {
        handling = false
        lastSeenNanos = monotonicNanos()
    }

    /** Nanoseconds until the silence exceeds [timeoutNanos] (≤ 0 once it has), or `null` while the watchdog is not armed. */
    @Synchronized
    private fun remainingNanos(timeoutNanos: Long): Long? = if (heartbeats && !handling) lastSeenNanos + timeoutNanos - monotonicNanos() else null

    /**
     * Returns once heartbeats are armed and the stream has been silent for [timeout] outside event
     * handling; otherwise waits until cancelled.
     */
    suspend fun silence(timeout: Duration) {
        val timeoutNanos = timeout.inWholeNanoseconds
        while (true) {
            val remaining = remainingNanos(timeoutNanos)
            if (remaining != null && remaining <= 0) return
            delay(((remaining ?: timeoutNanos) / 1_000_000).coerceAtLeast(1))
        }
    }
}

/** A subscription dropped because it went silent after sending heartbeats. */
private class EventStreamSilent : kotlin.Exception()

/** How one subscription ended: whether it connected first, and why. */
private class StreamOutcome(val connected: Boolean, val reason: String)

/**
 * How long a subscription that sends heartbeats (every 5 s from the operators) may stay silent
 * before it is dropped and resubscribed — the reference SDK's `STREAM_HEARTBEAT_TIMEOUT_MS`.
 */
internal val EVENT_STREAM_HEARTBEAT_TIMEOUT: Duration = 15.seconds

/**
 * Events for this wallet, until the collector stops collecting or the wallet is closed. Like the
 * reference SDK's background stream it stays up on its own:
 * - a subscription that fails, or that the operator ends, is retried forever — 1 s doubling to
 *   15 s between attempts — with [SparkEvent.Reconnecting] before each wait;
 * - on every connection the wallet's pending transfers are claimed, so payments that arrived
 *   while the stream was down are not left waiting, and each is reported as
 *   [SparkEvent.TransferReceived];
 * - a payment that arrives while connected is claimed, then reported;
 * - once the operator sends heartbeats, a subscription silent for 15 s — a connection that died
 *   without closing, as after a network change — is dropped and resubscribed.
 *
 * The flow is cold: each collection runs its own subscription. [SparkWallet.close] ends every
 * running collection normally.
 *
 * @throws SparkError.InvalidArgument when the wallet is already closed.
 */
public suspend fun SparkWallet.subscribeToEvents(): Flow<SparkEvent> = subscribeToEvents(EVENT_STREAM_HEARTBEAT_TIMEOUT)

internal fun SparkWallet.subscribeToEvents(heartbeatTimeout: Duration): Flow<SparkEvent> {
    if (eventStreams.isClosed) throw SparkError.InvalidArgument("the wallet is closed")
    return channelFlow {
        val run = launch { runEventStream(this@channelFlow, heartbeatTimeout) }
        if (!eventStreams.register(run)) {
            run.cancel()
            return@channelFlow
        }
        try {
            // Returns normally when close() cancels the run: the flow then simply ends.
            run.join()
        } finally {
            eventStreams.finished(run)
        }
    }
}

/**
 * Wait before event-stream attempt `attempt + 1` after [attempt] failures in a row: 1 s doubling
 * to 15 s, the reference SDK's background-stream backoff.
 */
internal fun eventStreamBackoff(attempt: Int): Duration = minOf(15, 1 shl minOf(maxOf(attempt, 1) - 1, 4)).seconds

/** Subscribes, reports, and subscribes again — until cancelled. */
private suspend fun SparkWallet.runEventStream(events: ProducerScope<SparkEvent>, heartbeatTimeout: Duration) {
    var attempt = 0
    while (currentCoroutineContext().isActive) {
        val outcome = subscribeOnce(events, heartbeatTimeout)
        if (!currentCoroutineContext().isActive) break
        attempt = if (outcome.connected) 1 else attempt + 1
        val wait = eventStreamBackoff(attempt)
        events.send(SparkEvent.Reconnecting(attempt = attempt, retryIn = wait, reason = outcome.reason))
        delay(wait)
    }
}

/**
 * One subscription, reported until the operator ends it, it fails, or it goes silent after
 * sending heartbeats. Returns whether it connected and why it ended.
 */
private suspend fun SparkWallet.subscribeOnce(events: ProducerScope<SparkEvent>, heartbeatTimeout: Duration): StreamOutcome {
    val activity = EventStreamActivity()
    return try {
        val request = Spark.SubscribeToEventsRequest.newBuilder()
            .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
            .build()
        val messages = getCoordinatorStub().subscribeToEvents(request)
        coroutineScope {
            // The first to finish decides: the stream ended, failed or went silent.
            val watchdog = async {
                activity.silence(heartbeatTimeout)
                throw EventStreamSilent()
            }
            try {
                report(messages, events, activity)
            } finally {
                watchdog.cancel()
            }
        }
        StreamOutcome(activity.connected, "the operator ended the event stream")
    } catch (_: EventStreamSilent) {
        StreamOutcome(activity.connected, "no heartbeat for $heartbeatTimeout")
    } catch (e: CancellationException) {
        throw e
    } catch (e: kotlin.Exception) {
        StreamOutcome(activity.connected, e.toString())
    }
}

/** Reports one subscription's messages until it ends. */
private suspend fun SparkWallet.report(messages: Flow<Spark.SubscribeToEventsResponse>, events: ProducerScope<SparkEvent>, activity: EventStreamActivity,) {
    var claimedOnConnect = emptySet<String>()
    messages.collect { message ->
        activity.received(heartbeat = message.hasHeartbeat())
        try {
            when {
                message.hasConnected() -> {
                    activity.markConnected()
                    events.send(SparkEvent.Connected)
                    claimedOnConnect = claimPendingOnConnect(events)
                }
                message.hasReceiverTransfer() -> {
                    val transfer = message.receiverTransfer.transfer
                    if (transfer.id !in claimedOnConnect) {
                        claimOnArrival(transfer)
                        mapEvent(message)?.let { events.send(it) }
                    }
                }
                else -> mapEvent(message)?.let { events.send(it) }
            }
        } finally {
            activity.handled()
        }
    }
}

/**
 * Claims every pending transfer once the stream is up — payments that arrived while it was down
 * included — and reports the claimed payments. Returns the ids claimed, so that their stream
 * events, if the operators send those too, are not handled twice.
 */
private suspend fun SparkWallet.claimPendingOnConnect(events: ProducerScope<SparkEvent>): Set<String> {
    val claim = bestEffort { claimPendingTransfers() } ?: return emptySet()
    for (transfer in claim.claimedTransfers) {
        if (isIncomingPayment(transfer)) events.send(SparkEvent.TransferReceived(transfer.toSparkTransfer()))
    }
    return claim.claimedTransferIds.toSet()
}

/**
 * Claims a payment that arrived on the stream before it is reported, as the reference SDK does.
 * Best effort: one that cannot be claimed now stays pending for the next claim pass.
 */
private suspend fun SparkWallet.claimOnArrival(transfer: Spark.Transfer) {
    if (!isIncomingPayment(transfer) || transfer.status !in PendingTransferDrain.CLAIMABLE_STATUSES) return
    bestEffort { claimTransfer(transfer) } ?: return
    renewClaimedLeaves(transfer.leavesList.map { it.leaf.id })
}

/**
 * The event an operator's stream message is reported as, if any. As in the reference SDK, a
 * counter-transfer of the wallet's own swap and a self-transfer are not reported as received —
 * the operation that made them claims them — and a deposit is reported once its leaf is
 * available.
 */
internal fun mapEvent(response: Spark.SubscribeToEventsResponse): SparkEvent? = when {
    response.hasConnected() -> SparkEvent.Connected
    response.hasReceiverTransfer() -> {
        val transfer = response.receiverTransfer.transfer
        if (isIncomingPayment(transfer)) SparkEvent.TransferReceived(transfer.toSparkTransfer()) else null
    }
    response.hasSenderTransfer() -> SparkEvent.TransferSent(response.senderTransfer.transfer.toSparkTransfer())
    response.hasDeposit() -> {
        val deposit = response.deposit.deposit
        if (deposit.status == "AVAILABLE") SparkEvent.DepositConfirmed(deposit.treeId) else null
    }
    else -> null
}

/**
 * Whether a transfer to this wallet is a payment someone made to it, rather than the
 * counter-transfer of one of its own swaps or a transfer to itself.
 */
internal fun isIncomingPayment(transfer: Spark.Transfer): Boolean = transfer.type != Spark.TransferType.COUNTER_SWAP &&
    transfer.type != Spark.TransferType.COUNTER_SWAP_V3 &&
    transfer.senderIdentityPublicKey != transfer.receiverIdentityPublicKey
