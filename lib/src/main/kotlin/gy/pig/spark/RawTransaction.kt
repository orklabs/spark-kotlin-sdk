package gy.pig.spark

import java.io.ByteArrayOutputStream

/**
 * A parsed Bitcoin transaction. Parsing is fully bounds-checked and never throws anything but
 * [SparkError.MalformedTransaction] on malformed input.
 *
 * This replaces the ad-hoc offset arithmetic the SDK previously used on transaction bytes
 * received from operators, the SSP, and the block explorer.
 *
 * Swift models this as a value type with mutable fields; here it is immutable and changed with
 * `copy`, which gives the same copy-on-write semantics.
 */
internal data class RawTransaction(
    val version: UInt,
    val inputs: List<Input>,
    val outputs: List<Output>,
    val locktime: UInt,
    /**
     * `true` when the bytes carried the segwit marker and flag. Preserved so that
     * re-serialisation reproduces the input format.
     */
    val hasWitnessSerialization: Boolean,
) {
    data class Input(
        /** Previous output's txid in internal (little-endian) byte order. */
        val previousTxid: ByteArray,
        val previousIndex: UInt,
        val scriptSig: ByteArray = ByteArray(0),
        val sequence: UInt = 0xFFFF_FFFFu,
        val witness: List<ByteArray> = emptyList(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Input) return false
            return previousTxid.contentEquals(other.previousTxid) &&
                previousIndex == other.previousIndex &&
                scriptSig.contentEquals(other.scriptSig) &&
                sequence == other.sequence &&
                witness.size == other.witness.size &&
                witness.indices.all { witness[it].contentEquals(other.witness[it]) }
        }

        override fun hashCode(): Int {
            var result = previousTxid.contentHashCode()
            result = 31 * result + previousIndex.hashCode()
            result = 31 * result + scriptSig.contentHashCode()
            result = 31 * result + sequence.hashCode()
            for (item in witness) result = 31 * result + item.contentHashCode()
            return result
        }
    }

    data class Output(val value: ULong, val scriptPubKey: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Output) return false
            return value == other.value && scriptPubKey.contentEquals(other.scriptPubKey)
        }

        override fun hashCode(): Int = 31 * value.hashCode() + scriptPubKey.contentHashCode()
    }

    /**
     * Serialise. [includeWitness] is only honoured when the transaction uses witness
     * serialisation; the non-witness form is what the txid commits to.
     */
    fun serialized(includeWitness: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        writeLittleEndian(out, version.toULong(), 4)
        val withWitness = includeWitness && hasWitnessSerialization
        if (withWitness) {
            out.write(0x00)
            out.write(0x01)
        }
        out.write(encodeVarInt(inputs.size.toULong()))
        for (input in inputs) {
            out.write(input.previousTxid)
            writeLittleEndian(out, input.previousIndex.toULong(), 4)
            out.write(encodeVarInt(input.scriptSig.size.toULong()))
            out.write(input.scriptSig)
            writeLittleEndian(out, input.sequence.toULong(), 4)
        }
        out.write(encodeVarInt(outputs.size.toULong()))
        for (output in outputs) {
            writeLittleEndian(out, output.value, 8)
            out.write(encodeVarInt(output.scriptPubKey.size.toULong()))
            out.write(output.scriptPubKey)
        }
        if (withWitness) {
            for (input in inputs) {
                out.write(encodeVarInt(input.witness.size.toULong()))
                for (item in input.witness) {
                    out.write(encodeVarInt(item.size.toULong()))
                    out.write(item)
                }
            }
        }
        writeLittleEndian(out, locktime.toULong(), 4)
        return out.toByteArray()
    }

    /** Transaction id in internal (little-endian) byte order — the form used in input prevouts. */
    val txid: ByteArray
        get() = sha256(sha256(serialized(includeWitness = false)))

    /** Transaction id as the conventional display hex (big-endian). */
    val txidHex: String
        get() = txid.reversedArray().toHexString()

    fun output(vout: UInt): Output {
        if (vout >= outputs.size.toUInt()) {
            throw SparkError.MalformedTransaction("vout $vout out of range: transaction has ${outputs.size} output(s)")
        }
        return outputs[vout.toInt()]
    }

    /** nSequence of the first input. Spark encodes leaf timelocks here. */
    val firstInputSequence: UInt
        get() = inputs.firstOrNull()?.sequence
            ?: throw SparkError.MalformedTransaction("transaction has no inputs")

    companion object {
        /**
         * Sanity caps so a hostile response cannot make the parser allocate absurd amounts
         * before the byte-level bounds checks fire.
         */
        private const val MAX_ITEMS = 100_000

        fun parse(data: ByteArray, context: String = "transaction"): RawTransaction {
            val reader = ByteReader(data, context)
            val version = reader.readUInt32LE()

            var witnessSerialization = false
            var inputCount = reader.readVarInt()
            if (inputCount == 0uL && reader.remaining > 0) {
                val flag = reader.readByte()
                if (flag != 0x01) {
                    throw SparkError.MalformedTransaction("$context: unknown segwit flag $flag")
                }
                witnessSerialization = true
                inputCount = reader.readVarInt()
            }
            if (inputCount > MAX_ITEMS.toULong()) {
                throw SparkError.MalformedTransaction("$context: implausible input count $inputCount")
            }

            val inputs = ArrayList<Input>(inputCount.toInt())
            for (i in 0 until inputCount.toInt()) {
                val txid = reader.readBytes(32)
                val index = reader.readUInt32LE()
                val script = reader.readVarBytes()
                val sequence = reader.readUInt32LE()
                inputs.add(Input(previousTxid = txid, previousIndex = index, scriptSig = script, sequence = sequence))
            }

            val outputCount = reader.readVarInt()
            if (outputCount > MAX_ITEMS.toULong()) {
                throw SparkError.MalformedTransaction("$context: implausible output count $outputCount")
            }
            val outputs = ArrayList<Output>(outputCount.toInt())
            for (i in 0 until outputCount.toInt()) {
                val value = reader.readUInt64LE()
                val script = reader.readVarBytes()
                outputs.add(Output(value = value, scriptPubKey = script))
            }

            if (witnessSerialization) {
                for (i in inputs.indices) {
                    val itemCount = reader.readVarInt()
                    if (itemCount > MAX_ITEMS.toULong()) {
                        throw SparkError.MalformedTransaction("$context: implausible witness item count $itemCount")
                    }
                    val items = ArrayList<ByteArray>()
                    repeat(itemCount.toInt()) { items.add(reader.readVarBytes()) }
                    inputs[i] = inputs[i].copy(witness = items)
                }
            }

            val locktime = reader.readUInt32LE()
            reader.expectEnd()

            return RawTransaction(
                version = version,
                inputs = inputs,
                outputs = outputs,
                locktime = locktime,
                hasWitnessSerialization = witnessSerialization,
            )
        }

        /**
         * Whether two txids refer to the same transaction, accepting either byte order.
         * Mirrors the operators' accept-both tolerance for ids that arrive as display hex.
         */
        fun txidMatches(a: ByteArray, b: ByteArray): Boolean {
            if (a.size != 32 || b.size != 32) return false
            return a.contentEquals(b) || a.contentEquals(b.reversedArray())
        }
    }
}
