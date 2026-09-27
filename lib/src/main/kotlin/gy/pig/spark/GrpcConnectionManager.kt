package gy.pig.spark

import io.grpc.ClientInterceptor
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * One gRPC channel per operator, built lazily.
 *
 * Every channel carries [SERVICE_CONFIG] — the official Spark SDK's transport policy: a 60 s
 * default deadline, 20 MB messages and a retry policy on UNAVAILABLE and CANCELLED — plus the
 * interceptors [interceptorFactory] builds for its address (see [AuthRetryInterceptor] and
 * [ServerTimeInterceptor]).
 *
 * grpc-java channels reconnect by themselves after a transport failure, so unlike the Swift SDK
 * there is no need to evict a client whose connection loop ended.
 *
 * @param allowsPlaintext whether an `http://` operator may be reached without TLS: never on mainnet.
 */
internal class GrpcConnectionManager(
    private val addresses: List<String>,
    private val allowsPlaintext: Boolean = false,
    private val interceptorFactory: (String) -> List<ClientInterceptor> = { emptyList() },
) {
    private val channels = mutableMapOf<String, ManagedChannel>()
    private val mutex = Mutex()

    val allAddresses: List<String> get() = addresses

    suspend fun getChannel(address: String): ManagedChannel = mutex.withLock {
        channels.getOrPut(address) { createChannel(address) }
    }

    private fun createChannel(address: String): ManagedChannel {
        val uri = try {
            URI(address)
        } catch (_: java.net.URISyntaxException) {
            throw SparkError.GrpcError("Invalid SO address: $address")
        }
        val host = uri.host ?: throw SparkError.GrpcError("Invalid SO address: $address")
        val useTLS = uri.scheme == "https"
        if (!useTLS && !(uri.scheme == "http" && allowsPlaintext)) {
            throw SparkError.InvalidArgument("operator $address must be reached over https")
        }
        val port = if (uri.port > 0) {
            uri.port
        } else if (useTLS) {
            443
        } else {
            80
        }

        val builder = OkHttpChannelBuilder.forAddress(host, port)
        if (useTLS) {
            builder.useTransportSecurity()
        } else {
            builder.usePlaintext()
        }
        // Transport on the defaults, like the official SDK: no client keepalive (the operators
        // send their own keepalive pings and bound how often clients may ping), default idle time.
        return builder
            .defaultServiceConfig(SERVICE_CONFIG)
            // The service config above is the policy; never let a resolver's TXT record replace it.
            .disableServiceConfigLookUp()
            .enableRetry()
            .maxInboundMessageSize(MAX_MESSAGE_BYTES)
            .intercept(interceptorFactory(address))
            .build()
    }

    suspend fun close() = mutex.withLock {
        for ((_, channel) in channels) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
        }
        channels.clear()
    }

    internal companion object {
        /**
         * Deadline applied to every RPC by default ([SERVICE_CONFIG]). Mirrors the official Spark
         * SDK's last-resort 60 s cap on unary calls: without any deadline a call on a connection
         * that looks alive but never answers parks the caller until the process restarts.
         */
        const val DEFAULT_RPC_TIMEOUT_SECONDS: Long = 60

        /**
         * Largest message sent or received: the reference SDK's 20 MB, raised from gRPC's 4 MiB
         * default after `start_transfer_v2` answers of ~5 MB.
         */
        const val MAX_MESSAGE_BYTES: Int = 20 * 1024 * 1024

        /** The event subscription: a long-lived server stream. */
        const val EVENT_STREAM_SERVICE = "spark.SparkService"
        const val EVENT_STREAM_METHOD = "subscribe_to_events"

        /**
         * The official SDK's retry policy: up to 3 attempts, 1 s → 10 s exponential backoff, on
         * UNAVAILABLE and CANCELLED. That is how a pooled connection the server closed while idle
         * (or rotated out by its max connection age) heals: the failed attempt never reached the
         * server, and the retry re-establishes the connection. A deadline is deliberately NOT
         * retryable.
         *
         * UNAUTHENTICATED is not listed, unlike in the Swift SDK: grpc-java's transport retries
         * re-send the original headers, stale token included, so [AuthRetryInterceptor] re-issues
         * a rejected call itself, with a fresh token, as often as this policy would.
         *
         * grpc-java reads service configs as JSON-shaped maps, so numbers must be `Double`.
         */
        val RETRY_POLICY: Map<String, Any> = mapOf(
            "maxAttempts" to 3.0,
            "initialBackoff" to "1s",
            "maxBackoff" to "10s",
            "backoffMultiplier" to 2.0,
            "retryableStatusCodes" to listOf("UNAVAILABLE", "CANCELLED"),
        )

        /**
         * The event subscription is unbounded and never retried here (reconnecting it with
         * backoff is the subscriber's job, as in the official wallet). The token service is not
         * retried here either: a retried `verify_challenge` re-sends a challenge the operator may
         * already have consumed ("challenge reused"), so [SparkAuthenticator] retries whole
         * exchanges instead. A per-method or per-service entry takes precedence over the global
         * (empty-name) one.
         */
        val SERVICE_CONFIG: Map<String, Any> = mapOf(
            "methodConfig" to listOf(
                mapOf(
                    "name" to listOf(emptyMap<String, Any>()),
                    "timeout" to "${DEFAULT_RPC_TIMEOUT_SECONDS}s",
                    "maxRequestMessageBytes" to MAX_MESSAGE_BYTES.toDouble(),
                    "maxResponseMessageBytes" to MAX_MESSAGE_BYTES.toDouble(),
                    "retryPolicy" to RETRY_POLICY,
                ),
                mapOf(
                    "name" to listOf(mapOf("service" to EVENT_STREAM_SERVICE, "method" to EVENT_STREAM_METHOD)),
                    "maxRequestMessageBytes" to MAX_MESSAGE_BYTES.toDouble(),
                    "maxResponseMessageBytes" to MAX_MESSAGE_BYTES.toDouble(),
                ),
                mapOf(
                    "name" to listOf(mapOf("service" to AuthRetryInterceptor.AUTHN_SERVICE)),
                    "timeout" to "${DEFAULT_RPC_TIMEOUT_SECONDS}s",
                    "maxRequestMessageBytes" to MAX_MESSAGE_BYTES.toDouble(),
                    "maxResponseMessageBytes" to MAX_MESSAGE_BYTES.toDouble(),
                ),
            ),
        )
    }
}
