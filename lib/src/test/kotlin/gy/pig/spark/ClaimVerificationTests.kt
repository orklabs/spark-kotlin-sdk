package gy.pig.spark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark

/**
 * The sender-signature check that runs before an inbound transfer is claimed.
 * Ported from the Swift SDK's `ClaimVerificationTests.swift`.
 */
class ClaimVerificationTests {

    private val sender = KeyDerivation.fromMnemonic(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        account = 0,
    )
    private val receiver = KeyDerivation.fromMnemonic(
        "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
        account = 0,
    )
    private val transferId = "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"

    private fun leaf(id: String, transferId: String, cipher: ByteArray, signWith: KeyDerivation? = null, compact: Boolean = true): Spark.TransferLeaf {
        val builder = Spark.TransferLeaf.newBuilder()
            .setLeaf(Spark.TreeNode.newBuilder().setId(id).build())
            .setSecretCipher(cipher.toByteString())
        if (signWith != null) {
            val digest = TransferLeafVerifier.payloadHash(leafId = id, transferId = transferId, secretCipher = cipher)
            val signature = if (compact) {
                signWith.signCompactECDSA(digest, signWith.identityPrivateKey)
            } else {
                signWith.signECDSA(digest, signWith.identityPrivateKey)
            }
            builder.setSignature(signature.toByteString())
        }
        return builder.build()
    }

    private fun transfer(leaves: List<Spark.TransferLeaf>, id: String = transferId): Spark.Transfer = Spark.Transfer.newBuilder()
        .setId(id)
        .setSenderIdentityPublicKey(sender.identityPublicKey.toByteString())
        .setReceiverIdentityPublicKey(receiver.identityPublicKey.toByteString())
        .addAllLeaves(leaves)
        .build()

    @Test
    fun compactAndDerSignaturesFromTheSendersIdentityKeyVerify() {
        val digest = TransferLeafVerifier.payloadHash(leafId = "leaf-1", transferId = "tx-1", secretCipher = byteArrayOf(1, 2, 3))
        assertEquals(32, digest.size)
        val compact = sender.signCompactECDSA(digest, sender.identityPrivateKey)
        val der = sender.signECDSA(digest, sender.identityPrivateKey)
        assertEquals(64, compact.size)
        assertTrue(TransferLeafVerifier.verifyECDSA(compact, digest, sender.identityPublicKey))
        assertTrue(TransferLeafVerifier.verifyECDSA(der, digest, sender.identityPublicKey))
    }

    @Test
    fun wrongKeyTamperedPayloadMalformedOrEmptySignaturesAreRejectedWithoutCrashing() {
        val digest = TransferLeafVerifier.payloadHash(leafId = "leaf-1", transferId = "tx-1", secretCipher = byteArrayOf(1, 2, 3))
        val compact = sender.signCompactECDSA(digest, sender.identityPrivateKey)
        val tampered = TransferLeafVerifier.payloadHash(leafId = "leaf-1", transferId = "tx-2", secretCipher = byteArrayOf(1, 2, 3))

        assertFalse(TransferLeafVerifier.verifyECDSA(compact, tampered, sender.identityPublicKey))
        assertFalse(TransferLeafVerifier.verifyECDSA(compact, digest, receiver.identityPublicKey))
        assertFalse(TransferLeafVerifier.verifyECDSA(ByteArray(0), digest, sender.identityPublicKey))
        assertFalse(TransferLeafVerifier.verifyECDSA(ByteArray(64), digest, sender.identityPublicKey))
        assertFalse(TransferLeafVerifier.verifyECDSA(byteArrayOf(0x30, 0x01), digest, sender.identityPublicKey))
        assertFalse(TransferLeafVerifier.verifyECDSA(compact, byteArrayOf(1), sender.identityPublicKey))
        assertFalse(TransferLeafVerifier.verifyECDSA(compact, digest, bytes(0x04, 65)))
        assertFalse(TransferLeafVerifier.verifyECDSA(compact, digest, bytes(0x02, 33)))
        val flipped = compact.copyOf()
        flipped[10] = (flipped[10].toInt() xor 0x01).toByte()
        assertFalse(TransferLeafVerifier.verifyECDSA(flipped, digest, sender.identityPublicKey))
    }

    @Test
    fun aTransferWhoseLeavesAreAllSignedByTheSenderPasses() {
        val t = transfer(
            listOf(
                leaf("leaf-a", transferId, bytes(1, 40), signWith = sender),
                leaf("leaf-b", transferId, bytes(2, 40), signWith = sender, compact = false),
            ),
        )
        TransferLeafVerifier.verify(t, receiverIdentityPublicKey = receiver.identityPublicKey)
    }

    @Test
    fun unsignedWronglySignedIncompleteOrMisaddressedTransfersAreRefused() {
        val good = leaf("leaf-a", transferId, bytes(1, 40), signWith = sender)

        // One good leaf, one unsigned leaf.
        val unsigned = leaf("leaf-b", transferId, bytes(2, 40))
        expectSparkError { TransferLeafVerifier.verify(transfer(listOf(good, unsigned)), receiver.identityPublicKey) }
        // Signed by someone other than the transfer's sender.
        val impostor = leaf("leaf-b", transferId, bytes(2, 40), signWith = receiver)
        expectSparkError { TransferLeafVerifier.verify(transfer(listOf(good, impostor)), receiver.identityPublicKey) }
        // Signature made for a different transfer id.
        val replayed = leaf("leaf-b", "other-transfer", bytes(2, 40), signWith = sender)
        expectSparkError { TransferLeafVerifier.verify(transfer(listOf(good, replayed)), receiver.identityPublicKey) }
        // Cipher swapped after signing.
        val swapped = good.toBuilder().setSecretCipher(bytes(9, 40).toByteString()).build()
        expectSparkError { TransferLeafVerifier.verify(transfer(listOf(swapped)), receiver.identityPublicKey) }
        // Missing node, empty cipher, no leaves, wrong receiver.
        val noNode = good.toBuilder().clearLeaf().build()
        expectSparkError { TransferLeafVerifier.verify(transfer(listOf(noNode)), receiver.identityPublicKey) }
        val emptyCipher = good.toBuilder().setSecretCipher(ByteArray(0).toByteString()).build()
        expectSparkError { TransferLeafVerifier.verify(transfer(listOf(emptyCipher)), receiver.identityPublicKey) }
        expectSparkError { TransferLeafVerifier.verify(transfer(emptyList()), receiver.identityPublicKey) }
        expectSparkError { TransferLeafVerifier.verify(transfer(listOf(good)), sender.identityPublicKey) }
    }

    @Test
    fun highSSignaturesAreRejectedLikeLibsecp256k1() {
        // The Swift SDK verifies with libsecp256k1, which refuses the malleated (high-S) twin of
        // a valid signature; the BouncyCastle-based verifier must give the same answer.
        val digest = TransferLeafVerifier.payloadHash(leafId = "leaf-1", transferId = "tx-1", secretCipher = byteArrayOf(1, 2, 3))
        val compact = sender.signCompactECDSA(digest, sender.identityPrivateKey)
        val n = KeyDerivation.ecDomainParams.n
        val s = java.math.BigInteger(1, compact.copyOfRange(32, 64))
        val highS = n.subtract(s).toByteArray().let { raw -> ByteArray(32 - minOf(32, raw.size)) + raw.takeLast(32).toByteArray() }
        val malleated = compact.copyOfRange(0, 32) + highS
        assertTrue(TransferLeafVerifier.verifyECDSA(compact, digest, sender.identityPublicKey))
        assertFalse(TransferLeafVerifier.verifyECDSA(malleated, digest, sender.identityPublicKey))
    }
}
