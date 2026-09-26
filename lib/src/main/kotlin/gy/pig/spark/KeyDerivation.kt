package gy.pig.spark

import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.ByteBuffer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class DerivedKey(val privateKeyData: ByteArray, val publicKeyData: ByteArray,) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedKey) return false
        return privateKeyData.contentEquals(other.privateKeyData)
    }
    override fun hashCode(): Int = privateKeyData.contentHashCode()
}

class KeyDerivation private constructor(val accountKeyData: ByteArray, val accountChainCodeData: ByteArray,) {
    companion object {
        const val SPARK_PURPOSE: Int = 8797555
        private const val HARDENED_OFFSET: Long = 0x80000000L

        private val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        private val curveOrder: BigInteger = ecSpec.n

        val ecDomainParams = ECDomainParameters(ecSpec.curve, ecSpec.g, ecSpec.n, ecSpec.h)

        /**
         * @param validateMnemonic Check the phrase against the BIP-39 English wordlist and
         *   checksum first (throws [SparkError.InvalidMnemonic]). Pass `false` only to load a
         *   phrase that is known to be non-standard.
         * @throws SparkError.InvalidArgument for an account index outside `0..2^31-1`.
         */
        fun fromMnemonic(mnemonic: String, account: Int = 0, passphrase: String = "", validateMnemonic: Boolean = true): KeyDerivation {
            if (validateMnemonic) {
                BIP39.validate(mnemonic)
            }
            // Kotlin's Int tops out at 2^31-1, so only the lower bound needs checking.
            if (account < 0) {
                throw SparkError.InvalidArgument("account index must be between 0 and 2^31-1, got $account")
            }
            val seed = mnemonicToSeed(mnemonic, passphrase)
            val master = hmacSHA512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)

            val masterKey = master.copyOfRange(0, 32)
            val masterChainCode = master.copyOfRange(32, 64)

            // m/8797555'
            val purposeKey = deriveHardened(masterKey, masterChainCode, SPARK_PURPOSE)
            // m/8797555'/{account}'
            val accountKey = deriveHardened(purposeKey.first, purposeKey.second, account)

            return KeyDerivation(accountKey.first, accountKey.second)
        }

        fun fromAccountKey(accountKey: ByteArray, accountChainCode: ByteArray): KeyDerivation = KeyDerivation(accountKey, accountChainCode)

        private fun deriveHardened(key: ByteArray, chainCode: ByteArray, index: Int): Pair<ByteArray, ByteArray> {
            val data = ByteBuffer.allocate(1 + 32 + 4)
            data.put(0x00.toByte())
            data.put(key)
            data.putInt((index.toLong() or HARDENED_OFFSET).toInt())

            val derived = hmacSHA512(chainCode, data.array())
            val il = derived.copyOfRange(0, 32)
            val ir = derived.copyOfRange(32, 64)

            // BIP32: childKey = (IL + parentKey) mod n
            val childKey = addPrivateKeys(il, key)
            return childKey to ir
        }

        private fun addPrivateKeys(a: ByteArray, b: ByteArray): ByteArray {
            val aBig = BigInteger(1, a)
            val bBig = BigInteger(1, b)
            val result = aBig.add(bBig).mod(curveOrder)
            if (result == BigInteger.ZERO) throw SparkError.KeyDerivationFailed

            val bytes = result.toByteArray()
            // Ensure exactly 32 bytes (pad or trim leading zero)
            return when {
                bytes.size == 32 -> bytes
                bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
                else -> ByteArray(32 - bytes.size) + bytes
            }
        }

        fun compressedPublicKey(privateKey: ByteArray): ByteArray {
            val privKeyBig = BigInteger(1, privateKey)
            val point: ECPoint = ecSpec.g.multiply(privKeyBig).normalize()
            return point.getEncoded(true)
        }

        fun mnemonicToSeed(mnemonic: String, passphrase: String): ByteArray {
            val password = java.text.Normalizer.normalize(mnemonic, java.text.Normalizer.Form.NFKD)
            val salt = java.text.Normalizer.normalize("mnemonic$passphrase", java.text.Normalizer.Form.NFKD)

            val spec = PBEKeySpec(
                password.toCharArray(),
                salt.toByteArray(Charsets.UTF_8),
                2048,
                512
            )
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            return factory.generateSecret(spec).encoded
        }
    }

    val identityPrivateKey: ByteArray
    val identityPublicKey: ByteArray
    val depositPublicKey: ByteArray

    private val signingKey: ByteArray
    private val signingChainCode: ByteArray
    private val depositKey: ByteArray
    private val staticDepositKey: ByteArray
    private val staticDepositChainCode: ByteArray
    private val htlcPreimageKey: ByteArray

    init {
        val identity = deriveHardened(accountKeyData, accountChainCodeData, 0)
        val signing = deriveHardened(accountKeyData, accountChainCodeData, 1)
        val deposit = deriveHardened(accountKeyData, accountChainCodeData, 2)
        val staticDeposit = deriveHardened(accountKeyData, accountChainCodeData, 3)
        val htlcPreimage = deriveHardened(accountKeyData, accountChainCodeData, 4)

        identityPrivateKey = identity.first
        identityPublicKey = compressedPublicKey(identity.first)
        depositPublicKey = compressedPublicKey(deposit.first)
        signingKey = signing.first
        signingChainCode = signing.second
        depositKey = deposit.first
        staticDepositKey = staticDeposit.first
        staticDepositChainCode = staticDeposit.second
        htlcPreimageKey = htlcPreimage.first
    }

    fun deriveLeafKey(leafID: String): DerivedKey {
        val hash = sha256(leafID.toByteArray(Charsets.UTF_8))
        val index = ((hash[0].toInt() and 0xFF) shl 24) or
            ((hash[1].toInt() and 0xFF) shl 16) or
            ((hash[2].toInt() and 0xFF) shl 8) or
            (hash[3].toInt() and 0xFF)
        val childIndex = (index.toLong() and 0xFFFFFFFFL) % HARDENED_OFFSET

        val result = deriveHardened(signingKey, signingChainCode, childIndex.toInt())
        return DerivedKey(
            privateKeyData = result.first,
            publicKeyData = compressedPublicKey(result.first),
        )
    }

    fun deriveStaticDepositChildKey(index: Int): DerivedKey {
        val result = deriveHardened(staticDepositKey, staticDepositChainCode, index)
        return DerivedKey(
            privateKeyData = result.first,
            publicKeyData = compressedPublicKey(result.first),
        )
    }

    fun computePreimage(transferID: String): ByteArray = hmacSHA256(htlcPreimageKey, transferID.toByteArray(Charsets.UTF_8))

    fun signECDSA(messageHash: ByteArray, privateKey: ByteArray): ByteArray {
        val signer = ECDSASigner()
        val privKeyParams = ECPrivateKeyParameters(BigInteger(1, privateKey), ecDomainParams)
        signer.init(true, privKeyParams)
        val components = signer.generateSignature(messageHash)
        val r = components[0]
        // Ensure low-S
        var s = components[1]
        val halfN = curveOrder.shiftRight(1)
        if (s > halfN) s = curveOrder.subtract(s)
        return derEncode(r, s)
    }

    fun signCompactECDSA(messageHash: ByteArray, privateKey: ByteArray): ByteArray {
        val signer = ECDSASigner()
        val privKeyParams = ECPrivateKeyParameters(BigInteger(1, privateKey), ecDomainParams)
        signer.init(true, privKeyParams)
        val components = signer.generateSignature(messageHash)
        val r = components[0]
        var s = components[1]
        val halfN = curveOrder.shiftRight(1)
        if (s > halfN) s = curveOrder.subtract(s)

        val rBytes = toFixedLength(r.toByteArray(), 32)
        val sBytes = toFixedLength(s.toByteArray(), 32)
        return rBytes + sBytes
    }

    private fun toFixedLength(bytes: ByteArray, length: Int): ByteArray = when {
        bytes.size == length -> bytes
        bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
        else -> ByteArray(length - bytes.size) + bytes
    }

    private fun derEncode(r: BigInteger, s: BigInteger): ByteArray {
        val rBytes = r.toByteArray()
        val sBytes = s.toByteArray()
        val totalLen = 2 + rBytes.size + 2 + sBytes.size
        val result = ByteArray(2 + totalLen)
        var offset = 0
        result[offset++] = 0x30.toByte()
        result[offset++] = totalLen.toByte()
        result[offset++] = 0x02.toByte()
        result[offset++] = rBytes.size.toByte()
        rBytes.copyInto(result, offset)
        offset += rBytes.size
        result[offset++] = 0x02.toByte()
        result[offset++] = sBytes.size.toByte()
        sBytes.copyInto(result, offset)
        return result
    }
}
