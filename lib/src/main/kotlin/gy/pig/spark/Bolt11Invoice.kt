package gy.pig.spark

import java.util.Date
import java.util.UUID

/**
 * A decoded BOLT-11 payment request.
 *
 * The decoder follows the reader rules of BOLT-11: bech32 checksum, all-lower or all-upper
 * case, a recognised currency prefix, an integer amount with an optional `m`/`u`/`n`/`p`
 * multiplier (`p` amounts must be a multiple of 10), and tagged fields whose lengths are
 * validated. Amount arithmetic is overflow-checked so a hostile string throws instead of
 * wrapping. The signature is not verified here; the SSP and operators verify it before
 * anything is paid.
 */
internal data class Bolt11Invoice(
    val network: Network,
    /** 32-byte payment hash (tag `p`). */
    val paymentHash: ByteArray,
    /** Amount in millisatoshi (an unsigned 64-bit value), `null` for an amountless invoice. */
    val amountMsat: ULong?,
    /** Invoice creation time (seconds since the Unix epoch). */
    val timestamp: ULong,
    /** Seconds after [timestamp] until the invoice expires (tag `x`, default 3600). */
    val expirySeconds: ULong,
    /**
     * Payment secret (tag `s`). BOLT-11 readers must fail a payment without one, and the
     * reference SDK refuses such invoices, so decoding does too.
     */
    val paymentSecret: ByteArray,
    /** Short description (tag `d`), if present. */
    val description: String?,
    /**
     * A Spark payment target embedded in the invoice, decoded as the reference SDK does: a Spark
     * invoice in a fallback-address field (tag `f`, version 31), or else a Spark identity public
     * key (hex) in a route hint whose short channel id is the sentinel `f42400f424000001`.
     */
    val sparkFallback: String? = null,
) {
    enum class Network(val label: String) {
        MAINNET("mainnet"),
        TESTNET("testnet"),
        SIGNET("signet"),
        REGTEST("regtest"),
    }

    /** Computed in floating point like Swift's `Date`, so a hostile expiry cannot wrap around. */
    val expiresAt: Date get() = Date(((timestamp.toDouble() + expirySeconds.toDouble()) * 1000.0).toLong())

    /** Whether this invoice belongs to the wallet's network. */
    fun belongsTo(network: SparkNetwork): Boolean = when (this.network) {
        Network.MAINNET -> network == SparkNetwork.MAINNET
        Network.REGTEST -> network == SparkNetwork.REGTEST
        Network.TESTNET, Network.SIGNET -> false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bolt11Invoice) return false
        return network == other.network &&
            paymentHash.contentEquals(other.paymentHash) &&
            amountMsat == other.amountMsat &&
            timestamp == other.timestamp &&
            expirySeconds == other.expirySeconds &&
            paymentSecret.contentEquals(other.paymentSecret) &&
            description == other.description &&
            sparkFallback == other.sparkFallback
    }

    override fun hashCode(): Int {
        var result = network.hashCode()
        result = 31 * result + paymentHash.contentHashCode()
        result = 31 * result + (amountMsat?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + expirySeconds.hashCode()
        result = 31 * result + paymentSecret.contentHashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (sparkFallback?.hashCode() ?: 0)
        return result
    }

    companion object {
        private const val SIGNATURE_WORDS = 104 // 65 bytes
        private const val TIMESTAMP_WORDS = 7
        private const val TAG_PAYMENT_HASH = 1
        private const val TAG_PAYMENT_SECRET = 16
        private const val TAG_EXPIRY = 6
        private const val TAG_DESCRIPTION = 13
        private const val TAG_FALLBACK = 9
        private const val TAG_ROUTE_HINT = 3
        private const val HASH_FIELD_WORDS = 52

        /** Fallback-address version the reference SDK uses for an embedded Spark invoice. */
        private const val SPARK_INVOICE_FALLBACK_VERSION = 31

        /** Short channel id of the route hint hop that carries a Spark identity public key. */
        private val SPARK_IDENTITY_SHORT_CHANNEL_ID = byteArrayOf(
            0xF4.toByte(),
            0x24,
            0x00,
            0xF4.toByte(),
            0x24,
            0x00,
            0x00,
            0x01,
        )

        /** Bytes of one route-hint hop: pubkey (33), short channel id (8), base fee (4), proportional fee (4), CLTV delta (2). */
        private const val ROUTE_HINT_HOP_BYTES = 51

        /** 21 000 000 BTC in millisatoshi. */
        private const val MAX_SUPPLY_MSAT: ULong = 2_100_000_000_000_000_000uL

        private fun invalidInvoice(message: String): Nothing = throw SparkError.InvalidInvoice(message)

        fun decode(invoice: String): Bolt11Invoice {
            val trimmed = invoice.trim()
            val decoded = try {
                Bech32.decode(trimmed, maxLength = null)
            } catch (e: SparkError) {
                invalidInvoice(e.message)
            }
            if (decoded.encoding != Bech32.Encoding.BECH32) {
                invalidInvoice("BOLT-11 invoices use the bech32 checksum, not bech32m")
            }
            val (network, amountMsat) = parseHRP(decoded.hrp)

            val words = decoded.data
            if (words.size < TIMESTAMP_WORDS + SIGNATURE_WORDS) {
                invalidInvoice("data part too short (${words.size} words)")
            }
            var timestamp = 0uL
            for (w in words.subList(0, TIMESTAMP_WORDS)) timestamp = (timestamp shl 5) or w.toULong()

            val fields = TaggedFields()
            fields.read(words.subList(TIMESTAMP_WORDS, words.size - SIGNATURE_WORDS))

            return Bolt11Invoice(
                network = network,
                paymentHash = fields.paymentHash ?: invalidInvoice("missing payment hash (p field)"),
                amountMsat = amountMsat,
                timestamp = timestamp,
                expirySeconds = fields.expiry ?: 3600uL,
                paymentSecret = fields.paymentSecret ?: invalidInvoice("missing payment secret (s field)"),
                description = fields.description,
                sparkFallback = fields.sparkInvoiceFallback ?: fields.routeHintFallback,
            )
        }

        /** The tagged fields of an invoice's data part, as far as this SDK reads them. */
        private class TaggedFields {
            var paymentHash: ByteArray? = null
            var paymentSecret: ByteArray? = null
            var expiry: ULong? = null
            var description: String? = null
            var sparkInvoiceFallback: String? = null
            var routeHintFallback: String? = null

            fun read(fields: List<Int>) {
                var pos = 0
                while (pos + 3 <= fields.size) {
                    val type = fields[pos]
                    val length = fields[pos + 1] * 32 + fields[pos + 2]
                    pos += 3
                    if (pos + length > fields.size) {
                        invalidInvoice("tagged field $type runs past the end of the invoice")
                    }
                    readField(type, fields.subList(pos, pos + length))
                    pos += length
                }
                if (pos != fields.size) {
                    invalidInvoice("trailing bytes after the last tagged field")
                }
            }

            private fun readField(type: Int, data: List<Int>) {
                when {
                    type == TAG_PAYMENT_HASH && data.size == HASH_FIELD_WORDS -> {
                        if (paymentHash == null) paymentHash = bytesFromWords(data, 32)
                    }
                    type == TAG_PAYMENT_SECRET && data.size == HASH_FIELD_WORDS -> {
                        if (paymentSecret == null) paymentSecret = bytesFromWords(data, 32)
                    }
                    type == TAG_EXPIRY -> {
                        if (data.size > 12) invalidInvoice("expiry field too long")
                        var value = 0uL
                        for (w in data) value = (value shl 5) or w.toULong()
                        expiry = value
                    }
                    type == TAG_DESCRIPTION -> {
                        Bech32.convertBits(data, fromBits = 5, toBits = 8, pad = false)?.let { bytes ->
                            description = String(ByteArray(bytes.size) { bytes[it].toByte() }, Charsets.UTF_8)
                        }
                    }
                    // f: fallback address; version 31 carries a Spark invoice. Lossy on purpose, like
                    // the reference SDK's TextDecoder: a version-31 field counts as a Spark fallback
                    // even when its bytes are not valid UTF-8.
                    type == TAG_FALLBACK -> {
                        if (sparkInvoiceFallback == null && data.firstOrNull() == SPARK_INVOICE_FALLBACK_VERSION) {
                            sparkInvoiceFallback = String(lenientBytes(data.drop(1)), Charsets.UTF_8)
                        }
                    }
                    // r: route hints; the sentinel hop names a Spark identity.
                    type == TAG_ROUTE_HINT -> {
                        if (routeHintFallback == null) routeHintFallback = sparkIdentityInRouteHint(lenientBytes(data))
                    }
                    // Unknown or wrongly-sized fields are skipped (BOLT-11).
                    else -> Unit
                }
            }
        }

        /** The public key (hex) of the first route-hint hop with the Spark sentinel short channel id. */
        private fun sparkIdentityInRouteHint(bytes: ByteArray): String? {
            var offset = 0
            while (offset + ROUTE_HINT_HOP_BYTES <= bytes.size) {
                if (bytes.copyOfRange(offset + 33, offset + 41).contentEquals(SPARK_IDENTITY_SHORT_CHANNEL_ID)) {
                    return bytes.copyOfRange(offset, offset + 33).toHexString()
                }
                offset += ROUTE_HINT_HOP_BYTES
            }
            return null
        }

        /** 5-bit words to bytes, ignoring leftover padding bits (the reference SDK's `fromWordsLenient`). */
        private fun lenientBytes(words: List<Int>): ByteArray {
            var accumulator = 0
            var bits = 0
            val result = java.io.ByteArrayOutputStream()
            for (word in words) {
                accumulator = ((accumulator shl 5) or (word and 0x1F)) and 0xFFFF
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    result.write((accumulator shr bits) and 0xFF)
                }
            }
            return result.toByteArray()
        }

        /** `ln` + currency + optional amount + optional multiplier. */
        private fun parseHRP(hrp: String): Pair<Network, ULong?> {
            if (!hrp.startsWith("ln")) {
                invalidInvoice("not a lightning invoice (prefix '$hrp')")
            }
            val rest = hrp.substring(2)
            // Longest prefixes first: "bcrt" before "bc", "tbs" before "tb".
            val (network, amountPart) = when {
                rest.startsWith("bcrt") -> Network.REGTEST to rest.substring(4)
                rest.startsWith("tbs") -> Network.SIGNET to rest.substring(3)
                rest.startsWith("tb") -> Network.TESTNET to rest.substring(2)
                rest.startsWith("bc") -> Network.MAINNET to rest.substring(2)
                else -> invalidInvoice("unknown currency prefix '$hrp'")
            }

            if (amountPart.isEmpty()) return network to null

            var digits = amountPart
            var multiplier: Char? = null
            val last = digits.last()
            if (!last.isDigit()) {
                multiplier = last
                digits = digits.dropLast(1)
            }
            if (digits.isEmpty() || !digits.all { it in '0'..'9' }) {
                invalidInvoice("malformed amount '$amountPart'")
            }
            if (digits.first() == '0') {
                invalidInvoice("amount has a leading zero")
            }
            val number = if (digits.length <= 19) digits.toULongOrNull() else null
            if (number == null || number == 0uL) {
                invalidInvoice("amount '$amountPart' is out of range")
            }

            // msat per unit: BTC = 1e11, m = 1e8, u = 1e5, n = 1e2, p = 1e-1.
            val msat = when (multiplier) {
                null -> checkedMultiply(number, 100_000_000_000uL, amountPart)
                'm' -> checkedMultiply(number, 100_000_000uL, amountPart)
                'u' -> checkedMultiply(number, 100_000uL, amountPart)
                'n' -> checkedMultiply(number, 100uL, amountPart)
                'p' -> {
                    if (number % 10uL != 0uL) {
                        invalidInvoice("pico-bitcoin amount '$amountPart' has sub-millisatoshi precision")
                    }
                    number / 10uL
                }
                else -> invalidInvoice("invalid amount multiplier '$multiplier'")
            }
            return network to msat
        }

        private fun checkedMultiply(a: ULong, b: ULong, amount: String): ULong {
            // a * b overflows exactly when a > ULong.MAX / b (b > 0).
            if (a > ULong.MAX_VALUE / b || a * b > MAX_SUPPLY_MSAT) {
                invalidInvoice("amount '$amount' exceeds the total bitcoin supply")
            }
            return a * b
        }

        /** Fixed-size byte field carried as 5-bit words (trailing padding bits are ignored). */
        private fun bytesFromWords(words: List<Int>, count: Int): ByteArray? {
            val bytes = Bech32.convertBits(words, fromBits = 5, toBits = 8, pad = true) ?: return null
            if (bytes.size < count) return null
            return ByteArray(count) { bytes[it].toByte() }
        }
    }
}

/**
 * A Lightning payment [payLightningInvoice] has checked before touching a leaf: a BOLT-11 invoice
 * for the wallet's network, the amount to pay and the fee cap.
 */
internal class LightningPayment(paymentRequest: String, val maxFeeSats: Long, amountSats: Long?, val idempotencyKey: String?, network: SparkNetwork) {
    /**
     * The invoice as validated, in the form sent on: surrounding whitespace dropped and lower case
     * (the reference SDK lower-cases it before use; the SSP refuses anything else).
     */
    val encodedInvoice: String
    val invoice: Bolt11Invoice

    /** Sats the invoice is paid with: its own amount, or the caller's for an amountless invoice. */
    val amountSats: Long

    init {
        if (maxFeeSats < 0) {
            throw SparkError.InvalidArgument("maxFeeSats must not be negative, got $maxFeeSats")
        }
        val trimmed = paymentRequest.trim()
        val decoded = Bolt11Invoice.decode(trimmed)
        if (!decoded.belongsTo(network)) {
            throw SparkError.InvalidInvoice("invoice is for ${decoded.network.label}, wallet is on ${network.networkString}")
        }
        // Decoded before lower-casing, so a mixed-case string is still refused.
        encodedInvoice = trimmed.lowercase()
        invoice = decoded
        this.amountSats = LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = decoded.amountMsat, requestedAmountSats = amountSats)
    }

    /** The caller's amount for an amountless invoice — the only case the SSP is told one. */
    val amountlessInvoiceAmountSats: Long?
        get() = if (invoice.amountMsat == null) amountSats else null
}

/** Client-side checks around lightning payments and invoices. */
internal object LightningValidator {

    private fun untrusted(message: String): Nothing = throw SparkError.UntrustedResponse(message)

    private fun refuse(message: String): Nothing = throw SparkError.InvalidArgument(message)

    /**
     * Resolve the amount to pay: the invoice amount (rounded up to whole sats), or the caller's
     * amount for an amountless invoice. A caller amount that contradicts the invoice is refused.
     */
    fun resolvePaymentAmountSats(invoiceAmountMsat: ULong?, requestedAmountSats: Long?): Long {
        if (invoiceAmountMsat != null) {
            // Rounded up without overflowing for amounts near ULong.MAX.
            val sats = invoiceAmountMsat / 1000uL + if (invoiceAmountMsat % 1000uL != 0uL) 1uL else 0uL
            if (sats == 0uL || sats > Long.MAX_VALUE.toULong()) {
                throw SparkError.InvalidInvoice("invoice amount $invoiceAmountMsat msat is out of range")
            }
            if (requestedAmountSats != null && requestedAmountSats != sats.toLong()) {
                throw SparkError.InvalidArgument(
                    "amountSats ($requestedAmountSats) does not match the invoice amount ($sats sats); " +
                        "omit it for invoices that carry an amount",
                )
            }
            return sats.toLong()
        }
        if (requestedAmountSats == null) {
            throw SparkError.InvalidArgument("the invoice has no amount; pass amountSats")
        }
        if (requestedAmountSats <= 0) {
            throw SparkError.InvalidArgument("amountSats must be positive, got $requestedAmountSats")
        }
        return requestedAmountSats
    }

    /**
     * Verify that an invoice the SSP created is the one we asked for: same network, same payment
     * hash, same amount. Run before preimage shares are stored and before the invoice is shown.
     */
    fun verifyCreatedInvoice(
        encodedInvoice: String,
        reportedPaymentHashHex: String?,
        expectedPaymentHash: ByteArray,
        expectedAmountSats: Long,
        network: SparkNetwork,
    ): Bolt11Invoice {
        val invoice = Bolt11Invoice.decode(encodedInvoice)
        if (!invoice.belongsTo(network)) {
            untrusted(
                "SSP returned an invoice for ${invoice.network.label}, wallet is on ${network.networkString}",
            )
        }
        if (!invoice.paymentHash.contentEquals(expectedPaymentHash)) {
            untrusted(
                "SSP invoice payment hash ${invoice.paymentHash.toHexString()} does not match ours ${expectedPaymentHash.toHexString()}",
            )
        }
        if (reportedPaymentHashHex != null && reportedPaymentHashHex.lowercase() != expectedPaymentHash.toHexString()) {
            untrusted(
                "SSP reported payment hash $reportedPaymentHashHex does not match ours ${expectedPaymentHash.toHexString()}",
            )
        }
        // The wallet never asks for a Spark fallback. One in the invoice would let a payer that
        // prefers Spark pay whoever it names instead of this wallet (reference SDK: "Spark fallback
        // address found in lightning invoice but includeSparkAddress is false").
        invoice.sparkFallback?.let { untrusted("SSP invoice carries a Spark fallback ($it) the wallet did not ask for") }
        if (expectedAmountSats == 0L) {
            if (invoice.amountMsat != null) {
                untrusted(
                    "SSP invoice carries an amount (${invoice.amountMsat} msat) but an amountless invoice was requested",
                )
            }
        } else {
            // expectedAmountSats is non-negative here (createLightningInvoice checks it), so the
            // product only overflows for amounts no invoice can carry.
            val expected = expectedAmountSats.toULong()
            val expectedMsat = if (expected <= ULong.MAX_VALUE / 1000uL) expected * 1000uL else null
            if (expectedMsat == null || invoice.amountMsat != expectedMsat) {
                untrusted(
                    "SSP invoice amount ${invoice.amountMsat ?: "none"} msat does not match the requested $expectedAmountSats sats",
                )
            }
        }
        return invoice
    }

    /**
     * The fee a Lightning send offers the SSP: its estimate, refused above the caller's cap — the
     * reference SDK's `maxFeeSats < feeEstimate` check. No floor: an estimate of 0 is offered as
     * 0, so `maxFeeSats = estimate` always goes through.
     */
    fun sendFeeSats(estimate: Long, maxFeeSats: Long): Long {
        if (estimate < 0) {
            throw SparkError.InvalidResponse("negative lightning fee estimate: $estimate")
        }
        if (estimate > maxFeeSats) {
            throw SparkError.FeeExceedsLimit(feeSats = estimate, maxFeeSats = maxFeeSats)
        }
        return estimate
    }

    /**
     * Check the Lightning send the coordinator holds under a transfer id the caller is resuming,
     * before the SSP is asked to pay from it: this wallet's HTLC to the SSP for this invoice's
     * payment hash, neither returned nor expired, whose leaves cover the amount with at most
     * `maxFeeSats` on top — what the SSP keeps beyond the invoice.
     */
    fun verifyHeldSend(
        held: spark.Spark.PreimageRequestWithTransfer,
        transferId: String,
        payment: LightningPayment,
        identityPublicKey: ByteArray,
        sspIdentityPublicKey: ByteArray,
    ) {
        val transfer = held.transfer
        val ours = held.hasTransfer() &&
            transfer.id.lowercase() == transferId &&
            transfer.type == spark.Spark.TransferType.PREIMAGE_SWAP &&
            held.senderIdentityPubkey.toByteArray().contentEquals(identityPublicKey) &&
            transfer.senderIdentityPublicKey.toByteArray().contentEquals(identityPublicKey) &&
            held.receiverIdentityPubkey.toByteArray().contentEquals(sspIdentityPublicKey) &&
            transfer.receiverIdentityPublicKey.toByteArray().contentEquals(sspIdentityPublicKey)
        if (!ours) {
            refuse("transfer $transferId is not a Lightning send from this wallet to the SSP")
        }
        if (!held.paymentHash.toByteArray().contentEquals(payment.invoice.paymentHash)) {
            refuse(
                "transfer $transferId pays another invoice (payment hash ${held.paymentHash.toByteArray().toHexString()})",
            )
        }
        if (held.status == spark.Spark.PreimageRequestStatus.PREIMAGE_REQUEST_STATUS_RETURNED ||
            transfer.status == spark.Spark.TransferStatus.TRANSFER_STATUS_RETURNED ||
            transfer.status == spark.Spark.TransferStatus.TRANSFER_STATUS_EXPIRED
        ) {
            refuse(
                "the Lightning send of transfer $transferId failed and was returned; pay again with a new transferId",
            )
        }
        // total_value is a uint64: compared unsigned, so no value can pass as a small one.
        val total = transfer.totalValue.toULong()
        val amount = payment.amountSats.toULong()
        if (total < amount) {
            refuse("transfer $transferId holds $total sats, less than the ${payment.amountSats} sats to pay")
        }
        val feeSats = total - amount
        if (feeSats > payment.maxFeeSats.toULong()) {
            val reported = if (feeSats > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else feeSats.toLong()
            throw SparkError.FeeExceedsLimit(feeSats = reported, maxFeeSats = payment.maxFeeSats)
        }
    }

    /** Lower-cased UUID for a caller-supplied transfer id used to resume a lightning send. */
    fun normalizeTransferId(transferId: String?): String? {
        if (transferId == null) return null
        return parseUuid(transferId)?.toString()?.lowercase()
            ?: throw SparkError.InvalidArgument("transferId must be a UUID, got '$transferId'")
    }

    /**
     * Strict 8-4-4-4-12 hex UUID, like Foundation's `UUID(uuidString:)`. `java.util.UUID.fromString`
     * alone also accepts shortened groups such as `1-2-3-4-5`.
     */
    private fun parseUuid(value: String): UUID? {
        val groups = value.split('-')
        if (groups.map { it.length } != listOf(8, 4, 4, 4, 12)) return null
        if (!groups.all { group -> group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }) return null
        return UUID.fromString(value)
    }
}
