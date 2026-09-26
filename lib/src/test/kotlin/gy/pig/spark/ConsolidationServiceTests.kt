package gy.pig.spark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from the Swift SDK's `ConsolidationServiceTests.swift`, plus coverage for
 * the timelock-floor guard the Swift SDK added alongside consolidation.
 */
class ConsolidationServiceTests {

    @Test
    fun binaryDecompositionYieldsMinimalPowerOfTwoSet() {
        assertEquals(emptyList<Long>(), binaryDecomposition(0))
        assertEquals(listOf(1L), binaryDecomposition(1))
        assertEquals(listOf(64L, 32L, 16L, 8L, 1L), binaryDecomposition(121))
        assertEquals(listOf(65536L, 4096L, 2048L, 1024L, 256L, 4L, 1L), binaryDecomposition(72965))
        assertEquals(72965L, binaryDecomposition(72965).sum())
        assertEquals(emptyList<Long>(), binaryDecomposition(-5))
    }

    @Test
    fun p2trAddressMatchesBip86ReferenceVector() {
        // BIP-86 first receive address: output key -> bc1p5cyxnux...
        val script = "5120a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c".hexToByteArray()
        val address = BitcoinAddress.p2trAddress(scriptPubKey = script, network = SparkNetwork.MAINNET)
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", address)

        try {
            BitcoinAddress.p2trAddress(scriptPubKey = byteArrayOf(0x00, 0x14), network = SparkNetwork.MAINNET)
            throw AssertionError("Expected SparkError for non-P2TR script")
        } catch (_: SparkError.InvalidResponse) {
        }
    }

    // ── Timelock floor guard ────────────────────────────────────────────────

    /**
     * Minimal legacy raw tx with one input whose nSequence is [sequence] and no outputs. It has
     * to be a complete transaction: the bounds-checked parser rejects truncated bytes.
     */
    private fun rawTxWithSequence(sequence: UInt): ByteArray = RawTransaction(
        version = 0u,
        inputs = listOf(RawTransaction.Input(previousTxid = ByteArray(32), previousIndex = 0u, sequence = sequence)),
        outputs = emptyList(),
        locktime = 0u,
        hasWitnessSerialization = false,
    ).serialized(includeWitness = false)

    @Test
    fun computeNextSequencesDecrementsAboveFloor() {
        val bit30 = 1u shl 30
        val (cpfp, direct) = computeNextSequences(rawTxWithSequence(bit30 or 2000u))
        assertEquals(bit30 or 1900u, cpfp)
        assertEquals(bit30 or 1950u, direct)
    }

    @Test
    fun computeNextSequencesThrowsAtOrBelowFloor() {
        val bit30 = 1u shl 30
        for (timelock in listOf(100u, 50u, 0u)) {
            try {
                computeNextSequences(rawTxWithSequence(bit30 or timelock))
                throw AssertionError("Expected LeafTimelockExhausted at timelock $timelock")
            } catch (e: SparkError.LeafTimelockExhausted) {
                assertTrue(e.message.contains("needs renewal"))
            }
        }
    }

    @Test
    fun timelockCanDecrementMatchesFloorRule() {
        val bit30 = 1u shl 30
        assertTrue(timelockCanDecrement(rawTxWithSequence(bit30 or 2000u)))
        assertTrue(timelockCanDecrement(rawTxWithSequence(bit30 or 101u)))
        assertTrue(!timelockCanDecrement(rawTxWithSequence(bit30 or 100u)))
        assertTrue(!timelockCanDecrement(rawTxWithSequence(bit30 or 0u)))
    }
}
