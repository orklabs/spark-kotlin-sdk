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

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` with the integers' content bytes exactly as given. */
    private fun der(rContent: ByteArray, sContent: ByteArray): ByteArray {
        val body = byteArrayOf(0x02, rContent.size.toByte()) + rContent + byteArrayOf(0x02, sContent.size.toByte()) + sContent
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    @Test
    fun derIntegersMissingTheirSignPadAreRejectedLikeLibsecp256k1() {
        // Find a valid signature whose r has its top bit set: canonical DER then needs a 0x00 pad.
        val n = KeyDerivation.ecDomainParams.n
        var found: Triple<ByteArray, java.math.BigInteger, java.math.BigInteger>? = null
        for (i in 0 until 256) {
            val digest = TransferLeafVerifier.payloadHash(leafId = "leaf-$i", transferId = "tx", secretCipher = byteArrayOf(1, 2, 3))
            val compact = sender.signCompactECDSA(digest, sender.identityPrivateKey)
            val r = java.math.BigInteger(1, compact.copyOfRange(0, 32))
            if (r.testBit(255)) {
                found = Triple(digest, r, java.math.BigInteger(1, compact.copyOfRange(32, 64)))
                break
            }
        }
        val (digest, r, s) = found ?: throw AssertionError("no high-bit r in 256 signatures")
        assertTrue(s < n.shiftRight(1))

        // BigInteger.toByteArray() is the minimal two's-complement form, i.e. canonical DER content:
        // r carries its 0x00 pad, and so does s in the rare case its first byte needs one.
        val rPadded = r.toByteArray()
        assertEquals(33, rPadded.size)
        assertEquals(0, rPadded[0].toInt())
        val rUnpadded = rPadded.copyOfRange(1, rPadded.size)
        val sContent = s.toByteArray()
        val canonical = der(rPadded, sContent)
        assertTrue(TransferLeafVerifier.verifyECDSA(canonical, digest, sender.identityPublicKey))

        // The same r without its 0x00 pad is a negative INTEGER in DER. libsecp256k1 reads it as
        // zero, so the Swift SDK rejects it; reading it as the positive value would accept it.
        val unpadded = der(rUnpadded, sContent)
        assertFalse(TransferLeafVerifier.verifyECDSA(unpadded, digest, sender.identityPublicKey))
        // Redundant padding and a non-minimal length are rejected too.
        assertFalse(TransferLeafVerifier.verifyECDSA(der(byteArrayOf(0x00) + rPadded, sContent), digest, sender.identityPublicKey))
        val longForm = byteArrayOf(0x30, 0x81.toByte(), canonical[1]) + canonical.copyOfRange(2, canonical.size)
        assertFalse(TransferLeafVerifier.verifyECDSA(longForm, digest, sender.identityPublicKey))
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

    private fun edge(id: String, key: ByteArray, status: Spark.TransferReceiverStatus = Spark.TransferReceiverStatus.TRANSFER_RECEIVER_STATUS_KEY_TWEAKED) =
        Spark.TransferReceiver.newBuilder().setId(id).setIdentityPublicKey(key.toByteString()).setStatus(status).build()

    @Test
    fun aMultiReceiverTransferIsClaimedForThisWalletsOwnLeavesWhicheverReceiverTheOperatorsRecorded() {
        val other = byteArrayOf(0x03) + bytes(0x44, 32)
        val mine = leaf("leaf-a", transferId, bytes(1, 40), signWith = sender).toBuilder().setTransferReceiverId("edge-me").build()
        val theirs = leaf("leaf-b", transferId, bytes(2, 40), signWith = sender).toBuilder().setTransferReceiverId("edge-other").build()
        // The operators record the lowest receiver key, which is not this wallet's.
        val split = transfer(listOf(mine, theirs)).toBuilder()
            .setReceiverIdentityPublicKey(other.toByteString())
            .addReceivers(edge("edge-other", other))
            .addReceivers(edge("edge-me", receiver.identityPublicKey))
            .build()

        val scoped = TransferLeafVerifier.scoped(split, receiver.identityPublicKey)
        assertEquals(listOf("leaf-a"), scoped.leavesList.map { it.leaf.id })
        TransferLeafVerifier.verify(scoped, receiver.identityPublicKey)

        // Not among the receivers, or no leaves on this wallet's edge.
        expectSparkError { TransferLeafVerifier.scoped(split, sender.identityPublicKey) }
        val unassigned = split.toBuilder().clearLeaves().addLeaves(theirs).build()
        expectSparkError { TransferLeafVerifier.scoped(unassigned, receiver.identityPublicKey) }
        // A single-receiver transfer is not narrowed.
        val single = transfer(listOf(mine))
        assertEquals(single, TransferLeafVerifier.scoped(single, receiver.identityPublicKey))

        // This wallet's leg completes with its own edge, before the whole transfer does.
        assertFalse(TransferLeafVerifier.isReceiverLegComplete(split, receiver.identityPublicKey))
        val legDone = split.toBuilder()
            .clearReceivers()
            .addReceivers(edge("edge-other", other))
            .addReceivers(edge("edge-me", receiver.identityPublicKey, Spark.TransferReceiverStatus.TRANSFER_RECEIVER_STATUS_COMPLETED))
            .build()
        assertTrue(TransferLeafVerifier.isReceiverLegComplete(legDone, receiver.identityPublicKey))
        assertFalse(TransferLeafVerifier.isReceiverLegComplete(legDone, other))
        val whole = single.toBuilder().setStatus(Spark.TransferStatus.TRANSFER_STATUS_COMPLETED).build()
        assertTrue(TransferLeafVerifier.isReceiverLegComplete(whole, receiver.identityPublicKey))
        assertFalse(TransferLeafVerifier.isReceiverLegComplete(single, receiver.identityPublicKey))
    }

    @Test(timeout = 60_000)
    fun aTransferIsLookedUpByIdWithTheOperatorsByIdQuery() = kotlinx.coroutines.runBlocking {
        val state = FakeOperatorState { false }
        val known = Spark.Transfer.newBuilder()
            .setId("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b")
            .setTotalValue(42)
            .setStatus(Spark.TransferStatus.TRANSFER_STATUS_COMPLETED)
            .build()
        state.know(known)
        withFakeOperator(state) { wallet ->
            val transfer = wallet.getTransfer(known.id.uppercase())
            assertEquals(known.id, transfer.id)
            assertEquals(42L, transfer.totalValueSats)
            expectSparkErrorSuspending { wallet.getTransfer("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5c") }
        }
        assertEquals(listOf("query_transfers_by_id", "query_transfers_by_id"), state.methods)
    }

    @Test(timeout = 60_000)
    fun historyListsTheReferenceSdksTransferTypesALookupByIdsAsksForExactlyThose() = kotlinx.coroutines.runBlocking {
        val state = FakeOperatorState { false }
        withFakeOperator(state) { wallet ->
            wallet.getTransfers(limit = 10)
            wallet.getTransfers(ids = listOf("a", "b"))
        }
        val filters = state.transferFilters
        assertEquals(2, filters.size)
        assertEquals(
            listOf(Spark.TransferType.COOPERATIVE_EXIT, Spark.TransferType.PREIMAGE_SWAP, Spark.TransferType.UTXO_SWAP, Spark.TransferType.TRANSFER),
            filters.first().typesList,
        )
        assertTrue(filters.first().transferIdsList.isEmpty())
        assertTrue(filters.last().typesList.isEmpty())
        assertEquals(listOf("a", "b"), filters.last().transferIdsList)
    }

    @Test
    fun anOperatorReportedAmountAbove2To63IsCappedNotNegative() {
        val hostile = Spark.Transfer.newBuilder().setId("hostile").setTotalValue(-1L).build() // uint64 max
        assertEquals(MAX_SUPPLY_SATS, hostile.toSparkTransfer().totalValueSats)
    }
}
