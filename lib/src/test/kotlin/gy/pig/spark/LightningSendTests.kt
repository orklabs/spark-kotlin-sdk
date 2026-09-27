package gy.pig.spark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The part of a lightning send that runs after the coordinator may hold the leaves: the SSP step
 * must surface the transfer id however it ends, even when the caller is cancelled (Kotlin only:
 * the swap and the SSP step run under `NonCancellable`). Resuming is covered by
 * `LightningResumeTests`.
 */
class LightningSendTests {

    private val transferId = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"
    private val sendResponse = JSONObject("""{"request_lightning_send":{"request":{"id":"LightningSendRequest:1","status":"CREATED"}}}""")

    // ── completeLightningSend ───────────────────────────────────────────────

    @Test
    fun aSuccessfulSspStepReturnsTheRequestId() = runBlocking {
        assertEquals("LightningSendRequest:1", completeLightningSend(transferId) { sendResponse })
    }

    /** The [SparkError.LightningSendIncomplete] [block] throws; any other outcome fails the test. */
    private suspend fun incomplete(block: suspend () -> Unit): SparkError.LightningSendIncomplete {
        try {
            block()
        } catch (e: SparkError.LightningSendIncomplete) {
            return e
        }
        throw AssertionError("expected LightningSendIncomplete")
    }

    @Test
    fun anSspFailureOrAnswerWithoutIdSurfacesTheTransferId() = runBlocking {
        for (request in listOf<suspend () -> JSONObject>(
            { throw SparkError.GraphqlError("HTTP 502") },
            { throw IOException("connection reset") },
            { throw CancellationException("request aborted") },
            { JSONObject("""{"request_lightning_send":{"request":{}}}""") },
            { JSONObject("{}") },
        )) {
            assertEquals(transferId, incomplete { completeLightningSend(transferId, request) }.transferId)
        }
    }

    @Test
    fun cancellingTheCallerDoesNotAbandonTheSspStepOrLoseTheTransferId() = runBlocking {
        supervisorScope {
            val sspCalled = CompletableDeferred<Unit>()
            var sspFinished = false
            val send = async {
                completeLightningSend(transferId) {
                    sspCalled.complete(Unit)
                    delay(200)
                    sspFinished = true
                    throw SparkError.GraphqlError("HTTP 503")
                }
            }
            sspCalled.await()
            send.cancel()
            // The request still ran to the end, and its failure (with the id) is what the caller
            // sees, not a bare CancellationException.
            assertEquals(transferId, incomplete { send.await() }.transferId)
            assertTrue(sspFinished)
        }
    }

    @Test
    fun aCancelledCallerStillLetsASuccessfulSspStepFinish() = runBlocking {
        supervisorScope {
            val sspCalled = CompletableDeferred<Unit>()
            var sspFinished = false
            val send = async {
                completeLightningSend(transferId) {
                    sspCalled.complete(Unit)
                    delay(200)
                    sspFinished = true
                    sendResponse
                }
            }
            sspCalled.await()
            send.cancel()
            try {
                send.await()
                fail("a cancelled coroutine completes as cancelled")
            } catch (_: CancellationException) {
            }
            assertTrue(sspFinished)
        }
    }
}
