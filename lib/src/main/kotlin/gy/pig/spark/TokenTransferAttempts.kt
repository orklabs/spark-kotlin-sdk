package gy.pig.spark

import com.google.protobuf.ByteString
import spark_token.OutputWithPreviousTransactionData
import java.math.BigInteger

/**
 * Token transfers sent with an idempotency key, so a retry with the key resends the same
 * transaction rather than building another.
 *
 * The operators recognise the same transaction again. They answer a repeated key from their
 * idempotency records (kept 24 hours). Without the record, V3's `broadcast_transaction` answers
 * with the transaction stored under the partial transaction's hash, and V2's `start_transaction`
 * answers from the stored transaction while it is started, where `commit_transaction` of a
 * finalized transfer reports it finalized. A rebuilt transaction would differ in its client
 * timestamp, and in its outputs while the first transaction's outputs are spent or locked
 * ([TokenOutputLocks]), so it could not match what the operators answer for the key. Resending
 * never makes a second transfer: an attempt whose transaction expired unsent fails again, and a
 * new key starts a new transfer.
 */
internal class TokenTransferAttempts {
    /** What a transfer asked for: a retry with the key must ask for the same. */
    data class Request(val tokenIdentifier: ByteString, val amount: BigInteger, val receiverIdentityPublicKey: ByteString)

    class Attempt(
        val request: Request,
        /** The transaction, as first built. */
        val transaction: TokenTransactionDraft,
        /** The outputs it spends. */
        val spentOutputs: List<OutputWithPreviousTransactionData>,
    )

    /** Attempts by key, oldest first. */
    private val attempts = LinkedHashMap<String, Attempt>()

    @Synchronized
    fun attempt(key: String): Attempt? = attempts[key]

    /** Remembers [attempt] under [key]; beyond [CAPACITY] keys the oldest is forgotten first. */
    @Synchronized
    fun remember(attempt: Attempt, key: String) {
        // A key already remembered keeps its place in the order.
        attempts[key] = attempt
        while (attempts.size > CAPACITY) {
            attempts.remove(attempts.keys.first())
        }
    }

    companion object {
        /** How many keys are remembered. */
        const val CAPACITY = 1_000
    }
}
