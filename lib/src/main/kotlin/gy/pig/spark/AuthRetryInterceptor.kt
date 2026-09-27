package gy.pig.spark

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch

/**
 * Keeps operator calls on a live session token, like the official SDK's auth middleware: a call
 * the operator answers UNAUTHENTICATED is re-issued with a fresh token, up to [MAX_ATTEMPTS]
 * attempts in all (the retry policy's count).
 *
 * [SparkAuthenticator] caches a session token until its `expiresAt`; if the operator stops
 * honouring it before then (restart, rotation, a forgotten session, a device clock the operator
 * disagrees with) the same rejected token would otherwise be sent on every call — and every call
 * would fail — until it expires. The operator returns `codes.Unauthenticated` for every token
 * problem, and only from its pre-handler interceptors, so nothing has run server-side when a
 * call is re-issued. The status code is matched rather than the message.
 *
 * grpc-java interceptors are callback based and the transport's own retries re-send the original
 * headers, so the re-issue is done here, by [ReplayingCall]: it records what the caller sent,
 * holds back response headers until the first response message (a call that already delivered
 * data is never replayed), asks [refreshToken] on [scope] for a new token — handing it the
 * rejected one, which is dropped only if it is still the cached one — and replays the recorded
 * call on a new stream with the new `authorization` header. Unlike grpc-swift, a rejection sent
 * after the response headers but before any message is re-issued too. Calls that carry no
 * `authorization` header, and the token-issuing service, pass through untouched.
 *
 * @param refreshToken drops the rejected token (when it is still the operator's cached one) and
 *   returns the operator's current token, authenticating again if needed.
 */
internal class AuthRetryInterceptor(private val scope: CoroutineScope, private val refreshToken: suspend (rejectedToken: String?) -> String) :
    ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(method: MethodDescriptor<ReqT, RespT>, callOptions: CallOptions, next: Channel): ClientCall<ReqT, RespT> {
        // The service that issues the tokens; its own calls never carry one.
        if (method.serviceName == AUTHN_SERVICE) return next.newCall(method, callOptions)
        return ReplayingCall(method, callOptions, next)
    }

    private inner class ReplayingCall<ReqT, RespT>(
        private val method: MethodDescriptor<ReqT, RespT>,
        private val callOptions: CallOptions,
        private val next: Channel,
    ) : ClientCall<ReqT, RespT>() {
        // Every field below is guarded by `lock`.
        private val lock = Any()
        private var attempt: ClientCall<ReqT, RespT> = next.newCall(method, callOptions)
        private var listener: Listener<RespT>? = null
        private var headers: Metadata? = null
        private val sent = ArrayList<ReqT>()
        private var requested = 0
        private var halfClosed = false
        private var compression: Boolean? = null
        private var cancelled = false
        private var attempts = 1

        /** The caller sent no session token: nothing to renew, so nothing is replayed. */
        private var passThrough = false

        /** The caller received a response message: from here on nothing may be replayed. */
        private var committed = false

        /** Between swallowing an UNAUTHENTICATED close and starting the replay. */
        private var refreshing = false

        /** `onClose` was delivered to the caller. */
        private var closed = false

        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
            synchronized(lock) {
                listener = responseListener
                // Snapshot: the transport may add its own entries to the Metadata it is given.
                this.headers = Metadata().apply { merge(headers) }
                passThrough = !headers.containsKey(SparkAuthenticator.AUTHORIZATION_KEY)
                attempt.start(AttemptListener(), headers)
            }
        }

        override fun request(numMessages: Int) {
            synchronized(lock) {
                requested += numMessages
                if (!refreshing) attempt.request(numMessages)
            }
        }

        override fun sendMessage(message: ReqT) {
            synchronized(lock) {
                if (!committed) sent.add(message)
                if (!refreshing) attempt.sendMessage(message)
            }
        }

        override fun halfClose() {
            synchronized(lock) {
                halfClosed = true
                if (!refreshing) attempt.halfClose()
            }
        }

        override fun cancel(message: String?, cause: Throwable?) {
            val closeNow = synchronized(lock) {
                if (cancelled) return
                cancelled = true
                if (!refreshing) attempt.cancel(message, cause)
                // Mid-refresh there is no live attempt to report the cancellation: do it here.
                refreshing
            }
            if (closeNow) deliverClose(Status.CANCELLED.withDescription(message).withCause(cause), Metadata())
        }

        override fun isReady(): Boolean = synchronized(lock) { !refreshing && attempt.isReady }

        override fun setMessageCompression(enabled: Boolean) {
            synchronized(lock) {
                compression = enabled
                if (!refreshing) attempt.setMessageCompression(enabled)
            }
        }

        override fun getAttributes(): io.grpc.Attributes = synchronized(lock) { attempt.attributes }

        private fun deliverClose(status: Status, trailers: Metadata, pendingHeaders: Metadata? = null) {
            val target = synchronized(lock) {
                if (closed) return
                closed = true
                listener
            } ?: return
            if (pendingHeaders != null) target.onHeaders(pendingHeaders)
            target.onClose(status, trailers)
        }

        private fun replay(token: String?, failure: Throwable?, status: Status, trailers: Metadata) {
            synchronized(lock) {
                refreshing = false
                // cancel() already reported the cancellation to the caller.
                if (cancelled) return
                val original = headers
                if (token != null && original != null) {
                    val replayHeaders = Metadata().apply { merge(original) }
                    replayHeaders.removeAll(SparkAuthenticator.AUTHORIZATION_KEY)
                    replayHeaders.put(SparkAuthenticator.AUTHORIZATION_KEY, "Bearer $token")
                    headers = Metadata().apply { merge(replayHeaders) }
                    val call = next.newCall(method, callOptions)
                    attempt = call
                    call.start(AttemptListener(), replayHeaders)
                    compression?.let { call.setMessageCompression(it) }
                    if (requested > 0) call.request(requested)
                    for (message in sent) call.sendMessage(message)
                    if (halfClosed) call.halfClose()
                    return
                }
            }
            val reported = if (failure != null) {
                Status.UNAUTHENTICATED.withDescription("re-authentication failed: ${failure.message}").withCause(failure)
            } else {
                status
            }
            deliverClose(reported, trailers)
        }

        /** Listener for one attempt. Response headers are held back until the first message. */
        private inner class AttemptListener : Listener<RespT>() {
            private var pendingHeaders: Metadata? = null

            override fun onHeaders(headers: Metadata) {
                val deliverNow = synchronized(lock) { committed }
                if (deliverNow) listener?.onHeaders(headers) else pendingHeaders = headers
            }

            override fun onMessage(message: RespT) {
                synchronized(lock) {
                    committed = true
                    sent.clear()
                }
                val target = listener ?: return
                pendingHeaders?.let {
                    pendingHeaders = null
                    target.onHeaders(it)
                }
                target.onMessage(message)
            }

            override fun onReady() {
                listener?.onReady()
            }

            override fun onClose(status: Status, trailers: Metadata) {
                var rejectedToken: String? = null
                val shouldReplay = synchronized(lock) {
                    val replay = status.code == Status.Code.UNAUTHENTICATED &&
                        !committed &&
                        !passThrough &&
                        attempts < MAX_ATTEMPTS &&
                        !cancelled
                    if (replay) {
                        attempts++
                        refreshing = true
                        rejectedToken = headers?.get(SparkAuthenticator.AUTHORIZATION_KEY)?.removePrefix("Bearer ")
                    }
                    replay
                }
                if (!shouldReplay) {
                    val held = pendingHeaders
                    pendingHeaders = null
                    deliverClose(status, trailers, held)
                    return
                }
                // ATOMIC: the body runs even if the scope were cancelled, so the call is always
                // either replayed or closed — never left hanging.
                @OptIn(DelicateCoroutinesApi::class)
                scope.launch(start = CoroutineStart.ATOMIC) {
                    var failure: Throwable? = null
                    val token = try {
                        refreshToken(rejectedToken)
                    } catch (e: Throwable) {
                        // Includes cancellation: the call must still be closed below.
                        failure = e
                        null
                    }
                    replay(token, failure, status, trailers)
                }
            }
        }
    }

    companion object {
        /** The service that issues the tokens; exempt from the retry. */
        const val AUTHN_SERVICE: String = "spark_authn.SparkAuthnService"

        /** Attempts of a call the operator keeps rejecting: the transport retry policy's `maxAttempts`. */
        const val MAX_ATTEMPTS: Int = 3
    }
}
