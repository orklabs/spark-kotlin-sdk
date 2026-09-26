package gy.pig.spark

/**
 * Client-side checks on the SSP's cooperative-exit response, run BEFORE any refund transaction
 * is signed or any key tweak is prepared. Mirrors the reference SDK's
 * `validateCoopExitPayoutTransaction` and `validateConnectorTxBindsToCoopExitTxid`.
 *
 * Without these checks the wallet hands its leaves to the SSP on the SSP's word: the operators
 * release the transfer once the exit txid confirms, but only the client knows what that
 * transaction was supposed to pay.
 */
internal object CoopExitValidator {

    private fun untrusted(message: String): Nothing = throw SparkError.UntrustedResponse(message)

    private fun invalidArgument(message: String): Nothing = throw SparkError.InvalidArgument(message)

    private fun feeExceedsLimit(feeSats: Long, maxFeeSats: Long): Nothing = throw SparkError.FeeExceedsLimit(feeSats, maxFeeSats)

    class ValidatedExit(
        /** Exit txid in internal (little-endian) byte order, as the coordinator expects it. */
        val exitTxid: ByteArray,
        val connectorTx: RawTransaction,
        /** The payout output that satisfied the check. */
        val payoutVout: Int,
        val payoutSats: ULong,
    )

    /**
     * The fee the withdrawal is allowed to pay: the SSP's quote, bounded by the caller's cap.
     * The payout is later required to be at least `amountSats - feeCap`.
     */
    fun resolveFeeCap(quotedFeeSats: Long, maxFeeSats: Long?, amountSats: Long): Long {
        if (amountSats <= 0) {
            invalidArgument("withdrawal amount must be positive, got $amountSats")
        }
        if (quotedFeeSats < 0) {
            untrusted("SSP quoted a negative withdrawal fee ($quotedFeeSats sats)")
        }
        if (maxFeeSats != null) {
            if (maxFeeSats < 0) {
                invalidArgument("maxFeeSats must not be negative")
            }
            if (quotedFeeSats > maxFeeSats) {
                feeExceedsLimit(feeSats = quotedFeeSats, maxFeeSats = maxFeeSats)
            }
        }
        val cap = maxFeeSats ?: quotedFeeSats
        if (cap >= amountSats) {
            feeExceedsLimit(feeSats = cap, maxFeeSats = amountSats - 1)
        }
        return cap
    }

    /**
     * Validate the SSP's exit and connector transactions.
     *
     * - The raw exit transaction must hash to [coopExitTxidHex] (either byte order is accepted,
     *   like the operators do).
     * - It must contain an output paying [payoutAddress] at least [minimumPayoutSats].
     * - The connector transaction's first input must spend that exit transaction, and it must
     *   carry one connector output per leaf plus the SSP's own output.
     */
    fun validate(
        rawCoopExitTransactionHex: String,
        rawConnectorTransactionHex: String,
        coopExitTxidHex: String,
        payoutAddress: String,
        minimumPayoutSats: Long,
        leafCount: Int,
        network: SparkNetwork,
    ): ValidatedExit {
        if (minimumPayoutSats <= 0) {
            invalidArgument("minimum payout must be positive")
        }
        val expectedScript = BitcoinAddress.scriptPubKey(payoutAddress, network)

        val exitBytes = rawCoopExitTransactionHex.hexToBytesOrNull()
        if (exitBytes == null || exitBytes.isEmpty()) {
            untrusted("SSP coop exit response: raw_coop_exit_transaction is not valid hex")
        }
        val exitTx = RawTransaction.parse(exitBytes, context = "coop exit tx")

        val claimedTxid = coopExitTxidHex.hexToBytesOrNull()
        if (claimedTxid == null || claimedTxid.size != 32) {
            untrusted("SSP coop exit response: coop_exit_txid is not a 32-byte hex id")
        }
        if (!RawTransaction.txidMatches(exitTx.txid, claimedTxid)) {
            untrusted(
                "SSP coop exit response is inconsistent: coop_exit_txid $coopExitTxidHex does not match " +
                    "raw_coop_exit_transaction (${exitTx.txidHex})",
            )
        }

        var payout: Pair<Int, ULong>? = null
        for ((index, output) in exitTx.outputs.withIndex()) {
            if (output.scriptPubKey.contentEquals(expectedScript) && output.value >= minimumPayoutSats.toULong()) {
                payout = index to output.value
                break
            }
        }
        if (payout == null) {
            untrusted(
                "SSP cooperative exit transaction does not pay $payoutAddress at least $minimumPayoutSats sats",
            )
        }

        val connectorBytes = rawConnectorTransactionHex.hexToBytesOrNull()
        if (connectorBytes == null || connectorBytes.isEmpty()) {
            untrusted("SSP coop exit response: raw_connector_transaction is not valid hex")
        }
        val connectorTx = RawTransaction.parse(connectorBytes, context = "connector tx")
        val parent = connectorTx.inputs.firstOrNull()
            ?: untrusted("SSP coop exit response is malformed: connector transaction has no inputs")
        if (!RawTransaction.txidMatches(parent.previousTxid, exitTx.txid)) {
            untrusted(
                "SSP coop exit response is inconsistent: connector transaction does not spend the coop exit transaction",
            )
        }
        if (leafCount <= 0 || connectorTx.outputs.size != leafCount + 1) {
            untrusted(
                "SSP coop exit response is malformed: connector transaction has ${connectorTx.outputs.size} outputs for $leafCount leaves",
            )
        }

        return ValidatedExit(
            exitTxid = exitTx.txid,
            connectorTx = connectorTx,
            payoutVout = payout.first,
            payoutSats = payout.second,
        )
    }
}
