package gy.pig.spark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from the Swift SDK's `BitcoinPrimitivesTests.swift`, same vectors.

/** bech32 characters → 5-bit words */
private fun words(s: String): List<Int> = s.map { "qpzry9x8gf2tvdw0s3jn54khce6mua7l".indexOf(it) }.filter { it >= 0 }

/** The Bitcoin genesis block coinbase transaction (legacy serialisation). */
private const val GENESIS_COINBASE_HEX =
    "01000000010000000000000000000000000000000000000000000000000000000000000000ffffffff4d04ffff001d0104455468652054696d6573" +
        "2030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062" +
        "616e6b73ffffffff0100f2052a01000000434104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4c" +
        "ef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000"
private const val GENESIS_COINBASE_TXID = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"

/** A witness transaction with two inputs and two outputs, built by hand. */
private fun sampleWitnessTransaction(): RawTransaction = RawTransaction(
    version = 2u,
    inputs = listOf(
        RawTransaction.Input(
            previousTxid = bytes(0x11, 32),
            previousIndex = 1u,
            scriptSig = ByteArray(0),
            sequence = 0x4000_07D0u,
            witness = listOf(bytes(0xAA, 64)),
        ),
        RawTransaction.Input(
            previousTxid = bytes(0x22, 32),
            previousIndex = 0u,
            scriptSig = byteArrayOf(0x51),
            sequence = 0xFFFF_FFFFu,
            witness = emptyList(),
        ),
    ),
    outputs = listOf(
        RawTransaction.Output(value = 100_000uL, scriptPubKey = hex("5120") + bytes(0x33, 32)),
        RawTransaction.Output(value = 955uL, scriptPubKey = hex("0014") + bytes(0x44, 20)),
    ),
    locktime = 0u,
    hasWitnessSerialization = true,
)

/** RawTransaction parser */
class RawTransactionTests {

    @Test
    fun parsesTheGenesisCoinbaseAndReproducesItsTxid() {
        val tx = RawTransaction.parse(hex(GENESIS_COINBASE_HEX))
        assertEquals(1u, tx.version)
        assertFalse(tx.hasWitnessSerialization)
        assertEquals(1, tx.inputs.size)
        assertArrayEquals(ByteArray(32), tx.inputs[0].previousTxid)
        assertEquals(0xFFFF_FFFFu, tx.inputs[0].previousIndex)
        assertEquals(0x4d, tx.inputs[0].scriptSig.size)
        assertEquals(0xFFFF_FFFFu, tx.inputs[0].sequence)
        assertEquals(1, tx.outputs.size)
        assertEquals(5_000_000_000uL, tx.outputs[0].value)
        assertEquals(0x43, tx.outputs[0].scriptPubKey.size)
        assertEquals(0u, tx.locktime)
        assertEquals(GENESIS_COINBASE_TXID, tx.txidHex)
        assertArrayEquals(hex(GENESIS_COINBASE_TXID).reversedArray(), tx.txid)
        assertArrayEquals(hex(GENESIS_COINBASE_HEX), tx.serialized(includeWitness = true))
        assertArrayEquals(tx.txid, computeTxId(hex(GENESIS_COINBASE_HEX)))
    }

    @Test
    fun witnessTransactionsRoundTripAndTheTxidIgnoresWitnessData() {
        val tx = sampleWitnessTransaction()
        val bytes = tx.serialized(includeWitness = true)
        // marker + flag present
        assertTrue(bytes[4] == 0x00.toByte() && bytes[5] == 0x01.toByte())
        val parsed = RawTransaction.parse(bytes)
        assertEquals(tx, parsed)
        assertEquals(1, parsed.inputs[0].witness.size)
        assertArrayEquals(bytes(0xAA, 64), parsed.inputs[0].witness[0])
        assertTrue(parsed.inputs[1].witness.isEmpty())

        // The legacy serialisation is the version, inputs, outputs and locktime only.
        val legacy = hex("02000000") + byteArrayOf(0x02) +
            bytes(0x11, 32) + hex("01000000") + byteArrayOf(0x00) + hex("d0070040") +
            bytes(0x22, 32) + hex("00000000") + hex("0151") + hex("ffffffff") +
            byteArrayOf(0x02) +
            hex("a086010000000000") + byteArrayOf(0x22) + hex("5120") + bytes(0x33, 32) +
            hex("bb03000000000000") + byteArrayOf(0x16) + hex("0014") + bytes(0x44, 20) +
            hex("00000000")
        assertArrayEquals(legacy, tx.serialized(includeWitness = false))

        val stripped = tx.copy(hasWitnessSerialization = false, inputs = tx.inputs.map { it.copy(witness = emptyList()) })
        assertEquals(stripped, RawTransaction.parse(legacy))
        assertArrayEquals(stripped.txid, tx.txid)
        assertEquals(0x4000_07D0u, tx.firstInputSequence)
        assertEquals(0x4000_07D0u, parseSequenceFromRawTx(bytes))
        assertEquals(955uL, parseTxOutput(bytes, 1u).value)
    }

    @Test
    fun everyTruncationOfAValidTransactionThrowsInsteadOfCrashing() {
        for (raw in listOf(hex(GENESIS_COINBASE_HEX), sampleWitnessTransaction().serialized(includeWitness = true))) {
            for (length in 0 until raw.size) {
                expectSparkError("prefix of $length bytes") { RawTransaction.parse(raw.copyOf(length)) }
            }
        }
    }

    @Test
    fun trailingBytesBadSegwitFlagsAndHostileCountsAreRejected() {
        expectSparkError { RawTransaction.parse(hex(GENESIS_COINBASE_HEX) + byteArrayOf(0x00)) }
        expectSparkError { RawTransaction.parse(hex("02000000") + hex("0002") + hex("00000000")) }
        // input count 0xFFFFFFFF with nothing behind it
        expectSparkError { RawTransaction.parse(hex("02000000") + hex("feffffffff")) }
        // varbytes length far beyond the buffer
        expectSparkError {
            RawTransaction.parse(hex("02000000") + hex("01") + ByteArray(36) + hex("fdffff") + hex("00"))
        }
        expectSparkError { RawTransaction.parse(ByteArray(0)) }
    }

    @Test
    fun outputLookupInputAppendsAndWitnessAttachmentAreBoundsChecked() {
        val tx = sampleWitnessTransaction()
        val bytes = tx.serialized(includeWitness = true)
        expectSparkError { tx.output(2u) }
        expectSparkError { parseTxOutput(bytes, 7u) }

        val connector = RawTransaction.Input(previousTxid = bytes(0x55, 32), previousIndex = 3u)
        val extended = RawTransaction.parse(addInputToRawTx(bytes, connector))
        assertEquals(3, extended.inputs.size)
        assertArrayEquals(bytes(0x55, 32), extended.inputs[2].previousTxid)
        assertEquals(3u, extended.inputs[2].previousIndex)
        assertEquals(0xFFFF_FFFFu, extended.inputs[2].sequence)
        assertTrue(extended.inputs[2].witness.isEmpty())
        assertEquals(tx.inputs[0].witness.size, extended.inputs[0].witness.size)
        assertArrayEquals(tx.inputs[0].witness[0], extended.inputs[0].witness[0])
        assertEquals(tx.outputs, extended.outputs)
        assertTrue(extended.hasWitnessSerialization)

        // Legacy serialisation stays legacy after an append.
        val legacyExtended = RawTransaction.parse(addInputToRawTx(hex(GENESIS_COINBASE_HEX), connector))
        assertEquals(2, legacyExtended.inputs.size)
        assertFalse(legacyExtended.hasWitnessSerialization)

        val spend = constructSpendTx(
            depositTxId = GENESIS_COINBASE_TXID,
            outputIndex = 0u,
            destinationAddress = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
            amountSats = 12_345uL,
            network = SparkNetwork.MAINNET,
        )
        val parsedSpend = RawTransaction.parse(spend)
        assertEquals(3u, parsedSpend.version)
        assertArrayEquals(hex(GENESIS_COINBASE_TXID).reversedArray(), parsedSpend.inputs[0].previousTxid)
        assertArrayEquals(hex("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"), parsedSpend.outputs[0].scriptPubKey)
        assertEquals(12_345uL, parsedSpend.outputs[0].value)
        val signed = RawTransaction.parse(addWitnessToTx(spend, bytes(0xCC, 64)))
        assertEquals(1, signed.inputs[0].witness.size)
        assertArrayEquals(bytes(0xCC, 64), signed.inputs[0].witness[0])
        assertArrayEquals(parsedSpend.txid, signed.txid)

        expectSparkError { txidBytesFromDisplayHex("abc") }
        expectSparkError { txidBytesFromDisplayHex("zz".repeat(32)) }
        assertFalse(isZeroTimelockNode(hex(GENESIS_COINBASE_HEX)))
    }

    @Test
    fun timelockHelpersTreatUnparseableRefundTransactionsAsExhaustedRatherThanCrashing() {
        assertFalse(timelockCanDecrement(ByteArray(0)))
        assertFalse(timelockCanDecrement(byteArrayOf(1, 2, 3)))
        expectSparkError { computeNextSequences(byteArrayOf(0xFF.toByte())) }
        val node = spark.Spark.TreeNode.newBuilder().setRefundTx(byteArrayOf(0x00, 0x01).toByteString()).build()
        val leaf = SparkLeaf(id = "x", treeID = "t", valueSats = 1, status = "AVAILABLE", node = node)
        assertEquals(0u, leaf.refundTimelockBlocks)
    }
}

/** Bech32 and Bech32m */
class Bech32Tests {

    @Test
    fun bip173ValidVectorsDecodeAsBech32() {
        for (s in listOf(
            "A12UEL5L",
            "a12uel5l",
            "an83characterlonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1tt5tgs",
            "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw",
            "split1checkupstagehandshakeupstreamerranterredcaperred2y9e3w",
        )) {
            assertEquals(s, Bech32.Encoding.BECH32, Bech32.decode(s, maxLength = 90).encoding)
        }
    }

    @Test
    fun bip350ValidVectorsDecodeAsBech32m() {
        for (s in listOf("A1LQFN3A", "a1lqfn3a", "abcdef1l7aum6echk45nj3s0wdvt2fg8x9yrzpqzd3ryx", "?1v759aa")) {
            assertEquals(s, Bech32.Encoding.BECH32M, Bech32.decode(s, maxLength = 90).encoding)
        }
    }

    @Test
    fun malformedStringsAreRejected() {
        for (s in listOf(
            "an84characterslonghumanreadablepartthatcontainsthenumber1andtheexcludedcharactersbio1569pvx",
            "pzry9x8gf2tvdw0s3jn54khce6mua7l", // no separator
            "1pzry9x8gf2tvdw0s3jn54khce6mua7l", // empty hrp
            "x1b4n0q5v", // invalid data character
            "li1dgmt3", // too short checksum
            "A1G7SGD8", // checksum computed with uppercase hrp
            "10a06t8",
            "1qzzfhee", // empty hrp
            "in1muywd",
            "mm1crxm3i",
            "au1s5cgom",
            "M1VUXWEZ",
            "16plkw9",
            "1p2gdwpf",
            "bc1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", // mixed case
            "",
        )) {
            expectSparkError(s) { Bech32.decode(s, maxLength = 90) }
        }
    }

    @Test
    fun encodingRoundTripsForBothChecksumVariants() {
        val payload = List(20) { 7 }
        for (encoding in listOf(Bech32.Encoding.BECH32, Bech32.Encoding.BECH32M)) {
            val encoded = Bech32.encode("spark", payload, encoding)
            val decoded = Bech32.decode(encoded, maxLength = null)
            assertEquals("spark", decoded.hrp)
            assertEquals(payload, decoded.data)
            assertEquals(encoding, decoded.encoding)
        }
        // Spark address helper still requires bech32m.
        val bech32Only = Bech32.encode("spark", payload, Bech32.Encoding.BECH32)
        expectSparkError { Bech32m.decodeBech32m(bech32Only) }
    }
}

/** Bitcoin address decoding */
class BitcoinAddressTests {

    private val p2wpkhScript = "0014751e76e8199196d454941c45d1b3a323f1433bd6"

    @Test
    fun segwitAddressesDecodeToTheRightScriptAndKind() {
        val vectors = listOf(
            Triple("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", BitcoinAddress.Kind.P2WPKH, p2wpkhScript),
            Triple("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", BitcoinAddress.Kind.P2WPKH, p2wpkhScript),
            Triple(
                "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3",
                BitcoinAddress.Kind.P2WSH,
                "00201863143c14c5166804bd19203356da136c985678cd4d27a1b8c6329604903262",
            ),
            Triple(
                "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
                BitcoinAddress.Kind.P2TR,
                "512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            ),
        )
        for ((address, kind, script) in vectors) {
            val decoded = BitcoinAddress.decode(address, SparkNetwork.MAINNET)
            assertEquals(address, kind, decoded.kind)
            assertArrayEquals(address, hex(script), decoded.scriptPubKey)
            assertArrayEquals(hex(script), BitcoinAddress.scriptPubKey(address, SparkNetwork.MAINNET))
        }
        // BOLT-11 P2TR fallback example: program is the decoded 5-bit words after the version.
        val fallback = "bc1pptdvg0d2nj99568qn6ssdy4cygnwuxgw2ukmnwgwz7jpqjz2kszse2s3lm"
        val program = Bech32.fromWords(Bech32.decode(fallback, maxLength = 90).data.drop(1))!!
        val decoded = BitcoinAddress.decode(fallback, SparkNetwork.MAINNET)
        assertArrayEquals(hex("5120") + program, decoded.scriptPubKey)
        assertEquals(32, program.size)
    }

    @Test
    fun legacyBase58CheckAddressesDecodeForBothNetworks() {
        val hash160 = hex("751e76e8199196d454941c45d1b3a323f1433bd6")
        val p2pkh = hex("76a914") + hash160 + hex("88ac")
        val p2sh = hex("a914") + hash160 + hex("87")
        assertEquals(
            BitcoinAddress.Decoded(BitcoinAddress.Kind.P2PKH, p2pkh),
            BitcoinAddress.decode("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH", SparkNetwork.MAINNET),
        )
        assertEquals(
            BitcoinAddress.Decoded(BitcoinAddress.Kind.P2SH, p2sh),
            BitcoinAddress.decode("3CNHUhP3uyB9EUtRLsmvFUmvGdjGdkTxJw", SparkNetwork.MAINNET),
        )
        assertEquals(
            BitcoinAddress.Decoded(BitcoinAddress.Kind.P2PKH, p2pkh),
            BitcoinAddress.decode("mrCDrCybB6J1vRfbwM5hemdJz73FwDBC8r", SparkNetwork.REGTEST),
        )
        assertEquals(
            BitcoinAddress.Decoded(BitcoinAddress.Kind.P2SH, p2sh),
            BitcoinAddress.decode("2N3vVYSK5XRgVSGWy21PnsRmBUywSQNdCsf", SparkNetwork.REGTEST),
        )

        // BOLT-11 fallback-address examples, cross-checked against the 5-bit words in the spec.
        val rustyHash = Bech32.fromWords(words("qjmp7lwpagxun9pygexvgpjdc4jdj85f"))!!
        assertArrayEquals(
            hex("76a914") + rustyHash + hex("88ac"),
            BitcoinAddress.decode("1RustyRX2oai4EYYDpQGWvEL62BBGqN9T", SparkNetwork.MAINNET).scriptPubKey,
        )
        val p2shHash = Bech32.fromWords(words("3a24vwu6r8ejrss3axul8rxldph2q7z9"))!!
        assertArrayEquals(
            hex("a914") + p2shHash + hex("87"),
            BitcoinAddress.decode("3EktnHQD7RiAE6uzMj2ZifT9YgRrkSgzQX", SparkNetwork.MAINNET).scriptPubKey,
        )
    }

    @Test
    fun wrongNetworkBadChecksumsWrongWitnessRulesAndExoticVersionsAreRefused() {
        val cases = listOf(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4" to SparkNetwork.REGTEST, // mainnet address on regtest
            "mrCDrCybB6J1vRfbwM5hemdJz73FwDBC8r" to SparkNetwork.MAINNET, // regtest legacy on mainnet
            "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH" to SparkNetwork.REGTEST,
            "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx" to SparkNetwork.MAINNET, // testnet
            "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx" to SparkNetwork.REGTEST,
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5" to SparkNetwork.MAINNET, // bad checksum
            "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMJ" to SparkNetwork.MAINNET, // bad base58 checksum
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh" to SparkNetwork.MAINNET, // v0 with bech32m checksum
            "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P" to SparkNetwork.MAINNET, // v0 with 16-byte program
            "bc1pw5dgrnzv" to SparkNetwork.MAINNET, // v1 with 1-byte program
            "bc1zw508d6qejxtdg4y5r3zarvaryvaxxpcs" to SparkNetwork.MAINNET, // v2: valid bech32m, unsupported here
            "bc1gmk9yu" to SparkNetwork.MAINNET, // empty data
            "bc1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4" to SparkNetwork.MAINNET, // mixed case
            "" to SparkNetwork.MAINNET,
            "not an address" to SparkNetwork.MAINNET,
        )
        for ((address, network) in cases) {
            expectSparkError("$address on $network") { BitcoinAddress.decode(address, network) }
        }
    }

    @Test
    fun p2trEncodingRoundTripsOnBothNetworks() {
        val program = hex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        val mainnet = BitcoinAddress.encodeP2TR(program, SparkNetwork.MAINNET)
        assertEquals("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", mainnet)
        val regtest = BitcoinAddress.encodeP2TR(program, SparkNetwork.REGTEST)
        assertTrue(regtest.startsWith("bcrt1p"))
        assertArrayEquals(hex("5120") + program, BitcoinAddress.decode(regtest, SparkNetwork.REGTEST).scriptPubKey)
        expectSparkError { BitcoinAddress.decode(regtest, SparkNetwork.MAINNET) }
        assertEquals(mainnet, BitcoinAddress.p2trAddress(hex("5120") + program, SparkNetwork.MAINNET))
        expectSparkError { BitcoinAddress.p2trAddress(hex("0014") + bytes(1, 20), SparkNetwork.MAINNET) }
    }
}
