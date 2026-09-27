package gy.pig.spark

import spark.Spark
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An output paying a static deposit address, by display-order txid (lower-case hex) and vout.
 *
 * @throws SparkError.InvalidArgument unless [txid] is 64 hex characters.
 */
internal class DepositOutpoint(txid: String, val vout: UInt) {
    val txid: String = normalizedTxid(txid)

    /** The txid bytes in display order: how the operators store and look up deposit UTXOs. */
    val displayOrderTxid: ByteArray get() = txid.hexToByteArray()

    /** The txid bytes in internal order: how a transaction input spends the output. */
    val internalOrderTxid: ByteArray get() = displayOrderTxid.reversedArray()

    fun utxo(network: Spark.Network): Spark.UTXO = Spark.UTXO.newBuilder()
        .setTxid(displayOrderTxid.toByteString())
        .setVout(vout.toInt())
        .setNetwork(network)
        .build()

    internal companion object {
        /**
         * A display-order txid in the lower-case form the operators print; throws
         * [SparkError.InvalidArgument] unless it is 64 hex characters.
         */
        fun normalizedTxid(txid: String): String {
            val normalized = txid.trim().lowercase()
            if (normalized.length != 64 || !normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw SparkError.InvalidArgument("transaction id must be 64 hex characters, got '$txid'")
            }
            return normalized
        }
    }
}

private fun ByteArray.toByteString(): com.google.protobuf.ByteString = com.google.protobuf.ByteString.copyFrom(this)

/** What a static-deposit statement authorizes (the operators' `UtxoSwapRequestType`). */
internal enum class StaticDepositRequestType(val code: Int) {
    FIXED(0),
    MAX_FEE(1),
    REFUND(2),
}

/**
 * The statement the wallet signs (SHA-256, identity key) to authorize a static-deposit claim or
 * refund — the operators' `createUserStatementLegacy` and the reference SDK's
 * `getStaticDepositSigningPayload`: "claim_static_deposit", the lower-case network, the
 * display-order txid, the vout (little-endian u32), the request type (u8), the credit amount
 * (little-endian u64), then [authorization] as raw bytes — the SSP's quote signature for a claim,
 * the refund transaction's 32-byte sighash for a refund.
 */
internal fun staticDepositStatement(
    outpoint: DepositOutpoint,
    network: SparkNetwork,
    requestType: StaticDepositRequestType,
    creditAmountSats: ULong,
    authorization: ByteArray,
): ByteArray {
    val statement = ByteArrayOutputStream()
    statement.write("claim_static_deposit".toByteArray(Charsets.UTF_8))
    statement.write(network.networkString.toByteArray(Charsets.UTF_8))
    statement.write(outpoint.txid.toByteArray(Charsets.UTF_8))
    statement.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(outpoint.vout.toInt()).array())
    statement.write(requestType.code)
    statement.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(creditAmountSats.toLong()).array())
    statement.write(authorization)
    return statement.toByteArray()
}
