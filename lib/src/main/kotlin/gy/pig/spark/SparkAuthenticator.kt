package gy.pig.spark

import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import spark_authn.SparkAuthn
import spark_authn.SparkAuthnServiceGrpcKt

/**
 * Session tokens per operator and identity, like the official SDK's auth cache.
 *
 * @param clock token expiry is the operators' time, so it is compared with their clock, not the device's.
 * @param scope runs the authentications callers share: one caller giving up does not cancel it for the others.
 */
internal class SparkAuthenticator(private val clock: ServerClock, private val scope: CoroutineScope) {
    private class CachedToken(val token: String, val expiresAtMillis: Long)

    private val tokenCache = mutableMapOf<String, CachedToken>()

    /**
     * The authentication in progress per operator and identity: concurrent callers share it
     * instead of each running a challenge (the reference SDK's `authInflight`).
     */
    private val inFlight = mutableMapOf<String, Deferred<CachedToken>>()
    private val mutex = Mutex()

    internal companion object {
        private const val REFRESH_BUFFER_MS = 60_000L

        /** Challenge exchanges tried before authentication fails, as in the reference SDK. */
        const val MAX_ATTEMPTS: Int = 8

        /** Pause before a new exchange when the last one failed on the way. */
        const val CONNECTION_RETRY_DELAY_MS: Long = 250

        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

        /** The operator refused the challenge as expired or already used; a fresh one will do. */
        fun isStaleChallenge(status: Status): Boolean {
            val message = status.description.orEmpty()
            return status.code == Status.Code.FAILED_PRECONDITION &&
                (message.contains("challenge expired") || message.contains("challenge reused"))
        }

        /** The exchange failed on the way rather than on its content. */
        fun isConnectionFailure(status: Status): Boolean = status.code in setOf(
            Status.Code.UNAVAILABLE,
            Status.Code.INTERNAL,
            Status.Code.UNKNOWN,
            Status.Code.CANCELLED,
            Status.Code.DEADLINE_EXCEEDED,
        )
    }

    suspend fun getToken(connectionManager: GrpcConnectionManager, soAddress: String, signer: SparkSignerProtocol): String {
        val cacheKey = "$soAddress:${signer.identityPublicKey.toHexString()}"
        val authentication = mutex.withLock {
            val cached = tokenCache[cacheKey]
            if (cached != null && cached.expiresAtMillis > clock.nowMillis() + REFRESH_BUFFER_MS) {
                return cached.token
            }
            inFlight.getOrPut(cacheKey) {
                scope.async {
                    val result = runCatching { authenticate(connectionManager, soAddress, signer) }
                    withContext(NonCancellable) {
                        mutex.withLock {
                            inFlight.remove(cacheKey)
                            result.getOrNull()?.let { tokenCache[cacheKey] = it }
                        }
                    }
                    result.getOrThrow()
                }
            }
        }
        return authentication.await().token
    }

    /**
     * Forget this operator's cached session if it is still [token]. Called by
     * [AuthRetryInterceptor] when the operator answers UNAUTHENTICATED: a token the server no
     * longer honours stays "valid" by its own `expiresAt`, and reusing it would fail every call
     * until then. Only the rejected token is dropped — a concurrent call may already have
     * replaced it with a fresh one. Per operator and identity, like the official SDK's cache.
     */
    suspend fun invalidate(soAddress: String, signer: SparkSignerProtocol, token: String) {
        val cacheKey = "$soAddress:${signer.identityPublicKey.toHexString()}"
        mutex.withLock {
            if (tokenCache[cacheKey]?.token == token) tokenCache.remove(cacheKey)
        }
    }

    suspend fun getAuthMetadata(connectionManager: GrpcConnectionManager, soAddress: String, signer: SparkSignerProtocol): Metadata {
        val token = getToken(connectionManager, soAddress, signer)
        val metadata = Metadata()
        metadata.put(AUTHORIZATION_KEY, "Bearer $token")
        return metadata
    }

    /**
     * Up to [MAX_ATTEMPTS] challenge exchanges, as the reference SDK makes them: a fresh challenge
     * at once when the last one expired or was already used (a lost answer), after 250 ms when
     * the connection failed; any other failure ends authentication.
     */
    private suspend fun authenticate(connectionManager: GrpcConnectionManager, soAddress: String, signer: SparkSignerProtocol): CachedToken {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) {
            try {
                return exchangeChallenge(connectionManager, soAddress, signer)
            } catch (e: kotlin.Exception) {
                val status = e.grpcStatus ?: throw e
                when {
                    isStaleChallenge(status) -> lastError = e
                    isConnectionFailure(status) -> {
                        lastError = e
                        delay(CONNECTION_RETRY_DELAY_MS)
                    }
                    else -> throw e
                }
            }
        }
        throw lastError ?: SparkError.AuthenticationFailed("authentication failed after $MAX_ATTEMPTS attempts")
    }

    private suspend fun exchangeChallenge(connectionManager: GrpcConnectionManager, soAddress: String, signer: SparkSignerProtocol): CachedToken {
        val channel = connectionManager.getChannel(soAddress)
        val authnStub = SparkAuthnServiceGrpcKt.SparkAuthnServiceCoroutineStub(channel)

        // Step 1: Get challenge
        val challengeRequest = SparkAuthn.GetChallengeRequest.newBuilder()
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(signer.identityPublicKey))
            .build()

        val challengeResponse = authnStub.getChallenge(challengeRequest)

        // Step 2: Sign the challenge
        val challengeData = challengeResponse.protectedChallenge.challenge.toByteArray()
        val challengeHash = sha256(challengeData)
        val signature = signer.signWithIdentityKey(challengeHash)

        // Step 3: Verify and get token
        val verifyRequest = SparkAuthn.VerifyChallengeRequest.newBuilder()
            .setProtectedChallenge(challengeResponse.protectedChallenge)
            .setSignature(com.google.protobuf.ByteString.copyFrom(signature))
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(signer.identityPublicKey))
            .build()

        val verifyResponse = authnStub.verifyChallenge(verifyRequest)

        return CachedToken(
            token = verifyResponse.sessionToken,
            expiresAtMillis = verifyResponse.expirationTimestamp * 1000,
        )
    }
}
