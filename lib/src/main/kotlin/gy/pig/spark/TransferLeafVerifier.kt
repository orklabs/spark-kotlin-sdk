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
 * `verifyPendingTransfer`: each leaf carries the sender's signature over
 * `sha256(leafId || transferId || secretCipher)`, made with the sender's identity key — a legacy
 * ECDSA signature or a scheme-tagged one.
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
        if (signature.isEmpty()) return false
        val parsed = if (signature.size == 64) parseCompact(signature) else parseStrictDerOrNull(signature)
        return verifyParsedECDSA(parsed, digest, compressedPublicKey)
    }

    /** ECDSA in strict DER only, as a typed ECDSA signature must be. */
    private fun verifyStrictDerECDSA(signature: ByteArray, digest: ByteArray, compressedPublicKey: ByteArray): Boolean =
        verifyParsedECDSA(parseStrictDerOrNull(signature), digest, compressedPublicKey)

    private fun verifyParsedECDSA(signature: Pair<BigInteger, BigInteger>?, digest: ByteArray, compressedPublicKey: ByteArray): Boolean {
        if (signature == null || digest.size != 32 || compressedPublicKey.size != 33) return false
        if (compressedPublicKey[0] != 0x02.toByte() && compressedPublicKey[0] != 0x03.toByte()) return false
        val (r, s) = signature
        return try {
            val point = domain.curve.decodePoint(compressedPublicKey)
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

    /**
     * DER `SEQUENCE { INTEGER r, INTEGER s }`, rejected unless it re-encodes to the same bytes and
     * both integers are positive as encoded. BouncyCastle already refuses redundant 0x00/0xFF
     * padding; the sign is checked here because `positiveValue` would read an integer that is
     * missing its 0x00 pad (high bit set, i.e. negative in DER) as the positive value, where
     * libsecp256k1 treats it as zero and the signature as invalid.
     */
    private fun parseStrictDerOrNull(signature: ByteArray): Pair<BigInteger, BigInteger>? = try {
        parseStrictDer(signature)
    } catch (_: Exception) {
        // Not DER at all (BouncyCastle throws on malformed ASN.1).
        null
    }

    /** See [parseStrictDerOrNull]; throws on bytes that are not ASN.1. */
    private fun parseStrictDer(signature: ByteArray): Pair<BigInteger, BigInteger>? {
        val sequence = ASN1Primitive.fromByteArray(signature) as? ASN1Sequence ?: return null
        if (sequence.size() != 2) return null
        val r = (sequence.getObjectAt(0) as? ASN1Integer)?.value ?: return null
        val s = (sequence.getObjectAt(1) as? ASN1Integer)?.value ?: return null
        if (!sequence.getEncoded(ASN1Encoding.DER).contentEquals(signature)) return null
        if (r.signum() <= 0 || s.signum() <= 0) return null
        return r to s
    }

    /**
     * A leaf's sender signature, the reference SDK's `verifyTypedSignature`: the legacy field is
     * ECDSA in either encoding (senders emit compact or DER); a typed signature is verified by its
     * scheme — ECDSA in strict DER only, BIP-340 Schnorr against the x-only form of the key — and
     * an unspecified or unknown scheme, a missing signature, a key that is not 33-byte compressed
     * or a digest that is not 32 bytes is refused.
     */
    fun verifySenderSignature(leaf: Spark.TransferLeaf, digest: ByteArray, compressedPublicKey: ByteArray): Boolean {
        if (digest.size != 32 || compressedPublicKey.size != 33) return false
        if (compressedPublicKey[0] != 0x02.toByte() && compressedPublicKey[0] != 0x03.toByte()) return false
        return when (leaf.sigCase) {
            Spark.TransferLeaf.SigCase.SIGNATURE -> verifyECDSA(leaf.signature.toByteArray(), digest, compressedPublicKey)
            Spark.TransferLeaf.SigCase.TYPED_SIGNATURE -> {
                val signature = leaf.typedSignature.signature.toByteArray()
                if (signature.isEmpty()) return false
                when (leaf.typedSignature.scheme) {
                    common.Common.SignatureScheme.SIGNATURE_SCHEME_ECDSA -> verifyStrictDerECDSA(signature, digest, compressedPublicKey)
                    common.Common.SignatureScheme.SIGNATURE_SCHEME_SCHNORR ->
                        Bip340.verify(signature, digest, compressedPublicKey.copyOfRange(1, 33))
                    else -> false
                }
            }
            else -> false
        }
    }

    /**
     * A transfer narrowed to [receiverIdentityPublicKey]'s own receiver edge and its leaves — the
     * reference SDK's `scopeTransferLeavesToReceiver`. The operators record only the first
     * (lowest-key) receiver of a multi-receiver transfer in `receiver_identity_public_key`, but
     * deliver it to every receiver and may return every receiver's leaves. A transfer with one
     * receiver is returned unchanged.
     */
    fun scoped(transfer: Spark.Transfer, receiverIdentityPublicKey: ByteArray): Spark.Transfer {
        if (transfer.receiversCount <= 1) return transfer
        val own = transfer.receiversList.firstOrNull { it.identityPublicKey.toByteArray().contentEquals(receiverIdentityPublicKey) }
        if (own == null || own.id.isEmpty()) {
            untrusted("transfer ${transfer.id} does not list this wallet among its receivers")
        }
        val leaves = transfer.leavesList.filter { it.transferReceiverId == own.id }
        if (leaves.isEmpty()) {
            untrusted("transfer ${transfer.id} assigns no leaves to this wallet")
        }
        return transfer.toBuilder().clearLeaves().addAllLeaves(leaves).build()
    }

    /**
     * Whether [receiverIdentityPublicKey]'s leg of a transfer is complete: the whole transfer, or
     * for a multi-receiver transfer this receiver's edge — the whole transfer completes only once
     * every receiver has claimed (the reference SDK's `isReceiverLegComplete`).
     */
    fun isReceiverLegComplete(transfer: Spark.Transfer, receiverIdentityPublicKey: ByteArray): Boolean {
        if (transfer.status == Spark.TransferStatus.TRANSFER_STATUS_COMPLETED) return true
        if (transfer.receiversCount <= 1) return false
        return transfer.receiversList
            .firstOrNull { it.identityPublicKey.toByteArray().contentEquals(receiverIdentityPublicKey) }
            ?.status == Spark.TransferReceiverStatus.TRANSFER_RECEIVER_STATUS_COMPLETED
    }

    /**
     * Throws [SparkError.UntrustedResponse] unless every leaf is present and carries a valid
     * sender signature, and the transfer is addressed to [receiverIdentityPublicKey] — as its
     * recorded receiver or one of its receivers.
     */
    fun verify(transfer: Spark.Transfer, receiverIdentityPublicKey: ByteArray) {
        val addressed = transfer.receiverIdentityPublicKey.toByteArray().contentEquals(receiverIdentityPublicKey) ||
            transfer.receiversList.any { it.identityPublicKey.toByteArray().contentEquals(receiverIdentityPublicKey) }
        if (!addressed) {
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
            if (!verifySenderSignature(transferLeaf, digest, senderKey)) {
                untrusted(
                    "sender signature on leaf $leafId in transfer ${transfer.id} is missing or invalid",
                )
            }
        }
    }
}
