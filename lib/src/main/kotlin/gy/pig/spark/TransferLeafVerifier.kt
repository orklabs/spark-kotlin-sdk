package gy.pig.spark

import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import spark.Spark
import java.math.BigInteger

/**
 * Authenticates the leaves of a pending inbound transfer before the wallet decrypts the leaf
 * secrets, derives tweaks, or signs refund transactions. Mirrors the reference SDK's
 * `verifyPendingTransfer`: each leaf carries the sender's ECDSA signature over
 * `sha256(leafId || transferId || secretCipher)`, made with the sender's identity key.
 */
internal object TransferLeafVerifier {

    private val domain = KeyDerivation.ecDomainParams
    private val halfOrder: BigInteger = domain.n.shiftRight(1)

    private fun untrusted(message: String): Nothing = throw SparkError.UntrustedResponse(message)

    fun payloadHash(leafId: String, transferId: String, secretCipher: ByteArray): ByteArray =
        sha256(leafId.toByteArray(Charsets.UTF_8) + transferId.toByteArray(Charsets.UTF_8) + secretCipher)

    /**
     * ECDSA verification over a 32-byte digest with a 33-byte compressed public key.
     * Accepts the 64-byte compact form (what this SDK produces) and DER. Malformed input is an
     * invalid signature, never a crash.
     *
     * The Swift SDK verifies with libsecp256k1, which only accepts strict DER and low-S
     * signatures; BouncyCastle accepts both forms of S and lenient BER, so both rules are
     * enforced explicitly here to give the same answers.
     */
    fun verifyECDSA(signature: ByteArray, digest: ByteArray, compressedPublicKey: ByteArray): Boolean {
        if (digest.size != 32 || compressedPublicKey.size != 33 || signature.isEmpty()) return false
        if (compressedPublicKey[0] != 0x02.toByte() && compressedPublicKey[0] != 0x03.toByte()) return false
        return try {
            val point = domain.curve.decodePoint(compressedPublicKey)
            val (r, s) = (if (signature.size == 64) parseCompact(signature) else parseStrictDer(signature)) ?: return false
            if (r.signum() <= 0 || s.signum() <= 0 || r >= domain.n || s >= domain.n || s > halfOrder) return false
            val verifier = ECDSASigner()
            verifier.init(false, ECPublicKeyParameters(point, domain))
            verifier.verifySignature(digest, r, s)
        } catch (_: Exception) {
            false
        }
    }

    private fun parseCompact(signature: ByteArray): Pair<BigInteger, BigInteger> =
        BigInteger(1, signature.copyOfRange(0, 32)) to BigInteger(1, signature.copyOfRange(32, 64))

    /** DER `SEQUENCE { INTEGER r, INTEGER s }`, rejected unless it re-encodes to the same bytes. */
    private fun parseStrictDer(signature: ByteArray): Pair<BigInteger, BigInteger>? {
        val sequence = ASN1Primitive.fromByteArray(signature) as? ASN1Sequence ?: return null
        if (sequence.size() != 2) return null
        val r = sequence.getObjectAt(0) as? ASN1Integer ?: return null
        val s = sequence.getObjectAt(1) as? ASN1Integer ?: return null
        if (!sequence.getEncoded(ASN1Encoding.DER).contentEquals(signature)) return null
        return r.positiveValue to s.positiveValue
    }

    /**
     * Throws [SparkError.UntrustedResponse] unless every leaf is present and carries a valid
     * sender signature, and the transfer is addressed to [receiverIdentityPublicKey].
     */
    fun verify(transfer: Spark.Transfer, receiverIdentityPublicKey: ByteArray) {
        if (!transfer.receiverIdentityPublicKey.toByteArray().contentEquals(receiverIdentityPublicKey)) {
            untrusted("transfer ${transfer.id} is not addressed to this wallet")
        }
        if (transfer.leavesList.isEmpty()) {
            untrusted("transfer ${transfer.id} has no leaves")
        }
        val senderKey = transfer.senderIdentityPublicKey.toByteArray()
        for (transferLeaf in transfer.leavesList) {
            if (!transferLeaf.hasLeaf() || transferLeaf.leaf.id.isEmpty()) {
                untrusted("transfer ${transfer.id} contains a leaf without node data")
            }
            val leafId = transferLeaf.leaf.id
            if (transferLeaf.secretCipher.isEmpty) {
                untrusted("leaf $leafId in transfer ${transfer.id} has no secret cipher")
            }
            val digest = payloadHash(leafId, transfer.id, transferLeaf.secretCipher.toByteArray())
            if (!verifyECDSA(transferLeaf.signature.toByteArray(), digest, senderKey)) {
                untrusted(
                    "sender signature on leaf $leafId in transfer ${transfer.id} is missing or invalid",
                )
            }
        }
    }
}
