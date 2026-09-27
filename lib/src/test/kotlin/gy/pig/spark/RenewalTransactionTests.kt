package gy.pig.spark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import spark.Spark
import uniffi.spark_frost.getTaprootPubkey

/**
 * Renewal transactions must be exactly what the operators rebuild and compare byte for byte
 * (`renew_leaf_handler.go`): the new node (or split node) spends the parent's output at the
 * leaf's `vout` and pays P2TR(leaf verifying key); the zero-timelock variant spends the leaf's own
 * node transaction. Renewals used to spend parent output 0, so a leaf at another output could
 * never be renewed. Ported from the Swift SDK's `RenewalTransactionTests.swift`; the transactions
 * come from the FROST library (see [NativeFrost]), except the variant and key rules.
 */
class RenewalTransactionTests {
    private val config = SparkConfig(network = SparkNetwork.MAINNET)
    private val signer = SparkSigner.fromMnemonic(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        0,
    )
    private val verifyingKey = signer.identityPublicKey
    private val signingPublicKey = KeyDerivation.compressedPublicKey(signer.deriveLeafSigningKey("leaf"))
    private val otherKey = KeyDerivation.compressedPublicKey(signer.deriveLeafSigningKey("sibling"))

    private fun p2tr(key: ByteArray): ByteArray = byteArrayOf(0x51, 0x20) + getTaprootPubkey(key).copyOfRange(1, 33)

    private fun tx(inputSequence: UInt, outputs: List<RawTransaction.Output>): ByteArray = RawTransaction(
        version = 3u,
        inputs = listOf(RawTransaction.Input(previousTxid = bytes(0xAB, 32), previousIndex = 0u, sequence = inputSequence)),
        outputs = outputs,
        locktime = 0u,
        hasWitnessSerialization = false,
    ).serialized(includeWitness = true)

    /** A parent node transaction that split into a sibling at output 0 and our leaf at output 1. */
    private fun parentAndLeaf(): Pair<Spark.TreeNode, Spark.TreeNode> {
        val parent = Spark.TreeNode.newBuilder()
            .setId("parent")
            .setNodeTx(
                tx(
                    1u shl 30,
                    listOf(RawTransaction.Output(3_000uL, p2tr(otherKey)), RawTransaction.Output(5_000uL, p2tr(verifyingKey))),
                ).toByteString(),
            )
            .build()
        val leaf = Spark.TreeNode.newBuilder()
            .setId("leaf")
            .setParentNodeId(parent.id)
            .setVout(1)
            .setVerifyingPublicKey(verifyingKey.toByteString())
            .setNodeTx(tx((1u shl 30) or 2000u, listOf(RawTransaction.Output(5_000uL, p2tr(verifyingKey)))).toByteString())
            .setRefundTx(tx((1u shl 30) or 150u, listOf(RawTransaction.Output(5_000uL, p2tr(signingPublicKey)))).toByteString())
            .build()
        return parent to leaf
    }

    @Before
    fun requireFrost() = NativeFrost.assume()

    @Test
    fun theLeafsNodeAddressIsItsVerifyingKeyWithTheBip86Tweak() {
        // BIP-86 test vector: internal key cc8a4bc6… -> bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr
        val internalKey = hex("02cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115")
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", leafNodeAddress(internalKey, SparkNetwork.MAINNET))
    }

    @Test
    fun refundRenewalSpendsTheParentsOutputAtTheLeafsVoutAndPaysTheLeafsNodeAddress() {
        val (parent, leaf) = parentAndLeaf()
        val txs = refundRenewalTransactions(leaf, parent, signingPublicKey, config)
        val parentTxid = RawTransaction.parse(parent.nodeTx.toByteArray()).txid
        for (nodeTx in listOf(txs.node.cpfp.tx, txs.node.direct.tx)) {
            val parsed = RawTransaction.parse(nodeTx)
            assertArrayEquals(parentTxid, parsed.inputs[0].previousTxid)
            assertEquals(1u, parsed.inputs[0].previousIndex)
            assertArrayEquals(p2tr(verifyingKey), parsed.outputs[0].scriptPubKey)
        }
        assertEquals(5_000uL, RawTransaction.parse(txs.node.cpfp.tx).outputs[0].value)
        assertEquals((1u shl 30) or 1900u, RawTransaction.parse(txs.node.cpfp.tx).inputs[0].sequence)
        assertEquals(2000u, RawTransaction.parse(txs.refunds.cpfpRefund.tx).inputs[0].sequence and 0xFFFFu)
        assertNull(txs.split)
    }

    @Test
    fun nodeRenewalsSplitNodeSpendsTheParentsOutputAtTheLeafsVout() {
        val (parent, leaf) = parentAndLeaf()
        val txs = nodeRenewalTransactions(leaf, parent, signingPublicKey, config)
        val split = txs.split
        assertNotNull(split)
        val splitTx = RawTransaction.parse(split!!.cpfp.tx)
        assertArrayEquals(RawTransaction.parse(parent.nodeTx.toByteArray()).txid, splitTx.inputs[0].previousTxid)
        assertEquals(1u, splitTx.inputs[0].previousIndex)
        assertEquals(0u, splitTx.inputs[0].sequence and 0xFFFFu)
        assertArrayEquals(p2tr(verifyingKey), splitTx.outputs[0].scriptPubKey)
        assertEquals(5_000uL, splitTx.outputs[0].value)
        val nodeTx = RawTransaction.parse(txs.node.cpfp.tx)
        assertArrayEquals(splitTx.txid, nodeTx.inputs[0].previousTxid)
        assertEquals(0u, nodeTx.inputs[0].previousIndex)
        assertEquals(2000u, nodeTx.inputs[0].sequence and 0xFFFFu)
        assertArrayEquals(p2tr(verifyingKey), nodeTx.outputs[0].scriptPubKey)
    }

    @Test
    fun zeroTimelockRenewalSpendsTheLeafsOwnNodeTransaction() {
        val leaf = parentAndLeaf().second.toBuilder()
            .setNodeTx(tx(1u shl 30, listOf(RawTransaction.Output(5_000uL, p2tr(verifyingKey)))).toByteString())
            .build()
        val txs = zeroTimelockRenewalTransactions(leaf, signingPublicKey, config)
        val nodeTx = RawTransaction.parse(txs.node.cpfp.tx)
        assertArrayEquals(RawTransaction.parse(leaf.nodeTx.toByteArray()).txid, nodeTx.inputs[0].previousTxid)
        assertEquals(0u, nodeTx.inputs[0].previousIndex)
        assertEquals(0u, nodeTx.inputs[0].sequence and 0xFFFFu)
        assertArrayEquals(p2tr(verifyingKey), nodeTx.outputs[0].scriptPubKey)
        assertNull(txs.refunds.directRefund)
    }
}

/** The renewal rules that need no FROST library. */
class RenewalRuleTests {
    @Test
    fun theRenewalVariantFollowsTheOperatorsRulesIncludingFinalSequenceDepositRoots() {
        assertEquals(RenewalVariant.ZERO_TIMELOCK, renewalVariant(0u))
        assertEquals(RenewalVariant.ZERO_TIMELOCK, renewalVariant(1u shl 30))
        // A legacy deposit root's final sequence cannot be decremented; the operators renew it
        // like a zero-timelock node (renew_leaf_handler.go validateRenewZeroTimelock).
        assertEquals(RenewalVariant.ZERO_TIMELOCK, renewalVariant(0xFFFF_FFFFu))
        assertEquals(RenewalVariant.ZERO_TIMELOCK, renewalVariant(0xFFFF_FFFEu))
        assertEquals(RenewalVariant.NODE_TIMELOCK, renewalVariant((1u shl 30) or 100u))
        assertEquals(RenewalVariant.NODE_TIMELOCK, renewalVariant((1u shl 30) or 199u))
        assertEquals(RenewalVariant.REFUND_TIMELOCK, renewalVariant((1u shl 30) or 200u))
        assertEquals(RenewalVariant.REFUND_TIMELOCK, renewalVariant((1u shl 30) or 2000u))
    }

    @Test
    fun aRenewalIsKeyedByTheTxidOfTheRefundItReplacesAsInTheReferenceSdk() {
        val refund = RawTransaction(
            version = 3u,
            inputs = listOf(RawTransaction.Input(previousTxid = bytes(0x21, 32), previousIndex = 0u, sequence = 150u)),
            outputs = listOf(RawTransaction.Output(1_000uL, byteArrayOf(0x51, 0x20) + bytes(0x07, 32))),
            locktime = 0u,
            hasWitnessSerialization = false,
        )
        val node = Spark.TreeNode.newBuilder().setRefundTx(refund.serialized(includeWitness = false).toByteString()).build()
        assertEquals(refund.txidHex, renewalIdempotencyKey(node))
        assertEquals(64, renewalIdempotencyKey(node).length)
        expectSparkError { renewalIdempotencyKey(node.toBuilder().clearRefundTx().build()) }
    }
}
