package gy.pig.spark

import java.text.Normalizer

/**
 * BIP-39 mnemonic validation (English wordlist).
 *
 * A mnemonic must be 12, 15, 18, 21 or 24 lower-case English words separated by single
 * spaces, and its embedded checksum must match. Without this check a mistyped phrase silently
 * derives a different, empty wallet — and funds deposited to it can only be recovered with the
 * exact typo.
 */
internal object BIP39 {
    private val index: Map<String, Int> by lazy {
        BIP39Wordlist.english.withIndex().associate { (i, word) -> word to i }
    }

    private fun invalidMnemonic(message: String): Nothing = throw SparkError.InvalidMnemonic(message)

    val validWordCounts: Set<Int> = setOf(12, 15, 18, 21, 24)

    /** Throws [SparkError.InvalidMnemonic] describing the first problem found. */
    fun validate(mnemonic: String) {
        val normalized = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
        val words = normalized.split(" ")
        if (words.size !in validWordCounts) {
            invalidMnemonic("expected 12, 15, 18, 21 or 24 words separated by single spaces, got ${words.size}")
        }
        val indices = ArrayList<Int>(words.size)
        for ((position, word) in words.withIndex()) {
            val i = index[word]
            if (i == null) {
                if (word.isEmpty()) {
                    invalidMnemonic("empty word at position ${position + 1} (double space or leading/trailing space)")
                }
                if (index[word.lowercase()] != null) {
                    invalidMnemonic("word ${position + 1} must be lower case")
                }
                invalidMnemonic("word ${position + 1} is not in the BIP-39 English wordlist")
            }
            indices.add(i)
        }

        // Concatenate 11-bit indices; the last count/3 bits are the checksum of the entropy.
        val totalBits = words.size * 11
        val checksumBits = words.size / 3
        val entropyBits = totalBits - checksumBits
        val bits = BooleanArray(totalBits)
        var bitIndex = 0
        for (i in indices) {
            for (shift in 10 downTo 0) {
                bits[bitIndex++] = (i shr shift) and 1 == 1
            }
        }
        val entropy = ByteArray(entropyBits / 8)
        for (bit in 0 until entropyBits) {
            if (bits[bit]) {
                entropy[bit / 8] = (entropy[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
            }
        }
        val hash = sha256(entropy)
        for (bit in 0 until checksumBits) {
            val expected = ((hash[bit / 8].toInt() and 0xFF) shr (7 - bit % 8)) and 1 == 1
            if (bits[entropyBits + bit] != expected) {
                invalidMnemonic("checksum mismatch — one or more words are wrong")
            }
        }
    }

    fun isValid(mnemonic: String): Boolean = try {
        validate(mnemonic)
        true
    } catch (_: SparkError) {
        false
    }
}
