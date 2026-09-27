package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import spark_token.FinalTokenOutput
import spark_token.FinalTokenTransaction
import spark_token.InvoiceAttachment
import spark_token.PartialTokenOutput
import spark_token.PartialTokenTransaction
import spark_token.TokenCreateInput
import spark_token.TokenMintInput
import spark_token.TokenOutputToSpend
import spark_token.TokenTransactionMetadata
import spark_token.TokenTransferInput
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The operators' deterministic hash of a protobuf message (`spark/common/protohash`, an object
 * hash over field numbers), which V3 token transactions are signed and identified by. Port of
 * the Swift SDK's `ProtoHash`.
 *
 * A message hashes as `SHA256("d" ‖ key ‖ value ‖ …)` over its fields in field-number order,
 * each key being `SHA256("i" ‖ field number as big-endian u64)`. Values hash by type: integers
 * and enums `SHA256("i" ‖ big-endian u64)`, `true` `SHA256("b" ‖ "1")`, strings
 * `SHA256("u" ‖ UTF-8)`, bytes `SHA256("r" ‖ bytes)`, repeated fields `SHA256("l" ‖ element
 * hashes)` in order, nested messages recursively, and a Timestamp as the list of its seconds and
 * nanos. A scalar field holding its default (0, false, "", no bytes) or an empty repeated field
 * is left out, even when it was set explicitly; a message field that is set is always hashed,
 * even when empty.
 *
 * protobuf-lite carries no descriptors, so each message the V3 transactions hash is spelled out
 * here field by field, as `spark_token.proto` declares it; the operators' cross-language vectors
 * check them. As the operators hash the fields their descriptors declare, unknown fields are not
 * part of the hash.
 */
internal object ProtoHash {
    fun hash(transaction: PartialTokenTransaction): ByteArray = message {
        uint32(1, transaction.version)
        if (transaction.hasTokenTransactionMetadata()) field(2, hash(transaction.tokenTransactionMetadata))
        when (transaction.tokenInputsCase) {
            PartialTokenTransaction.TokenInputsCase.MINT_INPUT -> field(3, hash(transaction.mintInput))
            PartialTokenTransaction.TokenInputsCase.TRANSFER_INPUT -> field(4, hash(transaction.transferInput))
            PartialTokenTransaction.TokenInputsCase.CREATE_INPUT -> field(5, hash(transaction.createInput))
            else -> Unit
        }
        repeated(6, transaction.partialTokenOutputsList.map { hash(it) })
        if (transaction.hasExecuteBefore()) field(7, hash(transaction.executeBefore))
    }

    fun hash(transaction: FinalTokenTransaction): ByteArray = message {
        uint32(1, transaction.version)
        if (transaction.hasTokenTransactionMetadata()) field(2, hash(transaction.tokenTransactionMetadata))
        when (transaction.tokenInputsCase) {
            FinalTokenTransaction.TokenInputsCase.MINT_INPUT -> field(3, hash(transaction.mintInput))
            FinalTokenTransaction.TokenInputsCase.TRANSFER_INPUT -> field(4, hash(transaction.transferInput))
            FinalTokenTransaction.TokenInputsCase.CREATE_INPUT -> field(5, hash(transaction.createInput))
            else -> Unit
        }
        repeated(6, transaction.finalTokenOutputsList.map { hash(it) })
        if (transaction.hasExecuteBefore()) field(7, hash(transaction.executeBefore))
    }

    fun hash(metadata: TokenTransactionMetadata): ByteArray = message {
        repeated(2, metadata.sparkOperatorIdentityPublicKeysList.map { bytesValue(it) })
        enum(3, metadata.networkValue)
        if (metadata.hasClientCreatedTimestamp()) field(4, hash(metadata.clientCreatedTimestamp))
        uint64(5, metadata.validityDurationSeconds)
        repeated(6, metadata.invoiceAttachmentsList.map { hash(it) })
    }

    fun hash(attachment: InvoiceAttachment): ByteArray = message {
        string(1, attachment.sparkInvoice)
    }

    fun hash(input: TokenMintInput): ByteArray = message {
        bytes(1, input.issuerPublicKey)
        bytes(2, input.tokenIdentifier)
    }

    fun hash(input: TokenTransferInput): ByteArray = message {
        repeated(1, input.outputsToSpendList.map { hash(it) })
    }

    fun hash(output: TokenOutputToSpend): ByteArray = message {
        bytes(1, output.prevTokenTransactionHash)
        uint32(2, output.prevTokenTransactionVout)
    }

    fun hash(input: TokenCreateInput): ByteArray = message {
        bytes(1, input.issuerPublicKey)
        string(2, input.tokenName)
        string(3, input.tokenTicker)
        uint32(4, input.decimals)
        bytes(5, input.maxSupply)
        bool(6, input.isFreezable)
        bytes(7, input.creationEntityPublicKey)
        bytes(8, input.extraMetadata)
    }

    fun hash(output: PartialTokenOutput): ByteArray = message {
        bytes(1, output.ownerPublicKey)
        uint64(2, output.withdrawBondSats)
        uint64(3, output.withdrawRelativeBlockLocktime)
        bytes(4, output.tokenIdentifier)
        bytes(5, output.tokenAmount)
    }

    fun hash(output: FinalTokenOutput): ByteArray = message {
        if (output.hasPartialTokenOutput()) field(1, hash(output.partialTokenOutput))
        bytes(2, output.revocationCommitment)
    }

    /** A Timestamp hashes as the list of its seconds and nanos, both always present. */
    fun hash(timestamp: Timestamp): ByteArray = list(listOf(integer(timestamp.seconds), integer(timestamp.nanos.toLong())))

    /** The hash of a message whose fields [build] adds. */
    inline fun message(build: Fields.() -> Unit): ByteArray = Fields().apply(build).digest()

    /** The fields of one message that its hash includes, as `(field number, value hash)`. */
    class Fields {
        private val entries = mutableListOf<Pair<Int, ByteArray>>()

        /** A field whose value hash is already computed: a set message field, always included. */
        fun field(number: Int, valueHash: ByteArray) {
            entries.add(number to valueHash)
        }

        /** An `int32`, `int64` or `uint64` field (widened to 64 bits as Go widens it), left out when 0. */
        fun uint64(number: Int, value: Long) {
            if (value != 0L) field(number, integer(value))
        }

        /** A `uint32` field, which protobuf-lite hands out as a signed `Int`: widened unsigned. */
        fun uint32(number: Int, value: Int) {
            if (value != 0) field(number, integer(value.toLong() and 0xFFFF_FFFFL))
        }

        /** An enum field by its number, left out when 0. */
        fun enum(number: Int, value: Int) {
            if (value != 0) field(number, integer(value.toLong()))
        }

        fun bool(number: Int, value: Boolean) {
            if (value) field(number, tagged("b", "1".toByteArray()))
        }

        fun string(number: Int, value: String) {
            if (value.isNotEmpty()) field(number, tagged("u", value.toByteArray(Charsets.UTF_8)))
        }

        fun bytes(number: Int, value: ByteString) {
            if (!value.isEmpty) field(number, bytesValue(value))
        }

        /** A repeated field: one list of element hashes, in order and none left out; no field when empty. */
        fun repeated(number: Int, elements: List<ByteArray>) {
            if (elements.isNotEmpty()) field(number, ProtoHash.list(elements))
        }

        fun digest(): ByteArray {
            val data = ByteArrayOutputStream()
            data.write('d'.code)
            for ((number, valueHash) in entries.sortedBy { it.first }) {
                data.write(integer(number.toLong()))
                data.write(valueHash)
            }
            return sha256(data.toByteArray())
        }
    }

    fun tagged(tag: String, bytes: ByteArray): ByteArray = sha256(tag.toByteArray() + bytes)

    /** An integer by its 64-bit pattern, big-endian: signed and unsigned values share it. */
    fun integer(value: Long): ByteArray = tagged("i", ByteBuffer.allocate(8).putLong(value).array())

    fun bytesValue(value: ByteString): ByteArray = tagged("r", value.toByteArray())

    fun list(elements: List<ByteArray>): ByteArray {
        val data = ByteArrayOutputStream()
        data.write('l'.code)
        elements.forEach { data.write(it) }
        return sha256(data.toByteArray())
    }
}
