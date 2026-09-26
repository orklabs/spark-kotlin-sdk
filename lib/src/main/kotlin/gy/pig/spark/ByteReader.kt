package gy.pig.spark

import java.io.ByteArrayOutputStream

/**
 * Bounds-checked reader over raw bytes.
 *
 * Every read throws [SparkError.MalformedTransaction] instead of throwing an index exception
 * when the input is shorter than expected, so bytes received from an operator, the SSP, or a
 * block explorer can never crash the host application.
 */
internal class ByteReader(private val bytes: ByteArray, private val context: String) {
    var offset: Int = 0
        private set

    val isAtEnd: Boolean get() = offset >= bytes.size
    val remaining: Int get() = bytes.size - offset

    fun readByte(): Int {
        if (offset >= bytes.size) throw truncated(needed = 1)
        val value = bytes[offset].toInt() and 0xFF
        offset += 1
        return value
    }

    fun readBytes(count: Int): ByteArray {
        if (count < 0 || count > remaining) throw truncated(needed = count)
        val slice = bytes.copyOfRange(offset, offset + count)
        offset += count
        return slice
    }

    fun readUInt16LE(): UInt {
        val b = readBytes(2)
        return (b[0].toUInt() and 0xFFu) or ((b[1].toUInt() and 0xFFu) shl 8)
    }

    fun readUInt32LE(): UInt {
        val b = readBytes(4)
        var value = 0u
        for (i in 0 until 4) value = value or ((b[i].toUInt() and 0xFFu) shl (8 * i))
        return value
    }

    fun readUInt64LE(): ULong {
        val b = readBytes(8)
        var value = 0uL
        for (i in 0 until 8) value = value or ((b[i].toULong() and 0xFFuL) shl (8 * i))
        return value
    }

    /** Bitcoin CompactSize integer. */
    fun readVarInt(): ULong = when (val first = readByte()) {
        in 0 until 0xFD -> first.toULong()
        0xFD -> readUInt16LE().toULong()
        0xFE -> readUInt32LE().toULong()
        else -> readUInt64LE()
    }

    /**
     * CompactSize length prefix followed by that many bytes. The length is checked against the
     * bytes actually remaining before anything is allocated.
     */
    fun readVarBytes(): ByteArray {
        val length = readVarInt()
        if (length > remaining.toULong()) {
            throw truncated(needed = if (length > Int.MAX_VALUE.toULong()) Int.MAX_VALUE else length.toInt())
        }
        return readBytes(length.toInt())
    }

    fun expectEnd() {
        if (!isAtEnd) {
            throw SparkError.MalformedTransaction("$context: $remaining trailing byte(s) after the end of the transaction")
        }
    }

    private fun truncated(needed: Int): SparkError = SparkError.MalformedTransaction(
        "$context: truncated at byte $offset, needed $needed more byte(s) but only $remaining remain",
    )
}

/** Bitcoin CompactSize encoding. */
internal fun encodeVarInt(value: ULong): ByteArray {
    val out = ByteArrayOutputStream()
    when {
        value < 0xFDuL -> out.write(value.toInt())
        value <= 0xFFFFuL -> {
            out.write(0xFD)
            writeLittleEndian(out, value, 2)
        }
        value <= 0xFFFF_FFFFuL -> {
            out.write(0xFE)
            writeLittleEndian(out, value, 4)
        }
        else -> {
            out.write(0xFF)
            writeLittleEndian(out, value, 8)
        }
    }
    return out.toByteArray()
}

internal fun writeLittleEndian(out: ByteArrayOutputStream, value: ULong, byteCount: Int) {
    for (i in 0 until byteCount) {
        out.write(((value shr (8 * i)) and 0xFFuL).toInt())
    }
}
