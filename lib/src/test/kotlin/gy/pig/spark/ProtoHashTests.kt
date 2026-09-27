package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark
import spark_token.FinalTokenOutput
import spark_token.FinalTokenTransaction
import spark_token.InvoiceAttachment
import spark_token.PartialTokenOutput
import spark_token.PartialTokenTransaction
import spark_token.TokenCreateInput
import spark_token.TokenMintInput
import spark_token.TokenOutput
import spark_token.TokenOutputToSpend
import spark_token.TokenTransaction
import spark_token.TokenTransactionMetadata
import spark_token.TokenTransferInput
import java.io.File
import java.time.Instant
import java.util.Base64

/**
 * [ProtoHash] against the operators' cross-language vectors, which the Go operators, the
 * TypeScript SDK and the Rust token primitives all check: the `*_hash_cases.json` files of
 * `spark/testdata` in buildonspark/spark @ 0b3a32a, copied unchanged into
 * `src/test/resources/vectors/`. Port of the Swift SDK's `ProtoHashTests`.
 *
 * Swift hashes any message through SwiftProtobuf's reflection; protobuf-lite has none, so the
 * Kotlin hash spells out each message it covers. [everyDeclaredFieldIsHashed] checks that those
 * spellings keep up with `spark_token.proto`.
 */
class ProtoHashTests {
    private class Vector(val name: String, val expectedHash: String, val message: JSONObject)

    private fun vectors(file: String, key: String): List<Vector> {
        val url = requireNotNull(javaClass.classLoader?.getResource("vectors/$file.json")) { "missing vectors/$file.json" }
        val cases = JSONObject(url.readText()).getJSONArray("testCases")
        return (0 until cases.length()).map { index ->
            val case = cases.getJSONObject(index)
            Vector(case.getString("name"), case.getString("expectedHash"), case.getJSONObject(key))
        }
    }

    @Test
    fun partialTokenTransactions() {
        val vectors = vectors("partial_token_transaction_hash_cases", "partialTokenTransaction")
        assertEquals(4, vectors.size)
        for (vector in vectors) {
            val transaction = ProtoJson.partialTransaction(vector.message)
            assertEquals(vector.name, vector.expectedHash, ProtoHash.hash(transaction).toHexString())
        }
    }

    @Test
    fun v3TokenTransactionsHashedAsThePartialOrTheFinalTransaction() {
        val vectors = vectors("token_transaction_v3_hash_cases", "tokenTransaction")
        assertEquals(13, vectors.size)
        for (vector in vectors) {
            val transaction = ProtoJson.legacyTransaction(vector.message)
            val hash = if ("partial" in vector.name) ProtoHash.hash(partial(transaction)) else ProtoHash.hash(final(transaction))
            assertEquals(vector.name, vector.expectedHash, hash.toHexString())
        }
    }

    @Test
    fun sparkInvoiceFields() {
        val vectors = vectors("invoice_hash_cases", "sparkInvoiceFields")
        assertEquals(12, vectors.size)
        for (vector in vectors) {
            assertEquals(vector.name, vector.expectedHash, invoiceFieldsHash(ProtoJson.invoiceFields(vector.message)).toHexString())
        }
    }

    @Test
    fun aDefaultScalarIsLeftOutEvenWhenSetAndASetMessageIsHashedEvenWhenEmpty() {
        val owner = bytes(2, 33).toByteString()
        val explicitZero = PartialTokenOutput.newBuilder()
            .setOwnerPublicKey(owner)
            .setWithdrawBondSats(0)
            .setTokenIdentifier(ByteString.EMPTY)
            .build()
        val unset = PartialTokenOutput.newBuilder().setOwnerPublicKey(owner).build()
        assertArrayEquals(ProtoHash.hash(unset), ProtoHash.hash(explicitZero))

        // An optional field with explicit presence, set to its default, too.
        val emptyIdentifier = TokenMintInput.newBuilder().setIssuerPublicKey(owner).setTokenIdentifier(ByteString.EMPTY).build()
        assertTrue(emptyIdentifier.hasTokenIdentifier())
        assertArrayEquals(ProtoHash.hash(TokenMintInput.newBuilder().setIssuerPublicKey(owner).build()), ProtoHash.hash(emptyIdentifier))

        val withEmptyMetadata = PartialTokenTransaction.newBuilder().setTokenTransactionMetadata(TokenTransactionMetadata.getDefaultInstance()).build()
        assertFalse(ProtoHash.hash(withEmptyMetadata).contentEquals(ProtoHash.hash(PartialTokenTransaction.getDefaultInstance())))
    }

    @Test
    fun hashesByTheRulesAnEmptyMessageATimestampAndListOrder() {
        val zeroInt = sha256("i".toByteArray() + ByteArray(8))
        assertArrayEquals(sha256("d".toByteArray()), ProtoHash.hash(TokenTransferInput.getDefaultInstance()))
        assertArrayEquals(sha256("l".toByteArray() + zeroInt + zeroInt), ProtoHash.hash(Timestamp.getDefaultInstance()))

        val ascending = TokenTransactionMetadata.newBuilder()
            .addSparkOperatorIdentityPublicKeys(byteArrayOf(2).toByteString())
            .addSparkOperatorIdentityPublicKeys(byteArrayOf(3).toByteString())
            .build()
        val descending = TokenTransactionMetadata.newBuilder()
            .addAllSparkOperatorIdentityPublicKeys(ascending.sparkOperatorIdentityPublicKeysList.reversed())
            .build()
        assertFalse(ProtoHash.hash(ascending).contentEquals(ProtoHash.hash(descending)))
    }

    @Test
    fun unsignedFieldsHashTheirUnsignedValue() {
        // protobuf-lite hands a uint32 out as a signed Int: 0xFFFFFFFF arrives as -1.
        val spend = TokenOutputToSpend.newBuilder().setPrevTokenTransactionVout(-1).build()
        val field2 = sha256("i".toByteArray() + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2))
        val value = sha256("i".toByteArray() + byteArrayOf(0, 0, 0, 0, -1, -1, -1, -1))
        assertArrayEquals(sha256("d".toByteArray() + field2 + value), ProtoHash.hash(spend))

        // A uint64 above Long.MAX_VALUE keeps its 64-bit pattern.
        val output = PartialTokenOutput.newBuilder().setWithdrawBondSats(-1).build()
        val all = sha256("i".toByteArray() + ByteArray(8) { -1 })
        assertArrayEquals(sha256("d".toByteArray() + field2 + all), ProtoHash.hash(output))
    }

    @Test
    fun everyDeclaredFieldIsHashed() {
        // The field numbers each hand-written hash covers (see ProtoHash).
        val hashed = mapOf(
            "PartialTokenTransaction" to setOf(1, 2, 3, 4, 5, 6, 7),
            "FinalTokenTransaction" to setOf(1, 2, 3, 4, 5, 6, 7),
            "TokenTransactionMetadata" to setOf(2, 3, 4, 5, 6),
            "InvoiceAttachment" to setOf(1),
            "TokenMintInput" to setOf(1, 2),
            "TokenTransferInput" to setOf(1),
            "TokenOutputToSpend" to setOf(1, 2),
            "TokenCreateInput" to setOf(1, 2, 3, 4, 5, 6, 7, 8),
            "PartialTokenOutput" to setOf(1, 2, 3, 4, 5),
            "FinalTokenOutput" to setOf(1, 2),
        )
        val proto = File("src/main/proto/spark_token.proto").readText()
        for ((message, numbers) in hashed) {
            assertEquals(message, numbers, declaredFieldNumbers(proto, message))
        }
    }

    /** The field numbers `message [name] { … }` declares in [proto]: every `= N` before a `;` or `[`. */
    private fun declaredFieldNumbers(proto: String, name: String): Set<Int> {
        val start = Regex("""(?m)^message $name \{""").find(proto)?.range?.last ?: error("no message $name")
        val body = proto.substring(start + 1, proto.indexOf("\n}", start))
            .lines()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        return Regex("""=\s*(\d+)\s*[;\[]""").findAll(body).map { it.groupValues[1].toInt() }.toSet()
    }

    // The operators' conversions from the legacy transaction shape (`ConvertV2TxShapeToPartial`
    // and `ConvertV2TxShapeToFinal` in `so/protoconverter`), which their V3 vectors hash through.

    private fun metadata(legacy: TokenTransaction): TokenTransactionMetadata = TokenTransactionMetadata.newBuilder()
        .addAllSparkOperatorIdentityPublicKeys(legacy.sparkOperatorIdentityPublicKeysList)
        .setNetworkValue(legacy.networkValue)
        .apply { if (legacy.hasClientCreatedTimestamp()) clientCreatedTimestamp = legacy.clientCreatedTimestamp }
        .addAllInvoiceAttachments(legacy.invoiceAttachmentsList)
        .setValidityDurationSeconds(legacy.validityDurationSeconds)
        .build()

    private fun partialOutput(output: TokenOutput): PartialTokenOutput = PartialTokenOutput.newBuilder()
        .setOwnerPublicKey(output.ownerPublicKey)
        .setWithdrawBondSats(output.withdrawBondSats)
        .setWithdrawRelativeBlockLocktime(output.withdrawRelativeBlockLocktime)
        .setTokenIdentifier(output.tokenIdentifier)
        .setTokenAmount(output.tokenAmount)
        .build()

    private fun partial(legacy: TokenTransaction): PartialTokenTransaction {
        val partial = PartialTokenTransaction.newBuilder()
            .setVersion(legacy.version)
            .setTokenTransactionMetadata(metadata(legacy))
        if (legacy.hasExecuteBefore()) partial.executeBefore = legacy.executeBefore
        when (legacy.tokenInputsCase) {
            TokenTransaction.TokenInputsCase.MINT_INPUT -> partial.mintInput = legacy.mintInput
            TokenTransaction.TokenInputsCase.TRANSFER_INPUT -> partial.transferInput = legacy.transferInput
            // Server-set: not in a partial transaction.
            TokenTransaction.TokenInputsCase.CREATE_INPUT -> partial.createInput = legacy.createInput.toBuilder().clearCreationEntityPublicKey().build()
            else -> Unit
        }
        return partial.addAllPartialTokenOutputs(legacy.tokenOutputsList.map { partialOutput(it) }).build()
    }

    private fun final(legacy: TokenTransaction): FinalTokenTransaction {
        val final = FinalTokenTransaction.newBuilder()
            .setVersion(legacy.version)
            .setTokenTransactionMetadata(metadata(legacy))
        if (legacy.hasExecuteBefore()) final.executeBefore = legacy.executeBefore
        when (legacy.tokenInputsCase) {
            TokenTransaction.TokenInputsCase.MINT_INPUT -> final.mintInput = legacy.mintInput
            TokenTransaction.TokenInputsCase.TRANSFER_INPUT -> final.transferInput = legacy.transferInput
            TokenTransaction.TokenInputsCase.CREATE_INPUT -> final.createInput = legacy.createInput
            else -> Unit
        }
        for (output in legacy.tokenOutputsList) {
            final.addFinalTokenOutputs(
                FinalTokenOutput.newBuilder()
                    .setPartialTokenOutput(partialOutput(output))
                    .setRevocationCommitment(output.revocationCommitment),
            )
        }
        return final.build()
    }

    /** `SparkInvoiceFields`, which the operators also protohash; the SDK itself has no use for it. */
    private fun invoiceFieldsHash(fields: Spark.SparkInvoiceFields): ByteArray = ProtoHash.message {
        uint32(1, fields.version)
        bytes(2, fields.id)
        if (fields.hasTokensPayment()) {
            field(
                3,
                ProtoHash.message {
                    bytes(1, fields.tokensPayment.tokenIdentifier)
                    bytes(2, fields.tokensPayment.amount)
                },
            )
        }
        if (fields.hasSatsPayment()) field(4, ProtoHash.message { uint64(1, fields.satsPayment.amount) })
        string(5, fields.memo)
        bytes(6, fields.senderPublicKey)
        if (fields.hasExpiryTime()) field(7, ProtoHash.hash(fields.expiryTime))
    }
}

/**
 * The vectors' messages from canonical Protobuf JSON (protobuf-lite has no JSON support). Strict:
 * a key it does not know fails the test, rather than dropping a field from the hash.
 */
private object ProtoJson {
    private fun JSONObject.only(vararg allowed: String): JSONObject = apply {
        val unknown = keys().asSequence().toSet() - allowed.toSet()
        require(unknown.isEmpty()) { "unexpected keys $unknown" }
    }

    private fun JSONObject.bytes(key: String): ByteString = ByteString.copyFrom(Base64.getDecoder().decode(getString(key)))

    /** A 64-bit integer, which canonical JSON writes as a string (and some vectors as a number). */
    private fun JSONObject.uint64(key: String): Long = get(key).toString().toULong().toLong()

    private fun JSONObject.timestamp(key: String): Timestamp {
        val instant = Instant.parse(getString(key))
        return Timestamp.newBuilder().setSeconds(instant.epochSecond).setNanos(instant.nano).build()
    }

    private fun <T> JSONObject.each(key: String, read: (JSONObject) -> T): List<T> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { read(array.getJSONObject(it)) }
    }

    private fun JSONObject.byteList(key: String): List<ByteString> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { ByteString.copyFrom(Base64.getDecoder().decode(array.getString(it))) }
    }

    fun partialTransaction(json: JSONObject): PartialTokenTransaction {
        json.only("version", "tokenTransactionMetadata", "mintInput", "transferInput", "createInput", "partialTokenOutputs", "executeBefore")
        val transaction = PartialTokenTransaction.newBuilder().setVersion(json.optInt("version"))
        json.optJSONObject("tokenTransactionMetadata")?.let { transaction.tokenTransactionMetadata = metadata(it) }
        json.optJSONObject("mintInput")?.let { transaction.mintInput = mintInput(it) }
        json.optJSONObject("transferInput")?.let { transaction.transferInput = transferInput(it) }
        json.optJSONObject("createInput")?.let { transaction.createInput = createInput(it) }
        transaction.addAllPartialTokenOutputs(json.each("partialTokenOutputs") { partialOutput(it) })
        if (json.has("executeBefore")) transaction.executeBefore = json.timestamp("executeBefore")
        return transaction.build()
    }

    fun legacyTransaction(json: JSONObject): TokenTransaction {
        json.only(
            "version", "mintInput", "transferInput", "createInput", "tokenOutputs", "sparkOperatorIdentityPublicKeys", "network",
            "validityDurationSeconds", "clientCreatedTimestamp", "invoiceAttachments",
        )
        val transaction = TokenTransaction.newBuilder().setVersion(json.optInt("version"))
        json.optJSONObject("mintInput")?.let { transaction.mintInput = mintInput(it) }
        json.optJSONObject("transferInput")?.let { transaction.transferInput = transferInput(it) }
        json.optJSONObject("createInput")?.let { transaction.createInput = createInput(it) }
        transaction.addAllTokenOutputs(json.each("tokenOutputs") { tokenOutput(it) })
        transaction.addAllSparkOperatorIdentityPublicKeys(json.byteList("sparkOperatorIdentityPublicKeys"))
        if (json.has("network")) transaction.network = Spark.Network.valueOf(json.getString("network"))
        if (json.has("validityDurationSeconds")) transaction.validityDurationSeconds = json.uint64("validityDurationSeconds")
        if (json.has("clientCreatedTimestamp")) transaction.clientCreatedTimestamp = json.timestamp("clientCreatedTimestamp")
        transaction.addAllInvoiceAttachments(json.each("invoiceAttachments") { attachment(it) })
        return transaction.build()
    }

    fun invoiceFields(json: JSONObject): Spark.SparkInvoiceFields {
        json.only("version", "id", "tokensPayment", "satsPayment", "memo", "senderPublicKey", "expiryTime")
        val fields = Spark.SparkInvoiceFields.newBuilder().setVersion(json.optInt("version"))
        if (json.has("id")) fields.id = json.bytes("id")
        json.optJSONObject("tokensPayment")?.let { payment ->
            payment.only("tokenIdentifier", "amount")
            val tokens = Spark.TokensPayment.newBuilder()
            if (payment.has("tokenIdentifier")) tokens.tokenIdentifier = payment.bytes("tokenIdentifier")
            if (payment.has("amount")) tokens.amount = payment.bytes("amount")
            fields.tokensPayment = tokens.build()
        }
        json.optJSONObject("satsPayment")?.let { payment ->
            payment.only("amount")
            val sats = Spark.SatsPayment.newBuilder()
            if (payment.has("amount")) sats.amount = payment.uint64("amount")
            fields.satsPayment = sats.build()
        }
        if (json.has("memo")) fields.memo = json.getString("memo")
        if (json.has("senderPublicKey")) fields.senderPublicKey = json.bytes("senderPublicKey")
        if (json.has("expiryTime")) fields.expiryTime = json.timestamp("expiryTime")
        return fields.build()
    }

    private fun metadata(json: JSONObject): TokenTransactionMetadata {
        json.only("sparkOperatorIdentityPublicKeys", "network", "clientCreatedTimestamp", "validityDurationSeconds", "invoiceAttachments")
        val metadata = TokenTransactionMetadata.newBuilder()
            .addAllSparkOperatorIdentityPublicKeys(json.byteList("sparkOperatorIdentityPublicKeys"))
            .addAllInvoiceAttachments(json.each("invoiceAttachments") { attachment(it) })
        if (json.has("network")) metadata.network = Spark.Network.valueOf(json.getString("network"))
        if (json.has("clientCreatedTimestamp")) metadata.clientCreatedTimestamp = json.timestamp("clientCreatedTimestamp")
        if (json.has("validityDurationSeconds")) metadata.validityDurationSeconds = json.uint64("validityDurationSeconds")
        return metadata.build()
    }

    private fun attachment(json: JSONObject): InvoiceAttachment =
        InvoiceAttachment.newBuilder().setSparkInvoice(json.only("sparkInvoice").getString("sparkInvoice")).build()

    private fun mintInput(json: JSONObject): TokenMintInput {
        json.only("issuerPublicKey", "tokenIdentifier")
        val input = TokenMintInput.newBuilder()
        if (json.has("issuerPublicKey")) input.issuerPublicKey = json.bytes("issuerPublicKey")
        if (json.has("tokenIdentifier")) input.tokenIdentifier = json.bytes("tokenIdentifier")
        return input.build()
    }

    private fun transferInput(json: JSONObject): TokenTransferInput {
        json.only("outputsToSpend")
        return TokenTransferInput.newBuilder()
            .addAllOutputsToSpend(
                json.each("outputsToSpend") { spend ->
                    spend.only("prevTokenTransactionHash", "prevTokenTransactionVout")
                    TokenOutputToSpend.newBuilder()
                        .setPrevTokenTransactionHash(spend.bytes("prevTokenTransactionHash"))
                        .setPrevTokenTransactionVout(spend.optInt("prevTokenTransactionVout"))
                        .build()
                },
            )
            .build()
    }

    private fun createInput(json: JSONObject): TokenCreateInput {
        json.only(
            "issuerPublicKey",
            "tokenName",
            "tokenTicker",
            "decimals",
            "maxSupply",
            "isFreezable",
            "creationEntityPublicKey",
            "extraMetadata",
        )
        val input = TokenCreateInput.newBuilder()
            .setTokenName(json.optString("tokenName"))
            .setTokenTicker(json.optString("tokenTicker"))
            .setDecimals(json.optInt("decimals"))
            .setIsFreezable(json.optBoolean("isFreezable"))
        if (json.has("issuerPublicKey")) input.issuerPublicKey = json.bytes("issuerPublicKey")
        if (json.has("maxSupply")) input.maxSupply = json.bytes("maxSupply")
        if (json.has("creationEntityPublicKey")) input.creationEntityPublicKey = json.bytes("creationEntityPublicKey")
        if (json.has("extraMetadata")) input.extraMetadata = json.bytes("extraMetadata")
        return input.build()
    }

    private fun partialOutput(json: JSONObject): PartialTokenOutput {
        json.only("ownerPublicKey", "withdrawBondSats", "withdrawRelativeBlockLocktime", "tokenIdentifier", "tokenAmount")
        val output = PartialTokenOutput.newBuilder()
        if (json.has("ownerPublicKey")) output.ownerPublicKey = json.bytes("ownerPublicKey")
        if (json.has("withdrawBondSats")) output.withdrawBondSats = json.uint64("withdrawBondSats")
        if (json.has("withdrawRelativeBlockLocktime")) output.withdrawRelativeBlockLocktime = json.uint64("withdrawRelativeBlockLocktime")
        if (json.has("tokenIdentifier")) output.tokenIdentifier = json.bytes("tokenIdentifier")
        if (json.has("tokenAmount")) output.tokenAmount = json.bytes("tokenAmount")
        return output.build()
    }

    private fun tokenOutput(json: JSONObject): TokenOutput {
        json.only("id", "ownerPublicKey", "revocationCommitment", "withdrawBondSats", "withdrawRelativeBlockLocktime", "tokenIdentifier", "tokenAmount")
        val output = TokenOutput.newBuilder()
        if (json.has("id")) output.id = json.getString("id")
        if (json.has("ownerPublicKey")) output.ownerPublicKey = json.bytes("ownerPublicKey")
        if (json.has("revocationCommitment")) output.revocationCommitment = json.bytes("revocationCommitment")
        if (json.has("withdrawBondSats")) output.withdrawBondSats = json.uint64("withdrawBondSats")
        if (json.has("withdrawRelativeBlockLocktime")) output.withdrawRelativeBlockLocktime = json.uint64("withdrawRelativeBlockLocktime")
        if (json.has("tokenIdentifier")) output.tokenIdentifier = json.bytes("tokenIdentifier")
        if (json.has("tokenAmount")) output.tokenAmount = json.bytes("tokenAmount")
        return output.build()
    }
}
