package gy.pig.spark

import com.google.protobuf.ByteString

/**
 * Finds which output of an on-chain transaction pays one of the wallet's unused deposit
 * addresses, so a claim is built for the leaf that actually received the funds.
 */
internal object DepositMatcher {
    data class Match(val vout: UInt, val address: String)

    fun match(rawTx: ByteArray, candidateAddresses: List<String>, requestedVout: UInt?, network: SparkNetwork): Match {
        val tx = RawTransaction.parse(rawTx, context = "deposit tx")
        // Keyed by ByteString: ByteArray has identity equality.
        val scripts = mutableMapOf<ByteString, String>()
        for (address in candidateAddresses) {
            val script = try {
                BitcoinAddress.scriptPubKey(address, network)
            } catch (_: SparkError) {
                continue
            }
            scripts[ByteString.copyFrom(script)] = address
        }
        if (scripts.isEmpty()) {
            throw SparkError.InvalidResponse("No unused deposit address found. Generate one first with getDepositAddress().")
        }
        if (requestedVout != null) {
            val output = tx.output(requestedVout)
            val address = scripts[ByteString.copyFrom(output.scriptPubKey)]
                ?: throw SparkError.InvalidArgument("output $requestedVout of ${tx.txidHex} does not pay one of this wallet's deposit addresses")
            return Match(vout = requestedVout, address = address)
        }
        for ((index, output) in tx.outputs.withIndex()) {
            val address = scripts[ByteString.copyFrom(output.scriptPubKey)]
            if (address != null) return Match(vout = index.toUInt(), address = address)
        }
        throw SparkError.InvalidArgument("transaction ${tx.txidHex} does not pay any of this wallet's unused deposit addresses")
    }
}
