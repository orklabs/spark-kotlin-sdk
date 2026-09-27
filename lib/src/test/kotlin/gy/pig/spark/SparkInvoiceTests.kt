package gy.pig.spark

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.UUID

/**
 * A Spark invoice is a Spark address whose payload also carries invoice fields. The SDK decodes
 * the whole payload, as the reference SDK's `decodeSparkAddress` does, and refuses to pay an
 * invoice as a plain address. Vectors from the reference SDK's `address.test.ts`, via the Swift
 * SDK's `SparkInvoiceTests.swift`.
 */
class SparkInvoiceTests {
    private companion object {
        const val REGTEST_ADDRESS = "sparkrt1pgssx5us3wkqjza8g80xz3a9gznx25msq6g3ty8exfym9q3ahcv86vsnxxdy83"
        const val LEGACY_REGTEST_ADDRESS = "sprt1pgssx63fa5g6uyv450rajp5ndwy9laxzpsp9e37su58jddmcdsvhgm5n7y0ud6"
        const val MAINNET_ADDRESS = "spark1pgss9qg3vdslzmt2name9v550skuvlu6lj5xt9sly90k7p0gxughlqv023jqmc"
        const val LEGACY_MAINNET_ADDRESS = "sp1pgssxwh6hznfdc3c0cuqrhgttder539d52a0rqcf34amge69huh664gd2ew787"

        /** A signed regtest invoice for 1000 units of a token, with memo, sender and expiry. */
        const val TOKENS_INVOICE =
            "sparkrt1pgssx5us3wkqjza8g80xz3a9gznx25msq6g3ty8exfym9q3ahcv86vsnzfmssqgjzqqejtaxmwj8ms9rn58574nvlq4j5zr5v4ehgnt9d4hnyggr2wgghtqfpw" +
                "n5rhnpg7j5pfn92dcqdyg4jrunyjdjsg7muxraxgfn5rqgandgr3sxzrqdmew8qydzvz3qpylysylkgcaw9vpm2jzspls0qtr5kfmlwz244rvuk25w5w2sgc2pyqsraqdyp" +
                "8tf57a6cn2egttaas9ms3whssenmjqt8wag3lgyvdzjskfeupt8xwwdx4agxdm9f0wefzj28jmdxqeudwcwdj9vfl9sdr65x06r0tasf5fwz2"
        const val VECTOR_IDENTITY = "0353908bac090ba741de6147a540a665537006911590f93249b2823dbe187d3213"

        fun uuidBytes(uuid: UUID = UUID.randomUUID()): ByteArray =
            ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

        /** An unsigned invoice for [amountSats] to [identityPublicKey], as a payee would publish one. */
        fun satsInvoice(identityPublicKey: ByteArray, amountSats: Long, network: SparkNetwork): String {
            val payload = Spark.SparkAddress.newBuilder()
                .setIdentityPublicKey(identityPublicKey.toByteString())
                .setSparkInvoiceFields(
                    Spark.SparkInvoiceFields.newBuilder()
                        .setVersion(1)
                        .setId(uuidBytes().toByteString())
                        .setSatsPayment(Spark.SatsPayment.newBuilder().setAmount(amountSats)),
                )
                .build()
            return Bech32m.encode(SparkAddress.hrp(network), Bech32.toWords(payload.toByteArray()))
        }

        fun address(payload: ByteArray): String = Bech32m.encode("spark", Bech32.toWords(payload))
    }

    @Test
    fun knownAddressesDecodeToTheirIdentityKeysAndCarryNoInvoice() {
        assertEquals(VECTOR_IDENTITY, SparkAddress.decode(REGTEST_ADDRESS, SparkNetwork.REGTEST).toHexString())
        assertEquals(
            "036a29ed11ae1195a3c7d906936b885ff4c20c025cc7d0e50f26b7786c19746e93",
            SparkAddress.decode(LEGACY_REGTEST_ADDRESS, SparkNetwork.REGTEST).toHexString(),
        )
        SparkAddress.decode(MAINNET_ADDRESS, SparkNetwork.MAINNET)
        SparkAddress.decode(LEGACY_MAINNET_ADDRESS, SparkNetwork.MAINNET)
        val payload = SparkAddress.decodePayload(REGTEST_ADDRESS, SparkNetwork.REGTEST)
        assertNull(payload.invoiceFields)
        assertNull(payload.signature)
    }

    @Test
    fun aSparkInvoiceDecodesToItsFieldsAsInTheReferenceSdksVector() {
        val payload = SparkAddress.decodePayload(TOKENS_INVOICE, SparkNetwork.REGTEST)
        assertEquals(VECTOR_IDENTITY, payload.identityPublicKey.toHexString())
        val fields = payload.invoiceFields!!
        assertEquals(1, fields.version)
        assertEquals("01992fa6dba47dc0a39d0f4f566cf82b", fields.id.toByteArray().toHexString())
        assertEquals(Spark.SparkInvoiceFields.PaymentTypeCase.TOKENS_PAYMENT, fields.paymentTypeCase)
        assertEquals("093e4813f6463ae2b03b548500fe0f02c74b277f70955a8d9cb2a8ea39504614", fields.tokensPayment.tokenIdentifier.toByteArray().toHexString())
        assertArrayEquals(byteArrayOf(0x03, 0xE8.toByte()), fields.tokensPayment.amount.toByteArray())
        assertEquals("testMemo", fields.memo)
        assertEquals(VECTOR_IDENTITY, fields.senderPublicKey.toByteArray().toHexString())
        // 2025-09-09T18:09:48.419Z
        assertEquals(1_757_441_388L, fields.expiryTime.seconds)
        assertEquals(419_000_000, fields.expiryTime.nanos)
        assertEquals(
            "9d69a7bbac4d5942d7dec0bb845d784333dc80b3bba88fd046345285939e0567" +
                "339cd357a8337654bdd948a4a3cb6d3033c6bb0e6c8ac4fcb068f5433f437afb",
            payload.signature?.toHexString(),
        )
    }

    @Test
    fun aSparkInvoiceIsRefusedWhereASparkAddressIsExpected() {
        val error = expectSparkError { SparkAddress.decode(TOKENS_INVOICE, SparkNetwork.REGTEST) }
        assertTrue(error is SparkError.InvalidAddress && error.message.contains("Spark invoice"))
        val key = hex(VECTOR_IDENTITY)
        val sats = satsInvoice(key, amountSats = 1_000, network = SparkNetwork.MAINNET)
        assertEquals(1_000L, SparkAddress.decodePayload(sats, SparkNetwork.MAINNET).invoiceFields?.satsPayment?.amount)
        expectSparkError { SparkAddress.decode(sats, SparkNetwork.MAINNET) }
        // Invoice fields with nothing set still make an invoice.
        val empty = Spark.SparkAddress.newBuilder()
            .setIdentityPublicKey(key.toByteString())
            .setSparkInvoiceFields(Spark.SparkInvoiceFields.getDefaultInstance())
            .build()
        expectSparkError { SparkAddress.decode(address(empty.toByteArray()), SparkNetwork.MAINNET) }
    }

    @Test
    fun thePayloadIsDecodedWholeFieldOrderIsFreeTheKeyMustBeACurvePointJunkIsRefused() {
        val key = hex(VECTOR_IDENTITY)
        // Identity key after an unknown field: still a plain address.
        val reordered = byteArrayOf(0x78, 0x01, 0x0a, 33) + key
        assertArrayEquals(key, SparkAddress.decode(address(reordered), SparkNetwork.MAINNET))
        // x beyond the field prime: not a point.
        val offCurve = byteArrayOf(0x0a, 33, 0x02) + bytes(0xFF, 32)
        expectSparkError { SparkAddress.decode(address(offCurve), SparkNetwork.MAINNET) }
        // A truncated field.
        val truncated = byteArrayOf(0x0a, 33) + key.copyOfRange(0, 20)
        expectSparkError { SparkAddress.decode(address(truncated), SparkNetwork.MAINNET) }
        // No identity key at all.
        expectSparkError { SparkAddress.decode(address(byteArrayOf(0x78, 0x01)), SparkNetwork.MAINNET) }
        // What getSparkAddress hands out round-trips.
        assertArrayEquals(key, SparkAddress.decode(SparkAddress.encode(key, SparkNetwork.MAINNET), SparkNetwork.MAINNET))
    }

    @Test(timeout = 60_000)
    fun sendingOrTransferringTokensToASparkInvoiceIsRefusedBeforeAnyNetworkCall() = runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            val invoice = satsInvoice(hex(VECTOR_IDENTITY), amountSats = 10, network = SparkNetwork.REGTEST)
            expectSparkErrorSuspending { wallet.send(receiverSparkAddress = invoice, amountSats = 10) }
            val token = encodeBech32mTokenIdentifier(bytes(0x42, 32), SparkNetwork.REGTEST)
            expectSparkErrorSuspending {
                wallet.transferTokens(tokenIdentifier = token, tokenAmount = BigInteger.TEN, receiverSparkAddress = invoice)
            }
        }
        assertTrue(state.methods.isEmpty())
    }
}
