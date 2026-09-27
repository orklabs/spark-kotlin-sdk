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
 * CANCELLED; re-authenticate and re-issue a call rejected as UNAUTHENTICATED; 20 MB messages;
 * the operators' clock; SSP retries). Ported from the Swift SDK's `TransportHardeningTests.swift`,
 * plus behaviour tests for the grpc-java replay mechanics.
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

    /** UNAUTHENTICATED is re-issued by [AuthRetryInterceptor], with a fresh token, not by the transport. */
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
    fun messagesUpToTheReferenceSdks20MbAreSentAndReceivedTheEventStreamIncluded() {
        val stream = methodConfigs.firstOrNull {
            it["name"] == listOf(mapOf("service" to "spark.SparkService", "method" to "subscribe_to_events"))
        }
        assertEquals(20 * 1024 * 1024, GrpcConnectionManager.MAX_MESSAGE_BYTES)
        for (config in listOf(global, stream)) {
            assertEquals(GrpcConnectionManager.MAX_MESSAGE_BYTES.toDouble(), config?.get("maxRequestMessageBytes"))
            assertEquals(GrpcConnectionManager.MAX_MESSAGE_BYTES.toDouble(), config?.get("maxResponseMessageBytes"))
        }
    }

    private fun availableNode(index: Int, payload: Int = 0): spark.Spark.TreeNode = spark.Spark.TreeNode.newBuilder()
        .setId("node-%04d".format(index))
        .setStatus("AVAILABLE")
        .setValue(1)
        .setNodeTx(bytes(0xAB, payload).toByteString())
        .build()

    @Test(timeout = 60_000)
    fun aWalletsNodesAreReadAPageOf100AtATimeUntilAShortPage() = runBlocking {
        val state = FakeOperatorState { false }
        state.setNodes((0 until 250).map { availableNode(it) })
        val leaves = withFakeOperator(state) { it.getLeaves() }
        assertEquals(250, leaves.size)
        assertEquals(listOf(listOf(100L, 0L), listOf(100L, 100L), listOf(100L, 200L)), state.nodePages)
    }

    @Test(timeout = 60_000)
    fun aPageLargerThanGrpcs4MibDefaultIsReceived() = runBlocking {
        val state = FakeOperatorState { false }
        // 100 nodes of 50 kB: one 5 MB page, then an empty one.
        state.setNodes((0 until 100).map { availableNode(it, payload = 50_000) })
        val leaves = withFakeOperator(state) { it.getLeaves() }
        assertEquals(100, leaves.size)
        assertEquals(listOf(listOf(100L, 0L), listOf(100L, 100L)), state.nodePages)
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
        val manager = GrpcConnectionManager(listOf("http://localhost:1"), allowsPlaintext = true)
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
    fun theAuthInterceptorLeavesTheTokenIssuingServiceAloneAndTheTransportNeverRetriesIt() {
        assertEquals("spark_authn.SparkAuthnService", AuthRetryInterceptor.AUTHN_SERVICE)
        assertEquals(spark_authn.SparkAuthnServiceGrpc.SERVICE_NAME, AuthRetryInterceptor.AUTHN_SERVICE)

        val channel = FakeChannel()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) { error("must not refresh") }
        val call = interceptor.interceptCall(method("spark_authn.SparkAuthnService/get_challenge"), CallOptions.DEFAULT, channel)
        assertSame(channel.calls.single(), call)

        val authn = methodConfigs.firstOrNull { it["name"] == listOf(mapOf("service" to "spark_authn.SparkAuthnService")) }
        assertNotNull(authn)
        assertNull(authn!!["retryPolicy"])
        assertEquals("${GrpcConnectionManager.DEFAULT_RPC_TIMEOUT_SECONDS}s", authn["timeout"])
    }

    @Test
    fun sspAmountsAreReadInTheirReportedUnitOtherUnitsAreRefused() {
        fun amount(value: Any?, unit: String?): org.json.JSONObject = org.json.JSONObject().apply {
            put("original_value", value)
            if (unit != null) put("original_unit", unit)
        }
        // As the SSP's JSON arrives.
        assertEquals(2L, SspCurrencyAmount.sats(org.json.JSONObject("""{"original_value": 2000, "original_unit": "MILLISATOSHI"}"""), "fee"))
        assertEquals(2L, SspCurrencyAmount.sats(amount(2L, "SATOSHI"), "fee"))
        assertEquals(3L, SspCurrencyAmount.sats(amount(2001L, "MILLISATOSHI"), "fee"))
        assertEquals(0L, SspCurrencyAmount.sats(amount(0L, "MILLISATOSHI"), "fee"))
        // Long.MAX_VALUE msat rounds up without overflowing.
        assertEquals(Long.MAX_VALUE / 1000 + 1, SspCurrencyAmount.sats(amount(Long.MAX_VALUE, "MILLISATOSHI"), "fee"))
        for (bad in listOf(amount(1L, "BITCOIN"), amount(1L, "USD"), amount(1L, null), amount(-1L, "SATOSHI"), amount("12", "SATOSHI"))) {
            expectSparkError(bad.toString()) { SspCurrencyAmount.sats(bad, "fee") }
        }
        expectSparkError { SspCurrencyAmount.sats(null, "fee") }
        assertTrue(GraphQLQueries.LIGHTNING_SEND_FEE_ESTIMATE.contains("original_unit"))
    }

    private fun reply(status: Int): okhttp3.Response = okhttp3.Response.Builder()
        .request(okhttp3.Request.Builder().url("https://ssp.example/graphql").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(status)
        .message("")
        .body(okhttp3.ResponseBody.Companion.create(null, ""))
        .build()

    @Test
    fun sspRequestsAreRetriedLikeTheReferenceSdks() = runBlocking {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L), (0..6).map { SspRetry.STANDARD.delayMs(it) })
        val instant = SspRetry(maxRetries = 5, baseDelayMs = 0, maxDelayMs = 0)

        var attempts = 0
        var response = instant.run {
            attempts++
            reply(if (attempts < 3) listOf(503, 502)[attempts - 1] else 200)
        }
        assertEquals(3, attempts)
        assertEquals(200, response.code)

        attempts = 0
        response = instant.run {
            attempts++
            if (attempts == 1) throw java.net.SocketException("Connection reset")
            reply(200)
        }
        assertEquals(2, attempts)

        attempts = 0
        instant.run {
            attempts++
            if (attempts == 1) throw java.net.UnknownHostException("ssp.example")
            reply(200)
        }
        assertEquals(2, attempts)

        // Out of retries, the last answer stands; other statuses, timeouts and bad certificates are not retried.
        attempts = 0
        response = instant.run {
            attempts++
            reply(504)
        }
        assertEquals(6, attempts)
        assertEquals(504, response.code)
        attempts = 0
        instant.run {
            attempts++
            reply(500)
        }
        assertEquals(1, attempts)
        attempts = 0
        assertThrows(java.net.SocketTimeoutException::class.java) {
            runBlocking {
                instant.run {
                    attempts++
                    throw java.net.SocketTimeoutException("timeout")
                }
            }
        }
        assertEquals(1, attempts)
        attempts = 0
        assertThrows(javax.net.ssl.SSLPeerUnverifiedException::class.java) {
            runBlocking {
                instant.run {
                    attempts++
                    throw javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun operatorsAreReachedOverTlsPlaintextOnlyWhereAllowedNeverOnMainnetOtherSchemesNever() = runBlocking {
        val mainnet = GrpcConnectionManager(addresses = emptyList())
        assertTrue(runCatching { mainnet.getChannel("http://operator.example") }.exceptionOrNull() is SparkError.InvalidArgument)
        mainnet.getChannel("https://operator.example")
        val regtest = GrpcConnectionManager(addresses = emptyList(), allowsPlaintext = true)
        regtest.getChannel("http://127.0.0.1:9001")
        assertTrue(runCatching { regtest.getChannel("ftp://operator.example") }.exceptionOrNull() is SparkError.InvalidArgument)
        mainnet.close()
        regtest.close()
    }

    @Test
    fun theSspIdentityKeyFollowsTheSsp() {
        assertEquals("023e33e2920326f64ea31058d44777442d97d7d5cbfcf54e3060bc1695e5261c93", SparkConfig().sspIdentityPublicKey.toHexString())
        assertEquals(
            "022bf283544b16c0622daecb79422007d167eca6ce9f0c98c0c49833b1f7170bfe",
            SparkConfig(network = SparkNetwork.REGTEST).sspIdentityPublicKey.toHexString(),
        )
        assertTrue(SparkConfig(sspURL = SparkConfig.DEFAULT_SSP_URL).sspIdentityPublicKey.contentEquals(SparkConfig().sspIdentityPublicKey))

        // A custom SSP without its key: no key, and transfers to the SSP refuse to run.
        val custom = SparkConfig(sspURL = "https://ssp.example/graphql")
        assertTrue(custom.sspIdentityPublicKey.isEmpty())
        expectSparkError { custom.requireSspIdentityPublicKey() }

        val key = "03" + "ab".repeat(32)
        val configured = SparkConfig(sspURL = "https://ssp.example/graphql", sspIdentityPublicKeyHex = key)
        assertEquals(key, configured.requireSspIdentityPublicKey().toHexString())
        expectSparkError { SparkConfig(sspIdentityPublicKeyHex = "zz").requireSspIdentityPublicKey() }
        expectSparkError { SparkConfig(sspIdentityPublicKeyHex = "04" + "ab".repeat(32)).requireSspIdentityPublicKey() }
    }

    @Test
    fun theRegtestPresetUsesTheHostedOperatorsAndKeys() {
        val regtest = SparkConfig(network = SparkNetwork.REGTEST)
        val mainnet = SparkConfig()
        assertEquals(mainnet.signingOperators.map { it.address }, regtest.signingOperators.map { it.address })
        assertEquals(mainnet.signingOperators.map { it.identityPublicKeyHex }, regtest.signingOperators.map { it.identityPublicKeyHex })
        assertTrue(regtest.signingOperators.all { it.address.startsWith("https://") && it.identityPublicKeyHex.length == 66 })
        assertEquals(2u, regtest.signingThreshold)
    }

    @Test
    fun theOperatorsClockIsEstimatedFromTheirDateAndProcessingTimeHeaders() {
        var monotonic = 5_000_000_000L
        val clock = ServerClock { monotonic }
        assertFalse(clock.isSynced)
        assertTrue(kotlin.math.abs(clock.nowMillis() - System.currentTimeMillis()) < 1_000)
        // Garbage headers are ignored.
        clock.record("yesterday", "1", monotonic, monotonic)
        clock.record("Mon, 02 Jan 2006 15:04:05 UTC", "-5", monotonic, monotonic)
        clock.record("Mon, 02 Jan 2006 15:04:05 UTC trailing", "1", monotonic, monotonic)
        assertFalse(clock.isSynced)

        // Answered 200 ms after sending, 100 ms of it processing: 50 ms each way.
        clock.record("Mon, 02 Jan 2006 15:04:05 UTC", "100", sentNanos = monotonic - 200_000_000, receivedNanos = monotonic)
        assertTrue(clock.isSynced)
        val stated = ServerClock.parseDate("Mon, 02 Jan 2006 15:04:05 UTC")!!
        assertEquals(1_136_214_245_000L, stated.time)
        assertEquals(stated.time + 50, clock.nowMillis())
        // Then it advances on the monotonic clock, whatever the device clock does.
        monotonic += 1_500_000_000
        assertEquals(stated.time + 1_550, clock.nowMillis())
        assertEquals("Mon, 02 Jan 2006 15:04:05 UTC", ServerClock.formatDate(stated))
    }

    @Test(timeout = 60_000)
    fun sessionTokensAreKeptByTheOperatorsClockADeviceClockTwoHoursAheadDoesNotReauthenticateEveryCall() = runBlocking {
        val state = FakeOperatorState { false }
        state.clockOffsetMillis = -2 * 3_600_000L
        withFakeOperator(state) { wallet ->
            repeat(3) { wallet.getLeaves() }
            assertTrue(wallet.serverClock.isSynced)
            assertTrue(kotlin.math.abs(wallet.serverClock.nowMillis() - System.currentTimeMillis() + 2 * 3_600_000L) < 5_000)
        }
        assertEquals(listOf("session-1"), state.issuedTokens)
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

    private fun authorized(token: String = "stale"): Metadata = Metadata().apply { put(SparkAuthenticator.AUTHORIZATION_KEY, "Bearer $token") }

    @Test
    fun anUnauthenticatedCallIsReplayedWithAFreshTokenAndTheRejectedOneIsNamed() {
        val channel = FakeChannel()
        val rejected = mutableListOf<String?>()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) {
            rejected.add(it)
            "fresh"
        }
        val listener = RecordingListener()
        val call = interceptor.interceptCall(method(), CallOptions.DEFAULT, channel)
        call.start(listener, authorized())
        call.request(2)
        call.sendMessage("request")
        call.halfClose()

        val first = channel.calls.single()
        assertEquals("Bearer stale", first.headers.get(SparkAuthenticator.AUTHORIZATION_KEY))
        first.listener.onHeaders(Metadata())
        first.listener.onClose(Status.UNAUTHENTICATED.withDescription("token has expired"), Metadata())

        // The failed attempt is invisible to the caller; the replay carries the new token only.
        assertEquals(listOf<String?>("stale"), rejected)
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
    fun aRejectionOnTheLastAttemptIsReturnedAsIs() {
        val channel = FakeChannel()
        val rejected = mutableListOf<String?>()
        var refreshes = 0
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) {
            rejected.add(it)
            "fresh-${++refreshes}"
        }
        val listener = RecordingListener()
        val call = interceptor.interceptCall(method(), CallOptions.DEFAULT, channel)
        call.start(listener, authorized())
        call.sendMessage("request")
        call.halfClose()
        repeat(AuthRetryInterceptor.MAX_ATTEMPTS) { channel.calls[it].listener.onClose(Status.UNAUTHENTICATED, Metadata()) }
        // Each refresh names the token that attempt was rejected with.
        assertEquals(listOf<String?>("stale", "fresh-1"), rejected)
        assertEquals(AuthRetryInterceptor.MAX_ATTEMPTS, channel.calls.size)
        assertEquals(listOf("close:UNAUTHENTICATED"), listener.events)
    }

    @Test
    fun aCallWithoutASessionTokenIsNeverReplayed() {
        val channel = FakeChannel()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) { error("must not refresh") }
        val listener = RecordingListener()
        interceptor.interceptCall(method(), CallOptions.DEFAULT, channel).start(listener, Metadata())
        channel.calls.single().listener.onClose(Status.UNAUTHENTICATED, Metadata())
        assertEquals(listOf("close:UNAUTHENTICATED"), listener.events)
        assertEquals(1, channel.calls.size)
    }

    @Test
    fun otherFailuresAndStreamsThatAlreadyDeliveredDataAreNotReplayed() {
        val channel = FakeChannel()
        val interceptor = AuthRetryInterceptor(CoroutineScope(Dispatchers.Unconfined)) { error("must not refresh") }

        val unavailable = RecordingListener()
        interceptor.interceptCall(method(), CallOptions.DEFAULT, channel).start(unavailable, authorized())
        channel.calls.last().listener.onClose(Status.UNAVAILABLE, Metadata())
        assertEquals(listOf("close:UNAVAILABLE"), unavailable.events)

        val stream = RecordingListener()
        interceptor.interceptCall(method(), CallOptions.DEFAULT, channel).start(stream, authorized())
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
        interceptor.interceptCall(method(), CallOptions.DEFAULT, channel).start(listener, authorized())
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
        call.start(listener, authorized())
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
