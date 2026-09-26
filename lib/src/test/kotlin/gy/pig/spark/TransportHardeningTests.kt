package gy.pig.spark

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Pins the transport behaviour that stops a wedged connection or a rejected session token from
 * parking every call until the host process restarts — the values mirror the official Spark
 * SDK's connection manager (60 s unary cap; 3 attempts, 1 s → 10 s backoff on UNAVAILABLE and
 * CANCELLED; re-authenticate and replay once on an expired token). Ported from the Swift SDK's
 * `TransportHardeningTests.swift`, plus behaviour tests for the grpc-java replay mechanics.
 */
class TransportHardeningTests {

    @Suppress("UNCHECKED_CAST")
    private val methodConfigs = GrpcConnectionManager.SERVICE_CONFIG["methodConfig"] as List<Map<String, Any>>

    private val global: Map<String, Any>?
        get() = methodConfigs.firstOrNull { it["name"] == listOf(emptyMap<String, Any>()) }

    @Test
    fun everyRpcCarriesTheOfficial60SecondDeadline() {
        assertEquals("${GrpcConnectionManager.DEFAULT_RPC_TIMEOUT_SECONDS}s", global?.get("timeout"))
        assertEquals(60L, GrpcConnectionManager.DEFAULT_RPC_TIMEOUT_SECONDS)
    }

    @Test
    fun everyRpcRetriesLikeTheOfficialSdk() {
        @Suppress("UNCHECKED_CAST")
        val policy = global?.get("retryPolicy") as Map<String, Any>
        assertEquals(3.0, policy["maxAttempts"])
        assertEquals("1s", policy["initialBackoff"])
        assertEquals("10s", policy["maxBackoff"])
        assertEquals(2.0, policy["backoffMultiplier"])
        assertEquals(listOf("UNAVAILABLE", "CANCELLED"), policy["retryableStatusCodes"])
        assertFalse((policy["retryableStatusCodes"] as List<*>).contains("DEADLINE_EXCEEDED"))
    }

    @Test
    fun theEventSubscriptionStreamIsUnboundedAndNeverRetriedByTheTransport() {
        val stream = methodConfigs.firstOrNull {
            it["name"] == listOf(mapOf("service" to "spark.SparkService", "method" to "subscribe_to_events"))
        }
        assertNotNull(stream)
        assertNull(stream!!["timeout"])
        assertNull(stream["retryPolicy"])
        // The name must match the generated descriptor, or the override silently never applies.
        assertEquals(
            "spark.SparkService/subscribe_to_events",
            spark.SparkServiceGrpc.getSubscribeToEventsMethod().fullMethodName,
        )
    }

    @Test
    fun grpcJavaAcceptsTheServiceConfigWhenAChannelIsBuilt() = runBlocking {
        // No connection is made: channels connect lazily. Building parses the default service
        // config, so a config grpc-java cannot read fails here instead of on the first call.
        val manager = GrpcConnectionManager(listOf("http://localhost:1"))
        manager.getChannel("http://localhost:1")
        manager.close()

        // Negative control: the same builder does reject a malformed config at build time.
        val broken = mapOf("methodConfig" to listOf(mapOf("name" to listOf(emptyMap<String, Any>()), "timeout" to "sixty")))
        assertThrows(RuntimeException::class.java) {
            OkHttpChannelBuilder.forAddress("localhost", 1).usePlaintext().defaultServiceConfig(broken).build().shutdownNow()
        }
        Unit
    }

    @Test
    fun theAuthInterceptorLeavesTheTokenIssuingServiceAlone() {
        assertEquals("spark_authn.SparkAuthnService", AuthRetryInterceptor.AUTHN_SERVICE)
        assertEquals(spark_authn.SparkAuthnServiceGrpc.SERVICE_NAME, AuthRetryInterceptor.AUTHN_SERVICE)

        val channel = FakeChannel()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) { error("must not refresh") }
        val call = interceptor.interceptCall(method("spark_authn.SparkAuthnService/get_challenge"), CallOptions.DEFAULT, channel)
        assertSame(channel.calls.single(), call)
    }

    @Test
    fun anSspAuthRejectionIsRecognisedOtherFailuresAreNot() {
        assertTrue(SspGraphQLClient.isAuthFailure(SparkError.GraphqlError("HTTP 401")))
        assertTrue(SspGraphQLClient.isAuthFailure(SparkError.GraphqlError("HTTP 403")))
        assertTrue(SspGraphQLClient.isAuthFailure(SparkError.GraphqlError("Unauthorized")))
        assertTrue(SspGraphQLClient.isAuthFailure(SparkError.GraphqlError("Not authenticated: token expired")))
        assertFalse(SspGraphQLClient.isAuthFailure(SparkError.GraphqlError("HTTP 500")))
        assertFalse(SspGraphQLClient.isAuthFailure(SparkError.GraphqlError("Insufficient funds for coop exit")))
        assertFalse(SspGraphQLClient.isAuthFailure(SparkError.InvalidResponse("Invalid fee estimate response")))
    }

    // ── AuthRetryInterceptor mechanics ──────────────────────────────────────

    @Test
    fun anUnauthenticatedCallIsReplayedOnceWithAFreshToken() {
        val channel = FakeChannel()
        var refreshes = 0
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) {
            refreshes++
            "fresh"
        }
        val listener = RecordingListener()
        val call = interceptor.interceptCall(method(), CallOptions.DEFAULT, channel)
        call.start(listener, Metadata().apply { put(SparkAuthenticator.AUTHORIZATION_KEY, "Bearer stale") })
        call.request(2)
        call.sendMessage("request")
        call.halfClose()

        val first = channel.calls.single()
        assertEquals("Bearer stale", first.headers.get(SparkAuthenticator.AUTHORIZATION_KEY))
        first.listener.onHeaders(Metadata())
        first.listener.onClose(Status.UNAUTHENTICATED.withDescription("token has expired"), Metadata())

        // The failed attempt is invisible to the caller; the replay carries the new token only.
        assertEquals(1, refreshes)
        assertTrue(listener.events.isEmpty())
        val second = channel.calls[1]
        assertEquals(listOf("Bearer fresh"), second.headers.getAll(SparkAuthenticator.AUTHORIZATION_KEY)?.toList())
        assertEquals(listOf("request"), second.messages)
        assertEquals(2, second.requested)
        assertTrue(second.halfClosed)

        second.listener.onHeaders(Metadata())
        second.listener.onMessage("response")
        second.listener.onClose(Status.OK, Metadata())
        assertEquals(listOf("headers", "message:response", "close:OK"), listener.events)
    }

    @Test
    fun aSecondRejectionIsReturnedAsIs() {
        val channel = FakeChannel()
        var refreshes = 0
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) {
            refreshes++
            "fresh"
        }
        val listener = RecordingListener()
        val call = interceptor.interceptCall(method(), CallOptions.DEFAULT, channel)
        call.start(listener, Metadata())
        call.sendMessage("request")
        call.halfClose()
        channel.calls[0].listener.onClose(Status.UNAUTHENTICATED, Metadata())
        channel.calls[1].listener.onClose(Status.UNAUTHENTICATED, Metadata())
        assertEquals(1, refreshes)
        assertEquals(2, channel.calls.size)
        assertEquals(listOf("close:UNAUTHENTICATED"), listener.events)
    }

    @Test
    fun otherFailuresAndStreamsThatAlreadyDeliveredDataAreNotReplayed() {
        val channel = FakeChannel()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) { error("must not refresh") }

        val unavailable = RecordingListener()
        interceptor.interceptCall(method(), CallOptions.DEFAULT, channel).start(unavailable, Metadata())
        channel.calls.last().listener.onClose(Status.UNAVAILABLE, Metadata())
        assertEquals(listOf("close:UNAVAILABLE"), unavailable.events)

        val stream = RecordingListener()
        interceptor.interceptCall(method(), CallOptions.DEFAULT, channel).start(stream, Metadata())
        val attempt = channel.calls.last()
        attempt.listener.onHeaders(Metadata())
        attempt.listener.onMessage("event")
        attempt.listener.onClose(Status.UNAUTHENTICATED, Metadata())
        assertEquals(listOf("headers", "message:event", "close:UNAUTHENTICATED"), stream.events)
        assertEquals(2, channel.calls.size)
    }

    @Test
    fun aFailedReauthenticationClosesTheCallAsUnauthenticated() {
        val channel = FakeChannel()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) {
            throw SparkError.AuthenticationFailed("operator unreachable")
        }
        val listener = RecordingListener()
        interceptor.interceptCall(method(), CallOptions.DEFAULT, channel).start(listener, Metadata())
        channel.calls.single().listener.onClose(Status.UNAUTHENTICATED, Metadata())
        assertEquals(listOf("close:UNAUTHENTICATED"), listener.events)
        assertTrue(listener.closeStatus?.cause is SparkError.AuthenticationFailed)
        assertEquals(1, channel.calls.size)
    }

    @Test
    fun cancellingDuringTheRefreshClosesTheCallOnceAndStartsNoReplay() {
        val channel = FakeChannel()
        val token = CompletableDeferred<String>()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) { token.await() }
        val listener = RecordingListener()
        val call = interceptor.interceptCall(method(), CallOptions.DEFAULT, channel)
        call.start(listener, Metadata())
        channel.calls.single().listener.onClose(Status.UNAUTHENTICATED, Metadata())
        assertFalse(call.isReady)

        call.cancel("caller gave up", null)
        assertEquals(listOf("close:CANCELLED"), listener.events)
        token.complete("fresh")
        assertEquals(1, channel.calls.size)
        assertEquals(listOf("close:CANCELLED"), listener.events)
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    private object StringMarshaller : MethodDescriptor.Marshaller<String> {
        override fun stream(value: String): InputStream = ByteArrayInputStream(value.toByteArray())

        override fun parse(stream: InputStream): String = stream.readBytes().decodeToString()
    }

    private fun method(fullName: String = "spark.SparkService/query_nodes"): MethodDescriptor<String, String> = MethodDescriptor.newBuilder<String, String>()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(fullName)
        .setRequestMarshaller(StringMarshaller)
        .setResponseMarshaller(StringMarshaller)
        .build()

    private class FakeCall : ClientCall<String, String>() {
        lateinit var listener: Listener<String>
        lateinit var headers: Metadata
        val messages = mutableListOf<String>()
        var requested = 0
        var halfClosed = false
        var cancelled = false

        override fun start(responseListener: Listener<String>, headers: Metadata) {
            listener = responseListener
            this.headers = headers
        }

        override fun request(numMessages: Int) {
            requested += numMessages
        }

        override fun cancel(message: String?, cause: Throwable?) {
            cancelled = true
        }

        override fun halfClose() {
            halfClosed = true
        }

        override fun sendMessage(message: String) {
            messages.add(message)
        }
    }

    private class FakeChannel : Channel() {
        val calls = mutableListOf<FakeCall>()

        @Suppress("UNCHECKED_CAST")
        override fun <RequestT, ResponseT> newCall(
            methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
            callOptions: CallOptions,
        ): ClientCall<RequestT, ResponseT> = FakeCall().also { calls.add(it) } as ClientCall<RequestT, ResponseT>

        override fun authority(): String = "fake"
    }

    private class RecordingListener : ClientCall.Listener<String>() {
        val events = mutableListOf<String>()
        var closeStatus: Status? = null

        override fun onHeaders(headers: Metadata) {
            events.add("headers")
        }

        override fun onMessage(message: String) {
            events.add("message:$message")
        }

        override fun onClose(status: Status, trailers: Metadata) {
            closeStatus = status
            events.add("close:${status.code}")
        }
    }
}
