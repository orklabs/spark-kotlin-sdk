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
        assertEquals("11".repeat(32), donation.paymentSecret?.toHexString())
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
}
