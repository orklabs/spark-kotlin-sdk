package gy.pig.spark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark
import uniffi.spark_frost.decryptEcies
import uniffi.spark_frost.randomSecretKeyBytes
import uniffi.spark_frost.splitSecretWithProofsUniffi

/**
 * Replaces the force-unwraps and unchecked indexing that let server data crash the app.
 * Ported from the Swift SDK's `RobustnessTests.swift` (operator reconciliation and input
 * validation). The share-mapping and ECIES tests need the FROST library ([NativeFrost]).
 */
class RobustnessTests {

    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val keysA = KeyDerivation.fromMnemonic(mnemonic, account = 0)
    private val keysB = KeyDerivation.fromMnemonic("ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic", account = 0)
    private val keysC = KeyDerivation.fromMnemonic(
        "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
        account = 0,
    )

    private val config: List<SigningOperatorConfig>
        get() = listOf(
            SigningOperatorConfig(address = "https://a", identifier = "op1", identityPublicKeyHex = keysA.identityPublicKey.toHexString()),
            SigningOperatorConfig(address = "https://b", identifier = "op2", identityPublicKeyHex = keysB.identityPublicKey.toHexString()),
            SigningOperatorConfig(address = "https://c", identifier = "op3", identityPublicKeyHex = keysC.identityPublicKey.toHexString()),
        )

    private fun server(vararg entries: Pair<String, Long>): Map<String, Spark.SigningOperatorInfo> = entries.associate { (id, index) ->
        id to Spark.SigningOperatorInfo.newBuilder()
            .setIdentifier(id)
            .setIndex(index)
            .setPublicKey(bytes(0xEE, 33).toByteString()) // never used: keys come from config
            .build()
    }

    @Test
    fun theCoordinatorsOperatorListIsReconciledWithTheConfiguration() {
        val targets = KeyTweakHelper.matchOperators(server("op2" to 1, "op3" to 2, "op1" to 0), config)
        assertEquals(listOf("op1", "op2", "op3"), targets.map { it.soID })
        assertEquals(listOf(1u, 2u, 3u), targets.map { it.shareIndex })
        assertArrayEquals(keysA.identityPublicKey, targets[0].identityPublicKey)
        assertArrayEquals(keysC.identityPublicKey, targets[2].identityPublicKey)

        expectSparkError { KeyTweakHelper.matchOperators(emptyMap(), config) }
        expectSparkError { KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 1), config) }
        expectSparkError { KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 1, "evil" to 2), config) }
        expectSparkError { KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 1, "op3" to 7), config) }
        expectSparkError { KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 0, "op3" to 2), config) }
        // uint64 on the wire: an index above Long.MAX_VALUE arrives negative and must be refused.
        expectSparkError { KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 1, "op3" to -1), config) }
        val noKey = config.toMutableList()
        noKey[1] = SigningOperatorConfig(address = "https://b", identifier = "op2", identityPublicKeyHex = "")
        expectSparkError { KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 1, "op3" to 2), noKey) }
    }

    @Test
    fun secretSharesMapToOperatorsByIndexAndAMissingShareIsAnErrorNotACrash() {
        NativeFrost.assume()
        val targets = KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 1, "op3" to 2), config)
        val secret = randomSecretKeyBytes()
        val shares = splitSecretWithProofsUniffi(secret, threshold = 2u, numShares = 3u)
        val mapped = KeyTweakHelper.shares(shares, targets)
        assertEquals(3, mapped.size)
        assertEquals(listOf("op1", "op2", "op3"), mapped.map { it.first.soID })
        assertEquals(listOf(1u, 2u, 3u), mapped.map { it.second.index })
        expectSparkError { KeyTweakHelper.shares(shares.dropLast(1), targets) }
    }

    @Test
    fun keyTweakPackagesAreEncryptedToConfiguredKeysAndRejectBadTransferIds() {
        NativeFrost.assume()
        val targets = KeyTweakHelper.matchOperators(server("op1" to 0, "op2" to 1, "op3" to 2), config)
        val signer = SparkSigner.fromMnemonic(mnemonic)
        val tweaks = targets.associate { it.soID to Spark.SendLeafKeyTweaks.getDefaultInstance() }
        val pkg = KeyTweakHelper.encryptAndSign(
            transferID = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
            perSoTweaks = tweaks,
            targets = targets,
            signer = signer,
            tag = "transfer",
        )
        assertEquals(setOf("op1", "op2", "op3"), pkg.keyTweakPackage.keys)
        assertTrue(pkg.signature.isNotEmpty())
        // Only the configured operator can decrypt its entry.
        val decrypted = decryptEcies(pkg.keyTweakPackage.getValue("op2"), keysB.identityPrivateKey)
        assertArrayEquals(Spark.SendLeafKeyTweaks.getDefaultInstance().toByteArray(), decrypted)
        assertThrows(Exception::class.java) { decryptEcies(pkg.keyTweakPackage.getValue("op2"), keysA.identityPrivateKey) }

        expectSparkError {
            KeyTweakHelper.encryptAndSign(transferID = "not-a-uuid", perSoTweaks = tweaks, targets = targets, signer = signer, tag = "transfer")
        }
        val missing = tweaks - "op3"
        expectSparkError {
            KeyTweakHelper.encryptAndSign(
                transferID = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
                perSoTweaks = missing,
                targets = targets,
                signer = signer,
                tag = "transfer",
            )
        }
    }

    @Test
    fun sparkAddressesRoundTripAndAreTiedToANetwork() {
        val key = keysA.identityPublicKey
        val mainnet = SparkAddress.encode(key, SparkNetwork.MAINNET)
        val regtest = SparkAddress.encode(key, SparkNetwork.REGTEST)
        assertTrue(mainnet.startsWith("spark1"))
        assertTrue(regtest.startsWith("sparkrt1"))
        assertArrayEquals(key, SparkAddress.decode(mainnet, SparkNetwork.MAINNET))
        assertArrayEquals(key, SparkAddress.decode(regtest, SparkNetwork.REGTEST))
        assertArrayEquals(key, SparkAddress.decode(mainnet.uppercase(), SparkNetwork.MAINNET))
        expectSparkError { SparkAddress.decode(mainnet, SparkNetwork.REGTEST) }
        expectSparkError { SparkAddress.decode(regtest, SparkNetwork.MAINNET) }

        // Legacy prefixes are still accepted.
        val payload = byteArrayOf(0x0a, 33) + key
        val legacy = Bech32m.encode("sp", Bech32.toWords(payload))
        assertArrayEquals(key, SparkAddress.decode(legacy, SparkNetwork.MAINNET))

        // Wrong checksum variant, wrong payload, garbage.
        expectSparkError { SparkAddress.decode(Bech32.encode("spark", Bech32.toWords(payload), Bech32.Encoding.BECH32), SparkNetwork.MAINNET) }
        expectSparkError {
            SparkAddress.decode(Bech32m.encode("spark", Bech32.toWords(byteArrayOf(0x0a, 32) + key.copyOfRange(0, 32))), SparkNetwork.MAINNET)
        }
        expectSparkError {
            SparkAddress.decode(
                Bech32m.encode("spark", Bech32.toWords(byteArrayOf(0x0a, 33, 0x04) + key.copyOfRange(1, key.size))),
                SparkNetwork.MAINNET,
            )
        }
        expectSparkError { SparkAddress.decode("spark1garbage", SparkNetwork.MAINNET) }
        expectSparkError { SparkAddress.decode("", SparkNetwork.MAINNET) }

        val wallet = SparkWallet.fromMnemonic(mnemonic = mnemonic)
        assertEquals(wallet.identityPublicKeyHex, SparkAddress.decode(wallet.getSparkAddress(), SparkNetwork.MAINNET).toHexString())
    }

    @Test
    fun depositClaimsTargetTheOutputThatPaysOneOfTheWalletsDepositAddresses() {
        val depositAddress = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
        val depositScript = hex("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
        val otherAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
        val tx = RawTransaction(
            version = 2u,
            inputs = listOf(RawTransaction.Input(previousTxid = bytes(1, 32), previousIndex = 0u)),
            outputs = listOf(
                RawTransaction.Output(value = 1_000uL, scriptPubKey = hex("0014") + bytes(0x55, 20)),
                RawTransaction.Output(value = 50_000uL, scriptPubKey = depositScript),
            ),
            locktime = 0u,
            hasWitnessSerialization = true,
        ).serialized(includeWitness = true)

        val match = DepositMatcher.match(tx, listOf(otherAddress, depositAddress, "not-an-address"), requestedVout = null, network = SparkNetwork.MAINNET)
        assertEquals(DepositMatcher.Match(vout = 1u, address = depositAddress), match)
        assertEquals(1u, DepositMatcher.match(tx, listOf(depositAddress), requestedVout = 1u, network = SparkNetwork.MAINNET).vout)
        expectSparkError { DepositMatcher.match(tx, listOf(depositAddress), requestedVout = 0u, network = SparkNetwork.MAINNET) }
        expectSparkError { DepositMatcher.match(tx, listOf(depositAddress), requestedVout = 5u, network = SparkNetwork.MAINNET) }
        expectSparkError { DepositMatcher.match(tx, listOf(otherAddress), requestedVout = null, network = SparkNetwork.MAINNET) }
        expectSparkError { DepositMatcher.match(tx, emptyList(), requestedVout = null, network = SparkNetwork.MAINNET) }
        expectSparkError { DepositMatcher.match(tx, listOf(depositAddress), requestedVout = null, network = SparkNetwork.REGTEST) }
        expectSparkError { DepositMatcher.match(byteArrayOf(1, 2, 3), listOf(depositAddress), requestedVout = null, network = SparkNetwork.MAINNET) }
    }

    @Test
    fun sparkTransfersValidateTheAmountAndReceiverKeyBeforeTouchingAnyLeaf() {
        val key = keysB.identityPublicKey
        validateSendArguments(receiverIdentityPublicKey = key, amountSats = 1)
        expectSparkError { validateSendArguments(receiverIdentityPublicKey = key, amountSats = 0) }
        expectSparkError { validateSendArguments(receiverIdentityPublicKey = key, amountSats = -10) }
        expectSparkError { validateSendArguments(receiverIdentityPublicKey = key.copyOfRange(0, 32), amountSats = 1) }
        expectSparkError { validateSendArguments(receiverIdentityPublicKey = byteArrayOf(0x04) + key.copyOfRange(1, key.size), amountSats = 1) }
        expectSparkError { validateSendArguments(receiverIdentityPublicKey = ByteArray(0), amountSats = 1) }
    }

    @Test
    fun exportingTheAccountKeyThrowsForCustomSignersInsteadOfCrashing() {
        val wallet = SparkWallet.fromMnemonic(mnemonic = mnemonic)
        val exported = wallet.exportAccountKey()
        assertEquals(64, exported.size)
        val restored = SparkWallet.fromAccountKey(accountKey = exported)
        assertEquals(wallet.identityPublicKeyHex, restored.identityPublicKeyHex)

        class ForwardingSigner(private val inner: SparkSigner) : SparkSignerProtocol by inner

        val custom = SparkWallet.fromSigner(signer = ForwardingSigner(SparkSigner.fromMnemonic(mnemonic)))
        val error = expectSparkError { custom.exportAccountKey() }
        assertTrue(error is SparkError.InvalidArgument)
        assertFalse(custom.identityPublicKeyHex.isEmpty())
    }
}
