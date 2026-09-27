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

/**
 * The refunds a send or a claim signs for a leaf. The operators' rule (`base_transfer_handler.go`:
 * "zero nodes must not have a direct refund tx", with `IsZeroNode` = node transaction timelock 0)
 * and the reference SDK's `isZeroNode` check decide whether a direct refund is built. Ported from
 * the Swift SDK's `RefundConstructionTests.swift`; the refunds come from the FROST library, so
 * these tests need it on the host (see [NativeFrost]).
 */
class RefundConstructionTests {
    private val receiver = KeyDerivation.fromMnemonic(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        account = 0,
    ).identityPublicKey

    @Before
    fun requireFrost() = NativeFrost.assume()

    private fun leaf(nodeTimelock: UInt, withDirect: Boolean): Spark.TreeNode {
        val builder = Spark.TreeNode.newBuilder()
            .setId("leaf")
            .setNodeTx(nodeTx(nodeTimelock).toByteString())
            .setRefundTx(nodeTx(2000u, value = 9_045uL, tag = 0x22).toByteString())
        if (withDirect) builder.setDirectTx(nodeTx(nodeTimelock + 50u, tag = 0x33).toByteString())
        return builder.build()
    }

    private fun trio(node: Spark.TreeNode): uniffi.spark_frost.RefundTxTrioResult {
        val (cpfp, direct) = computeNextSequences(node.refundTx.toByteArray())
        return leafRefundTrio(node = node, receivingPubkey = receiver, network = "mainnet", sequence = cpfp, directSequence = direct)
    }

    @Test
    fun aZeroTimelockNodeGetsNoDirectRefundEvenWithADirectNodeTransaction() {
        // Zero-timelock renewal leaves this shape: node transaction at timelock 0 plus a direct one.
        val zero = leaf(nodeTimelock = 0u, withDirect = true)
        assertTrue(isZeroTimelockNode(zero.nodeTx.toByteArray()))
        assertNull(directNodeTxForRefund(zero))
        val refunds = trio(zero)
        assertNull(refunds.directRefund)
        val nodeTxid = RawTransaction.parse(zero.nodeTx.toByteArray()).txid
        assertArrayEquals(nodeTxid, RawTransaction.parse(refunds.cpfpRefund.tx).inputs[0].previousTxid)
        assertArrayEquals(nodeTxid, RawTransaction.parse(refunds.directFromCpfpRefund.tx).inputs[0].previousTxid)
    }

    @Test
    fun aTimelockedNodeWithADirectNodeTransactionGetsADirectRefundSpendingIt() {
        val node = leaf(nodeTimelock = 1000u, withDirect = true)
        assertFalse(isZeroTimelockNode(node.nodeTx.toByteArray()))
        assertArrayEquals(node.directTx.toByteArray(), directNodeTxForRefund(node))
        val direct = trio(node).directRefund
        assertNotNull(direct)
        val directTx = RawTransaction.parse(direct!!.tx)
        assertArrayEquals(RawTransaction.parse(node.directTx.toByteArray()).txid, directTx.inputs[0].previousTxid)
        assertEquals(1950u, directTx.inputs[0].sequence and 0xFFFFu)
    }

    @Test
    fun aLeafWithoutADirectNodeTransactionGetsNoDirectRefund() {
        val node = leaf(nodeTimelock = 1000u, withDirect = false)
        assertNull(directNodeTxForRefund(node))
        assertNull(trio(node).directRefund)
    }
}

/**
 * Refund timelock arithmetic, pinned to the operators' own test vectors (`validation_test.go`:
 * RoundDownToTimelockInterval, ValidateSequence misaligned/aligned/floor) and the reference SDK's
 * (`transaction-construction.test.ts`: createDecrementedTimelockRefundTxs).
 */
class TimelockArithmeticTests {
    private fun refundTx(timelock: UInt): ByteArray = nodeTx(timelock)

    @Test
    fun timelocksRoundDownToThe100BlockIntervalLikeTheOperators() {
        val vectors = listOf(100u to 100u, 1000u to 1000u, 740u to 700u, 670u to 600u, 0u to 0u, 1970u to 1900u, 1870u to 1800u)
        for ((timelock, rounded) in vectors) {
            assertEquals("$timelock", rounded, roundedTimelock(timelock))
        }
    }

    @Test
    fun theNextRefundIsTheRoundedTimelockMinus100AndTheDirectRefunds50AboveIt() {
        // (current refund timelock, expected next CPFP timelock)
        val vectors = listOf(
            2000u to 1900u, // reference SDK: decrements by TIME_LOCK_INTERVAL
            550u to 400u, // reference SDK: a non-aligned 550 rounds to 500, then decrements
            740u to 600u, // operators: 600 accepted for a 740 leaf, 640 rejected
            700u to 600u, // operators: aligned
            1000u to 900u, // operators: 1000 expects 900
            200u to 100u,
        )
        for ((current, next) in vectors) {
            val (cpfp, direct) = computeNextSequences(refundTx(current))
            assertEquals("current $current", (1u shl 30) or next, cpfp)
            assertEquals("current $current", (1u shl 30) or (next + 50u), direct)
        }
        // Every aligned and misaligned value the operators accept: RoundDown(t) - 100.
        for (current in 200u..2100u) {
            val (cpfp, _) = computeNextSequences(refundTx(current))
            assertEquals("current $current", current - current % 100u - 100u, cpfp and 0xFFFFu)
        }
    }

    @Test
    fun aRefundTimelockThatRoundsTo100OrLessCannotBeDecrementedWithoutARenewal() {
        for (current in listOf(0u, 50u, 99u, 100u, 101u, 150u, 199u)) {
            expectSparkError("current $current") { computeNextSequences(refundTx(current)) }
            assertFalse("current $current", timelockCanDecrement(refundTx(current)))
        }
        assertTrue(timelockCanDecrement(refundTx(200u)))
        assertTrue(timelockCanDecrement(refundTx(740u)))
    }

    @Test
    fun lightningHtlcRefundsAreNotRoundedRefundSequenceMinus30AndMinus15AsTheOperatorsRebuildThem() {
        // Current refund timelock -> (CPFP HTLC, direct HTLC) timelocks.
        val vectors = mapOf(2000u to (1970u to 1985u), 740u to (710u to 725u), 1234u to (1204u to 1219u))
        for ((current, expected) in vectors) {
            val (cpfp, direct) = htlcSequences(refundTx(current))
            assertEquals("current $current", (1u shl 30) or expected.first, cpfp)
            assertEquals("current $current", (1u shl 30) or expected.second, direct)
        }
        expectSparkError { htlcSequences(refundTx(100u)) }
    }
}
