package gy.pig.spark

import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

/**
 * BIP-340 Schnorr signature verification over secp256k1 (what libsecp256k1's
 * `secp256k1_schnorrsig_verify` does for the Swift SDK), on BouncyCastle's curve arithmetic.
 * Malformed input is an invalid signature, never an exception.
 */
internal object Bip340 {
    private val domain = KeyDerivation.ecDomainParams

    /** The field prime p of secp256k1. */
    private val fieldPrime: BigInteger = domain.curve.field.characteristic

    /** `SHA256(SHA256(tag) || SHA256(tag) || data)`. */
    fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + data)
    }

    /** The point with x coordinate [x] and an even y (BIP-340 `lift_x`), or `null` when there is none. */
    fun liftX(x: ByteArray): ECPoint? {
        if (x.size != 32 || BigInteger(1, x) >= fieldPrime) return null
        return try {
            domain.curve.decodePoint(byteArrayOf(0x02) + x)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Whether [signature] (64 bytes, `r || s`) is a valid BIP-340 signature of [message] under the
     * x-only public key [xOnlyPublicKey] (32 bytes).
     */
    fun verify(signature: ByteArray, message: ByteArray, xOnlyPublicKey: ByteArray): Boolean {
        if (signature.size != 64 || xOnlyPublicKey.size != 32) return false
        val publicKey = liftX(xOnlyPublicKey) ?: return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= fieldPrime || s >= domain.n) return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32) + xOnlyPublicKey + message)).mod(domain.n)
        val point = domain.g.multiply(s).add(publicKey.multiply(e).negate()).normalize()
        if (point.isInfinity || point.affineYCoord.testBitZero()) return false
        return point.affineXCoord.toBigInteger() == r
    }
}
