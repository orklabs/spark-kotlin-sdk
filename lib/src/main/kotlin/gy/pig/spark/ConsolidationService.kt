// Service files are named after the Swift SDK's services, not their single public type.
@file:Suppress("MatchingDeclarationName")

package gy.pig.spark

/**
 * Leaf consolidation — direct port of the Swift SDK's `ConsolidationService.swift`.
 */

/**
 * Outcome of a leaf consolidation run. [feeSats] is MEASURED (total before minus
 * total after) rather than assumed — SSP swaps are requested with fee_sats 0 today,
 * and this surfaces it if that ever changes.
 */
public data class SparkLeafConsolidation(
    val leavesBefore: Int,
    val leavesAfter: Int,
    val totalSatsBefore: Long,
    val totalSatsAfter: Long,
    val rounds: Int,
    /**
     * Leaves whose refund timelock is exhausted — they cannot move off-chain until
     * the operators renew them, so consolidation skips them. They stay fully
     * exitable via the recovery bundle.
     */
    val skippedLeaves: Int,
) {
    val feeSats: Long get() = totalSatsBefore - totalSatsAfter
}

/**
 * Swap the wallet's leaves toward the fewest denominations (the binary decomposition
 * of the total — same greedy power-of-two shape blink's exit tooling consolidates
 * toward). Fewer leaves = a recovery bundle that is kilobytes instead of megabytes,
 * and a unilateral exit that costs a handful of transaction chains instead of one
 * per dust leaf.
 *
 * Off-chain and instant: each round is an atomic SSP leaf swap (the same mechanism
 * sends already use for denominations), requested with fee_sats 0. Requires
 * operators online — this is maintenance, not the emergency path. Batched so a very
 * fragmented wallet never swaps more than [maxLeavesPerRound] leaves in one request.
 */
public suspend fun SparkWallet.consolidateLeaves(maxLeavesPerRound: Int = 100): SparkLeafConsolidation {
    // Un-freeze what we can first: renewal resets low refund timelocks so those
    // leaves can join the swap instead of being skipped. Best-effort — a failed
    // renewal just leaves that leaf in the skipped bucket.
    bestEffort { renewExhaustedLeaves() }

    var current = getLeaves()
    val leavesBefore = current.size
    val totalBefore = current.sumOf { it.valueSats }

    // Leaves at the timelock floor cannot be swapped until renewed — consolidate
    // around them instead of failing the whole run.
    fun swappable(leaves: List<SparkLeaf>): List<SparkLeaf> = leaves.filter { leaf ->
        val refundTx = leaf.node?.refundTx?.toByteArray() ?: return@filter false
        refundTx.isNotEmpty() && timelockCanDecrement(refundTx)
    }

    var rounds = 0
    var progressing = true
    while (progressing && rounds < 12) {
        val movable = swappable(current)
        val ideal = binaryDecomposition(movable.sumOf { it.valueSats })

        // Merge the smallest leaves first — they are the ones that make exits
        // uneconomical and bundles huge.
        val batch = movable.sortedBy { it.valueSats }.take(maxLeavesPerRound)
        val batchTotal = batch.sumOf { it.valueSats }
        val targets = binaryDecomposition(batchTotal)
        if (movable.size <= ideal.size || batch.size <= targets.size || batchTotal <= 0) break

        processSwapBatch(leaves = batch, targetAmounts = targets)
        rounds++

        val refreshed = getLeaves()
        progressing = refreshed.size < current.size // no progress — stop
        if (progressing) current = refreshed
    }

    return SparkLeafConsolidation(
        leavesBefore = leavesBefore,
        leavesAfter = current.size,
        totalSatsBefore = totalBefore,
        totalSatsAfter = current.sumOf { it.valueSats },
        rounds = rounds,
        skippedLeaves = current.size - swappable(current).size,
    )
}

/**
 * Power-of-two denominations summing exactly to [total] (its set bits), largest
 * first. The minimal leaf set the SSP denomination system can represent the
 * amount with.
 */
internal fun binaryDecomposition(total: Long): List<Long> {
    if (total <= 0) return emptyList()
    val result = mutableListOf<Long>()
    for (bit in 62 downTo 0) {
        if ((total shr bit) and 1L == 1L) result.add(1L shl bit)
    }
    return result
}
