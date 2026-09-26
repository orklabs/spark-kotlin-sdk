package gy.pig.spark

import org.junit.Assert.*
import org.junit.Test

class SparkSDKTests {

    @Test
    fun testKeyDerivation() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val keys = KeyDerivation.fromMnemonic(mnemonic, account = 0)

        assertEquals(33, keys.identityPublicKey.size)
        assertEquals(33, keys.depositPublicKey.size)

        val keys2 = KeyDerivation.fromMnemonic(mnemonic, account = 0)
        assertArrayEquals(keys.identityPublicKey, keys2.identityPublicKey)
        assertArrayEquals(keys.depositPublicKey, keys2.depositPublicKey)

        // Cross-check against TypeScript SDK reference vectors (from @scure/bip32, account=0)
        assertEquals(
            "02698b27ac308b275671b3ca25436346469d04a5bba578ae39feba1d65897a6abc",
            keys.identityPublicKey.toHexString(),
        )
        assertEquals(
            "02f4f6db6cf8f0ab8c9c95659b78448d09ebf490c4251349c6ebef7caf9ad6e10a",
            keys.depositPublicKey.toHexString(),
        )
    }

    @Test
    fun testSparkSigner() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val signer = SparkSigner.fromMnemonic(mnemonic)

        assertEquals(33, signer.identityPublicKey.size)
        assertEquals(33, signer.depositPublicKey.size)
        assertTrue(signer.identityPublicKey.toHexString().isNotEmpty())
    }

    @Test
    fun mnemonicToSeedTrezorPassphrase() {
        // Official BIP39 test vector from trezor/python-mnemonic vectors.json
        val seed = KeyDerivation.mnemonicToSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "TREZOR",
        )
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seed.toHexString(),
        )
    }

    @Test
    fun mnemonicToSeedEmptyPassphrase() {
        val seed = KeyDerivation.mnemonicToSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "",
        )
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            seed.toHexString(),
        )
    }

    @Test
    fun passphraseChangesSeed() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seedEmpty = KeyDerivation.mnemonicToSeed(mnemonic, "")
        val seedTrezor = KeyDerivation.mnemonicToSeed(mnemonic, "TREZOR")
        assertFalse(seedEmpty.contentEquals(seedTrezor))
    }

    @Test
    fun seedDeterministic() {
        val mnemonic = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"
        val seed1 = KeyDerivation.mnemonicToSeed(mnemonic, "")
        val seed2 = KeyDerivation.mnemonicToSeed(mnemonic, "")
        assertArrayEquals(seed1, seed2)
        assertEquals(64, seed1.size) // 512 bits
    }

    @Test
    fun testHexConversion() {
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertEquals("deadbeef", data.toHexString())

        val roundtrip = "deadbeef".hexToByteArray()
        assertArrayEquals(data, roundtrip)
    }

    /** Tagged hash matches an independent implementation of the reference SDK's hash structure. */
    @Test
    fun testTaggedHash() {
        // Vector computed with a separate Python implementation of the TS SDK's Hasher (the same
        // vector the Swift SDK pins): tagHash = sha256(concat(8-byte BE len + component));
        // sha256(tagHash || tagHash || values), each value 8-byte BE length-prefixed, maps as
        // (count as uint64, then sorted key/value pairs). Insertion order must not matter.
        val hasher = SparkHasher(listOf("spark", "transfer", "signing payload"))
        hasher.addBytes("deadbeef".hexToByteArray())
        hasher.addMapStringToBytes(linkedMapOf("op2" to "babe".hexToByteArray(), "op1" to "cafe".hexToByteArray()))
        val result = hasher.hash()
        assertEquals("079a10347594aef138bac8153d261ba95406af52148d8368f8b81bd2f3f28c49", result.toHexString())
    }

    @Test
    fun mainnetDefaultAccount() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        // Account 0 (explicit)
        val keys0 = KeyDerivation.fromMnemonic(mnemonic, account = 0)
        // Account 1 (mainnet default via SparkWallet)
        val wallet = SparkWallet.fromMnemonic(mnemonic = mnemonic)
        // They must differ — mainnet defaults to account 1
        assertNotEquals(keys0.identityPublicKey.toHexString(), wallet.identityPublicKeyHex)

        // Account 1 explicit must match mainnet default
        val keys1 = KeyDerivation.fromMnemonic(mnemonic, account = 1)
        assertEquals(keys1.identityPublicKey.toHexString(), wallet.identityPublicKeyHex)
    }

    // --- Wallet initialization tests (public BIP-39 test vectors) ---

    private val walletAMnemonic = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"
    private val walletBMnemonic = "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal will"

    @Test
    fun initializeWallet() {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        assertTrue(wallet.identityPublicKeyHex.isNotEmpty())
    }

    @Test
    fun differentAccounts() {
        val account0 = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        val account1 = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 1)
        assertNotEquals(account0.identityPublicKeyHex, account1.identityPublicKeyHex)
    }

    @Test
    fun deterministicKeys() {
        val w1 = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        val w2 = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        assertEquals(w1.identityPublicKeyHex, w2.identityPublicKeyHex)
    }

    @Test
    fun differentMnemonics() {
        val wA = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        val wB = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletBMnemonic, account = 0)
        assertNotEquals(wA.identityPublicKeyHex, wB.identityPublicKeyHex)
    }

    // --- Spark Address Tests ---

    @Test
    fun sparkAddressPrefix() {
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        val address = wallet.getSparkAddress()
        assertTrue(address.startsWith("spark1"))
        assertTrue(address.length > 10)
    }

    @Test
    fun deterministicAddress() {
        val w1 = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        val w2 = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        assertEquals(w1.getSparkAddress(), w2.getSparkAddress())
    }

    @Test
    fun differentAddresses() {
        val wA = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        val wB = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletBMnemonic, account = 0)
        assertNotEquals(wA.getSparkAddress(), wB.getSparkAddress())
    }

    @Test
    fun sparkAddressRoundTrip() {
        // Decode the bech32m Spark address and verify the protobuf framing
        // (field 1, wire type 2, length-delimited) wraps exactly the wallet's
        // 33-byte identity public key. Locks in the encode path end-to-end.
        val wallet = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = walletAMnemonic, account = 0)
        val address = wallet.getSparkAddress()

        val (hrp, words) = Bech32m.decodeBech32m(address)
        assertEquals("spark", hrp)

        val payload = Bech32.fromWords(words)
        assertNotNull(payload)
        payload!!
        assertEquals("field 1, wire type 2 tag", 10.toByte(), payload[0])
        assertEquals("33-byte length prefix", 33, payload[1].toInt() and 0xFF)

        val pubkey = payload.copyOfRange(2, 2 + 33)
        assertEquals(wallet.identityPublicKeyHex, pubkey.toHexString())
    }
}
