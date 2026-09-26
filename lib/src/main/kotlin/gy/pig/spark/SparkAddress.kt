package gy.pig.spark

/**
 * Spark addresses: a bech32m encoding of the protobuf `SparkAddress { identity_public_key = 1 }`
 * payload under a network-specific human-readable part.
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

    /**
     * The identity public key an address encodes. Throws [SparkError.InvalidAddress] for a
     * malformed address or one for another network.
     */
    fun decode(address: String, network: SparkNetwork): ByteArray {
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
        val payload = Bech32.fromWords(words)
            ?: invalidAddress("'$trimmed' has an invalid payload encoding")
        // field 1 (identity_public_key), length-delimited, 33-byte compressed key
        if (payload.size < 35 || payload[0] != 0x0a.toByte() || payload[1] != 33.toByte()) {
            invalidAddress("'$trimmed' does not start with a 33-byte identity public key")
        }
        val key = payload.copyOfRange(2, 35)
        if (key[0] != 0x02.toByte() && key[0] != 0x03.toByte()) {
            invalidAddress("'$trimmed' carries an invalid compressed public key")
        }
        return key
    }
}
