package gy.pig.spark

import kotlinx.coroutines.CancellationException
import spark.Spark
import uniffi.spark_frost.constructNodeTxPair
import uniffi.spark_frost.constructRefundTxTrio
import uniffi.spark_frost.getPublicKeyBytes

/**
 * Leaf timelock renewal — direct port of the Swift SDK's `RenewalService.swift`.
 *
 * Spark leaves age: each transfer decrements the refund timelock by 100 and at the
 * floor the coordinator refuses to move them — sends, swaps, and withdrawals of those
 * sats all fail until renewal. The TS SDK renews automatically during operations;
 * this is the Kotlin equivalent, ported from its leaf-manager/transfer flows.
 */

/** Fresh refund txs are minted with this timelock (matches JS INITIAL_TIMELOCK). */
private const val RENEWAL_INITIAL_SEQUENCE: UInt = 2000u

/**
 * Renew when the refund timelock drops below this — prevents it going under 100 after
 * the next transfer, which would freeze the leaf and interfere with watchtowers
 * (matches JS doesTxnNeedRenewed).
 */
internal const val RENEWAL_THRESHOLD: UInt = 200u

/**
 * Remaining refund-tx timelock in blocks. Below 200 the leaf should be renewed; at or
 * below 100 it cannot move; below 100 the coordinator will not renew it either (frozen).
 */
public val SparkLeaf.refundTimelockBlocks: UInt
    get() {
        // Missing or unparseable refund tx → 0 ("exhausted"): never spent, renewal attempted and
        // its failure reported per leaf instead of crashing the caller.
        val refundTx = node?.refundTx?.toByteArray() ?: return 0u
        return try {
            parseSequenceFromRawTx(refundTx) and 0xFFFFu
        } catch (_: SparkError) {
            0u
        }
    }

/**
 * Whether the leaf can be transferred, paid or exited right now: its refund timelock is
 * above the floor the coordinator enforces. Leaves in the renewable range just above the
 * floor are still spendable; [getSpendableLeaves] renews them first.
 */
public val SparkLeaf.isSpendable: Boolean
    get() = refundTimelockBlocks > SPARK_TIME_LOCK_INTERVAL.toUInt()

/**
 * Whether the coordinator will renew this leaf's timelocks (refund timelock in [100, 200)).
 * A leaf below that range is frozen: only a unilateral exit can recover it.
 */
public val SparkLeaf.isRenewable: Boolean
    get() {
        val timelock = refundTimelockBlocks
        return timelock >= SPARK_TIME_LOCK_INTERVAL.toUInt() && timelock < RENEWAL_THRESHOLD
    }

/**
 * Outcome of a renewal sweep. Renewals are per-leaf and best-effort: one failing leaf
 * never aborts the rest.
 */
data class SparkLeafRenewal(
    val checked: Int,
    val renewed: Int,
    /** "leafId: error" for each leaf that could not be renewed. */
    val failures: List<String>,
)

/**
 * Renew every leaf whose refund timelock has run low (< 200 blocks).
 *
 * Three protocol variants, chosen per leaf like the TS SDK does:
 * - node timelock == 0 → renew_node_zero_timelock (L1-deposit roots)
 * - node timelock < 200 → renew_node_timelock (splices in a zero-timelock
 *   "split node", resets node+refund to 2000)
 * - otherwise → renew_refund_timelock (decrements node by 100, resets refund to 2000)
 */
public suspend fun SparkWallet.renewExhaustedLeaves(): SparkLeafRenewal {
    val leaves = getLeaves()
    val (needing, stuck) = renewalCandidates(leaves)
    // The coordinator refuses to renew a leaf whose refund timelock is already below one
    // interval (100 blocks); report those without a round trip.
    val failures = stuck.mapTo(mutableListOf()) {
        "${it.id}: refund timelock ${it.refundTimelockBlocks} is below the coordinator's renewal minimum of " +
            "$SPARK_TIME_LOCK_INTERVAL; only a unilateral exit can recover it"
    }
    if (needing.isEmpty()) {
        return SparkLeafRenewal(checked = leaves.size, renewed = 0, failures = failures)
    }

    // Parents provide the prev-out context for the new node txs.
    val parentIds = needing.mapNotNull { leaf ->
        val node = leaf.node ?: return@mapNotNull null
        if (node.hasParentNodeId() && node.parentNodeId.isNotEmpty()) node.parentNodeId else null
    }.toSortedSet()
    val parents = mutableMapOf<String, Spark.TreeNode>()
    if (parentIds.isNotEmpty()) {
        val stub = getCoordinatorStub()
        val request = Spark.QueryNodesRequest.newBuilder()
            .setNodeIds(Spark.TreeNodeIds.newBuilder().addAllNodeIds(parentIds).build())
            .build()
        val response = stub.queryNodes(request)
        parents.putAll(response.nodesMap)
    }

    var renewed = 0
    for (leaf in needing) {
        val node = leaf.node
        if (node == null) {
            failures.add("${leaf.id}: missing node data")
            continue
        }
        try {
            renewLeaf(node, parents)
            renewed++
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failures.add("${leaf.id}: $t")
        }
    }
    return SparkLeafRenewal(checked = leaves.size, renewed = renewed, failures = failures)
}

/** Result of [renewalCandidates]. */
internal data class RenewalCandidates(val renewable: List<SparkLeaf>, val stuck: List<SparkLeaf>)

/**
 * Split `AVAILABLE` leaves into those the coordinator will renew (refund timelock in
 * [100, 200)) and those it will not (below 100), which only a unilateral exit can recover.
 */
internal fun renewalCandidates(leaves: List<SparkLeaf>): RenewalCandidates {
    val renewable = mutableListOf<SparkLeaf>()
    val stuck = mutableListOf<SparkLeaf>()
    for (leaf in leaves) {
        val timelock = leaf.refundTimelockBlocks
        if (timelock >= RENEWAL_THRESHOLD) continue
        if (timelock >= SPARK_TIME_LOCK_INTERVAL.toUInt()) renewable.add(leaf) else stuck.add(leaf)
    }
    return RenewalCandidates(renewable, stuck)
}

private suspend fun SparkWallet.renewLeaf(node: Spark.TreeNode, parents: Map<String, Spark.TreeNode>) {
    val nodeTimelock = parseSequenceFromRawTx(node.nodeTx.toByteArray()) and 0xFFFFu
    if (nodeTimelock == 0u) {
        renewZeroTimelockNode(node)
        return
    }
    val parent = (if (node.hasParentNodeId()) parents[node.parentNodeId] else null)
        ?: throw SparkError.InvalidResponse("Parent node ${node.parentNodeId} not found for leaf ${node.id}")
    if (nodeTimelock < RENEWAL_THRESHOLD) {
        renewNodeTimelock(node, parent)
    } else {
        renewRefundTimelock(node, parent)
    }
}

// ── Variants ────────────────────────────────────────────────────────────────

/** Refund-only renewal: new node tx with timelock −100, fresh refunds at 2000. */
private suspend fun SparkWallet.renewRefundTimelock(node: Spark.TreeNode, parent: Spark.TreeNode) {
    val context = RenewalContext(node, signer)
    val parentTx = parent.nodeTx.toByteArray()
    val address = BitcoinAddress.p2trAddress(
        scriptPubKey = parseTxOutput(parentTx, 0u).scriptPubKey,
        network = config.network,
    )

    val nodeSequence = parseSequenceFromRawTx(node.nodeTx.toByteArray())
    val bit30 = nodeSequence and (1u shl 30)
    val nodeTimelock = nodeSequence and 0xFFFFu
    if (nodeTimelock < SPARK_TIME_LOCK_INTERVAL.toUInt()) {
        throw SparkError.LeafTimelockExhausted("Node timelock $nodeTimelock too low for refund renewal")
    }
    val newNodeSequence = bit30 or (nodeTimelock - SPARK_TIME_LOCK_INTERVAL.toUInt())

    val nodePair = constructNodeTxPair(
        parentTx = parentTx,
        vout = 0u,
        address = address,
        sequence = newNodeSequence,
        directSequence = newNodeSequence + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    val trio = constructRefundTxTrio(
        cpfpNodeTx = nodePair.cpfp.tx,
        directNodeTx = nodePair.direct.tx,
        vout = 0u,
        receivingPubkey = context.signingPublicKey,
        network = config.network.networkString,
        sequence = RENEWAL_INITIAL_SEQUENCE,
        directSequence = RENEWAL_INITIAL_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

    // Order defines which SO commitment each job consumes.
    val specs = mutableListOf(
        SigningSpec("node", nodePair.cpfp.tx, nodePair.cpfp.sighash),
        SigningSpec("directNode", nodePair.direct.tx, nodePair.direct.sighash),
        SigningSpec("cpfp", trio.cpfpRefund.tx, trio.cpfpRefund.sighash),
    )
    trio.directRefund?.let { specs.add(SigningSpec("direct", it.tx, it.sighash)) }
    specs.add(SigningSpec("directFromCpfp", trio.directFromCpfpRefund.tx, trio.directFromCpfpRefund.sighash))

    val jobs = signRenewalJobs(specs, context)

    val renewJob = Spark.RenewRefundTimelockSigningJob.newBuilder()
        .setNodeTxSigningJob(jobs.getValue("node"))
        .setRefundTxSigningJob(jobs.getValue("cpfp"))
        .setDirectNodeTxSigningJob(jobs.getValue("directNode"))
        .setDirectFromCpfpRefundTxSigningJob(jobs.getValue("directFromCpfp"))
    jobs["direct"]?.let { renewJob.setDirectRefundTxSigningJob(it) }

    val request = Spark.RenewLeafRequest.newBuilder()
        .setLeafId(node.id)
        .setRenewRefundTimelockSigningJob(renewJob.build())
        .build()
    submitRenewal(request, node.id)
}

/**
 * Full node renewal: zero-timelock "split node" spliced above a fresh node tx at
 * 2000, refunds reset to 2000.
 */
private suspend fun SparkWallet.renewNodeTimelock(node: Spark.TreeNode, parent: Spark.TreeNode) {
    val context = RenewalContext(node, signer)
    val parentTx = parent.nodeTx.toByteArray()
    val address = BitcoinAddress.p2trAddress(
        scriptPubKey = parseTxOutput(parentTx, 0u).scriptPubKey,
        network = config.network,
    )

    // Split node: spends the parent output with zero timelock.
    val splitPair = constructNodeTxPair(
        parentTx = parentTx,
        vout = 0u,
        address = address,
        sequence = 0u,
        directSequence = SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    // New node: spends the split node output at the initial timelock.
    val splitAddress = BitcoinAddress.p2trAddress(
        scriptPubKey = parseTxOutput(splitPair.cpfp.tx, 0u).scriptPubKey,
        network = config.network,
    )
    val nodePair = constructNodeTxPair(
        parentTx = splitPair.cpfp.tx,
        vout = 0u,
        address = splitAddress,
        sequence = RENEWAL_INITIAL_SEQUENCE,
        directSequence = RENEWAL_INITIAL_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    val trio = constructRefundTxTrio(
        cpfpNodeTx = nodePair.cpfp.tx,
        directNodeTx = nodePair.direct.tx,
        vout = 0u,
        receivingPubkey = context.signingPublicKey,
        network = config.network.networkString,
        sequence = RENEWAL_INITIAL_SEQUENCE,
        directSequence = RENEWAL_INITIAL_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

    val specs = mutableListOf(
        SigningSpec("split", splitPair.cpfp.tx, splitPair.cpfp.sighash),
        SigningSpec("directSplit", splitPair.direct.tx, splitPair.direct.sighash),
        SigningSpec("node", nodePair.cpfp.tx, nodePair.cpfp.sighash),
        SigningSpec("directNode", nodePair.direct.tx, nodePair.direct.sighash),
        SigningSpec("cpfp", trio.cpfpRefund.tx, trio.cpfpRefund.sighash),
    )
    trio.directRefund?.let { specs.add(SigningSpec("direct", it.tx, it.sighash)) }
    specs.add(SigningSpec("directFromCpfp", trio.directFromCpfpRefund.tx, trio.directFromCpfpRefund.sighash))

    val jobs = signRenewalJobs(specs, context)

    val renewJob = Spark.RenewNodeTimelockSigningJob.newBuilder()
        .setSplitNodeTxSigningJob(jobs.getValue("split"))
        .setSplitNodeDirectTxSigningJob(jobs.getValue("directSplit"))
        .setNodeTxSigningJob(jobs.getValue("node"))
        .setRefundTxSigningJob(jobs.getValue("cpfp"))
        .setDirectNodeTxSigningJob(jobs.getValue("directNode"))
        .setDirectFromCpfpRefundTxSigningJob(jobs.getValue("directFromCpfp"))
    jobs["direct"]?.let { renewJob.setDirectRefundTxSigningJob(it) }

    val request = Spark.RenewLeafRequest.newBuilder()
        .setLeafId(node.id)
        .setRenewNodeTimelockSigningJob(renewJob.build())
        .build()
    submitRenewal(request, node.id)
}

/**
 * Zero-node renewal: the node tx is at timelock 0 (L1-deposit roots) — appends
 * another zero-timelock node and resets the refunds.
 */
private suspend fun SparkWallet.renewZeroTimelockNode(node: Spark.TreeNode) {
    val context = RenewalContext(node, signer)
    val nodeTx = node.nodeTx.toByteArray()
    val address = BitcoinAddress.p2trAddress(
        scriptPubKey = parseTxOutput(nodeTx, 0u).scriptPubKey,
        network = config.network,
    )

    val nodePair = constructNodeTxPair(
        parentTx = nodeTx,
        vout = 0u,
        address = address,
        sequence = 0u,
        directSequence = SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    // Zero-timelock node → no direct node context for the refunds.
    val trio = constructRefundTxTrio(
        cpfpNodeTx = nodePair.cpfp.tx,
        directNodeTx = null,
        vout = 0u,
        receivingPubkey = context.signingPublicKey,
        network = config.network.networkString,
        sequence = RENEWAL_INITIAL_SEQUENCE,
        directSequence = RENEWAL_INITIAL_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

    val specs = listOf(
        SigningSpec("node", nodePair.cpfp.tx, nodePair.cpfp.sighash),
        SigningSpec("directNode", nodePair.direct.tx, nodePair.direct.sighash),
        SigningSpec("cpfp", trio.cpfpRefund.tx, trio.cpfpRefund.sighash),
        SigningSpec("directFromCpfp", trio.directFromCpfpRefund.tx, trio.directFromCpfpRefund.sighash),
    )

    val jobs = signRenewalJobs(specs, context)

    val renewJob = Spark.RenewNodeZeroTimelockSigningJob.newBuilder()
        .setNodeTxSigningJob(jobs.getValue("node"))
        .setRefundTxSigningJob(jobs.getValue("cpfp"))
        .setDirectNodeTxSigningJob(jobs.getValue("directNode"))
        .setDirectFromCpfpRefundTxSigningJob(jobs.getValue("directFromCpfp"))
        .build()

    val request = Spark.RenewLeafRequest.newBuilder()
        .setLeafId(node.id)
        .setRenewNodeZeroTimelockSigningJob(renewJob)
        .build()
    submitRenewal(request, node.id)
}

// ── Shared plumbing ─────────────────────────────────────────────────────────

private class SigningSpec(val slot: String, val tx: ByteArray, val sighash: ByteArray)

private class RenewalContext(node: Spark.TreeNode, signer: SparkSignerProtocol) {
    val leafId: String = node.id
    val signingKey: ByteArray = signer.deriveLeafSigningKey(node.id)
    val signingPublicKey: ByteArray = getPublicKeyBytes(signingKey, true)
    val verifyingKey: ByteArray = node.verifyingPublicKey.toByteArray()
}

/** Fetch one SO commitment per job (indexed by position) and FROST-sign. */
private suspend fun SparkWallet.signRenewalJobs(specs: List<SigningSpec>, context: RenewalContext,): Map<String, Spark.UserSignedTxSigningJob> {
    val stub = getCoordinatorStub()
    val commitmentsRequest = Spark.GetSigningCommitmentsRequest.newBuilder()
        .addNodeIds(context.leafId)
        .setCount(specs.size)
        .build()
    val commitmentsResponse = stub.getSigningCommitments(commitmentsRequest)
    val allCommitments = commitmentsResponse.signingCommitmentsList
    if (allCommitments.size < specs.size) {
        throw SparkError.InvalidResponse(
            "Got ${allCommitments.size} signing commitments, need ${specs.size}"
        )
    }

    val jobs = mutableMapOf<String, Spark.UserSignedTxSigningJob>()
    for ((index, spec) in specs.withIndex()) {
        jobs[spec.slot] = FrostSigningHelper.buildSigningJob(
            leafID = context.leafId,
            signingKey = context.signingKey,
            verifyingKey = context.verifyingKey,
            rawTx = spec.tx,
            sighash = spec.sighash,
            soCommitments = allCommitments[index].signingNonceCommitmentsMap,
        )
    }
    return jobs
}

private suspend fun SparkWallet.submitRenewal(request: Spark.RenewLeafRequest, leafId: String) {
    val stub = getCoordinatorStub()
    val response = stub.renewLeaf(request)
    if (response.renewResultCase == Spark.RenewLeafResponse.RenewResultCase.RENEWRESULT_NOT_SET) {
        throw SparkError.InvalidResponse("renew_leaf returned no result for leaf $leafId")
    }
}
