package gy.pig.spark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import spark.Spark
import uniffi.spark_frost.computeMultiInputSighashUniffi

// Ported from the Swift SDK's `CoopExitFlowTests.swift`.

private fun p2trScript(byte: Int): ByteArray = byteArrayOf(0x51, 0x20) + bytes(byte, 32)

/** A minimal node transaction (one input with the given timelock, one P2TR output). */
private fun nodeTx(timelock: UInt, value: ULong = 10_000uL, tag: Int = 0x11): ByteArray = RawTransaction(
    version = 2u,
    inputs = listOf(RawTransaction.Input(previousTxid = bytes(tag, 32), previousIndex = 0u, sequence = (1u shl 30) or timelock)),
    outputs = listOf(RawTransaction.Output(value = value, scriptPubKey = p2trScript(tag))),
    locktime = 0u,
    hasWitnessSerialization = false,
).serialized(includeWitness = true)

/**
 * Cooperative exit refund construction. The refunds come from the FROST library, so these
 * tests need it on the host (see [NativeFrost]).
 */
class ConnectorRefundTests {
    private val receiver = KeyDerivation.fromMnemonic(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        account = 0,
    ).identityPublicKey
    private val connectorTx = RawTransaction(
        version = 2u,
        inputs = listOf(RawTransaction.Input(previousTxid = bytes(0xEE, 32), previousIndex = 1u)),
        outputs = listOf(
            RawTransaction.Output(value = 330uL, scriptPubKey = byteArrayOf(0x00, 0x14) + bytes(0x77, 20)),
            RawTransaction.Output(value = 331uL, scriptPubKey = byteArrayOf(0x00, 0x14) + bytes(0x78, 20)),
            RawTransaction.Output(value = 5_000uL, scriptPubKey = byteArrayOf(0x00, 0x14) + bytes(0x79, 20)),
        ),
        locktime = 0u,
        hasWitnessSerialization = false,
    )

    @Before
    fun requireFrost() = NativeFrost.assume()

    private fun node(nodeTimelock: UInt, refundTimelock: UInt, withDirect: Boolean): Spark.TreeNode {
        val builder = Spark.TreeNode.newBuilder()
            .setId("leaf")
            .setNodeTx(nodeTx(nodeTimelock).toByteString())
            .setRefundTx(nodeTx(refundTimelock, value = 9_045uL, tag = 0x22).toByteString())
        if (withDirect) builder.setDirectTx(nodeTx(nodeTimelock + 50u, tag = 0x33).toByteString())
        return builder.build()
    }

    private fun refunds(node: Spark.TreeNode, connectorVout: UInt) = buildConnectorRefunds(
        node = node,
        receiverPubKey = receiver,
        connectorTxid = connectorTx.txid,
        connectorTx = connectorTx,
        connectorVout = connectorVout,
        network = "mainnet",
    )

    @Test
    fun refundsSpendTheNodeOutputFirstAndTheConnectorOutputSecondSighashOverBothPrevouts() {
        val leaf = node(nodeTimelock = 1000u, refundTimelock = 2000u, withDirect = true)
        val refunds = refunds(leaf, connectorVout = 1u)
        val nodeTxid = RawTransaction.parse(leaf.nodeTx.toByteArray()).txid
        for ((label, refund) in listOf("cpfp" to refunds.cpfp, "directFromCpfp" to refunds.directFromCpfp)) {
            val tx = RawTransaction.parse(refund.tx)
            assertEquals(label, 2, tx.inputs.size)
            assertArrayEquals(label, nodeTxid, tx.inputs[0].previousTxid)
            assertEquals(0u, tx.inputs[0].previousIndex)
            assertArrayEquals(label, connectorTx.txid, tx.inputs[1].previousTxid)
            assertEquals(1u, tx.inputs[1].previousIndex)
            assertEquals(0xFFFF_FFFFu, tx.inputs[1].sequence)
            assertEquals(32, refund.sighash.size)
            val recomputed = computeMultiInputSighashUniffi(
                tx = refund.tx,
                inputIndex = 0u,
                prevOutScripts = listOf(p2trScript(0x11), connectorTx.outputs[1].scriptPubKey),
                prevOutValues = listOf(10_000uL, 331uL),
            )
            assertArrayEquals(label, recomputed, refund.sighash)
        }
        // Timelocks step down by one interval; the direct refund carries the direct offset.
        assertEquals(1900u, RawTransaction.parse(refunds.cpfp.tx).inputs[0].sequence and 0xFFFFu)
        assertEquals(1950u, RawTransaction.parse(refunds.directFromCpfp.tx).inputs[0].sequence and 0xFFFFu)
        val direct = assertNotNullAndGet(refunds.direct)
        val directTx = RawTransaction.parse(direct.tx)
        assertEquals(2, directTx.inputs.size)
        assertArrayEquals(RawTransaction.parse(leaf.directTx.toByteArray()).txid, directTx.inputs[0].previousTxid)
        assertEquals(1950u, directTx.inputs[0].sequence and 0xFFFFu)
        assertFalse(direct.sighash.contentEquals(refunds.cpfp.sighash))
        assertFalse(refunds.cpfp.sighash.contentEquals(refunds.directFromCpfp.sighash))
    }

    @Test
    fun noDirectRefundForZeroTimelockNodesOrLeavesWithoutADirectNodeTransaction() {
        val zeroRefunds = refunds(node(nodeTimelock = 0u, refundTimelock = 2000u, withDirect = true), connectorVout = 0u)
        assertNull(zeroRefunds.direct)
        val noDirect = node(nodeTimelock = 1000u, refundTimelock = 2000u, withDirect = false)
        assertNull(refunds(noDirect, connectorVout = 0u).direct)
        // A leaf at the timelock floor cannot be exited cooperatively.
        val floor = node(nodeTimelock = 1000u, refundTimelock = 100u, withDirect = false)
        expectSparkError { refunds(floor, connectorVout = 0u) }
        // The connector output must exist.
        expectSparkError { refunds(noDirect, connectorVout = 9u) }
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}

/** Balance summary */
class BalanceSummaryTests {
    private fun treeNode(id: String, status: String, value: Long, refundTimelock: UInt): Spark.TreeNode = Spark.TreeNode.newBuilder()
        .setId(id)
        .setStatus(status)
        .setValue(value)
        .setRefundTx(nodeTx(refundTimelock).toByteString())
        .build()

    @Test
    fun availableExcludesFloorTimelockLeavesWhichAreReportedAsFrozenLockedAddsToOwnedOnly() {
        val nodes = mapOf(
            "a" to treeNode("a", status = "AVAILABLE", value = 8192, refundTimelock = 1600u),
            "b" to treeNode("b", status = "AVAILABLE", value = 32, refundTimelock = 0u),
            "c" to treeNode("c", status = "AVAILABLE", value = 2, refundTimelock = 100u),
            "d" to treeNode("d", status = "TRANSFER_LOCKED", value = 500, refundTimelock = 2000u),
            "e" to treeNode("e", status = "CREATING", value = 700, refundTimelock = 2000u),
            "f" to treeNode("f", status = "SPLIT_LOCKED", value = 9, refundTimelock = 2000u),
            "g" to treeNode("g", status = "AVAILABLE", value = 64, refundTimelock = 200u),
        )
        val s = summarizeNodes(nodes)
        assertEquals(8192L + 64, s.available)
        assertEquals(32L + 2, s.frozen)
        assertEquals(8192L + 32 + 2 + 500 + 9 + 64, s.owned)
        assertEquals(700L, s.creating)
        assertEquals(setOf("a", "b", "c", "g"), s.leaves.map { it.id }.toSet())
        val empty = summarizeNodes(emptyMap())
        assertTrue(empty.available == 0L && empty.owned == 0L && empty.frozen == 0L && empty.creating == 0L && empty.leaves.isEmpty())
    }

    @Test
    fun lockedIsWhatOwnedHoldsBeyondAvailableAndFrozen() {
        // SatsBalance.locked (0.2.1): sats held by an in-flight transfer, swap, renewal or exit.
        assertEquals(509L, SatsBalance(available = 8256, owned = 8799, incoming = 700, frozen = 34).locked)
        assertEquals(0L, SatsBalance(available = 10, owned = 10, incoming = 0, frozen = 0).locked)
        assertEquals(0L, SatsBalance(available = 10, owned = 5, incoming = 0, frozen = 0).locked)
    }
}
