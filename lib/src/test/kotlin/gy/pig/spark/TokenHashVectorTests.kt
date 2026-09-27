package gy.pig.spark

import com.google.protobuf.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Test
import spark.Spark
import spark_token.InvoiceAttachment
import spark_token.TokenOutput
import spark_token.TokenOutputToSpend
import spark_token.TokenTransaction
import spark_token.TokenTransferInput
import java.math.BigInteger

/**
 * The reference SDK's known-answer vectors for V2 token transaction hashes
 * (`token-hashing.test.ts`, on the operators' Go test data), and the operators' rules for invoice
 * attachments (`TestHashTokenTransactionV2UniqueHash`). Port of the Swift SDK's
 * `TokenHashVectorTests`.
 */
class TokenHashVectorTests {
    companion object {
        fun key(second: Int, last: Int = 46): ByteArray = intArrayOf(
            0x02, second, 155, 208, 90, 72, 211, 120, 244, 69, 99, 28, 101, 149, 222, 123,
            50, 252, 63, 99, 54, 137, 226, 7, 224, 163, 122, 93, 248, 42, 159, 173, last,
        ).map { it.toByte() }.toByteArray()

        /** Two regtest invoices whose ids (01992fa6… and 01992fac…) sort the other way from their strings. */
        val INVOICES = listOf(
            "sparkrt1pgssx5us3wkqjza8g80xz3a9gznx25msq6g3ty8exfym9q3ahcv86vsnzfmssqgjzqqejtaxmwj8ms9rn5" +
                "8574nvlq4j5zr5v4ehgnt9d4hnyggr2wgghtqfpwn5rhnpg7j5pfn92dcqdyg4jrunyjdjsg7muxraxgfn5rqgandg" +
                "r3sxzrqdmew8qydzvz3qpylysylkgcaw9vpm2jzspls0qtr5kfmlwz244rvuk25w5w2sgc2pyqsraqdyp8tf57a6cn" +
                "2egttaas9ms3whssenmjqt8wag3lgyvdzjskfeupt8xwwdx4agxdm9f0wefzj28jmdxqeudwcwdj9vfl9sdr65x06r" +
                "0tasf5fwz2",
            "sparkrt1pgssx5us3wkqjza8g80xz3a9gznx25msq6g3ty8exfym9q3ahcv86vsnzfmqsqgjzqqejtavuhf8n5uh9a" +
                "74zw66kqaz5zr5v4ehgnt9d4hnyggr2wgghtqfpwn5rhnpg7j5pfn92dcqdyg4jrunyjdjsg7muxraxgfn5zcglrwc" +
                "r3sxzzqt3wrjrgnq5gqf8eyp8ajx8t3tqw65s5q0urczca9jwlmsj4dgm89j4r4rj5zxzsfqyqlgrfqw9ucldgmfzs" +
                "5zmkekj90thwzmn6ps55gdjnz23aarjkf245608yg0v2x6xdpdrz6m8xjlhtru0kygcu4zhqwlth9duadfqpruuzx4" +
                "tc7fdckn",
        )

        /** The vectors' transfer: one input, one output, one operator, regtest, created at 100 ms. */
        fun transfer(invoices: List<String>): TokenTransaction {
            val spend = TokenOutputToSpend.newBuilder()
                .setPrevTokenTransactionHash(sha256("previous transaction".toByteArray()).toByteString())
                .setPrevTokenTransactionVout(0)
            val output = TokenOutput.newBuilder()
                .setId("db1a4e48-0fc5-4f6c-8a80-d9d6c561a436")
                .setOwnerPublicKey(key(25).toByteString())
                .setTokenPublicKey(key(242, last = 45).toByteString())
                .setTokenAmount(encodeUInt128(BigInteger.valueOf(1000)).toByteString())
                .setRevocationCommitment(key(100).toByteString())
                .setWithdrawBondSats(10_000)
                .setWithdrawRelativeBlockLocktime(100)
            return TokenTransaction.newBuilder()
                .setVersion(2)
                .setTransferInput(TokenTransferInput.newBuilder().addOutputsToSpend(spend))
                .addTokenOutputs(output)
                .addSparkOperatorIdentityPublicKeys(key(200).toByteString())
                .setNetwork(Spark.Network.REGTEST)
                .setExpiryTime(Timestamp.newBuilder().setSeconds(0).setNanos(0))
                .setClientCreatedTimestamp(Timestamp.newBuilder().setSeconds(0).setNanos(100_000_000))
                .addAllInvoiceAttachments(invoices.map { InvoiceAttachment.newBuilder().setSparkInvoice(it).build() })
                .build()
        }
    }

    @Test
    fun transferWithoutInvoiceAttachments() {
        assertEquals(
            "1c97fc102935c232bea737022bb3b3ff7596941d9ecb6bc152014d5f29a8d0b3",
            hashTokenTransactionV2(transfer(emptyList()), partialHash = false).toHexString(),
        )
    }

    @Test
    fun transferWithTwoInvoiceAttachmentsHashedInInvoiceIdOrderWhateverOrderTheyComeIn() {
        val expected = "b098dc228a0d8264254a2def34425cabe2230d4f7ba43cf2a32c27f031ae0883"
        assertEquals(expected, hashTokenTransactionV2(transfer(INVOICES), partialHash = false).toHexString())
        assertEquals(expected, hashTokenTransactionV2(transfer(INVOICES.reversed()), partialHash = false).toHexString())
    }

    @Test
    fun anAttachmentThatIsNotASparkInvoiceCannotBeHashed() {
        for (invoice in listOf("", "invalid", SparkAddress.encode(key(25), SparkNetwork.REGTEST))) {
            expectSparkError(invoice) { hashTokenTransactionV2(transfer(listOf(invoice)), partialHash = false) }
        }
    }
}
