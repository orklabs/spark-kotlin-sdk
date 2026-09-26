package gy.pig.spark

/**
 * Abstraction over the cryptographic operations the SDK needs from a wallet's key
 * material.
 *
 * The default implementation, [SparkSigner], derives everything in-process via
 * BIP-32 over either a BIP-39 mnemonic or a 64-byte account key. Apps that need
 * stronger isolation (Android Keystore, StrongBox, hardware tokens) should provide
 * their own implementation and pass it to [SparkWallet.fromSigner].
 *
 * **Security contract for custom implementations:**
 * - Never return raw private-key bytes for the identity key outside [deriveIdentityPrivateKey].
 *   The SDK uses that method strictly to ECIES-decrypt incoming transfer ciphers.
 * - Zero any transient `ByteArray` you return as soon as the caller is done with it
 *   if you can guarantee that timing — otherwise document the lifetime.
 * - Derivations must be **deterministic**: the same input must always yield the same
 *   output, otherwise FROST claim flows will fail.
 *
 * @see SparkSigner
 * @see SparkWallet.fromSigner
 */
interface SparkSignerProtocol {
    /** 33-byte compressed secp256k1 identity public key. Used as the wallet's address. */
    val identityPublicKey: ByteArray

    /** 33-byte compressed secp256k1 public key for the default static deposit address. */
    val depositPublicKey: ByteArray

    /** DER-encoded ECDSA signature over [messageHash] using the identity private key. */
    fun signWithIdentityKey(messageHash: ByteArray): ByteArray

    /** Compact 64-byte (r||s) signature over [messageHash] using the identity private key. */
    fun signCompactWithIdentityKey(messageHash: ByteArray): ByteArray

    /**
     * Return the raw 32-byte identity private key. Only the SDK's ECIES decrypt path
     * for incoming transfer ciphers calls this; custom implementations may throw if
     * raw export is impossible.
     */
    fun deriveIdentityPrivateKey(): ByteArray

    /** Return the deterministic FROST signing private key for the given Spark leaf id. */
    fun deriveLeafSigningKey(leafID: String): ByteArray

    /** Return the FROST signing key pair `(private, public)` for the given Spark leaf id. */
    fun deriveLeafSigningKeyPair(leafID: String): Pair<ByteArray, ByteArray>

    /** Return the secp256k1 private key for the [index]-th static deposit address. */
    fun deriveStaticDepositKey(index: Int): ByteArray

    /** Return the 32-byte deterministic preimage for the given Spark transfer id. */
    fun generatePreimage(transferID: String): ByteArray
}

class SparkSigner private constructor(private val keys: KeyDerivation,) : SparkSignerProtocol {

    companion object {
        /**
         * @param validateMnemonic Reject phrases that fail BIP-39 wordlist or checksum
         *   validation ([SparkError.InvalidMnemonic]). Defaults to `true`.
         */
        fun fromMnemonic(mnemonic: String, account: Int = 0, passphrase: String = "", validateMnemonic: Boolean = true): SparkSigner =
            SparkSigner(KeyDerivation.fromMnemonic(mnemonic, account, passphrase, validateMnemonic))

        fun fromAccountKey(accountKey: ByteArray, accountChainCode: ByteArray): SparkSigner =
            SparkSigner(KeyDerivation.fromAccountKey(accountKey, accountChainCode))
    }

    fun exportAccountKey(): ByteArray = keys.accountKeyData + keys.accountChainCodeData

    override val identityPublicKey: ByteArray get() = keys.identityPublicKey
    override val depositPublicKey: ByteArray get() = keys.depositPublicKey

    override fun signWithIdentityKey(messageHash: ByteArray): ByteArray = keys.signECDSA(messageHash, keys.identityPrivateKey)

    override fun signCompactWithIdentityKey(messageHash: ByteArray): ByteArray = keys.signCompactECDSA(messageHash, keys.identityPrivateKey)

    override fun deriveIdentityPrivateKey(): ByteArray = keys.identityPrivateKey

    override fun deriveLeafSigningKey(leafID: String): ByteArray = keys.deriveLeafKey(leafID).privateKeyData

    override fun deriveLeafSigningKeyPair(leafID: String): Pair<ByteArray, ByteArray> {
        val key = keys.deriveLeafKey(leafID)
        return key.privateKeyData to key.publicKeyData
    }

    override fun deriveStaticDepositKey(index: Int): ByteArray = keys.deriveStaticDepositChildKey(index).privateKeyData

    override fun generatePreimage(transferID: String): ByteArray = keys.computePreimage(transferID)
}
