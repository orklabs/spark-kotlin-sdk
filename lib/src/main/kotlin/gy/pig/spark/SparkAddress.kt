package gy.pig.spark

import com.google.protobuf.InvalidProtocolBufferException
import spark.Spark

/**
 * Spark addresses: a bech32m encoding of the protobuf `SparkAddress` payload under a
 * network-specific human-readable part. A plain address carries only `identity_public_key`; a
 * Spark invoice also carries `spark_invoice_fields` and the receiver's signature.
 */
internal object SparkAddress {
    /** Current prefix plus the legacy one the reference SDK still accepts. */
    private fun prefixes(network: SparkNetwork): Pair<String, String> = when (network) {
        SparkNetwork.MAINNET -> "spark" to "sp"
        SparkNetwork.REGTEST -> "sparkrt" to "sprt"
    }

    private fun invalidAddress(message: String): Nothing = throw SparkError.InvalidAddress(message)

    fun hrp(network: SparkNetwork): String = prefixes(network).first

    fun encode(identityPublicKey: ByteArray, network: SparkNetwork): String {
        // Protobuf wire encoding: field 1, wire type 2 (tag 0x0a), single-byte length, bytes.
        val payload = byteArrayOf(0x0a, identityPublicKey.size.toByte()) + identityPublicKey
        return Bech32m.encode(hrp(network), Bech32.toWords(payload))
    }

    /** A decoded Spark address or Spark invoice. */
    class Payload(
        val identityPublicKey: ByteArray,
        /**
         * Present when the string is a Spark invoice: what to pay (sats or tokens, and how much),
         * until when, from whom, with a memo.
         */
        val invoiceFields: Spark.SparkInvoiceFields?,
        /** The receiver's signature over an invoice. */
        val signature: ByteArray?,
    )

    /**
     * Decode the whole `SparkAddress` payload of a Spark address or Spark invoice, as the
     * reference SDK's `decodeSparkAddress` does. Throws [SparkError.InvalidAddress] for a
     * malformed string, one for another network, or an identity key that is not a compressed
     * secp256k1 point.
     */
    fun decodePayload(address: String, network: SparkNetwork): Payload {
        val trimmed = address.trim()
        val (hrp, words) = try {
            Bech32m.decodeBech32m(trimmed)
        } catch (e: SparkError) {
            invalidAddress("'$trimmed': ${e.message}")
        }
        val (current, legacy) = prefixes(network)
        if (hrp != current && hrp != legacy) {
            invalidAddress("'$trimmed' is not a ${network.networkString} Spark address (prefix '$hrp')")
        }
        val bytes = Bech32.fromWords(words)
            ?: invalidAddress("'$trimmed' has an invalid payload encoding")
        val payload = try {
            Spark.SparkAddress.parseFrom(bytes)
        } catch (_: InvalidProtocolBufferException) {
            invalidAddress("'$trimmed' has an invalid payload encoding")
        }
        val key = payload.identityPublicKey.toByteArray()
        if (!isCompressedPoint(key)) {
            invalidAddress("'$trimmed' does not carry a valid 33-byte identity public key")
        }
        return Payload(
            identityPublicKey = key,
            invoiceFields = if (payload.hasSparkInvoiceFields()) payload.sparkInvoiceFields else null,
            signature = if (payload.hasSignature()) payload.signature.toByteArray() else null,
        )
    }

    /**
     * The identity public key a plain Spark address encodes. Throws [SparkError.InvalidAddress]
     * for a malformed address, one for another network, or a Spark invoice: paying an invoice as
     * if it were an address ignores its amount, expiry and sender restriction, and the transfer is
     * not linked to it, so the payee never sees it paid (the reference SDK's `transfer` and
     * `transferTokens` refuse invoices too).
     */
    fun decode(address: String, network: SparkNetwork): ByteArray {
        val payload = decodePayload(address, network)
        if (payload.invoiceFields != null) {
            invalidAddress(
                "this is a Spark invoice, not a Spark address; paying it as an address would ignore its amount, expiry and sender",
            )
        }
        return payload.identityPublicKey
    }

    /** A 33-byte SEC1 compressed key that decodes to a point on secp256k1. */
    private fun isCompressedPoint(key: ByteArray): Boolean {
        if (key.size != 33 || (key[0] != 0x02.toByte() && key[0] != 0x03.toByte())) return false
        return try {
            KeyDerivation.ecDomainParams.curve.decodePoint(key)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
