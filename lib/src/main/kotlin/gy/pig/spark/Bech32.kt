package gy.pig.spark

/**
 * Bech32 (BIP-173) and Bech32m (BIP-350) encoding and decoding.
 *
 * Used for Spark addresses, token identifiers, Bitcoin segwit addresses, and BOLT-11 invoices.
 * The decoder enforces the character-set, case, and checksum rules of both BIPs; callers decide
 * which encoding they require for a given payload. Data is carried as 5-bit words in a
 * `List<Int>` (values 0..31).
 */
internal object Bech32 {
    enum class Encoding {
        BECH32,
        BECH32M,
    }

    /** Result of [decode]: the lower-cased human-readable part, the 5-bit data words, and the checksum variant. */
    data class Decoded(val hrp: String, val data: List<Int>, val encoding: Encoding)

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_MAP: Map<Char, Int> = CHARSET.withIndex().associate { (i, c) -> c to i }

    private const val BECH32_CONST: UInt = 1u
    private const val BECH32M_CONST: UInt = 0x2bc830a3u

    private val GENERATOR: List<UInt> = listOf(0x3b6a57b2u, 0x26508e6du, 0x1ea119fau, 0x3d4233ddu, 0x2a1462b3u)

    private fun polymod(values: List<Int>): UInt {
        var chk = 1u
        for (v in values) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffffu) shl 5) xor v.toUInt()
            for (i in 0 until 5) {
                if (((b shr i) and 1u) != 0u) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Int> {
        val result = ArrayList<Int>(hrp.length * 2 + 1)
        for (c in hrp) result.add(c.code shr 5)
        result.add(0)
        for (c in hrp) result.add(c.code and 31)
        return result
    }

    private fun invalid(message: String): Nothing = throw SparkError.InvalidResponse(message)

    private fun checksumConstant(encoding: Encoding): UInt = when (encoding) {
        Encoding.BECH32 -> BECH32_CONST
        Encoding.BECH32M -> BECH32M_CONST
    }

    private fun createChecksum(hrp: String, data: List<Int>, encoding: Encoding): List<Int> {
        val values = hrpExpand(hrp) + data + listOf(0, 0, 0, 0, 0, 0)
        val polymodValue = polymod(values) xor checksumConstant(encoding)
        return (0 until 6).map { ((polymodValue shr (5 * (5 - it))) and 31u).toInt() }
    }

    /** Encode 5-bit words with the given checksum variant. The HRP is emitted as given. */
    fun encode(hrp: String, data: List<Int>, encoding: Encoding): String {
        val combined = data + createChecksum(hrp, data, encoding)
        val sb = StringBuilder(hrp.length + 1 + combined.size)
        sb.append(hrp).append('1')
        for (d in combined) sb.append(CHARSET[d])
        return sb.toString()
    }

    /**
     * Decode a bech32 or bech32m string and report which checksum matched.
     *
     * @param maxLength BIP-173 caps addresses at 90 characters. Pass `null` for payloads
     *   without a length limit (BOLT-11 invoices, Spark addresses).
     */
    fun decode(string: String, maxLength: Int? = 90): Decoded {
        if (maxLength != null && string.length > maxLength) {
            invalid("Bech32 string longer than $maxLength characters")
        }
        val hasLower = string.any { it.isLowerCase() }
        val hasUpper = string.any { it.isUpperCase() }
        if (hasLower && hasUpper) {
            invalid("Bech32 string mixes upper and lower case")
        }
        val lower = string.lowercase()
        val sepIndex = lower.lastIndexOf('1')
        if (sepIndex < 0) invalid("No separator in bech32 string")
        val hrp = lower.substring(0, sepIndex)
        if (hrp.isEmpty()) invalid("Empty human-readable part in bech32 string")
        if (hrp.any { it.code < 33 || it.code > 126 }) {
            invalid("Invalid character in bech32 human-readable part")
        }
        val dataStr = lower.substring(sepIndex + 1)
        if (dataStr.length < 6) invalid("Bech32 data too short")

        val data = ArrayList<Int>(dataStr.length)
        for (c in dataStr) {
            data.add(CHARSET_MAP[c] ?: invalid("Invalid bech32 character: $c"))
        }

        val encoding = when (polymod(hrpExpand(hrp) + data)) {
            BECH32M_CONST -> Encoding.BECH32M
            BECH32_CONST -> Encoding.BECH32
            else -> invalid("Invalid bech32 checksum")
        }
        return Decoded(hrp, data.dropLast(6), encoding)
    }

    /** Convert between bit widths (e.g. 8-bit bytes to 5-bit words and back). */
    fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): List<Int>? {
        var acc = 0
        var bits = 0
        val result = ArrayList<Int>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            if (value < 0 || (value shr fromBits) != 0) return null
            acc = ((acc shl fromBits) or value) and 0xFFFFFF
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) result.add((acc shl (toBits - bits)) and maxv)
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return result
    }

    fun toWords(data: ByteArray): List<Int> = convertBits(data.map { it.toInt() and 0xFF }, fromBits = 8, toBits = 5, pad = true) ?: emptyList()

    fun fromWords(words: List<Int>): ByteArray? = convertBits(words, fromBits = 5, toBits = 8, pad = false)?.let { ints ->
        ByteArray(ints.size) { ints[it].toByte() }
    }
}

/** Bech32m-only conveniences for Spark addresses and token identifiers. */
internal object Bech32m {
    /** Encode 5-bit words with the bech32m checksum. Spark payloads have no length limit. */
    fun encode(hrp: String, data: List<Int>): String = Bech32.encode(hrp, data, Bech32.Encoding.BECH32M)

    /** Decode a string that must carry a bech32m checksum. Returns (hrp, 5-bit words). */
    fun decodeBech32m(str: String): Pair<String, List<Int>> {
        val decoded = Bech32.decode(str, maxLength = null)
        if (decoded.encoding != Bech32.Encoding.BECH32M) {
            throw SparkError.InvalidResponse("Invalid bech32m checksum")
        }
        return decoded.hrp to decoded.data
    }
}
