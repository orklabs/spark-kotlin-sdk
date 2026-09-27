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
    fun leavesBelowTheRenewalMinimumAreFrozenRenewableLeavesCountAsAvailableOtherStatusesAreIgnored() {
        val nodes = mapOf(
            "a" to treeNode("a", status = "AVAILABLE", value = 8192, refundTimelock = 1600u),
            "b" to treeNode("b", status = "AVAILABLE", value = 32, refundTimelock = 0u),
            "c" to treeNode("c", status = "AVAILABLE", value = 2, refundTimelock = 100u),
            "d" to treeNode("d", status = "TRANSFER_LOCKED", value = 500, refundTimelock = 2000u),
            "e" to treeNode("e", status = "CREATING", value = 700, refundTimelock = 2000u),
            // A renewal split node: permanently SPLIT_LOCKED, still carrying the owner key.
            "f" to treeNode("f", status = "SPLIT_LOCKED", value = 9, refundTimelock = 2000u),
            "g" to treeNode("g", status = "AVAILABLE", value = 64, refundTimelock = 200u),
            "h" to treeNode("h", status = "AVAILABLE", value = 16, refundTimelock = 150u),
            "i" to treeNode("i", status = "AVAILABLE", value = 4, refundTimelock = 99u),
        )
        val summary = summarizeNodes(nodes)
        // 100 and 150 are renewable (the coordinator renews refund timelocks from 100), so they
        // are available; 0 and 99 are below the renewal minimum and frozen.
        assertEquals(8192L + 2 + 64 + 16, summary.available)
        assertEquals(32L + 4, summary.frozen)
        assertEquals(setOf("a", "b", "c", "g", "h", "i"), summary.leaves.map { it.id }.toSet())
        val empty = summarizeNodes(emptyMap())
        assertTrue(empty.available == 0L && empty.frozen == 0L && empty.leaves.isEmpty())
    }

    private fun transfer(id: String, leaves: List<Pair<String, Long>>): Spark.Transfer = Spark.Transfer.newBuilder()
        .setId(id)
        .addAllLeaves(
            leaves.map { (leafId, value) ->
                Spark.TransferLeaf.newBuilder().setLeaf(Spark.TreeNode.newBuilder().setId(leafId).setValue(value)).build()
            }
        )
        .build()

    @Test
    fun inFlightSatsCountEachLeafOnceAndNeverALeafThatIsAlreadyAvailable() {
        val transfers = listOf(
            transfer("outgoing", listOf("l1" to 500L, "l2" to 20L)),
            // A self-transfer, or a counter-swap leaf mid-claim, shows up in two queries.
            transfer("counter", listOf("l2" to 20L, "l3" to 8L)),
            transfer("claimed", listOf("available-leaf" to 64L)),
        )
        assertEquals(500L + 20 + 8, leafSats(transfers, excludedLeafIds = setOf("available-leaf")))
        assertEquals(0L, leafSats(emptyList(), excludedLeafIds = emptySet()))
        val withoutNode = Spark.Transfer.newBuilder().addLeaves(Spark.TransferLeaf.getDefaultInstance()).build()
        assertEquals(0L, leafSats(listOf(withoutNode), excludedLeafIds = emptySet()))
    }

    @Test
    fun amountsAnOperatorReportsAboveTheBitcoinSupplyAreCappedInsteadOfGoingNegative() {
        assertEquals(0L, reportedSats(0L))
        assertEquals(12_345L, reportedSats(12_345L))
        assertEquals(MAX_SUPPLY_SATS, reportedSats(MAX_SUPPLY_SATS))
        // uint64 2^63 and above read as negative Longs.
        assertEquals(MAX_SUPPLY_SATS, reportedSats(Long.MIN_VALUE))
        assertEquals(MAX_SUPPLY_SATS, reportedSats(-1L))
        assertEquals(MAX_SUPPLY_SATS, reportedSats(Long.MAX_VALUE))
        assertEquals(MAX_SUPPLY_SATS, reportedSats(ULong.MAX_VALUE))
        assertEquals(7L, reportedSats(7uL))

        val hostile = Spark.Transfer.newBuilder().setId("hostile").setTotalValue(-1L).build()
        assertEquals(MAX_SUPPLY_SATS, hostile.toSparkTransfer().totalValueSats)

        // Two such leaves still add up without overflowing.
        val nodes = mapOf(
            "x" to treeNode("x", status = "AVAILABLE", value = -1L, refundTimelock = 2000u),
            "y" to treeNode("y", status = "AVAILABLE", value = -1L, refundTimelock = 50u),
        )
        val summary = summarizeNodes(nodes)
        assertEquals(MAX_SUPPLY_SATS, summary.available)
        assertEquals(MAX_SUPPLY_SATS, summary.frozen)
        assertEquals(2 * MAX_SUPPLY_SATS, leafSats(listOf(transfer("t", listOf("l1" to -1L, "l2" to -1L))), excludedLeafIds = emptySet()))
    }

    @Test
    fun incomingLeavesOutCounterTransfersOfTheWalletsOwnSwapsAndLeavesCountedElsewhere() {
        val counterSwap = transfer("counter", listOf("c1" to 512L)).toBuilder().setType(Spark.TransferType.COUNTER_SWAP_V3).build()
        val legacyCounterSwap = transfer("legacy-counter", listOf("c2" to 256L)).toBuilder().setType(Spark.TransferType.COUNTER_SWAP).build()
        val payment = transfer("lightning", listOf("p1" to 1_000L, "p2" to 24L)).toBuilder().setType(Spark.TransferType.PREIMAGE_SWAP).build()
        val selfTransfer = transfer("self", listOf("s1" to 7L)).toBuilder().setType(Spark.TransferType.TRANSFER).build()
        val pending = listOf(counterSwap, legacyCounterSwap, payment, selfTransfer)
        // The self-transfer's leaf is already counted as outgoing.
        val me = byteArrayOf(0x02) + bytes(0x33, 32)
        assertEquals(1_000L + 24, incomingSats(pending, excludedLeafIds = setOf("s1"), receiver = me))
        assertEquals(1_000L + 24 + 7, incomingSats(pending, excludedLeafIds = emptySet(), receiver = me))

        // A multi-receiver payment counts only this wallet's leaves.
        val splitBuilder = transfer("split", listOf("m1" to 300L, "m2" to 200L, "m3" to 100L)).toBuilder().setType(Spark.TransferType.TRANSFER)
        for ((id, key) in listOf("edge-me" to me, "edge-other" to (byteArrayOf(0x03) + bytes(0x44, 32)))) {
            splitBuilder.addReceivers(Spark.TransferReceiver.newBuilder().setId(id).setIdentityPublicKey(key.toByteString()))
        }
        for ((index, edge) in listOf("edge-me", "edge-other", "edge-me").withIndex()) {
            splitBuilder.setLeaves(index, splitBuilder.getLeaves(index).toBuilder().setTransferReceiverId(edge))
        }
        assertEquals(300L + 100, incomingSats(listOf(splitBuilder.build()), excludedLeafIds = emptySet(), receiver = me))
    }

    @Test
    fun inFlightTransfersAreQueriedWithTheReferenceSdksTypesAndStatuses() {
        // transfer.ts SENDER_PENDING_STATUSES: before the sender key tweak is applied.
        assertEquals(
            listOf(
                Spark.TransferStatus.TRANSFER_STATUS_SENDER_INITIATED,
                Spark.TransferStatus.TRANSFER_STATUS_SENDER_INITIATED_COORDINATOR,
                Spark.TransferStatus.TRANSFER_STATUS_APPLYING_SENDER_KEY_TWEAK,
                Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAK_PENDING,
            ),
            SENDER_PENDING_STATUSES,
        )
        // ACTIVE_COUNTER_SWAP_STATUSES: the whole counter-transfer lifecycle until completion.
        assertEquals(
            SENDER_PENDING_STATUSES + listOf(
                Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_LOCKED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_APPLIED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAKED,
                Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_REFUND_SIGNED,
            ),
            ACTIVE_COUNTER_SWAP_STATUSES,
        )
        assertFalse(Spark.TransferStatus.TRANSFER_STATUS_COMPLETED in ACTIVE_COUNTER_SWAP_STATUSES)
        assertEquals(
            listOf(Spark.TransferType.COOPERATIVE_EXIT, Spark.TransferType.UTXO_SWAP, Spark.TransferType.PREIMAGE_SWAP, Spark.TransferType.TRANSFER),
            OUTGOING_TRANSFER_TYPES,
        )
        assertEquals(listOf(Spark.TransferType.PRIMARY_SWAP_V3, Spark.TransferType.SWAP), PRIMARY_SWAP_TYPES)
        assertEquals(listOf(Spark.TransferType.COUNTER_SWAP_V3, Spark.TransferType.COUNTER_SWAP), COUNTER_SWAP_TYPES)
    }

    @Test
    fun lockedIsWhatOwnedHoldsBeyondAvailableAndFrozen() {
        assertEquals(509L, SatsBalance(available = 8256, owned = 8799, incoming = 700, frozen = 34).locked)
        assertEquals(0L, SatsBalance(available = 10, owned = 10, incoming = 0, frozen = 0).locked)
        assertEquals(0L, SatsBalance(available = 10, owned = 5, incoming = 0, frozen = 0).locked)
    }

    @Test(timeout = 60_000)
    fun theBalanceReadsAvailableNodesAndInFlightAndPendingTransfersAndSurvivesUnreadableTokens() = kotlinx.coroutines.runBlocking {
        val state = FakeOperatorState { false }
        state.setNodes(
            listOf(
                treeNode("a", status = "AVAILABLE", value = 64, refundTimelock = 2000u),
                treeNode("b", status = "AVAILABLE", value = 4, refundTimelock = 50u),
            ),
        )
        state.failsTokenMetadata = true
        state.setTokenOutputs(
            listOf(
                spark_token.OutputWithPreviousTransactionData.newBuilder()
                    .setOutput(spark_token.TokenOutput.newBuilder().setTokenIdentifier(bytes(1, 32).toByteString()).setTokenAmount(bytes(0, 16).toByteString()))
                    .build()
            )
        )
        val balance = withFakeOperator(state) { it.getBalance() }
        assertEquals(64L, balance.satsBalance.available)
        assertEquals(4L, balance.satsBalance.frozen)
        assertEquals(68L, balance.satsBalance.owned)
        assertEquals(0L, balance.satsBalance.incoming)
        assertTrue(balance.tokenBalances.isEmpty())
        // Three in-flight queries (outgoing, primary swaps, counter swaps), sender-only for the first two.
        val filters = state.transferFilters
        assertEquals(3, filters.size)
        assertEquals(2, filters.count { it.hasSenderIdentityPublicKey() })
        assertEquals(1, filters.count { it.hasSenderOrReceiverIdentityPublicKey() })
    }
}
