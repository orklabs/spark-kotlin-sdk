package gy.pig.spark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import spark.Spark
import kotlin.math.abs

/**
 * Pins the client-side checks on the SSP's cooperative-exit response — the checks that stop a
 * misbehaving or impersonated SSP from taking leaves in exchange for an exit transaction that
 * does not pay the user. Ported from the Swift SDK's `WithdrawalValidationTests.swift`.
 */
class WithdrawalValidationTests {

    private val payoutAddress = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
    private val payoutScript = hex("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
    private val sspScript = hex("0014") + bytes(0x99, 20)

    /** An exit transaction paying [payoutSats] to the user plus change to the SSP. */
    private fun exitTransaction(payoutSats: ULong, payTo: ByteArray? = null): RawTransaction = RawTransaction(
        version = 2u,
        inputs = listOf(RawTransaction.Input(previousTxid = bytes(0xAB, 32), previousIndex = 0u)),
        outputs = listOf(
            RawTransaction.Output(value = 50_000uL, scriptPubKey = sspScript),
            RawTransaction.Output(value = payoutSats, scriptPubKey = payTo ?: payoutScript),
        ),
        locktime = 0u,
        hasWitnessSerialization = true,
    )

    /** A connector transaction spending [parentTxid] with one output per leaf plus the SSP's. */
    private fun connectorTransaction(parentTxid: ByteArray, leafCount: Int): RawTransaction = RawTransaction(
        version = 2u,
        inputs = listOf(RawTransaction.Input(previousTxid = parentTxid, previousIndex = 1u)),
        outputs = List(leafCount + 1) { RawTransaction.Output(value = 330uL, scriptPubKey = sspScript) },
        locktime = 0u,
        hasWitnessSerialization = false,
    )

    private fun validate(
        exit: RawTransaction,
        connector: RawTransaction,
        txidHex: String? = null,
        address: String? = null,
        minimumPayout: Long = 9_000,
        leafCount: Int = 2,
        network: SparkNetwork = SparkNetwork.MAINNET,
    ): CoopExitValidator.ValidatedExit = CoopExitValidator.validate(
        rawCoopExitTransactionHex = exit.serialized(includeWitness = true).toHexString(),
        rawConnectorTransactionHex = connector.serialized(includeWitness = true).toHexString(),
        coopExitTxidHex = txidHex ?: exit.txidHex,
        payoutAddress = address ?: payoutAddress,
        minimumPayoutSats = minimumPayout,
        leafCount = leafCount,
        network = network,
    )

    @Test
    fun aConsistentSspResponseIsAcceptedAndYieldsTheExitTxidInInternalOrder() {
        val exit = exitTransaction(payoutSats = 9_000uL)
        val connector = connectorTransaction(exit.txid, leafCount = 2)
        val validated = validate(exit, connector)
        assertArrayEquals(exit.txid, validated.exitTxid)
        assertArrayEquals(hex(exit.txidHex).reversedArray(), validated.exitTxid)
        assertEquals(1, validated.payoutVout)
        assertEquals(9_000uL, validated.payoutSats)
        assertEquals(connector, validated.connectorTx)

        // A payout above the minimum is fine; a txid given in internal byte order is fine too.
        val bigger = exitTransaction(payoutSats = 20_000uL)
        validate(bigger, connectorTransaction(bigger.txid, leafCount = 2))
        validate(exit, connector, txidHex = exit.txid.toHexString())
    }

    @Test
    fun anExitTransactionThatUnderpaysOrPaysSomeoneElseIsRefused() {
        val short = exitTransaction(payoutSats = 8_999uL)
        expectSparkError { validate(short, connectorTransaction(short.txid, leafCount = 2)) }
        val elsewhere = exitTransaction(payoutSats = 9_000uL, payTo = hex("5120") + bytes(0x42, 32))
        expectSparkError { validate(elsewhere, connectorTransaction(elsewhere.txid, leafCount = 2)) }
        // Right address, wrong network for the wallet.
        val exit = exitTransaction(payoutSats = 9_000uL)
        expectSparkError { validate(exit, connectorTransaction(exit.txid, leafCount = 2), network = SparkNetwork.REGTEST) }
        // Several outputs to the address are checked per output, not summed across outputs.
        val base = exitTransaction(payoutSats = 4_500uL)
        val split = base.copy(outputs = base.outputs + RawTransaction.Output(value = 4_500uL, scriptPubKey = payoutScript))
        expectSparkError { validate(split, connectorTransaction(split.txid, leafCount = 2)) }
    }

    @Test
    fun theReportedTxidMustMatchTheRawExitTransaction() {
        val exit = exitTransaction(payoutSats = 9_000uL)
        val connector = connectorTransaction(exit.txid, leafCount = 2)
        expectSparkError { validate(exit, connector, txidHex = "ab".repeat(32)) }
        expectSparkError { validate(exit, connector, txidHex = "not hex") }
        expectSparkError { validate(exit, connector, txidHex = exit.txidHex + "00") }
    }

    @Test
    fun theConnectorTransactionMustSpendTheExitTransactionAndCarryOneOutputPerLeaf() {
        val exit = exitTransaction(payoutSats = 9_000uL)
        val other = connectorTransaction(bytes(0x77, 32), leafCount = 2)
        expectSparkError { validate(exit, other) }

        val noInputs = connectorTransaction(exit.txid, leafCount = 2).copy(inputs = emptyList())
        expectSparkError { validate(exit, noInputs) }

        expectSparkError { validate(exit, connectorTransaction(exit.txid, leafCount = 1), leafCount = 2) }
        expectSparkError { validate(exit, connectorTransaction(exit.txid, leafCount = 3), leafCount = 2) }
        // Reversed byte order in the connector's prevout is tolerated, like the operators do.
        val reversed = connectorTransaction(exit.txid.reversedArray(), leafCount = 2)
        validate(exit, reversed)
    }

    @Test
    fun garbageTransactionHexAndBadAddressesAreRefusedWithoutSigning() {
        val exit = exitTransaction(payoutSats = 9_000uL)
        val connector = connectorTransaction(exit.txid, leafCount = 2)
        expectSparkError {
            CoopExitValidator.validate(
                rawCoopExitTransactionHex = "zz",
                rawConnectorTransactionHex = connector.serialized(includeWitness = true).toHexString(),
                coopExitTxidHex = exit.txidHex,
                payoutAddress = payoutAddress,
                minimumPayoutSats = 9_000,
                leafCount = 2,
                network = SparkNetwork.MAINNET,
            )
        }
        expectSparkError {
            CoopExitValidator.validate(
                rawCoopExitTransactionHex = exit.serialized(includeWitness = true).toHexString(),
                rawConnectorTransactionHex = "0100",
                coopExitTxidHex = exit.txidHex,
                payoutAddress = payoutAddress,
                minimumPayoutSats = 9_000,
                leafCount = 2,
                network = SparkNetwork.MAINNET,
            )
        }
        expectSparkError { validate(exit, connector, address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5") }
        expectSparkError { validate(exit, connector, minimumPayout = 0) }
    }

    @Test
    fun theFeeCapIsTheSspQuoteBoundedByTheCallersMaximum() {
        assertEquals(100L, CoopExitValidator.resolveFeeCap(quotedFeeSats = 100, maxFeeSats = null, amountSats = 1_000))
        assertEquals(200L, CoopExitValidator.resolveFeeCap(quotedFeeSats = 100, maxFeeSats = 200, amountSats = 1_000))
        assertEquals(0L, CoopExitValidator.resolveFeeCap(quotedFeeSats = 0, maxFeeSats = null, amountSats = 1))

        expectSparkError { CoopExitValidator.resolveFeeCap(quotedFeeSats = 201, maxFeeSats = 200, amountSats = 1_000) }
        expectSparkError { CoopExitValidator.resolveFeeCap(quotedFeeSats = 1_000, maxFeeSats = null, amountSats = 1_000) }
        expectSparkError { CoopExitValidator.resolveFeeCap(quotedFeeSats = 10, maxFeeSats = 5_000, amountSats = 1_000) }
        expectSparkError { CoopExitValidator.resolveFeeCap(quotedFeeSats = -1, maxFeeSats = null, amountSats = 1_000) }
        expectSparkError { CoopExitValidator.resolveFeeCap(quotedFeeSats = 1, maxFeeSats = -1, amountSats = 1_000) }
        expectSparkError { CoopExitValidator.resolveFeeCap(quotedFeeSats = 1, maxFeeSats = null, amountSats = 0) }

        try {
            CoopExitValidator.resolveFeeCap(quotedFeeSats = 300, maxFeeSats = 250, amountSats = 1_000)
            fail("expected FeeExceedsLimit")
        } catch (e: SparkError.FeeExceedsLimit) {
            assertEquals(300L, e.feeSats)
            assertEquals(250L, e.maxFeeSats)
        }
    }

    @Test
    fun exactLeafSelectionNeverOvershootsTheRequestedAmount() {
        fun leaf(id: String, sats: Long) = SparkLeaf(id = id, treeID = "t", valueSats = sats, status = "AVAILABLE", node = Spark.TreeNode.getDefaultInstance())
        val leaves = listOf(leaf("a", 100_000), leaf("b", 8_192), leaf("c", 1_024), leaf("d", 512))
        // Exact selection either finds the exact denominations or reports that a swap is needed;
        // a 1k withdrawal can never be served by the 100k leaf.
        assertNull(tryExactSelection(leaves, amountSats = 1_000))
        assertEquals(listOf("c", "d"), tryExactSelection(leaves, amountSats = 1_536)?.map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), tryExactSelection(leaves, amountSats = 109_728)?.map { it.id })
        val picked = tryExactSelection(leaves, amountSats = 9_216)
        assertNotNull("expected an exact selection", picked)
        assertEquals(9_216L, picked!!.sumOf { it.valueSats })
    }
}

/** Spendable leaf selection */
class SpendableLeafTests {
    private fun leaf(id: String, sats: Long, timelock: UInt) = leafWithRefundTimelock(id, sats, timelock)

    @Test
    fun leavesAtOrBelowTheTimelockFloorAreNeverSelectedForSpending() {
        val leaves = listOf(
            leaf("stuck0", sats = 8, timelock = 0u),
            leaf("stuck100", sats = 8, timelock = 100u),
            leaf("ok200", sats = 8, timelock = 200u),
            leaf("ok2000", sats = 2, timelock = 2000u),
        )
        assertEquals(listOf("ok200", "ok2000"), movableLeaves(leaves).map { it.id })
        // The greedy exact selection over movable leaves finds the healthy 8 + 2 for a 10-sat send.
        assertEquals(listOf("ok200", "ok2000"), tryExactSelection(movableLeaves(leaves), amountSats = 10)?.map { it.id })
        val garbage = SparkLeaf(id = "garbage", treeID = "t", valueSats = 5, status = "AVAILABLE", node = Spark.TreeNode.newBuilder().setId("garbage").build())
        assertTrue(movableLeaves(listOf(garbage)).isEmpty())
    }

    @Test
    fun sparkLeafIsSpendableAndIsRenewableFollowTheCoordinatorsFloorAndRenewalRange() {
        assertFalse(leaf("a", sats = 1, timelock = 0u).isSpendable)
        assertFalse(leaf("b", sats = 1, timelock = 100u).isSpendable)
        assertTrue(leaf("c", sats = 1, timelock = 101u).isSpendable)
        assertTrue(leaf("d", sats = 1, timelock = 2000u).isSpendable)
        assertFalse(leaf("a", sats = 1, timelock = 99u).isRenewable)
        assertTrue(leaf("b", sats = 1, timelock = 100u).isRenewable)
        assertTrue(leaf("c", sats = 1, timelock = 199u).isRenewable)
        assertFalse(leaf("d", sats = 1, timelock = 200u).isRenewable)
        val garbage = SparkLeaf(id = "garbage", treeID = "t", valueSats = 5, status = "AVAILABLE", node = Spark.TreeNode.newBuilder().setId("garbage").build())
        assertFalse(garbage.isSpendable)
        assertFalse(garbage.isRenewable)
        // A leaf without node data (never produced by the SDK) is neither, rather than a crash.
        assertFalse(SparkLeaf(id = "bare", treeID = "t", valueSats = 5, status = "AVAILABLE").isSpendable)
        // The public flag and the internal selection filter agree.
        val leaves = listOf(leaf("x", sats = 1, timelock = 0u), leaf("y", sats = 1, timelock = 100u), leaf("z", sats = 1, timelock = 150u))
        val viaFlag = leaves.filter { it.isSpendable }.map { it.id }
        val viaFilter = movableLeaves(leaves).map { it.id }
        assertEquals(viaFlag, viaFilter)
        assertEquals(listOf("z"), viaFlag)
    }

    @Test
    fun renewalCandidatesAreRenewableFrom100To200StuckBelow100HealthyFrom200() {
        val leaves = listOf(
            leaf("a", sats = 1, timelock = 0u),
            leaf("b", sats = 1, timelock = 99u),
            leaf("c", sats = 1, timelock = 100u),
            leaf("d", sats = 1, timelock = 199u),
            leaf("e", sats = 1, timelock = 200u),
            leaf("f", sats = 1, timelock = 2000u),
        )
        val (renewable, stuck) = renewalCandidates(leaves)
        assertEquals(listOf("c", "d"), renewable.map { it.id })
        assertEquals(listOf("a", "b"), stuck.map { it.id })
    }
}

/** Withdraw-all quote and result */
class WithdrawAllTypesTests {
    @Test
    fun quoteDerivesPayoutFrozenShareAndFeeCoverage() {
        val q = WithdrawAllQuote(spendableSats = 9_700, quotedFeeSats = 1_950, frozenSats = 300, lockedSats = 0, incomingSats = 0, leafCount = 4)
        assertEquals(7_750L, q.estimatedPayoutSats)
        assertTrue(abs(q.frozenFraction - 0.03) < 1e-9)
        assertTrue(q.coversFee)

        val tiny = WithdrawAllQuote(spendableSats = 1_000, quotedFeeSats = 1_950, frozenSats = 0, lockedSats = 0, incomingSats = 0, leafCount = 1)
        assertFalse(tiny.coversFee)
        assertTrue(tiny.estimatedPayoutSats < 0)
        assertEquals(0.0, tiny.frozenFraction, 0.0)

        val onlyFrozen = WithdrawAllQuote(spendableSats = 0, quotedFeeSats = 0, frozenSats = 66, lockedSats = 0, incomingSats = 0, leafCount = 0)
        assertEquals(1.0, onlyFrozen.frozenFraction, 0.0)
        assertFalse(onlyFrozen.coversFee)

        val empty = WithdrawAllQuote(spendableSats = 0, quotedFeeSats = 0, frozenSats = 0, lockedSats = 0, incomingSats = 0, leafCount = 0)
        assertEquals(0.0, empty.frozenFraction, 0.0)
        assertFalse(empty.coversFee)
    }

    @Test
    fun resultReportsTheFeeTheSspActuallyTook() {
        val r = WithdrawAllResult(txid = "ab", sentSats = 5_000, payoutSats = 3_290, frozenSats = 66, lockedSats = 0, unclaimedSats = 0)
        assertEquals(1_710L, r.feeSats)
    }
}
