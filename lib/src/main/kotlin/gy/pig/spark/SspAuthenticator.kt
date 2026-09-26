package gy.pig.spark

import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.Date

class SspAuthenticator(private val httpClient: OkHttpClient, private val sspURL: String, private val signer: SparkSignerProtocol,) {
    private data class CachedToken(val token: String, val expiresAt: Date,)

    private var cachedToken: CachedToken? = null
    private val mutex = Mutex()

    companion object {
        private const val REFRESH_BUFFER_MS = 60_000L
    }

    suspend fun getToken(): String = mutex.withLock {
        val cached = cachedToken
        if (cached != null && cached.expiresAt.time > System.currentTimeMillis() + REFRESH_BUFFER_MS) {
            return cached.token
        }

        val token = authenticate()
        cachedToken = token
        return token.token
    }

    /**
     * Forget the cached session. [SspGraphQLClient] calls this when the SSP rejects the token
     * before its `valid_until` (rotation, restart): the next [getToken] authenticates afresh
     * instead of replaying the rejected one until the process restarts.
     */
    suspend fun invalidate() {
        mutex.withLock { cachedToken = null }
    }

    private suspend fun authenticate(): CachedToken {
        val identityPubKeyHex = signer.identityPublicKey.toHexString()

        // Step 1: Get challenge
        val challengeResult = executeGraphQL(
            httpClient = httpClient,
            url = sspURL,
            token = null,
            query = GraphQLMutations.GET_CHALLENGE,
            variables = mapOf("public_key" to identityPubKeyHex),
        )

        val getChallenge = challengeResult.optJSONObject("get_challenge")
            ?: throw SparkError.AuthenticationFailed("Invalid challenge response")
        val protectedChallenge = getChallenge.optString("protected_challenge")
        if (protectedChallenge.isNullOrEmpty()) {
            throw SparkError.AuthenticationFailed("Invalid challenge response")
        }

        // Step 2: Sign the challenge
        val challengeBytes = decodeBase64URL(protectedChallenge)
            ?: throw SparkError.AuthenticationFailed("Invalid base64url challenge")
        val challengeHash = sha256(challengeBytes)
        val signature = signer.signWithIdentityKey(challengeHash)
        val signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP)

        // Step 3: Verify and get token
        val verifyResult = executeGraphQL(
            httpClient = httpClient,
            url = sspURL,
            token = null,
            query = GraphQLMutations.VERIFY_CHALLENGE,
            variables = mapOf(
                "protected_challenge" to protectedChallenge,
                "signature" to signatureBase64,
                "identity_public_key" to identityPubKeyHex,
            ),
        )

        val verifyChallenge = verifyResult.optJSONObject("verify_challenge")
            ?: throw SparkError.AuthenticationFailed("Invalid verify response")
        val sessionToken = verifyChallenge.optString("session_token")
        val validUntil = verifyChallenge.optString("valid_until")

        if (sessionToken.isNullOrEmpty()) {
            throw SparkError.AuthenticationFailed("Invalid verify response")
        }

        val expiresAt = parseISODate(validUntil)

        return CachedToken(token = sessionToken, expiresAt = expiresAt)
    }
}
