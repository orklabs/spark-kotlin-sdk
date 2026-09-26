package gy.pig.spark

import io.grpc.Metadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import spark_authn.SparkAuthn
import spark_authn.SparkAuthnServiceGrpcKt
import java.util.Date

class SparkAuthenticator {
    private data class CachedToken(val token: String, val expiresAt: Date,)

    private val tokenCache = mutableMapOf<String, CachedToken>()
    private val mutex = Mutex()

    companion object {
        private const val REFRESH_BUFFER_MS = 60_000L

        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }

    suspend fun getToken(connectionManager: GrpcConnectionManager, soAddress: String, signer: SparkSignerProtocol,): String = mutex.withLock {
        val cacheKey = "$soAddress:${signer.identityPublicKey.toHexString()}"

        val cached = tokenCache[cacheKey]
        if (cached != null && cached.expiresAt.time > System.currentTimeMillis() + REFRESH_BUFFER_MS) {
            return cached.token
        }

        val result = authenticate(connectionManager, soAddress, signer)
        tokenCache[cacheKey] = result
        return result.token
    }

    /**
     * Forget this operator's cached session. Called by [AuthRetryInterceptor] when the operator
     * answers UNAUTHENTICATED: a token the server no longer honours stays "valid" by its own
     * `expiresAt` for hours, and replaying it would fail every call until the process restarts.
     * Per operator and identity, like the official SDK's cache.
     */
    suspend fun invalidate(soAddress: String, signer: SparkSignerProtocol) {
        mutex.withLock {
            tokenCache.remove("$soAddress:${signer.identityPublicKey.toHexString()}")
        }
    }

    suspend fun getAuthMetadata(connectionManager: GrpcConnectionManager, soAddress: String, signer: SparkSignerProtocol,): Metadata {
        val token = getToken(connectionManager, soAddress, signer)
        val metadata = Metadata()
        metadata.put(AUTHORIZATION_KEY, "Bearer $token")
        return metadata
    }

    private suspend fun authenticate(connectionManager: GrpcConnectionManager, soAddress: String, signer: SparkSignerProtocol,): CachedToken {
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
            expiresAt = Date(verifyResponse.expirationTimestamp * 1000),
        )
    }
}
