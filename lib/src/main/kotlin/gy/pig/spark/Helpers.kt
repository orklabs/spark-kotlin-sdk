@file:OptIn(ExperimentalUnsignedTypes::class)

package gy.pig.spark

import android.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun String.hexToByteArray(): ByteArray {
    val hex = if (startsWith("0x")) substring(2) else this
    check(hex.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(hex.length / 2) { i ->
        val index = i * 2
        ((Character.digit(hex[index], 16) shl 4) + Character.digit(hex[index + 1], 16)).toByte()
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/**
 * Strict hex decoding: `null` for an odd length or any non-hex character (Swift's
 * `Data(hexString:)`). Use it for bytes that come from a server or a caller; the lenient
 * [hexToByteArray] silently maps invalid characters to garbage.
 */
internal fun String.hexToBytesOrNull(): ByteArray? {
    val hex = if (startsWith("0x")) substring(2) else this
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = asciiHexDigit(hex[2 * i])
        val lo = asciiHexDigit(hex[2 * i + 1])
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun asciiHexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/**
 * The string at [key], or `null` when it is missing, JSON `null`, or not a string — Swift's
 * `json[key] as? String`. (`JSONObject.optString` returns `"null"` for a JSON null.)
 */
internal fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String

/**
 * Run a best-effort step (Swift's `try?`): any failure is swallowed and reported as `null`,
 * except coroutine cancellation, which is always propagated.
 */
internal inline fun <T> bestEffort(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun hmacSHA512(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(key, "HmacSHA512"))
    return mac.doFinal(data)
}

fun hmacSHA256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

fun decodeBase64URL(string: String): ByteArray? = try {
    Base64.decode(string, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
} catch (_: Exception) {
    null
}

/** Parse ISO 8601 date strings with various fractional second formats and timezone offsets. */
fun parseISODate(dateStr: String): java.util.Date = parseISODateOrNull(dateStr) ?: java.util.Date(System.currentTimeMillis() + 3600_000)

/** [parseISODate] without the "one hour from now" fallback: `null` when the string does not parse. */
internal fun parseISODateOrNull(dateStr: String): java.util.Date? = try {
    // Truncate fractional seconds to 3 digits (millis) and normalize timezone
    val normalized = dateStr
        .replace(Regex("(\\.\\d{3})\\d*"), "$1") // truncate micros to millis
        .replace("+00:00", "Z")
        .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2") // +05:30 -> +0530
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)
    formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
    // Z needs special handling for SimpleDateFormat
    val forParsing = normalized.replace("Z", "+0000")
    formatter.parse(forParsing)
} catch (_: Exception) {
    null
}

// MARK: - BIP-340 Tagged Hash

class SparkHasher(tag: List<String>) {
    private val buffer = java.io.ByteArrayOutputStream()

    init {
        // Serialize the tag: for each component, 8-byte BE length + UTF-8 bytes
        val tagStream = java.io.ByteArrayOutputStream()
        for (component in tag) {
            val componentBytes = component.toByteArray(Charsets.UTF_8)
            tagStream.write(ByteBuffer.allocate(8).putLong(componentBytes.size.toLong()).array())
            tagStream.write(componentBytes)
        }
        // BIP-340 tagged hash: tagHash = SHA256(serializedTag), then prefix with tagHash || tagHash
        val tagHash = sha256(tagStream.toByteArray())
        buffer.write(tagHash)
        buffer.write(tagHash)
    }

    fun addBytes(value: ByteArray) {
        buffer.write(ByteBuffer.allocate(8).putLong(value.size.toLong()).array())
        buffer.write(value)
    }

    fun addString(value: String) {
        addBytes(value.toByteArray(Charsets.UTF_8))
    }

    fun addUint32(value: UInt) {
        // Promoted to uint64 big-endian per TS SDK
        val valBytes = ByteBuffer.allocate(8).putLong(value.toLong()).array()
        addBytes(valBytes)
    }

    fun addUint64(value: ULong) {
        val valBytes = ByteBuffer.allocate(8).putLong(value.toLong()).array()
        addBytes(valBytes)
    }

    fun addMapStringToBytes(map: Map<String, ByteArray>) {
        addUint64(map.size.toULong())
        // Sort by raw UTF-8 byte order (not Kotlin's UTF-16 String ordering) so the
        // resulting tagged hash matches the Swift reference and the server for any
        // key. Identical to natural ordering for the ASCII hex operator ids used today.
        val sorted = map.entries.sortedWith { a, b ->
            val ka = a.key.toByteArray(Charsets.UTF_8)
            val kb = b.key.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(ka.size, kb.size)) {
                val cmp = (ka[i].toInt() and 0xFF) - (kb[i].toInt() and 0xFF)
                if (cmp != 0) return@sortedWith cmp
            }
            ka.size - kb.size
        }
        for ((key, value) in sorted) {
            addBytes(key.toByteArray(Charsets.UTF_8))
            addBytes(value)
        }
    }

    fun hash(): ByteArray = sha256(buffer.toByteArray())
}

// MARK: - Secp256k1 Scalar Arithmetic

private val SECP256K1_N = ulongArrayOf(
    0xBFD25E8CD0364141uL,
    0xBAAEDCE6AF48A03BuL,
    0xFFFFFFFFFFFFFFFEuL,
    0xFFFFFFFFFFFFFFFFuL,
)

private fun parseScalar(data: ByteArray): ULongArray {
    val limbs = ULongArray(4)
    for (i in 0 until 4) {
        var v = 0uL
        for (j in 0 until 8) {
            v = (v shl 8) or (data[i * 8 + j].toUByte().toULong())
        }
        limbs[3 - i] = v
    }
    return limbs
}

private fun serializeScalar(limbs: ULongArray): ByteArray {
    val output = ByteArray(32)
    for (i in 0 until 4) {
        val v = limbs[3 - i]
        for (j in 0 until 8) {
            output[i * 8 + j] = ((v shr (56 - j * 8)) and 0xFFuL).toByte()
        }
    }
    return output
}

/** Compute (a - b) mod n over the secp256k1 curve order */
fun subtractPrivateKeys(a: ByteArray, b: ByteArray): ByteArray {
    val aLimbs = parseScalar(a)
    val bLimbs = parseScalar(b)

    // Compute n - b
    val nMinusB = ULongArray(4)
    var borrow = 0uL
    for (i in 0 until 4) {
        val d1 = SECP256K1_N[i] - bLimbs[i]
        val b1 = if (SECP256K1_N[i] < bLimbs[i]) 1uL else 0uL
        val d2 = d1 - borrow
        val b2 = if (d1 < borrow) 1uL else 0uL
        nMinusB[i] = d2
        borrow = b1 + b2
    }

    // Add a + (n - b)
    val result = ULongArray(4)
    var carry = 0uL
    for (i in 0 until 4) {
        val s1 = aLimbs[i] + nMinusB[i]
        val c1 = if (s1 < aLimbs[i]) 1uL else 0uL
        val s2 = s1 + carry
        val c2 = if (s2 < s1) 1uL else 0uL
        result[i] = s2
        carry = c1 + c2
    }

    // Reduce mod n if needed
    var needsReduce = carry > 0uL
    if (!needsReduce) {
        for (i in 3 downTo 0) {
            if (result[i] > SECP256K1_N[i]) {
                needsReduce = true
                break
            }
            if (result[i] < SECP256K1_N[i]) break
        }
    }

    if (needsReduce) {
        borrow = 0uL
        for (i in 0 until 4) {
            val d1 = result[i] - SECP256K1_N[i]
            val b1 = if (result[i] < SECP256K1_N[i]) 1uL else 0uL
            val d2 = d1 - borrow
            val b2 = if (d1 < borrow) 1uL else 0uL
            result[i] = d2
            borrow = b1 + b2
        }
    }

    return serializeScalar(result)
}
