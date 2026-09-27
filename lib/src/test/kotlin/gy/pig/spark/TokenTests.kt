package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import spark_token.OutputWithPreviousTransactionData
import spark_token.TokenMintInput
import spark_token.TokenOutput
import spark_token.TokenOutputToSpend
import spark_token.TokenTransaction
import spark_token.TokenTransferInput
import java.math.BigInteger

/**
 * Port of spark-swift-sdk Tests/SparkSDKTests/TokenTests.swift.
 *
 * Token amounts and max-supply use [BigInteger] (constrained to unsigned 0 .. 2^128 − 1
 * at the encode/decode boundary), matching Swift's native UInt128 semantically.
 */
class TokenTests {

    // MARK: - Test fixtures

    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private val uint128Max: BigInteger = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    private fun bi(value: Long): BigInteger = BigInteger.valueOf(value)

    private fun timestamp(epochMillis: Long): Timestamp = Timestamp.newBuilder()
        .setSeconds(epochMillis / 1000)
        .setNanos(((epochMillis % 1000) * 1_000_000).toInt())
        .build()

    private fun makeTokenOutputs(amounts: List<BigInteger>): List<OutputWithPreviousTransactionData> = amounts.mapIndexed { i, amount ->
        val output = TokenOutput.newBuilder()
            .setOwnerPublicKey(ByteString.copyFrom(ByteArray(33).apply { this[0] = 0x02 }))
            .setTokenIdentifier(ByteString.copyFrom(ByteArray(32)))
            .setTokenAmount(ByteString.copyFrom(encodeUInt128(amount)))
            .build()

        OutputWithPreviousTransactionData.newBuilder()
            .setOutput(output)
            .setPreviousTransactionHash(ByteString.copyFrom(ByteArray(32)))
            .setPreviousTransactionVout(i)
            .build()
    }

    private fun lexCompare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    // MARK: - Token Identifier Tests

    @Test
    fun testTokenIdentifierEncodeDecodeMainnet() {
        val rawId = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890".hexToByteArray()
        val encoded = encodeBech32mTokenIdentifier(rawId, SparkNetwork.MAINNET)
        assertTrue(encoded.startsWith("btkn1"))

        val (decoded, network) = decodeBech32mTokenIdentifier(encoded, SparkNetwork.MAINNET)
        assertArrayEquals(rawId, decoded)
        assertEquals(SparkNetwork.MAINNET, network)
    }

    @Test
    fun testTokenIdentifierEncodeDecodeRegtest() {
        val rawId = "0000000000000000000000000000000000000000000000000000000000000001".hexToByteArray()
        val encoded = encodeBech32mTokenIdentifier(rawId, SparkNetwork.REGTEST)
        assertTrue(encoded.startsWith("btknrt1"))

        val (decoded, network) = decodeBech32mTokenIdentifier(encoded)
        assertArrayEquals(rawId, decoded)
        assertEquals(SparkNetwork.REGTEST, network)
    }

    @Test
    fun testTokenIdentifierRoundtrip() {
        val rawId = "deadbeefcafebabe1234567890abcdef1122334455667788aabbccddeeff0011".hexToByteArray()
        for (network in listOf(SparkNetwork.MAINNET, SparkNetwork.REGTEST)) {
            val encoded = encodeBech32mTokenIdentifier(rawId, network)
            val (decoded, decodedNetwork) = decodeBech32mTokenIdentifier(encoded)
            assertArrayEquals("Roundtrip failed for $network", rawId, decoded)
            assertEquals(network, decodedNetwork)
        }
    }

    @Test
    fun testTokenIdentifierInvalidLength() {
        val shortId = "abcdef".hexToByteArray()
        try {
            encodeBech32mTokenIdentifier(shortId, SparkNetwork.MAINNET)
            fail("Should have thrown for short identifier")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun testTokenIdentifierWrongNetwork() {
        val rawId = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890".hexToByteArray()
        val encoded = encodeBech32mTokenIdentifier(rawId, SparkNetwork.MAINNET)
        try {
            decodeBech32mTokenIdentifier(encoded, SparkNetwork.REGTEST)
            fail("Should have thrown for wrong network")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // MARK: - UInt128 Encoding Tests

    @Test
    fun testUInt128EncodeDecode() {
        val testCases: List<BigInteger> = listOf(
            BigInteger.ZERO,
            BigInteger.ONE,
            bi(255),
            bi(256),
            bi(1000),
            bi(1_000_000),
            BigInteger.ONE.shiftLeft(63), // 2^63 — past Long range
            BigInteger.ONE.shiftLeft(64), // 2^64 — past ULong range (regression for old impl)
            BigInteger.ONE.shiftLeft(127), // 2^127 — high bit set
            uint128Max, // 2^128 − 1
        )
        for (value in testCases) {
            val encoded = encodeUInt128(value)
            assertEquals("Encoded length should be 16 for $value", 16, encoded.size)
            val decoded = decodeUInt128(ByteString.copyFrom(encoded))
            assertEquals("Roundtrip failed for $value", value, decoded)
        }
    }

    @Test
    fun testUInt128EncodeDecodeSpecific() {
        // 1000 in big-endian 16 bytes: last two bytes are 0x03, 0xE8; rest are 0.
        val encoded = encodeUInt128(bi(1000))
        assertEquals(16, encoded.size)
        assertEquals(0x03.toByte(), encoded[14])
        assertEquals(0xE8.toByte(), encoded[15])
        for (i in 0 until 14) {
            assertEquals("Byte $i should be 0", 0.toByte(), encoded[i])
        }
    }

    @Test
    fun testUInt128Zero() {
        val encoded = encodeUInt128(BigInteger.ZERO)
        assertArrayEquals(ByteArray(16), encoded)
        assertEquals(BigInteger.ZERO, decodeUInt128(ByteString.copyFrom(encoded)))
    }

    @Test
    fun testUInt128MaxLayout() {
        // 2^128 − 1 encodes to sixteen 0xFF bytes.
        val encoded = encodeUInt128(uint128Max)
        assertEquals(16, encoded.size)
        for (i in 0 until 16) {
            assertEquals(0xFF.toByte(), encoded[i])
        }
        assertEquals(uint128Max, decodeUInt128(ByteString.copyFrom(encoded)))
    }

    @Test
    fun testUInt128EncodeRejectsNegative() {
        try {
            encodeUInt128(BigInteger.valueOf(-1))
            fail("Should have thrown for negative value")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun testUInt128EncodeRejectsOverflow() {
        try {
            encodeUInt128(BigInteger.ONE.shiftLeft(128)) // 2^128 — one past max
            fail("Should have thrown for value exceeding 2^128 - 1")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun testUInt128DecodeRejectsWrongLength() {
        try {
            decodeUInt128(ByteString.copyFrom(ByteArray(8)))
            fail("Should have thrown for 8-byte input")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            decodeUInt128(ByteString.copyFrom(ByteArray(17)))
            fail("Should have thrown for 17-byte input")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // MARK: - Token Output Selection Tests

    @Test
    fun testTokenOutputSelectionExactMatch() {
        val outputs = makeTokenOutputs(listOf(bi(100), bi(200), bi(300), bi(500)))
        val selected = selectTokenOutputs(outputs, bi(200), TokenOutputSelectionStrategy.SMALL_FIRST)
        assertEquals(1, selected.size)
        assertEquals(bi(200), decodeUInt128(selected[0].output.tokenAmount))
    }

    @Test
    fun testTokenOutputSelectionSmallFirst() {
        val outputs = makeTokenOutputs(listOf(bi(10), bi(20), bi(30), bi(50), bi(100)))
        val selected = selectTokenOutputs(outputs, bi(55), TokenOutputSelectionStrategy.SMALL_FIRST)
        // Smallest outputs summing to >= 55: 10+20+30 = 60.
        val total = selected.fold(BigInteger.ZERO) { acc, o -> acc + decodeUInt128(o.output.tokenAmount) }
        assertTrue("total ($total) must cover 55", total >= bi(55))
    }

    @Test
    fun testTokenOutputSelectionLargeFirst() {
        val outputs = makeTokenOutputs(listOf(bi(10), bi(20), bi(30), bi(50), bi(100)))
        val selected = selectTokenOutputs(outputs, bi(55), TokenOutputSelectionStrategy.LARGE_FIRST)
        val total = selected.fold(BigInteger.ZERO) { acc, o -> acc + decodeUInt128(o.output.tokenAmount) }
        assertTrue(total >= bi(55))
        assertTrue("Large first should use fewer outputs", selected.size <= 2)
    }

    @Test
    fun testTokenOutputSelectionInsufficientBalance() {
        val outputs = makeTokenOutputs(listOf(bi(10), bi(20), bi(30)))
        try {
            selectTokenOutputs(outputs, bi(100), TokenOutputSelectionStrategy.SMALL_FIRST)
            fail("Should have thrown for insufficient balance")
        } catch (e: SparkError.InsufficientTokenBalance) {
            // expected
        }
    }

    @Test
    fun testTokenOutputSelectionZeroAmount() {
        val outputs = makeTokenOutputs(listOf(bi(100)))
        try {
            selectTokenOutputs(outputs, BigInteger.ZERO, TokenOutputSelectionStrategy.SMALL_FIRST)
            fail("Should have thrown for zero amount")
        } catch (e: SparkError.TokenValidationFailed) {
            // expected
        }
    }

    @Test
    fun testTokenOutputSelectionSingleOutput() {
        val outputs = makeTokenOutputs(listOf(bi(500)))
        val selected = selectTokenOutputs(outputs, bi(300), TokenOutputSelectionStrategy.SMALL_FIRST)
        assertEquals(1, selected.size)
        assertEquals(bi(500), decodeUInt128(selected[0].output.tokenAmount))
    }

    @Test
    fun testTokenOutputSelectionAllOutputs() {
        val outputs = makeTokenOutputs(listOf(bi(10), bi(20), bi(30)))
        val selected = selectTokenOutputs(outputs, bi(60), TokenOutputSelectionStrategy.SMALL_FIRST)
        assertEquals(3, selected.size)
        val total = selected.fold(BigInteger.ZERO) { acc, o -> acc + decodeUInt128(o.output.tokenAmount) }
        assertEquals(bi(60), total)
    }

    @Test
    fun testTokenOutputSelectionLargeAmountsExceedingULong() {
        // Two outputs near UInt128.max — must select without silent truncation.
        val big = BigInteger.ONE.shiftLeft(100) // 2^100, well above 2^64
        val outputs = makeTokenOutputs(listOf(big, big))
        val target = big.add(BigInteger.ONE) // needs both
        val selected = selectTokenOutputs(outputs, target, TokenOutputSelectionStrategy.LARGE_FIRST)
        assertEquals(2, selected.size)
        val total = selected.fold(BigInteger.ZERO) { acc, o -> acc + decodeUInt128(o.output.tokenAmount) }
        assertEquals(big.add(big), total)
    }

    // MARK: - Token Hashing Tests

    @Test
    fun testTokenHashingV2Transfer() {
        val transferInput = TokenTransferInput.newBuilder()
            .addOutputsToSpend(
                TokenOutputToSpend.newBuilder()
                    .setPrevTokenTransactionHash(ByteString.copyFrom(ByteArray(32)))
                    .setPrevTokenTransactionVout(0)
                    .build()
            )
            .build()

        val tokenOutput = TokenOutput.newBuilder()
            .setOwnerPublicKey(ByteString.copyFrom(ByteArray(33)))
            .setTokenAmount(ByteString.copyFrom(encodeUInt128(bi(1000))))
            .build()

        val tx = TokenTransaction.newBuilder()
            .setVersion(2)
            .setNetwork(SparkNetwork.MAINNET.toProto())
            .setTransferInput(transferInput)
            .addTokenOutputs(tokenOutput)
            .addSparkOperatorIdentityPublicKeys(ByteString.copyFrom(ByteArray(33)))
            .setClientCreatedTimestamp(timestamp(System.currentTimeMillis()))
            .build()

        val partialHash = hashTokenTransactionV2(tx, partialHash = true)
        assertEquals(32, partialHash.size)

        val fullHash = hashTokenTransactionV2(tx, partialHash = false)
        assertEquals(32, fullHash.size)

        assertFalse(
            "Partial and full hashes should differ (full includes withdraw/locktime/expiry)",
            partialHash.contentEquals(fullHash),
        )
    }

    @Test
    fun testTokenHashingV2Deterministic() {
        val mintInput = TokenMintInput.newBuilder()
            .setIssuerPublicKey(ByteString.copyFrom(ByteArray(33).apply { fill(0x02) }))
            .setTokenIdentifier(ByteString.copyFrom(ByteArray(32)))
            .build()

        val tokenOutput = TokenOutput.newBuilder()
            .setOwnerPublicKey(ByteString.copyFrom(ByteArray(33).apply { fill(0x03) }))
            .setTokenAmount(ByteString.copyFrom(encodeUInt128(bi(42))))
            .build()

        val tx = TokenTransaction.newBuilder()
            .setVersion(2)
            .setNetwork(SparkNetwork.REGTEST.toProto())
            .setMintInput(mintInput)
            .addTokenOutputs(tokenOutput)
            .addSparkOperatorIdentityPublicKeys(ByteString.copyFrom(ByteArray(33).apply { fill(0x02) }))
            .setClientCreatedTimestamp(timestamp(1_700_000_000_000L))
            .build()

        val hash1 = hashTokenTransactionV2(tx, partialHash = true)
        val hash2 = hashTokenTransactionV2(tx, partialHash = true)
        assertArrayEquals("Hashing should be deterministic", hash1, hash2)
    }

    @Test
    fun testTokenHashingV2CreateInput() {
        val createInput = spark_token.TokenCreateInput.newBuilder()
            .setIssuerPublicKey(ByteString.copyFrom(ByteArray(33).apply { fill(0x02) }))
            .setTokenName("TestToken")
            .setTokenTicker("TST")
            .setDecimals(8)
            .setMaxSupply(ByteString.copyFrom(encodeUInt128(bi(21_000_000))))
            .setIsFreezable(false)
            .build()

        val tx = TokenTransaction.newBuilder()
            .setVersion(2)
            .setNetwork(SparkNetwork.MAINNET.toProto())
            .setCreateInput(createInput)
            .addSparkOperatorIdentityPublicKeys(ByteString.copyFrom(ByteArray(33).apply { fill(0x02) }))
            .setClientCreatedTimestamp(timestamp(1_700_000_000_000L))
            .build()

        val partialHash = hashTokenTransactionV2(tx, partialHash = true)
        assertEquals(32, partialHash.size)

        val fullHash = hashTokenTransactionV2(tx, partialHash = false)
        assertEquals(32, fullHash.size)
        assertFalse(partialHash.contentEquals(fullHash))
    }

    @Test
    fun testTokenHashingV2MintInput() {
        val mintInput = TokenMintInput.newBuilder()
            .setIssuerPublicKey(ByteString.copyFrom(ByteArray(33).apply { fill(0x03) }))
            .setTokenIdentifier(ByteString.copyFrom(ByteArray(32).apply { fill(0xAB.toByte()) }))
            .build()

        val tokenOutput = TokenOutput.newBuilder()
            .setOwnerPublicKey(ByteString.copyFrom(ByteArray(33).apply { fill(0x03) }))
            .setTokenIdentifier(ByteString.copyFrom(ByteArray(32).apply { fill(0xAB.toByte()) }))
            .setTokenAmount(ByteString.copyFrom(encodeUInt128(bi(5000))))
            .build()

        val tx = TokenTransaction.newBuilder()
            .setVersion(2)
            .setNetwork(SparkNetwork.REGTEST.toProto())
            .setMintInput(mintInput)
            .addTokenOutputs(tokenOutput)
            .addSparkOperatorIdentityPublicKeys(ByteString.copyFrom(ByteArray(33).apply { fill(0x02) }))
            .setClientCreatedTimestamp(timestamp(1_700_000_000_000L))
            .build()

        val hash = hashTokenTransactionV2(tx, partialHash = true)
        assertEquals(32, hash.size)
    }

    // MARK: - Operator-specific payload hash

    @Test
    fun testOperatorSpecificPayloadHash() {
        val txHash = ByteArray(32)
        val operatorKey = ByteArray(33).apply { fill(0x02) }

        val hash = hashOperatorSpecificPayload(
            finalTokenTransactionHash = txHash,
            operatorIdentityPublicKey = operatorKey,
        )
        assertEquals(32, hash.size)

        // Deterministic
        val hash2 = hashOperatorSpecificPayload(txHash, operatorKey)
        assertArrayEquals(hash, hash2)

        // Different inputs should produce different hashes
        val differentKey = ByteArray(33).apply { fill(0x03) }
        val hash3 = hashOperatorSpecificPayload(txHash, differentKey)
        assertFalse(hash.contentEquals(hash3))
    }

    @Test
    fun testOperatorSpecificPayloadHashInvalidInputs() {
        try {
            hashOperatorSpecificPayload(
                finalTokenTransactionHash = ByteArray(16), // wrong length
                operatorIdentityPublicKey = ByteArray(33),
            )
            fail("Should have thrown for invalid hash length")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            hashOperatorSpecificPayload(
                finalTokenTransactionHash = ByteArray(32),
                operatorIdentityPublicKey = ByteArray(0), // empty
            )
            fail("Should have thrown for empty operator key")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // MARK: - Operator Keys

    @Test
    fun testCollectOperatorIdentityPublicKeys() {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = testMnemonic)
        val keys = wallet.collectOperatorIdentityPublicKeys()
        // Mainnet default has 3 operators with known non-empty public keys.
        assertEquals(3, keys.size)
        for (i in 0 until keys.size - 1) {
            val a = keys[i].toByteArray()
            val b = keys[i + 1].toByteArray()
            assertTrue("Keys should be lexicographically sorted", lexCompare(a, b) < 0)
        }
    }

    // MARK: - Create/Mint Validation

    @Test
    fun testCreateTokenValidationNameTooLong() = runBlocking {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = testMnemonic)
        try {
            wallet.createToken(
                tokenName = "A".repeat(21),
                tokenTicker = "TST",
                decimals = 8u,
                isFreezable = false,
            )
            fail("Should have thrown for name too long")
        } catch (e: SparkError.TokenValidationFailed) {
            // expected
        }
    }

    @Test
    fun testCreateTokenValidationEmptyName() = runBlocking {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = testMnemonic)
        try {
            wallet.createToken(
                tokenName = "",
                tokenTicker = "TST",
                decimals = 8u,
                isFreezable = false,
            )
            fail("Should have thrown for empty name")
        } catch (e: SparkError.TokenValidationFailed) {
            // expected
        }
    }

    @Test
    fun testCreateTokenValidationTickerTooLong() = runBlocking {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = testMnemonic)
        try {
            wallet.createToken(
                tokenName = "Test",
                tokenTicker = "TOOLONG",
                decimals = 8u,
                isFreezable = false,
            )
            fail("Should have thrown for ticker too long")
        } catch (e: SparkError.TokenValidationFailed) {
            // expected
        }
    }

    @Test
    fun testCreateTokenValidationDecimalsTooHigh() = runBlocking {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = testMnemonic)
        try {
            wallet.createToken(
                tokenName = "Test",
                tokenTicker = "TST",
                decimals = 256u,
                isFreezable = false,
            )
            fail("Should have thrown for decimals > 255")
        } catch (e: SparkError.TokenValidationFailed) {
            // expected
        }
    }

    @Test
    fun testMintTokensValidationZeroAmount() = runBlocking {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = testMnemonic)
        val fakeTokenId = encodeBech32mTokenIdentifier(ByteArray(32), SparkNetwork.MAINNET)
        try {
            wallet.mintTokens(tokenIdentifier = fakeTokenId, tokenAmount = BigInteger.ZERO)
            fail("Should have thrown for zero amount")
        } catch (e: SparkError.TokenValidationFailed) {
            // expected
        }
    }
}

/**
 * Token balances against the operator stand-in, which refuses metadata queries for more than 500
 * tokens as the operators do. Port of the Swift SDK's `TokenBalanceTests`.
 */
class TokenBalanceTests {
    private fun outputs(kinds: Int): List<OutputWithPreviousTransactionData> = (0 until kinds).map { index ->
        OutputWithPreviousTransactionData.newBuilder()
            .setOutput(
                TokenOutput.newBuilder()
                    .setTokenIdentifier((ByteArray(28) + java.nio.ByteBuffer.allocate(4).putInt(index).array()).toByteString())
                    .setTokenAmount(encodeUInt128(BigInteger.ONE).toByteString())
                    .setStatus(spark_token.TokenOutputStatus.TOKEN_OUTPUT_STATUS_AVAILABLE),
            )
            .build()
    }

    @Test(timeout = 60_000)
    fun metadataIsAskedFor500TokensAtATimeSoAnyNumberOfTokensCanBeListed() = runBlocking {
        val state = FakeOperatorState { false }
        state.setTokenOutputs(outputs(kinds = 1_200))
        val balances = withFakeOperator(state) { it.getTokenBalances() }
        assertEquals(1_200, balances.size)
        assertTrue(balances.all { it.ownedBalance == BigInteger.ONE && it.availableToSendBalance == BigInteger.ONE })
        assertEquals(listOf(500, 500, 200), state.metadataRequestSizes)
    }

    @Test(timeout = 60_000)
    fun tokensThatCannotBeReadFailGetTokenBalances() = runBlocking {
        val state = FakeOperatorState { false }
        state.setTokenOutputs(outputs(kinds = 3))
        state.failsTokenMetadata = true
        withFakeOperator(state) { wallet ->
            expectGrpcFailure { wallet.getTokenBalances() }
            // getBalance keeps the sats: see BalanceSummaryTests.
            assertTrue(wallet.getBalance().tokenBalances.isEmpty())
        }
    }

    @Test(timeout = 60_000)
    fun onlyAvailableOutputsAreAvailableToSend() = runBlocking {
        val state = FakeOperatorState { false }
        state.setTokenOutputs(
            outputs(kinds = 1) + outputs(kinds = 1).map {
                it.toBuilder().setOutput(it.output.toBuilder().setStatus(spark_token.TokenOutputStatus.TOKEN_OUTPUT_STATUS_PENDING_OUTBOUND)).build()
            },
        )
        val balance = withFakeOperator(state) { it.getTokenBalances() }.single()
        assertEquals(BigInteger.TWO, balance.ownedBalance)
        assertEquals(BigInteger.ONE, balance.availableToSendBalance)
    }
}

/**
 * `createToken`'s checks, ported from the reference SDK's `token-create.test.ts`, with the
 * operators' NFC rule (`TokenMetadata.ValidatePartial`). Port of the Swift SDK's
 * `TokenCreationParameterTests`.
 */
class TokenCreationParameterTests {
    private fun validate(name: String, ticker: String, decimals: UInt = 0u, extra: ByteArray? = null) =
        validateTokenParameters(tokenName = name, tokenTicker = ticker, decimals = decimals, extraMetadata = extra)

    @Test
    fun accepted() {
        for ((name, ticker) in listOf(
            "abc" to "AAA", // shortest name
            "12345678901234567890" to "AAA", // longest name
            "Token" to "ABC", // shortest ticker
            "Token" to "ABCDEF", // longest ticker
            "ABCDEFGHIJKLMNOPQ" to "AAA",
            "Tok🚀n" to "TOK", // 8 bytes: the rocket is 4
            "Café" to "ÉCU", // precomposed é and É: NFC
        )) {
            validate(name, ticker)
        }
    }

    @Test
    fun refused() {
        for ((name, ticker) in listOf(
            "ab" to "AAA", // name too short
            "123456789012345678901" to "AAA", // name too long
            "Token" to "AB", // ticker too short
            "Token" to "ABCDEFG", // ticker too long
            "Café" to "TOK", // e + combining acute: not NFC
            "Token" to "ÉCU", // ticker not NFC
        )) {
            val error = expectSparkError("$name / $ticker") { validate(name, ticker) }
            assertTrue(error.toString(), error is SparkError.TokenValidationFailed)
        }
    }

    @Test
    fun decimalsUpTo255AndExtraMetadataUpTo1024Bytes() {
        validate("Token", "TOK", decimals = 255u, extra = ByteArray(1024))
        expectSparkError { validate("Token", "TOK", decimals = 256u) }
        expectSparkError { validate("Token", "TOK", extra = ByteArray(1025)) }
    }
}
