package gy.pig.spark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * BOLT-11 decoding against the specification's test vectors, plus the client-side checks on
 * lightning sends and SSP-created invoices. Ported from the Swift SDK's
 * `LightningValidationTests.swift`, same vectors.
 */
class Bolt11InvoiceTests {

    companion object {
        const val SPEC_PAYMENT_HASH = "0001020304050607080900010203040506070809000102030405060708090102"
        const val SPEC_TIMESTAMP: Long = 1_496_314_658

        const val DONATION =
            "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcy" +
                "q5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlq" +
                "nls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"

        const val COFFEE_2500U =
            "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqq" +
                "syqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53u" +
                "t353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"

        const val LIST_20M =
            "lnbc20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsy" +
                "qcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfn" +
                "h8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp7ynn44"

        const val TESTNET_20M =
            "lntb20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98" +
                "klysy043l2ahrqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un989qrs" +
                "gqdj545axuxtnfemtpwkc45hx9d2ft7x04mt8q7y6t0k2dge9e7h8kpy9p34ytyslj3yu569aalz2xdk8xkd7ltxqld94u8h2esmsmacgpghe9" +
                "k8"

        const val PICO =
            "lnbc9678785340p1pwmna7lpp5gc3xfm08u9qy06djf8dfflhugl6p7lgza6dsjxq454gxhj9t7a0sd8dgfkx7cmtwd68yetpd5s9xar0wfjn5" +
                "gpc8qhrsdfq24f5ggrxdaezqsnvda3kkum5wfjkzmfqf3jkgem9wgsyuctwdus9xgrcyqcjcgpzgfskx6eqf9hzqnteypzxz7fzypfhg6trddj" +
                "hygrcyqezcgpzfysywmm5ypxxjemgw3hxjmn8yptk7untd9hxwg3q2d6xjcmtv4ezq7pqxgsxzmnyyqcjqmt0wfjjq6t5v4khxsp5zyg3zyg3z" +
                "yg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygsxqyjw5qcqp2rzjq0gxwkzc8w6323m55m4jyxcjwmy7stt9hwkwe2qxmy8zpsgg7jcuw" +
                "z87fcqqeuqqqyqqqqlgqqqqn3qq9q9qrsgqrvgkpnmps664wgkp43l22qsgdw4ve24aca4nymnxddlnp8vh9v2sdxlu5ywdxefsfvm0fq3sesf" +
                "08uf6q9a2ke0hc9j6z6wlxg5z5kqpu2v9wz"

        const val UPPER_25M =
            "LNBC25M1PVJLUEZPP5QQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQQQSYQCYQ5RQWZQFQYPQDQ5VDHKVEN9V5SXYETPDEESSP5ZYG3ZYG3ZYG3ZY" +
                "G3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYG3ZYGS9Q5SQQQQQQQQQQQQQQQQSGQ2A25DXL5HRNTDTN6ZVYDT7D66HYZSYHQS4WDYNAVYS42XGL6S" +
                "GX9C4G7ME86A27T07MDTFRY458RTJR0V92CNMSWPSJSCGT2VCSE3SGPZ3UAPA"

        /**
         * The reference SDK's vector (`bolt11-spark.test.ts`): a mainnet invoice whose sentinel
         * route hint carries the receiver's Spark identity.
         */
        const val SPARK_ROUTE_HINT_INVOICE =
            "lnbc13u1p5xalmkpp5z79uwgne7znz76plf0q4zxmh8t3wke6gsnm5kn67h4satpgflkmssp5azht5ywc5s4m40jf9h0nwlr959a34n72pns50lfm93zz8lv" +
                "s7nqsxq9z0rgqnp4q0p92sfan5vj2a4f8q3gsfsy8qp60maeuxz858c5x0hvt5u0p0h9jr9yqtqd37k2ya0pv8pqeyjs4lklcexjyw600g9qqp62r4j0ph8f" +
                "cmlfwqqqqzfv7u6g85qqqqqqqqqqthqq9qpz9cat0ndmwmfx036y9fxfhdufta3mn95ta9xw34ynlwg7euxjck85ysq0gfqqqqq7u6egqrhxk2qqn3qqcqzp" +
                "gdq2w3jhxap3xv9qyyssqfahd64hu0lffl7cw2e4evu400s09yeupypvnfjvjjyq8rh05y9gzd3dqnmkvuyd9jszyhmdey75dujz8xaufgahsxkqktf3wxny" +
                "8ghsqpk4mg8"

        /** The specification's 20m vector without its `s` field. */
        const val LIST_20M_WITHOUT_SECRET =
            "lnbc20m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqhp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2" +
                "ahrqs9qrsgq7ea976txfraylvgzuxs8kgcw23ezlrszfnh8r6qtfpr6cxga50aj6txm9rxrydzd06dfeawfk6swupvz4erwnyutnjq7x39ymw6j38gp49qdk" +
                "j"

        const val METADATA_10M =
            "lnbc10m1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdp9wpshjmt9de6zqmt9w3skgct5vysxjmnnd9jx2" +
                "mq8q8a04uqsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9q2gqqqqqqsgq7hf8he7ecf7n4ffphs6awl9t6676rrcl" +
                "v9ckg3d3ncn7fct63p6s365duk5wrk202cfy3aj5xnnp5gs3vrdvruverwwq7yzhkf5a3xqpd05wjc"
    }

    @Test
    fun specificationVectorsDecodeWithTheRightNetworkAmountHashAndTimestamp() {
        val donation = Bolt11Invoice.decode(DONATION)
        assertEquals(Bolt11Invoice.Network.MAINNET, donation.network)
        assertNull(donation.amountMsat)
        assertEquals(SPEC_PAYMENT_HASH, donation.paymentHash.toHexString())
        assertEquals(SPEC_TIMESTAMP.toULong(), donation.timestamp)
        assertEquals(3600uL, donation.expirySeconds)
        assertEquals("11".repeat(32), donation.paymentSecret.toHexString())
        assertEquals("Please consider supporting this project", donation.description)

        val coffee = Bolt11Invoice.decode(COFFEE_2500U)
        assertEquals(250_000_000uL, coffee.amountMsat)
        assertEquals(250_000L, LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = coffee.amountMsat, requestedAmountSats = null))
        assertEquals(60uL, coffee.expirySeconds)
        assertEquals("1 cup coffee", coffee.description)
        assertEquals(Date((SPEC_TIMESTAMP + 60) * 1000), coffee.expiresAt)

        val list = Bolt11Invoice.decode(LIST_20M)
        assertEquals(2_000_000_000uL, list.amountMsat)
        assertEquals(SPEC_PAYMENT_HASH, list.paymentHash.toHexString())

        val testnet = Bolt11Invoice.decode(TESTNET_20M)
        assertEquals(Bolt11Invoice.Network.TESTNET, testnet.network)
        assertEquals(2_000_000_000uL, testnet.amountMsat)

        val pico = Bolt11Invoice.decode(PICO)
        assertEquals(967_878_534uL, pico.amountMsat)
        assertEquals(967_879L, LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = pico.amountMsat, requestedAmountSats = null))
        assertEquals("462264ede7e14047e9b249da94fefc47f41f7d02ee9b091815a5506bc8abf75f", pico.paymentHash.toHexString())
        assertEquals(1_572_468_703uL, pico.timestamp)
        assertEquals(604_800uL, pico.expirySeconds)

        val upper = Bolt11Invoice.decode(UPPER_25M)
        assertEquals(2_500_000_000uL, upper.amountMsat)
        assertEquals(SPEC_PAYMENT_HASH, upper.paymentHash.toHexString())

        val withMetadata = Bolt11Invoice.decode(METADATA_10M)
        assertEquals(1_000_000_000uL, withMetadata.amountMsat)

        // Surrounding whitespace is tolerated.
        assertEquals(coffee, Bolt11Invoice.decode("  $COFFEE_2500U\n"))
    }

    @Test
    fun aSparkIdentityInTheSentinelRouteHintIsDecodedAsInTheReferenceSdksVector() {
        val invoice = Bolt11Invoice.decode(SPARK_ROUTE_HINT_INVOICE)
        assertEquals("0222e3ab7cdbb76d267c7442a4c9bb7895f63b9968be94ce8d493fb91ecf0d2c58", invoice.sparkFallback)
        assertEquals(1_300_000uL, invoice.amountMsat)
        assertEquals("178bc72279f0a62f683f4bc1511b773ae2eb674884f74b4f5ebd61d58509fdb7", invoice.paymentHash.toHexString())
        // An on-chain fallback address (version 17, P2PKH) is not a Spark fallback.
        assertNull(Bolt11Invoice.decode(TESTNET_20M).sparkFallback)
        assertNull(Bolt11Invoice.decode(COFFEE_2500U).sparkFallback)
    }

    @Test
    fun aSparkInvoiceInAVersion31FallbackAddressFieldIsDecoded() {
        val sparkInvoice = "spark1pgssyut2gu37y00dg7pf5d2uc6nm00tdu4xujpmfykg24mjy9rzvt4w3me9q6g"
        val decoded = Bech32.decode(COFFEE_2500U, maxLength = null)
        val fieldWords = listOf(31) + Bech32.toWords(sparkInvoice.toByteArray())
        val tagged = listOf(9, fieldWords.size / 32, fieldWords.size % 32) + fieldWords
        // Tagged fields follow the 7-word timestamp; their order does not matter.
        val rebuilt = Bech32.encode(decoded.hrp, decoded.data.take(7) + tagged + decoded.data.drop(7), Bech32.Encoding.BECH32)
        assertEquals(sparkInvoice, Bolt11Invoice.decode(rebuilt).sparkFallback)
    }

    @Test
    fun anInvoiceWithoutAPaymentSecretIsRefusedAsBolt11ReadersAndTheReferenceSdkDo() {
        val missing = expectSparkError { Bolt11Invoice.decode(LIST_20M_WITHOUT_SECRET) }
        assertTrue(missing is SparkError.InvalidInvoice && missing.message.contains("payment secret"))
        // An s field of the wrong length is skipped as BOLT-11 requires, which leaves none.
        val decoded = Bech32.decode(COFFEE_2500U, maxLength = null)
        val fields = decoded.data.drop(7).dropLast(104)
        val rebuilt = mutableListOf<Int>()
        var position = 0
        while (position + 3 <= fields.size) {
            val length = fields[position + 1] * 32 + fields[position + 2]
            val field = fields.subList(position, position + 3 + length)
            // Shorten the 52-word payment secret to 51 words.
            rebuilt += if (field[0] == 16) listOf(16, 1, 19) + field.subList(3, field.size - 1) else field
            position += 3 + length
        }
        val shortSecret = Bech32.encode(decoded.hrp, decoded.data.take(7) + rebuilt + decoded.data.takeLast(104), Bech32.Encoding.BECH32)
        val short = expectSparkError { Bolt11Invoice.decode(shortSecret) }
        assertTrue(short is SparkError.InvalidInvoice && short.message.contains("payment secret"))
    }

    @Test
    fun specificationsInvalidInvoicesAreRejected() {
        val invalid = listOf(
            // Bech32 checksum is invalid.
            "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqz" +
                "puyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsr" +
                "nt",
            // Malformed bech32 string (no 1)
            "pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqzpuyk0sg5g7" +
                "0me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsrny",
            // Malformed bech32 string (mixed case)
            "LNBC2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpquwpc4curk03c9wlrswe78q4eyqc7d8d0xqz" +
                "puyk0sg5g70me25alkluzd2x62aysf2pyy8edtjeevuv4p2d5p76r4zkmneet7uvyakky2zr4cusd45tftc9c5fh0nnqpnl2jfll544esqchsr" +
                "ny",
            // String is too short.
            "lnbc1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6na" +
                "6hlh",
            // Invalid multiplier
            "lnbc2500x1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5zyg3zyg" +
                "3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgqrrzc4cvfue4zp3hggxp47ag7xnrlr8vgcmkjxk3j5jqethnumgkpqp23z9j" +
                "clu3v0a7e0aruz366e9wqdykw6dxhdzcjjhldxq0w6wgqcnu43j",
            // Invalid sub-millisatoshi precision.
            "lnbc2500000001p1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpusp5z" +
                "yg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygs9qrsgq0lzc236j96a95uv0m3umg28gclm5lqxtqqwk32uuk4k6673k6n5kf" +
                "vx3d2h8s295fad45fdhmusm8sjudfhlf6dcsxmfvkeywmjdkxcp99202x",
            // Missing required s field (the reference SDK's "invalid payment secret" vector).
            LIST_20M_WITHOUT_SECRET,
            "",
            "lnbc",
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
        )
        for (invoice in invalid) {
            expectSparkError(invoice.take(24)) { Bolt11Invoice.decode(invoice) }
        }
    }

    /**
     * Re-encode a valid invoice's data part under a different human-readable part so the
     * checksum is valid and only the prefix under test changes.
     */
    private fun withHRP(hrp: String, invoice: String, encoding: Bech32.Encoding = Bech32.Encoding.BECH32): String =
        Bech32.encode(hrp, Bech32.decode(invoice, maxLength = null).data, encoding)

    @Test
    fun hostileAmountsThrowInsteadOfOverflowingAndOtherPrefixesAreRefused() {
        val base = COFFEE_2500U
        for (hrp in listOf(
            "lnbc9223372036854775807m", // Int64.max × 1e8 — trapped in the old Swift parser
            "lnbc18446744073709551615", // UInt64.max BTC
            "lnbc99999999999999999999u", // more than 19 digits
            "lnbc21000001", // more than the supply
            "lnbc0250u",
            "lnbc0", // leading zero / zero
            "lnbc25.0m",
            "lnbc-1m",
            "lnbc2500um",
            "lnxx2500u",
            "ln2500u",
            "bc2500u",
        )) {
            expectSparkError(hrp) { Bolt11Invoice.decode(withHRP(hrp, base)) }
        }
        // Boundary: the whole supply in BTC decodes, one satoshi above it does not.
        assertEquals(21_000_000uL * 100_000_000_000uL, Bolt11Invoice.decode(withHRP("lnbc21000000", base)).amountMsat)
        assertEquals(100_000_000_000uL, Bolt11Invoice.decode(withHRP("lnbc1", base)).amountMsat)
        assertEquals(1_000uL, Bolt11Invoice.decode(withHRP("lnbc10n", base)).amountMsat)
        assertEquals(1uL, Bolt11Invoice.decode(withHRP("lnbc10p", base)).amountMsat)
        // Currency prefixes, longest match first.
        assertEquals(Bolt11Invoice.Network.REGTEST, Bolt11Invoice.decode(withHRP("lnbcrt2500u", base)).network)
        assertEquals(Bolt11Invoice.Network.SIGNET, Bolt11Invoice.decode(withHRP("lntbs2500u", base)).network)
        assertEquals(Bolt11Invoice.Network.TESTNET, Bolt11Invoice.decode(withHRP("lntb2500u", base)).network)
        // A bech32m checksum is not a BOLT-11 invoice.
        expectSparkError { Bolt11Invoice.decode(withHRP("lnbc2500u", base, Bech32.Encoding.BECH32M)) }
    }

    @Test
    fun networkMatchingAgainstTheWalletsNetwork() {
        val mainnet = Bolt11Invoice.decode(COFFEE_2500U)
        assertTrue(mainnet.belongsTo(SparkNetwork.MAINNET))
        assertFalse(mainnet.belongsTo(SparkNetwork.REGTEST))
        val regtest = Bolt11Invoice.decode(withHRP("lnbcrt2500u", COFFEE_2500U))
        assertTrue(regtest.belongsTo(SparkNetwork.REGTEST))
        assertFalse(regtest.belongsTo(SparkNetwork.MAINNET))
        val testnet = Bolt11Invoice.decode(TESTNET_20M)
        assertFalse(testnet.belongsTo(SparkNetwork.MAINNET))
        assertFalse(testnet.belongsTo(SparkNetwork.REGTEST))
    }
}

/** Lightning send and receive validation */
class LightningValidatorTests {

    @Test
    fun paymentAmountComesFromTheInvoiceOrFromTheCallerOnlyForAmountlessInvoices() {
        assertEquals(250_000L, LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = 250_000_000uL, requestedAmountSats = null))
        assertEquals(250_000L, LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = 250_000_000uL, requestedAmountSats = 250_000))
        assertEquals(2L, LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = 1_500uL, requestedAmountSats = null))
        assertEquals(500L, LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = null, requestedAmountSats = 500))

        expectSparkError { LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = 250_000_000uL, requestedAmountSats = 1) }
        expectSparkError { LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = null, requestedAmountSats = null) }
        expectSparkError { LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = null, requestedAmountSats = 0) }
        expectSparkError { LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = null, requestedAmountSats = -5) }
        expectSparkError { LightningValidator.resolvePaymentAmountSats(invoiceAmountMsat = 0uL, requestedAmountSats = null) }
    }

    @Test
    fun anSspCreatedInvoiceMustCarryOurPaymentHashAmountAndNetwork() {
        val hash = hex(Bolt11InvoiceTests.SPEC_PAYMENT_HASH)
        val coffee = Bolt11InvoiceTests.COFFEE_2500U
        val ok = LightningValidator.verifyCreatedInvoice(
            encodedInvoice = coffee,
            reportedPaymentHashHex = Bolt11InvoiceTests.SPEC_PAYMENT_HASH,
            expectedPaymentHash = hash,
            expectedAmountSats = 250_000,
            network = SparkNetwork.MAINNET,
        )
        assertEquals(250_000_000uL, ok.amountMsat)
        // The SSP's reported hash is optional and case-insensitive.
        LightningValidator.verifyCreatedInvoice(coffee, null, hash, 250_000, SparkNetwork.MAINNET)
        LightningValidator.verifyCreatedInvoice(coffee, Bolt11InvoiceTests.SPEC_PAYMENT_HASH.uppercase(), hash, 250_000, SparkNetwork.MAINNET)
        // Amountless invoice for an amountless request.
        LightningValidator.verifyCreatedInvoice(Bolt11InvoiceTests.DONATION, null, hash, 0, SparkNetwork.MAINNET)

        val wrongHash = bytes(0xAB, 32)
        expectSparkError { LightningValidator.verifyCreatedInvoice(coffee, null, wrongHash, 250_000, SparkNetwork.MAINNET) }
        expectSparkError { LightningValidator.verifyCreatedInvoice(coffee, wrongHash.toHexString(), hash, 250_000, SparkNetwork.MAINNET) }
        expectSparkError { LightningValidator.verifyCreatedInvoice(coffee, null, hash, 250_001, SparkNetwork.MAINNET) }
        expectSparkError { LightningValidator.verifyCreatedInvoice(coffee, null, hash, 0, SparkNetwork.MAINNET) }
        expectSparkError { LightningValidator.verifyCreatedInvoice(Bolt11InvoiceTests.DONATION, null, hash, 100, SparkNetwork.MAINNET) }
        expectSparkError { LightningValidator.verifyCreatedInvoice(coffee, null, hash, 250_000, SparkNetwork.REGTEST) }
        expectSparkError { LightningValidator.verifyCreatedInvoice("garbage", null, hash, 250_000, SparkNetwork.MAINNET) }
    }

    @Test
    fun resumableTransferIdsMustBeUuidsAndAreNormalisedToLowerCase() {
        assertNull(LightningValidator.normalizeTransferId(null))
        assertEquals(
            "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
            LightningValidator.normalizeTransferId("0190A1B2-C3D4-7E5F-8A9B-0C1D2E3F4A5B"),
        )
        expectSparkError { LightningValidator.normalizeTransferId("not-a-uuid") }
        expectSparkError { LightningValidator.normalizeTransferId("") }
        // java.util.UUID.fromString alone would accept this shortened form; Foundation does not.
        expectSparkError { LightningValidator.normalizeTransferId("1-2-3-4-5") }
    }

    @Test
    fun aLightningSendsPreimageSwapCarriesOnlyTheHtlcTransferRequest() {
        val transferRequest = spark.Spark.StartTransferRequest.newBuilder()
            .setTransferId("0199a8f0-0000-7000-8000-000000000001")
            .setReceiverIdentityPublicKey((byteArrayOf(0x02) + bytes(0xAA, 32)).toByteString())
            .setTransferPackage(spark.Spark.TransferPackage.newBuilder().setUserSignature(byteArrayOf(1, 2, 3).toByteString()))
            .build()
        val request = preimageSwapRequest(
            paymentHash = bytes(0x42, 32),
            invoiceAmountSats = 12,
            bolt11Invoice = "lnbc120n1...",
            feeSats = 2,
            transferRequest = transferRequest,
        )
        // The legacy `transfer` field is reserved in the protocol and absent from the generated request.
        assertEquals(transferRequest, request.transferRequest)
        assertEquals(transferRequest.receiverIdentityPublicKey, request.receiverIdentityPublicKey)
        assertEquals(spark.Spark.InitiatePreimageSwapRequest.Reason.REASON_SEND, request.reason)
        assertEquals(2L, request.feeSats)
        assertEquals(12L, request.invoiceAmount.valueSats)
        assertEquals("lnbc120n1...", request.invoiceAmount.invoiceAmountProof.bolt11Invoice)
    }

    @Test
    fun anSspCreatedInvoiceThatCarriesASparkFallbackIsRefused() {
        val error = expectSparkError {
            LightningValidator.verifyCreatedInvoice(
                encodedInvoice = Bolt11InvoiceTests.SPARK_ROUTE_HINT_INVOICE,
                reportedPaymentHashHex = null,
                expectedPaymentHash = hex("178bc72279f0a62f683f4bc1511b773ae2eb674884f74b4f5ebd61d58509fdb7"),
                expectedAmountSats = 1_300,
                network = SparkNetwork.MAINNET,
            )
        }
        assertTrue(error is SparkError.UntrustedResponse && error.message.contains("Spark fallback"))
    }

    @Test
    fun theSspGetsAmountSatsForAnAmountlessInvoiceOnlyAndOneOfIdempotencyKeyOrTransferId() {
        val amountless = lightningSendVariables("lnbc1...", amountlessInvoiceAmountSats = 1_000, idempotencyKey = null, transferId = "t")
        assertEquals(1_000L, amountless["amount_sats"])
        assertEquals("t", amountless["user_outbound_transfer_external_id"])
        assertNull(amountless["idempotency_key"])

        val fixed = lightningSendVariables("lnbc10n1...", amountlessInvoiceAmountSats = null, idempotencyKey = "key", transferId = "t")
        assertNull(fixed["amount_sats"])
        assertEquals("key", fixed["idempotency_key"])
        assertNull(fixed["user_outbound_transfer_external_id"])

        // The mutation declares the variable and passes it to the input (RequestLightningSendInput).
        assertTrue(GraphQLMutations.REQUEST_LIGHTNING_SEND.contains("\$amount_sats: Long"))
        assertTrue(GraphQLMutations.REQUEST_LIGHTNING_SEND.contains("amount_sats: \$amount_sats"))
    }

    private fun operatorKeys(count: Int): List<ByteArray> = (0 until count).map { uniffi.spark_frost.randomSecretKeyBytes() }

    private fun shareIndexConfig(identifiers: List<Int>, keys: List<ByteArray>): SparkConfig = SparkConfig(
        network = SparkNetwork.MAINNET,
        signingOperators = identifiers.zip(keys).map { (identifier, key) ->
            SigningOperatorConfig(
                address = "https://$identifier.example",
                identifier = "%064x".format(identifier),
                identityPublicKeyHex = KeyDerivation.compressedPublicKey(key).toHexString(),
            )
        },
    )

    @Test
    fun preimageSharesAreEncryptedToEachOperatorsKeyRecoverThePreimageAndCarryNoUserSignature() {
        NativeFrost.assume()
        val keys = operatorKeys(3)
        val config = shareIndexConfig(listOf(1, 2, 3), keys)
        val preimage = uniffi.spark_frost.randomSecretKeyBytes()
        val shares = uniffi.spark_frost.splitSecretWithProofsUniffi(preimage, config.signingThreshold, 3u)
        val request = storePreimageShareRequest(
            paymentHash = sha256(preimage),
            shares = shares,
            encodedInvoice = "lnbc1...",
            identityPublicKey = KeyDerivation.compressedPublicKey(keys[0]),
            config = config,
        )
        // No user signature: the protocol reserves the field, so the generated request has none.
        assertEquals(config.signingThreshold.toInt(), request.threshold)
        assertEquals("lnbc1...", request.invoiceString)
        val recovered = config.signingOperators.mapIndexed { index, signingOperator ->
            val encrypted = request.encryptedPreimageSharesMap.getValue(signingOperator.identifier).toByteArray()
            val share = spark.Spark.SecretShare.parseFrom(uniffi.spark_frost.decryptEcies(encrypted, keys[index]))
            assertEquals(config.signingThreshold.toInt(), share.proofsCount)
            uniffi.spark_frost.SecretShareResult(config.signingThreshold, (index + 1).toUInt(), share.secretShare.toByteArray())
        }
        assertTrue(preimage.contentEquals(uniffi.spark_frost.recoverSecretUniffi(recovered.take(2))))
        assertTrue(preimage.contentEquals(uniffi.spark_frost.recoverSecretUniffi(recovered.takeLast(2))))
    }

    @Test
    fun eachOperatorGetsThePreimageShareAtItsOwnIndexWhateverTheConfiguredOrder() {
        NativeFrost.assume()
        val keys = operatorKeys(3)
        // Operators listed out of order: identifiers 3, 1, 2.
        val config = shareIndexConfig(listOf(3, 1, 2), keys)
        val preimage = uniffi.spark_frost.randomSecretKeyBytes()
        val shares = uniffi.spark_frost.splitSecretWithProofsUniffi(preimage, config.signingThreshold, 3u)
        val request = storePreimageShareRequest(sha256(preimage), shares, "lnbc1...", KeyDerivation.compressedPublicKey(keys[0]), config)
        // Recover with the index each operator validates at (its identifier): only a correct
        // pairing reproduces the preimage.
        val recovered = config.signingOperators.zip(keys).map { (signingOperator, key) ->
            val encrypted = request.encryptedPreimageSharesMap.getValue(signingOperator.identifier).toByteArray()
            val share = spark.Spark.SecretShare.parseFrom(uniffi.spark_frost.decryptEcies(encrypted, key))
            uniffi.spark_frost.SecretShareResult(config.signingThreshold, operatorShareIndex(signingOperator.identifier)!!, share.secretShare.toByteArray())
        }
        assertTrue(preimage.contentEquals(uniffi.spark_frost.recoverSecretUniffi(recovered.take(2))))
        assertTrue(preimage.contentEquals(uniffi.spark_frost.recoverSecretUniffi(listOf(recovered[0], recovered[2]))))
    }

    @Test
    fun operatorShareIndexesComeFromTheIdentifier() {
        assertEquals(2u, operatorShareIndex("%064x".format(2)))
        assertNull(operatorShareIndex("0".repeat(64)))
        assertNull(operatorShareIndex("01"))
        assertNull(operatorShareIndex("f".repeat(64)))
        assertNull(operatorShareIndex("g" + "0".repeat(63)))
    }

    @Test
    fun theSspIsOfferedItsFeeEstimateAsIsAndAnEstimateAboveTheCapIsRefused() {
        assertEquals(0L, LightningValidator.sendFeeSats(estimate = 0, maxFeeSats = 0))
        assertEquals(2L, LightningValidator.sendFeeSats(estimate = 2, maxFeeSats = 2))
        assertEquals(2L, LightningValidator.sendFeeSats(estimate = 2, maxFeeSats = 50))
        val above = expectSparkError { LightningValidator.sendFeeSats(estimate = 3, maxFeeSats = 2) }
        assertEquals(SparkError.FeeExceedsLimit(feeSats = 3, maxFeeSats = 2), above)
        expectSparkError { LightningValidator.sendFeeSats(estimate = -1, maxFeeSats = 5) }
    }

    @Test
    fun aPaymentForwardsTheInvoiceItValidatedTrimmedAndLowerCaseMixedCaseStillRefused() {
        val upper =
            LightningPayment(" ${Bolt11InvoiceTests.UPPER_25M}\n", maxFeeSats = 5, amountSats = null, idempotencyKey = null, network = SparkNetwork.MAINNET)
        assertEquals(Bolt11InvoiceTests.UPPER_25M.lowercase(), upper.encodedInvoice)
        assertEquals(upper.invoice, Bolt11Invoice.decode(upper.encodedInvoice))
        assertEquals(2_500_000L, upper.amountSats)
        val lower = LightningPayment(Bolt11InvoiceTests.COFFEE_2500U, maxFeeSats = 5, amountSats = null, idempotencyKey = null, network = SparkNetwork.MAINNET)
        assertEquals(Bolt11InvoiceTests.COFFEE_2500U, lower.encodedInvoice)
        val mixed = "lnbc" + Bolt11InvoiceTests.COFFEE_2500U.drop(4).uppercase()
        expectSparkError { LightningPayment(mixed, maxFeeSats = 5, amountSats = null, idempotencyKey = null, network = SparkNetwork.MAINNET) }
        expectSparkError {
            LightningPayment(Bolt11InvoiceTests.COFFEE_2500U, maxFeeSats = -1, amountSats = null, idempotencyKey = null, network = SparkNetwork.MAINNET)
        }
        // Everything the SSP and the coordinator are sent carries that form.
        assertEquals(
            Bolt11InvoiceTests.UPPER_25M.lowercase(),
            lightningSendVariables(upper.encodedInvoice, amountlessInvoiceAmountSats = null, idempotencyKey = null, transferId = "t")["encoded_invoice"],
        )
    }

    @Test
    fun everyPreimageSwapCarriesAnIdempotencyKeyTheCallersElseTheTransferId() {
        assertEquals("t", preimageSwapIdempotencyKey(idempotencyKey = null, transferId = "t"))
        assertEquals("key", preimageSwapIdempotencyKey(idempotencyKey = "key", transferId = "t"))
    }

    @Test
    fun aResumedSendMustBeThisWalletsHtlcToTheSspForThisInvoiceNotReturnedWithinTheFeeCap() {
        val identity = byteArrayOf(0x02) + bytes(0x11, 32)
        val ssp = byteArrayOf(0x03) + bytes(0x22, 32)
        val transferId = LightningResumeTests.TRANSFER_ID
        val payment =
            LightningPayment(Bolt11InvoiceTests.COFFEE_2500U, maxFeeSats = 5, amountSats = null, idempotencyKey = null, network = SparkNetwork.MAINNET)
        val held = LightningResumeTests.heldSend(identity = identity, ssp = ssp, paymentHash = payment.invoice.paymentHash, totalValue = 250_002)
        fun verify(candidate: spark.Spark.PreimageRequestWithTransfer) =
            LightningValidator.verifyHeldSend(candidate, transferId, payment, identityPublicKey = identity, sspIdentityPublicKey = ssp)
        verify(held)
        // A send that went through resumes too: the SSP answers with the request it already has.
        verify(
            held.toBuilder()
                .setStatus(spark.Spark.PreimageRequestStatus.PREIMAGE_REQUEST_STATUS_PREIMAGE_SHARED)
                .setTransfer(held.transfer.toBuilder().setStatus(spark.Spark.TransferStatus.TRANSFER_STATUS_COMPLETED))
                .build(),
        )
        verify(held.toBuilder().setTransfer(held.transfer.toBuilder().setTotalValue(250_005)).build())

        val refused = listOf(
            "another invoice" to held.toBuilder().setPaymentHash(bytes(0xAB, 32).toByteString()).build(),
            "another transfer id" to held.toBuilder().setTransfer(held.transfer.toBuilder().setId("0199a8f0-0000-7000-8000-000000000002")).build(),
            "HTLC to someone else" to held.toBuilder().setReceiverIdentityPubkey(identity.toByteString()).build(),
            "transfer to someone else" to held.toBuilder().setTransfer(held.transfer.toBuilder().setReceiverIdentityPublicKey(identity.toByteString())).build(),
            "someone else's HTLC" to held.toBuilder().setSenderIdentityPubkey(ssp.toByteString()).build(),
            "not a preimage swap" to held.toBuilder().setTransfer(held.transfer.toBuilder().setType(spark.Spark.TransferType.TRANSFER)).build(),
            "no transfer" to held.toBuilder().clearTransfer().build(),
            "HTLC returned" to held.toBuilder().setStatus(spark.Spark.PreimageRequestStatus.PREIMAGE_REQUEST_STATUS_RETURNED).build(),
            "transfer returned" to
                held.toBuilder().setTransfer(held.transfer.toBuilder().setStatus(spark.Spark.TransferStatus.TRANSFER_STATUS_RETURNED)).build(),
            "transfer expired" to held.toBuilder().setTransfer(held.transfer.toBuilder().setStatus(spark.Spark.TransferStatus.TRANSFER_STATUS_EXPIRED)).build(),
            "less than the amount" to held.toBuilder().setTransfer(held.transfer.toBuilder().setTotalValue(249_999)).build(),
            // A uint64 total of 2^63 or more reads as negative here; it must not pass as small.
            "a hostile total" to held.toBuilder().setTransfer(held.transfer.toBuilder().setTotalValue(-1L)).build(),
        )
        for ((label, candidate) in refused) {
            expectSparkError(label) { verify(candidate) }
        }

        // Above the cap the SSP would keep more than maxFeeSats.
        val overCap = expectSparkError { verify(held.toBuilder().setTransfer(held.transfer.toBuilder().setTotalValue(250_006)).build()) }
        assertEquals(SparkError.FeeExceedsLimit(feeSats = 6, maxFeeSats = 5), overCap)
    }
}

/**
 * The resume path of `payLightningInvoice` against a local operator stand-in whose `query_htlc`
 * reports a held send; the wallet's SSP is unreachable, so the SSP request always fails.
 */
class LightningResumeTests {
    companion object {
        const val TRANSFER_ID = "0199a8f0-0000-7000-8000-000000000001"

        /** The specification's 2500u coffee invoice under the regtest prefix (the signature is not checked client-side). */
        fun regtestInvoice(): String =
            Bech32.encode("lnbcrt2500u", Bech32.decode(Bolt11InvoiceTests.COFFEE_2500U, maxLength = null).data, Bech32.Encoding.BECH32)

        /** A pending Lightning send as `query_htlc` reports it: the wallet's HTLC to the SSP with its preimage-swap transfer. */
        fun heldSend(
            identity: ByteArray,
            ssp: ByteArray,
            paymentHash: ByteArray,
            totalValue: Long,
            transferId: String = TRANSFER_ID,
        ): spark.Spark.PreimageRequestWithTransfer = spark.Spark.PreimageRequestWithTransfer.newBuilder()
            .setPaymentHash(paymentHash.toByteString())
            .setSenderIdentityPubkey(identity.toByteString())
            .setReceiverIdentityPubkey(ssp.toByteString())
            .setStatus(spark.Spark.PreimageRequestStatus.PREIMAGE_REQUEST_STATUS_WAITING_FOR_PREIMAGE)
            .setTransfer(
                spark.Spark.Transfer.newBuilder()
                    .setId(transferId)
                    .setType(spark.Spark.TransferType.PREIMAGE_SWAP)
                    .setStatus(spark.Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAK_PENDING)
                    .setSenderIdentityPublicKey(identity.toByteString())
                    .setReceiverIdentityPublicKey(ssp.toByteString())
                    .setTotalValue(totalValue),
            )
            .build()
    }

    @Test(timeout = 60_000)
    fun resumingASendTheCoordinatorHoldsSelectsSignsAndLocksNothingBeforeAskingTheSsp() = kotlinx.coroutines.runBlocking {
        val state = FakeOperatorState { false }
        val invoice = regtestInvoice()
        val paymentHash = Bolt11Invoice.decode(invoice).paymentHash
        withFakeOperator(state) { wallet ->
            state.hold(heldSend(wallet.signer.identityPublicKey, wallet.config.sspIdentityPublicKey, paymentHash, 250_002))
            val error = expectSparkErrorSuspending {
                wallet.payLightningInvoice(paymentRequest = invoice, maxFeeSats = 5, transferId = TRANSFER_ID.uppercase())
            }
            assertEquals(TRANSFER_ID, (error as SparkError.LightningSendIncomplete).transferId)
        }
        // Only the lookup reached the operator: no node query, signing commitments or preimage swap.
        assertEquals(listOf("query_htlc"), state.methods)
    }

    @Test(timeout = 60_000)
    fun aPreimageSwapWhoseOutcomeIsUnknownReportsTheTransferIdToResumeARefusedOneDoesNot() = kotlinx.coroutines.runBlocking {
        val state = FakeOperatorState { false }
        val request = spark.Spark.InitiatePreimageSwapRequest.newBuilder()
            .setTransferRequest(spark.Spark.StartTransferRequest.newBuilder().setTransferId(TRANSFER_ID))
            .build()
        withFakeOperator(state) { wallet ->
            state.preimageSwapError = io.grpc.Status.INTERNAL.withDescription("failed to commit")
            val unknown = expectSparkErrorSuspending { wallet.submitPreimageSwap(request, idempotencyKey = TRANSFER_ID) }
            assertEquals(TRANSFER_ID, (unknown as SparkError.LightningSendIncomplete).transferId)

            state.preimageSwapError = io.grpc.Status.FAILED_PRECONDITION.withDescription("leaf is not available")
            val refused = runCatching { wallet.submitPreimageSwap(request, idempotencyKey = TRANSFER_ID) }.exceptionOrNull()
            assertEquals(io.grpc.Status.Code.FAILED_PRECONDITION, refused?.grpcStatus?.code)
        }
        // Every swap carries the idempotency key the operators' interceptor reads.
        assertEquals(listOf(TRANSFER_ID, TRANSFER_ID), state.preimageSwapIdempotencyKeys)
    }

    @Test
    fun onlyASwapTheOperatorsRefusedBeforeCommittingCountsAsNotTaken() {
        val refused = listOf(
            io.grpc.Status.INVALID_ARGUMENT, io.grpc.Status.FAILED_PRECONDITION, io.grpc.Status.OUT_OF_RANGE, io.grpc.Status.NOT_FOUND,
            io.grpc.Status.ALREADY_EXISTS, io.grpc.Status.PERMISSION_DENIED, io.grpc.Status.UNAUTHENTICATED,
            io.grpc.Status.RESOURCE_EXHAUSTED, io.grpc.Status.ABORTED, io.grpc.Status.UNIMPLEMENTED,
        )
        for (status in refused) {
            assertFalse("${status.code}", preimageSwapMayHaveCommitted(io.grpc.StatusException(status)))
        }
        for (status in listOf(
            io.grpc.Status.UNAVAILABLE,
            io.grpc.Status.DEADLINE_EXCEEDED,
            io.grpc.Status.CANCELLED,
            io.grpc.Status.INTERNAL,
            io.grpc.Status.UNKNOWN,
            io.grpc.Status.DATA_LOSS
        )) {
            assertTrue("${status.code}", preimageSwapMayHaveCommitted(io.grpc.StatusRuntimeException(status)))
        }
        assertTrue(preimageSwapMayHaveCommitted(kotlinx.coroutines.CancellationException("cancelled")))
        assertTrue(preimageSwapMayHaveCommitted(SparkError.InvalidResponse("truncated")))
    }

    @Test(timeout = 60_000)
    fun aHeldSendForAnotherInvoiceOrAboveTheFeeCapIsRefusedWithoutAskingTheSsp() = kotlinx.coroutines.runBlocking {
        val state = FakeOperatorState { false }
        val invoice = regtestInvoice()
        val paymentHash = Bolt11Invoice.decode(invoice).paymentHash
        val otherInvoiceId = "0199a8f0-0000-7000-8000-00000000000a"
        val overCapId = "0199a8f0-0000-7000-8000-00000000000b"
        withFakeOperator(state) { wallet ->
            val identity = wallet.signer.identityPublicKey
            val ssp = wallet.config.sspIdentityPublicKey
            state.hold(heldSend(identity, ssp, bytes(0xAB, 32), totalValue = 250_002, transferId = otherInvoiceId))
            state.hold(heldSend(identity, ssp, paymentHash, totalValue = 250_010, transferId = overCapId))
            val other = expectSparkErrorSuspending { wallet.payLightningInvoice(invoice, maxFeeSats = 5, transferId = otherInvoiceId) }
            assertTrue(other is SparkError.InvalidArgument && other.message.contains("another invoice"))
            val overCap = expectSparkErrorSuspending { wallet.payLightningInvoice(invoice, maxFeeSats = 5, transferId = overCapId) }
            assertEquals(SparkError.FeeExceedsLimit(feeSats = 10, maxFeeSats = 5), overCap)
        }
        assertEquals(listOf("query_htlc", "query_htlc"), state.methods)
    }
}
