package gy.pig.spark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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

internal suspend fun executeGraphQL(httpClient: OkHttpClient, url: String, token: String?, query: String, variables: Map<String, Any>?,): JSONObject =
    withContext(Dispatchers.IO) {
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

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string()
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
