package gy.pig.spark

/** Base58 and Base58Check decoding (legacy P2PKH / P2SH addresses). */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val ALPHABET_MAP: Map<Char, Int> = ALPHABET.withIndex().associate { (i, c) -> c to i }

    /** Decode a base58 string to bytes, preserving leading zero bytes. */
    fun decode(string: String): ByteArray? {
        if (string.isEmpty() || string.length > 128) return null
        val bytes = ArrayList<Int>() // big-endian magnitude
        for (c in string) {
            var carry = ALPHABET_MAP[c] ?: return null
            for (i in bytes.indices.reversed()) {
                carry += 58 * bytes[i]
                bytes[i] = carry and 0xFF
                carry = carry shr 8
            }
            while (carry > 0) {
                bytes.add(0, carry and 0xFF)
                carry = carry shr 8
            }
        }
        val leadingOnes = string.takeWhile { it == '1' }.length
        return ByteArray(leadingOnes) + ByteArray(bytes.size) { bytes[it].toByte() }
    }

    /**
     * Decode a Base58Check string, verifying the 4-byte double-SHA256 checksum.
     * Returns the payload including its version byte.
     */
    fun decodeCheck(string: String): ByteArray? {
        val decoded = decode(string) ?: return null
        if (decoded.size < 5) return null
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val digest = sha256(sha256(payload))
        if (!digest.copyOfRange(0, 4).contentEquals(checksum)) return null
        return payload
    }
}
