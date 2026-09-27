package gy.pig.spark

import spark_token.OutputWithPreviousTransactionData
import spark_token.TokenOutputStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Token outputs the wallet has picked for a transaction that may still be in flight, kept as the
 * reference SDK's `TokenOutputManager` keeps them. The operators report an output of a started
 * transaction as AVAILABLE until that transaction is signed, and of two transactions spending
 * one output they keep only one (the earlier client timestamp), so two sends from one wallet
 * must not pick the same outputs.
 *
 * A lock ends after [expiry] (30 s, the reference SDK's default) or once the operators report the
 * output PENDING_OUTBOUND. A failed send does not release its outputs early: the operators may
 * already hold its transaction.
 *
 * @param monotonicNanos the monotonic clock, [System.nanoTime] outside tests.
 */
internal class TokenOutputLocks(val expiry: Duration = 30.seconds, private val monotonicNanos: () -> Long = System::nanoTime) {
    /** When each locked output ([key]) was picked, on [monotonicNanos]. */
    private val lockedAt = HashMap<String, Long>()

    /**
     * Picks with [select] among the [outputs] that can be spent (available and not locked) and
     * locks the picked ones. Picking and locking happen under one lock, so concurrent sends
     * never pick the same output; a pick that throws locks nothing.
     */
    @Synchronized
    fun acquire(
        outputs: List<OutputWithPreviousTransactionData>,
        select: (List<OutputWithPreviousTransactionData>) -> List<OutputWithPreviousTransactionData>,
    ): List<OutputWithPreviousTransactionData> {
        val now = monotonicNanos()
        lockedAt.values.removeAll { now - it >= expiry.inWholeNanoseconds }
        // Pending on the operators: their status covers it from here.
        for (output in outputs) {
            if (output.output.hasStatus() && output.output.status == TokenOutputStatus.TOKEN_OUTPUT_STATUS_PENDING_OUTBOUND) {
                lockedAt.remove(key(output))
            }
        }
        val spendable = outputs.filter { isAvailable(it) && key(it) !in lockedAt }
        val selected = select(spendable)
        for (output in selected) {
            lockedAt[key(output)] = now
        }
        return selected
    }

    companion object {
        /**
         * Whether the operators report [output] spendable: AVAILABLE, or no status at all.
         * PENDING_OUTBOUND outputs belong to a signed transaction that has not finalized.
         */
        fun isAvailable(output: OutputWithPreviousTransactionData): Boolean =
            !output.output.hasStatus() || output.output.status == TokenOutputStatus.TOKEN_OUTPUT_STATUS_AVAILABLE

        /** The token transaction output that [output] is: previous transaction hash and vout. */
        fun key(output: OutputWithPreviousTransactionData): String =
            "${output.previousTransactionHash.toByteArray().toHexString()}:${output.previousTransactionVout.toUInt()}"
    }
}
