package gy.pig.spark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import spark.Spark
import java.io.IOException

/**
 * The part of a lightning send that runs after the coordinator may hold the leaves: the SSP
 * step must surface the transfer id however it ends, and resuming must not select new leaves.
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

    // ── resuming with a transferId ─────────────────────────────────────────

    private val us = byteArrayOf(0x02) + bytes(0x11, 32)
    private val ssp = byteArrayOf(0x03) + bytes(0x22, 32)

    private fun lockedSend(
        sender: ByteArray = us,
        receiver: ByteArray = ssp,
        total: Long = 1_050,
        type: Spark.TransferType = Spark.TransferType.PREIMAGE_SWAP,
        status: Spark.TransferStatus = Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAK_PENDING,
    ): Spark.Transfer = Spark.Transfer.newBuilder()
        .setId(transferId)
        .setSenderIdentityPublicKey(sender.toByteString())
        .setReceiverIdentityPublicKey(receiver.toByteString())
        .setTotalValue(total)
        .setType(type)
        .setStatus(status)
        .build()

    private fun resume(existing: Spark.Transfer?, amountSats: Long = 1_000, maxFeeSats: Long = 50) =
        canResumeLightningSend(existing, ownIdentityPublicKey = us, sspIdentityPublicKey = ssp, invoiceAmountSats = amountSats, maxFeeSats = maxFeeSats)

    @Test
    fun nothingUnderTheIdYetMeansANewSendUnderThatId() {
        assertFalse(resume(null))
    }

    @Test
    fun ourLockedTransferToTheSspCoveringTheInvoiceIsResumedWithoutNewLeaves() {
        assertTrue(resume(lockedSend()))
        // The fee is whatever was agreed when the transfer was created, as long as it is within the cap.
        assertTrue(resume(lockedSend(total = 1_000)))
        assertTrue(resume(lockedSend(total = 1_050), maxFeeSats = 50))
        assertTrue(resume(lockedSend(status = Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED)))
    }

    @Test
    fun anIdThatBelongsToSomethingElseIsRefused() {
        val cases = listOf(
            "another wallet's transfer" to lockedSend(sender = byteArrayOf(0x02) + bytes(0x33, 32)),
            "a transfer to us" to lockedSend(sender = ssp, receiver = us),
            "not to the SSP" to lockedSend(receiver = byteArrayOf(0x02) + bytes(0x44, 32)),
            "a cooperative exit" to lockedSend(type = Spark.TransferType.COOPERATIVE_EXIT),
            "a leaf swap" to lockedSend(type = Spark.TransferType.PRIMARY_SWAP_V3),
            "expired" to lockedSend(status = Spark.TransferStatus.TRANSFER_STATUS_EXPIRED),
            "returned" to lockedSend(status = Spark.TransferStatus.TRANSFER_STATUS_RETURNED),
            "less than the invoice" to lockedSend(total = 999),
        )
        for ((label, transfer) in cases) {
            val error = expectSparkError(label) { resume(transfer) }
            assertTrue(label, error is SparkError.InvalidArgument)
        }
        // A transfer that would pay more than the caller now allows as a fee.
        val fee = expectSparkError { resume(lockedSend(total = 1_051), maxFeeSats = 50) }
        assertTrue(fee is SparkError.FeeExceedsLimit)
        assertEquals(51L, (fee as SparkError.FeeExceedsLimit).feeSats)
    }

    @Test
    fun theSspStepCarriesEitherTheIdempotencyKeyOrTheTransferId() {
        assertEquals(
            mapOf("encoded_invoice" to "lnbc1", "user_outbound_transfer_external_id" to transferId),
            lightningSendVariables("lnbc1", idempotencyKey = null, transferId = transferId),
        )
        assertEquals(
            mapOf("encoded_invoice" to "lnbc1", "idempotency_key" to "key-1"),
            lightningSendVariables("lnbc1", idempotencyKey = "key-1", transferId = transferId),
        )
    }
}
