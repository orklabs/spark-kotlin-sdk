package gy.pig.spark

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The operators' clock as this device estimates it, like the reference SDK's `ServerTimeSync`.
 * Every successful operator answer carries its `date` (whole seconds) and how long it took to
 * process (`x-processing-time-ms`); half the remaining round trip is added to the date, and the
 * estimate then advances on the monotonic clock, so a device clock that is wrong — or changes —
 * does not move it. Until the first answer the device clock is used.
 *
 * @param monotonicNanos the monotonic clock, [System.nanoTime] outside tests.
 */
internal class ServerClock(private val monotonicNanos: () -> Long = System::nanoTime) {
    /** The operators' time, in milliseconds since the epoch, at the monotonic instant [atNanos]. */
    private class Sample(val serverTimeMillis: Double, val atNanos: Long)

    @Volatile
    private var sample: Sample? = null

    val isSynced: Boolean
        get() = sample != null

    /** The monotonic clock the estimate advances on, in nanoseconds. */
    fun monotonicNow(): Long = monotonicNanos()

    /** The operators' current time: the latest estimate advanced by the monotonic clock, or the device clock before any answer. */
    fun nowMillis(): Long {
        val current = sample ?: return System.currentTimeMillis()
        return (current.serverTimeMillis + (monotonicNanos() - current.atNanos) / NANOS_PER_MILLI).toLong()
    }

    fun now(): Date = Date(nowMillis())

    /**
     * Records one answer: `date` and `processingTime` from its headers, sent at [sentNanos] and
     * answered at [receivedNanos] on the monotonic clock. A header that does not parse is ignored.
     */
    fun record(date: String, processingTime: String, sentNanos: Long, receivedNanos: Long) {
        val serverDate = parseDate(date) ?: return
        val processingMs = processingTime.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return
        val roundTripMs = maxOf(0.0, (receivedNanos - sentNanos) / NANOS_PER_MILLI - processingMs)
        sample = Sample(serverTimeMillis = serverDate.time + roundTripMs / 2, atNanos = receivedNanos)
    }

    internal companion object {
        private const val NANOS_PER_MILLI = 1_000_000.0

        val DATE_KEY: Metadata.Key<String> = Metadata.Key.of("date", Metadata.ASCII_STRING_MARSHALLER)
        val PROCESSING_TIME_KEY: Metadata.Key<String> = Metadata.Key.of("x-processing-time-ms", Metadata.ASCII_STRING_MARSHALLER)

        /** Go's `time.RFC1123`, which the operators send: "Mon, 02 Jan 2006 15:04:05 UTC". */
        private fun dateFormat(): SimpleDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

        /** An RFC 1123 date as the operators write it, or `null`. The whole string must parse. */
        fun parseDate(value: String): Date? {
            val position = ParsePosition(0)
            val date = dateFormat().parse(value, position) ?: return null
            return date.takeIf { position.index == value.length }
        }

        fun formatDate(date: Date): String = dateFormat().format(date)
    }
}

/**
 * Feeds [ServerClock] from the headers of every operator answer. Innermost interceptor, so the
 * measured round trip excludes the authentication a call may wait for first.
 */
internal class ServerTimeInterceptor(private val clock: ServerClock) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(method: MethodDescriptor<ReqT, RespT>, callOptions: CallOptions, next: Channel): ClientCall<ReqT, RespT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                val sent = clock.monotonicNow()
                val listener = object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onHeaders(headers: Metadata) {
                        val date = headers.get(ServerClock.DATE_KEY)
                        val processing = headers.get(ServerClock.PROCESSING_TIME_KEY)
                        if (date != null && processing != null) clock.record(date, processing, sent, clock.monotonicNow())
                        super.onHeaders(headers)
                    }
                }
                super.start(listener, headers)
            }
        }
}
