package gy.pig.spark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

internal class SspGraphQLClient(
    private val httpClient: OkHttpClient,
    private val sspURL: String,
    private val getToken: suspend () -> String,
    private val invalidateToken: suspend () -> Unit = {},
) {
    suspend fun executeRaw(query: String, variables: Map<String, Any>? = null,): JSONObject = try {
        execute(query, variables)
    } catch (e: SparkError) {
        if (!isAuthFailure(e)) throw e
        // The SSP no longer honours the cached session (it stays "valid" by its own
        // `valid_until` for hours): drop it and retry ONCE with a fresh one, instead of
        // failing every SSP call — fee quotes, coop exits, invoices — until the process restarts.
        invalidateToken()
        execute(query, variables)
    }

    private suspend fun execute(query: String, variables: Map<String, Any>?): JSONObject {
        val token = getToken()
        return executeGraphQL(httpClient, sspURL, token, query, variables)
    }

    internal companion object {
        private val AUTH_FAILURE_MARKERS = listOf(
            "http 401",
            "http 403",
            "unauthenticated",
            "unauthorized",
            "not authorized",
            "authentication",
            "invalid token",
            "expired token",
            "token expired",
        )

        /**
         * An HTTP 401/403, or a GraphQL error that names authentication. A false positive only
         * costs one re-authentication and one retry.
         */
        fun isAuthFailure(error: SparkError): Boolean {
            if (error !is SparkError.GraphqlError) return false
            val m = error.msg.lowercase()
            return AUTH_FAILURE_MARKERS.any { m.contains(it) }
        }
    }
}

/**
 * How SSP requests are retried: the reference SDK's fetch wrapper — up to 5 more attempts, 1 s
 * doubling to 10 s between them, on HTTP 502, 503 and 504 and on a connection that failed, but
 * not on a timeout or a cancellation. Mutations are retried too; the ones that move funds are
 * keyed by the transfer they pay from, and the SSP answers a repeat with its first answer.
 */
internal class SspRetry(val maxRetries: Int = 5, val baseDelayMs: Long = 1_000, val maxDelayMs: Long = 10_000) {

    fun delayMs(afterAttempt: Int): Long = minOf(baseDelayMs shl minOf(afterAttempt, 20), maxDelayMs)

    /**
     * [send], repeated per the policy. A retryable status on the last attempt is returned as is;
     * the body of a response that is retried is closed.
     */
    suspend fun run(send: suspend () -> Response): Response {
        var attempt = 0
        while (true) {
            val response = attemptOrNull(send, attempt)
            if (response != null && (response.code !in RETRYABLE_STATUS_CODES || attempt >= maxRetries)) return response
            response?.close()
            delay(delayMs(attempt))
            attempt++
        }
    }

    /** One attempt: its response, or `null` for a connection failure worth another attempt (anything else is thrown). */
    private suspend fun attemptOrNull(send: suspend () -> Response, attempt: Int): Response? = try {
        send()
    } catch (e: IOException) {
        if (!isRetryable(e) || attempt >= maxRetries) throw e
        null
    }

    internal companion object {
        val STANDARD: SspRetry = SspRetry()
        val RETRYABLE_STATUS_CODES: Set<Int> = setOf(502, 503, 504)

        /**
         * A connection that failed or was lost: refused, unreachable, a DNS failure, a reset, a
         * failed TLS handshake. Not a timeout or an interrupted call (`InterruptedIOException`),
         * and not a certificate the SSP's host failed to prove, which a retry cannot fix.
         */
        fun isRetryable(error: IOException): Boolean {
            if (error is InterruptedIOException || error is SSLPeerUnverifiedException) return false
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is CertificateException) return false
                cause = cause.cause
            }
            return true
        }
    }
}

internal suspend fun executeGraphQL(
    httpClient: OkHttpClient,
    url: String,
    token: String?,
    query: String,
    variables: Map<String, Any>?,
    retry: SspRetry = SspRetry.STANDARD,
): JSONObject = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("query", query)
        if (variables != null) {
            put("variables", JSONObject(variables))
        }
    }

    val requestBuilder = Request.Builder()
        .url(url)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
    if (token != null) {
        requestBuilder.addHeader("Authorization", "Bearer $token")
    }
    val request = requestBuilder.build()

    val response = retry.run { httpClient.newCall(request).execute() }
    val responseBody = response.use { it.body?.string() }
        ?: throw SparkError.GraphqlError("Empty response body")

    if (!response.isSuccessful) {
        throw SparkError.GraphqlError("HTTP ${response.code}")
    }

    val json = JSONObject(responseBody)

    val errors = json.optJSONArray("errors")
    if (errors != null && errors.length() > 0) {
        val messages = (0 until errors.length())
            .mapNotNull { errors.getJSONObject(it).optString("message") }
            .joinToString("; ")
        throw SparkError.GraphqlError(messages)
    }

    json.optJSONObject("data") ?: throw SparkError.GraphqlError("No data in response")
}

/** An SSP `CurrencyAmount` (`original_value` in `original_unit`) in sats. */
internal object SspCurrencyAmount {
    /**
     * SATOSHI as is, MILLISATOSHI rounded up to whole sats; any other unit, a missing field or a
     * value that is not a whole non-negative number is refused, as the reference SDK refuses a
     * fee estimate in another unit.
     */
    fun sats(amount: JSONObject?, field: String): Long {
        val value = amount?.let { wholeNonNegativeLong(it.opt("original_value")) }
        val unit = amount?.opt("original_unit") as? String
        if (value == null || unit == null) {
            throw SparkError.InvalidResponse("SSP $field is missing or malformed")
        }
        return when (unit) {
            "SATOSHI" -> value
            "MILLISATOSHI" -> value / 1000 + if (value % 1000 == 0L) 0 else 1
            else -> throw SparkError.InvalidResponse("SSP $field is in an unsupported unit: $unit")
        }
    }
}
