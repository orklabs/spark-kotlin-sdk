package gy.pig.spark

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import spark.Spark
import uniffi.spark_frost.computeMultiInputSighashUniffi
import uniffi.spark_frost.getTaprootPubkey
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

private val curveN: BigInteger = KeyDerivation.ecDomainParams.n

private fun randomPrivateKey(): ByteArray {
    val random = SecureRandom()
    while (true) {
        val candidate = ByteArray(32).also { random.nextBytes(it) }
        val value = BigInteger(1, candidate)
        if (value.signum() > 0 && value < curveN) return candidate
    }
}

private fun publicKey(privateKey: ByteArray): ByteArray = KeyDerivation.compressedPublicKey(privateKey)

private fun bytes32(value: BigInteger): ByteArray {
    val raw = value.toByteArray()
    return if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
}

/** Any KeyDerivation signs with the key it is handed. */
private val ecdsa = KeyDerivation.fromMnemonic(
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
    account = 0,
)

/**
 * A deposit address as the operators produce it (`deposit_handler.go`), with keys the test
 * controls: the verifying key is the wallet's signing key plus the operators' share, the address
 * pays it, the proof of possession is the operators' share (BIP-86 tweaked) signing the tagged hash
 * of identity key, operator key and address, and every operator signs sha256(address).
 */
private class SyntheticDeposit {
    val userSigningPublicKey: ByteArray = publicKey(randomPrivateKey())
    val identityPublicKey: ByteArray = publicKey(randomPrivateKey())
    val operatorShare: ByteArray = randomPrivateKey()
    val operatorKeys: List<ByteArray> = (1..3).map { randomPrivateKey() }
    val operatorConfigs: List<SigningOperatorConfig> = operatorKeys.mapIndexed { index, key ->
        SigningOperatorConfig(
            address = "https://${index + 1}.example",
            identifier = "0".repeat(63) + "${index + 1}",
            identityPublicKeyHex = publicKey(key).toHexString(),
        )
    }
    val coordinatorIdentifier: String get() = operatorConfigs[0].identifier

    val verifyingKey: ByteArray
        get() {
            val curve = KeyDerivation.ecDomainParams.curve
            return curve.decodePoint(userSigningPublicKey).add(curve.decodePoint(publicKey(operatorShare))).normalize().getEncoded(true)
        }

    /** BIP-340 signature by the BIP-86 tweak of the operators' share. */
    fun proofOfPossession(identity: ByteArray, address: String): ByteArray {
        val hasher = SparkHasher(listOf("spark", "deposit", "proof_of_possession"))
        hasher.addBytes(identity)
        hasher.addBytes(publicKey(operatorShare))
        hasher.addBytes(address.toByteArray())
        // BIP-341 key-path tweak: normalise the internal key to even y, then add hashTapTweak(x).
        val point = KeyDerivation.ecDomainParams.g.multiply(BigInteger(1, operatorShare)).normalize()
        val d = if (point.affineYCoord.testBitZero()) curveN.subtract(BigInteger(1, operatorShare)) else BigInteger(1, operatorShare)
        val tweak = BigInteger(1, Bip340.taggedHash("TapTweak", bytes32(point.affineXCoord.toBigInteger())))
        val tweaked = bytes32(d.add(tweak).mod(curveN))
        return bip340Sign(tweaked, hasher.hash())
    }

    fun addressSignature(key: ByteArray, address: String): ByteArray = ecdsa.signECDSA(sha256(address.toByteArray()), key)

    fun address(network: SparkNetwork = SparkNetwork.MAINNET, withCoordinatorSignature: Boolean = true): Spark.Address {
        val addressString = leafNodeAddress(verifyingKey, network)
        val proof = Spark.DepositAddressProof.newBuilder()
            .setProofOfPossessionSignature(proofOfPossession(identityPublicKey, addressString).toByteString())
        operatorConfigs.zip(operatorKeys).forEach { (config, key) ->
            if (withCoordinatorSignature || config.identifier != coordinatorIdentifier) {
                proof.putAddressSignatures(config.identifier, addressSignature(key, addressString).toByteString())
            }
        }
        return Spark.Address.newBuilder()
            .setAddress(addressString)
            .setVerifyingKey(verifyingKey.toByteString())
            .setDepositAddressProof(proof)
            .build()
    }

    fun verify(address: Spark.Address, isStatic: Boolean, network: SparkNetwork = SparkNetwork.MAINNET) = DepositAddressVerifier.verify(
        address,
        userSigningPublicKey = userSigningPublicKey,
        identityPublicKey = identityPublicKey,
        isStatic = isStatic,
        config = SparkConfig(network = network, signingOperators = operatorConfigs),
    )
}

/** Ported from the Swift SDK's `DepositAddressVerificationTests.swift`; the address math needs the FROST library. */
class DepositAddressVerificationTests {
    @Before
    fun requireFrost() = NativeFrost.assume()

    @Test
    fun anAddressWithAValidProofOfPossessionAndOperatorSignaturesIsAccepted() {
        val deposit = SyntheticDeposit()
        assertEquals(33, getTaprootPubkey(publicKey(deposit.operatorShare)).size)
        deposit.verify(deposit.address(), isStatic = false)
        deposit.verify(deposit.address(), isStatic = true)
        deposit.verify(deposit.address(SparkNetwork.REGTEST), isStatic = false, network = SparkNetwork.REGTEST)
    }

    @Test
    fun theCoordinatorsOwnSignatureIsRequiredForStaticAddressesOnly() {
        val deposit = SyntheticDeposit()
        val withoutCoordinator = deposit.address(withCoordinatorSignature = false)
        deposit.verify(withoutCoordinator, isStatic = false)
        expectSparkError { deposit.verify(withoutCoordinator, isStatic = true) }
    }

    @Test
    fun aMissingOrForgedOperatorSignatureIsRefused() {
        val deposit = SyntheticDeposit()
        val base = deposit.address()
        val missing = base.toBuilder().setDepositAddressProof(
            base.depositAddressProof.toBuilder().removeAddressSignatures(deposit.operatorConfigs[1].identifier),
        ).build()
        expectSparkError { deposit.verify(missing, isStatic = false) }
        val forged = base.toBuilder().setDepositAddressProof(
            base.depositAddressProof.toBuilder()
                .putAddressSignatures(deposit.operatorConfigs[2].identifier, deposit.addressSignature(randomPrivateKey(), base.address).toByteString()),
        ).build()
        expectSparkError { deposit.verify(forged, isStatic = false) }
        val garbage = base.toBuilder().setDepositAddressProof(
            base.depositAddressProof.toBuilder().putAddressSignatures(deposit.operatorConfigs[2].identifier, byteArrayOf(1, 2, 3).toByteString()),
        ).build()
        expectSparkError { deposit.verify(garbage, isStatic = false) }
    }

    @Test
    fun aProofOfPossessionThatIsTamperedWithOrMadeForOtherDataIsRefused() {
        val deposit = SyntheticDeposit()
        val base = deposit.address()
        val signature = base.depositAddressProof.proofOfPossessionSignature.toByteArray().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val tampered = base.toBuilder().setDepositAddressProof(
            base.depositAddressProof.toBuilder().setProofOfPossessionSignature(signature.toByteString())
        ).build()
        expectSparkError { deposit.verify(tampered, isStatic = false) }
        val stranger = deposit.proofOfPossession(publicKey(randomPrivateKey()), base.address)
        val otherIdentity = base.toBuilder().setDepositAddressProof(
            base.depositAddressProof.toBuilder().setProofOfPossessionSignature(stranger.toByteString())
        ).build()
        expectSparkError { deposit.verify(otherIdentity, isStatic = false) }
        expectSparkError { deposit.verify(base.toBuilder().clearDepositAddressProof().build(), isStatic = false) }
    }

    @Test
    fun anAddressThatDoesNotPayTheVerifyingKeyIsRefusedEvenWithGenuineSignaturesOverIt() {
        val deposit = SyntheticDeposit()
        // A coordinator's own address, signed by every operator key the test holds.
        val foreign = leafNodeAddress(publicKey(randomPrivateKey()), SparkNetwork.MAINNET)
        val proof = Spark.DepositAddressProof.newBuilder()
            .setProofOfPossessionSignature(deposit.proofOfPossession(deposit.identityPublicKey, foreign).toByteString())
        deposit.operatorConfigs.zip(deposit.operatorKeys).forEach { (config, key) ->
            proof.putAddressSignatures(config.identifier, deposit.addressSignature(key, foreign).toByteString())
        }
        val swapped = deposit.address().toBuilder().setAddress(foreign).setDepositAddressProof(proof).build()
        expectSparkError { deposit.verify(swapped, isStatic = true) }
        // An address for another network is refused too.
        expectSparkError { deposit.verify(deposit.address(SparkNetwork.REGTEST), isStatic = false) }
    }

    @Test
    fun degenerateKeysAreRefusedWithoutCrashing() {
        val key = publicKey(randomPrivateKey())
        expectSparkError { DepositAddressVerifier.subtractPublicKeys(key, key) }
        expectSparkError { DepositAddressVerifier.subtractPublicKeys(byteArrayOf(0x02), key) }
        assertTrue(!DepositAddressVerifier.verifySchnorr(ByteArray(64), ByteArray(32), taprootInternalKey = key))
        assertTrue(!DepositAddressVerifier.verifySchnorr(ByteArray(10), ByteArray(32), taprootInternalKey = key))
    }

    @Test(timeout = 60_000)
    fun aCoordinatorHandingOutAnAddressWithoutAProofIsRefusedBeforeTheWalletShowsIt() = runBlocking<Unit> {
        val unproven = Spark.Address.newBuilder()
            .setAddress("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr")
            .setVerifyingKey(hex("02cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115").toByteString())
            .build()
        val state = FakeOperatorState(depositAddress = unproven) { false }
        withFakeOperator(state) { wallet ->
            expectSparkErrorSuspending { wallet.getDepositAddress() }
            expectSparkErrorSuspending { wallet.getStaticDepositAddress() }
        }
    }
}

private fun le32(value: UInt): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

private fun le64(value: ULong): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong()).array()

/**
 * The pieces of a static-deposit refund, checked against what the operators verify
 * (`static_deposit_handler.go`, `internal_deposit_handler.go`) and what the reference SDK sends.
 * Ported from the Swift SDK's `StaticDepositRefundTests.swift`.
 */
class StaticDepositRefundTests {
    internal companion object {
        const val TXID = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"
        const val DESTINATION = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
        val DESTINATION_SCRIPT: ByteArray = hex("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")

        /**
         * BIP-341 key-path sighash with SIGHASH_DEFAULT for input 0 of a one-input transaction —
         * what the operators compute (`sighash.FromTx`), written out independently.
         */
        fun taprootSighash(tx: RawTransaction, prevoutScript: ByteArray, prevoutValue: ULong): ByteArray {
            var prevouts = ByteArray(0)
            var sequences = ByteArray(0)
            for (input in tx.inputs) {
                prevouts += input.previousTxid + le32(input.previousIndex)
                sequences += le32(input.sequence)
            }
            var outputs = ByteArray(0)
            for (output in tx.outputs) {
                outputs += le64(output.value) + byteArrayOf(output.scriptPubKey.size.toByte()) + output.scriptPubKey
            }
            val scripts = byteArrayOf(prevoutScript.size.toByte()) + prevoutScript
            val message = byteArrayOf(0x00, 0x00) + le32(tx.version) + le32(tx.locktime) + sha256(prevouts) + sha256(le64(prevoutValue)) +
                sha256(scripts) + sha256(sequences) + sha256(outputs) + byteArrayOf(0x00) + le32(0u)
            return Bip340.taggedHash("TapSighash", message)
        }
    }

    @Test
    fun aDepositOutpointIsALowerCaseDisplayTxidTheOperatorsLookItUpInDisplayOrder() {
        val outpoint = DepositOutpoint(" ${TXID.uppercase()}\n", 2u)
        assertEquals(TXID, outpoint.txid)
        assertArrayEquals(hex(TXID), outpoint.displayOrderTxid)
        assertArrayEquals(hex(TXID).reversedArray(), outpoint.internalOrderTxid)
        val utxo = outpoint.utxo(Spark.Network.MAINNET)
        // `hexToBytes(depositTransactionId)` in the reference SDK.
        assertArrayEquals(hex(TXID), utxo.txid.toByteArray())
        assertEquals(2, utxo.vout)
        for (bad in listOf("", "abc", TXID.dropLast(1), TXID.dropLast(1) + "g", TXID + "00")) {
            assertTrue(bad, expectSparkError(bad) { DepositOutpoint(bad, 0u) } is SparkError.InvalidArgument)
        }
    }

    @Test
    fun theUnsignedRefundIsExactlyTheTransactionTheOperatorsRebuildV3FinalSequenceNoWitness() {
        val outpoint = DepositOutpoint(TXID, 1u)
        val spend = constructSpendTx(outpoint, DESTINATION, 12_345uL, SparkNetwork.MAINNET)
        val expected = le32(3u) + byteArrayOf(0x01) + outpoint.internalOrderTxid + le32(1u) + byteArrayOf(0x00) + le32(0xFFFF_FFFFu) +
            byteArrayOf(0x01) + le64(12_345uL) + byteArrayOf(DESTINATION_SCRIPT.size.toByte()) + DESTINATION_SCRIPT + le32(0u)
        assertArrayEquals(expected, spend)

        // Signing adds the witness without changing the transaction.
        val signature = bytes(0xCC, 64)
        val signed = RawTransaction.parse(addWitnessToTx(spend, signature))
        assertTrue(signed.hasWitnessSerialization)
        assertArrayEquals(signature, signed.inputs[0].witness.single())
        assertArrayEquals(RawTransaction.parse(spend).txid, signed.txid)
    }

    @Test
    fun theRefundSighashIsTheBip341KeyPathSighashTheOperatorsCompute() {
        NativeFrost.assume()
        val outpoint = DepositOutpoint(TXID, 0u)
        val spend = constructSpendTx(outpoint, DESTINATION, 9_000uL, SparkNetwork.MAINNET)
        val depositScript = byteArrayOf(0x51, 0x20) + bytes(0x42, 32)
        val sighash = computeMultiInputSighashUniffi(spend, 0u, listOf(depositScript), listOf(10_000uL))
        assertArrayEquals(taprootSighash(RawTransaction.parse(spend), depositScript, 10_000uL), sighash)
    }

    private fun expectedStatement(network: String, requestType: Int, credit: ULong, authorization: ByteArray): ByteArray =
        "claim_static_deposit".toByteArray() + network.toByteArray() + TXID.toByteArray() + le32(1u) + byteArrayOf(requestType.toByte()) +
            le64(credit) + authorization

    @Test
    fun theRefundStatementEndsWithTheRaw32ByteSighashAsTheOperatorsVerifyIt() {
        val outpoint = DepositOutpoint(TXID, 1u)
        val sighash = bytes(0x11, 32)
        val statement = staticDepositStatement(outpoint, SparkNetwork.MAINNET, StaticDepositRequestType.REFUND, 1_000uL, sighash)
        assertArrayEquals(expectedStatement("mainnet", 2, 1_000uL, sighash), statement)
        assertEquals(20 + 7 + 64 + 4 + 1 + 8 + 32, statement.size)
        val regtest = staticDepositStatement(outpoint, SparkNetwork.REGTEST, StaticDepositRequestType.FIXED, 1uL, byteArrayOf(0xAB.toByte()))
        assertArrayEquals(expectedStatement("regtest", 0, 1uL, byteArrayOf(0xAB.toByte())), regtest)
    }

    @Test
    fun aClaimStatementCommitsToTheQuotesCreditAndTheSspsSignatureBytes() {
        val outpoint = DepositOutpoint(TXID.uppercase(), 3u)
        val quote = DepositFeeEstimate(creditAmountSats = 49_000, quoteSignature = "3045022100aabb")
        val statement = staticDepositStatement(outpoint, SparkNetwork.MAINNET, StaticDepositRequestType.FIXED, 49_000uL, hex(quote.quoteSignature))
        val expected = "claim_static_deposit".toByteArray() + "mainnet".toByteArray() + TXID.toByteArray() + le32(3u) + byteArrayOf(0) +
            le64(49_000uL) + hex("3045022100aabb")
        assertArrayEquals(expected, statement)
        assertEquals(1_000L, staticDepositFee(50_000, quote))
    }

    @Test(timeout = 60_000)
    fun aQuoteWithNoCreditOrASignatureThatIsNotHexIsRefusedBeforeTheSspIsAsked() = runBlocking<Unit> {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            val zero = expectSparkErrorSuspending { wallet.claimStaticDeposit(TXID, quote = DepositFeeEstimate(0, "aa")) }
            assertTrue(zero is SparkError.InvalidArgument)
            val notHex = expectSparkErrorSuspending { wallet.claimStaticDeposit(TXID, quote = DepositFeeEstimate(10, "not hex")) }
            assertTrue(notHex is SparkError.InvalidResponse)
            val badTxid = expectSparkErrorSuspending { wallet.claimStaticDeposit("abc", quote = DepositFeeEstimate(10, "aa")) }
            assertTrue(badTxid is SparkError.InvalidArgument)
        }
        assertTrue(state.methods.isEmpty())
    }

    @Test(timeout = 60_000)
    fun aTxidThatIsNot64HexCharactersThrowsBeforeAnyRequest() = runBlocking<Unit> {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            for (bad in listOf("", "not a txid", "zz" + "0".repeat(62), "a b", "0".repeat(65))) {
                val error = expectSparkErrorSuspending(bad) { wallet.fetchRawTransaction(bad) }
                assertTrue(bad, error is SparkError.InvalidArgument)
            }
        }
        assertEquals(TXID, DepositOutpoint.normalizedTxid(" ${TXID.uppercase()} "))
    }
}

/**
 * Without an output index, static-deposit calls use the output that pays the wallet's static
 * deposit address, as the reference SDK's `getDepositTransactionVout` finds it.
 */
class StaticDepositVoutTests {
    private val staticAddress = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
    private val otherAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    private fun transaction(paying: List<String>): RawTransaction = RawTransaction(
        version = 2u,
        inputs = listOf(RawTransaction.Input(previousTxid = bytes(0x01, 32), previousIndex = 0u)),
        outputs = paying.map { RawTransaction.Output(1_000uL, BitcoinAddress.scriptPubKey(it, SparkNetwork.MAINNET)) },
        locktime = 0u,
        hasWitnessSerialization = false,
    )

    @Test
    fun theFirstOutputPayingAStaticDepositAddressIsUsedNoneIsAnError() {
        val addresses = listOf(staticAddress)
        assertEquals(1u, staticDepositVout(transaction(listOf(otherAddress, staticAddress)), addresses, SparkNetwork.MAINNET))
        assertEquals(0u, staticDepositVout(transaction(listOf(staticAddress, staticAddress)), addresses, SparkNetwork.MAINNET))
        expectSparkError { staticDepositVout(transaction(listOf(otherAddress)), addresses, SparkNetwork.MAINNET) }
        expectSparkError { staticDepositVout(transaction(listOf(staticAddress)), emptyList(), SparkNetwork.MAINNET) }
    }
}
