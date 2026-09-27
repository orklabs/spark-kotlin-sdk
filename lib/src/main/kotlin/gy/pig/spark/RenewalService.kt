package gy.pig.spark

import kotlinx.coroutines.CancellationException
import spark.Spark
import uniffi.spark_frost.NodeTxPairResult
import uniffi.spark_frost.RefundTxTrioResult
import uniffi.spark_frost.constructNodeTxPair
import uniffi.spark_frost.constructRefundTxTrio
import uniffi.spark_frost.getPublicKeyBytes
import uniffi.spark_frost.getTaprootPubkey

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
 * Remaining refund-tx timelock in blocks. Below 200 the leaf must be renewed before it can move;
 * below 100 the coordinator will not renew it either (frozen).
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
 * Whether the leaf can be transferred, paid or exited right now without a renewal: its refund
 * timelock, rounded down to the 100-block interval, is above the floor the coordinator enforces —
 * at least 200. Leaves at 100…199 are renewable ([isRenewable]); [getSpendableLeaves] renews them
 * first.
 */
public val SparkLeaf.isSpendable: Boolean
    get() = isTransferableRefundTimelock(refundTimelockBlocks)

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
 * Whether the leaf is frozen: its refund timelock is below 100, the minimum the coordinator
 * renews, and it is too low to move, so only a unilateral on-chain exit can recover it. A leaf at
 * exactly 100 is renewable, not frozen. Leaves only get here through SDKs that decremented
 * timelocks without renewing.
 */
public val SparkLeaf.isFrozen: Boolean
    get() = refundTimelockBlocks < SPARK_TIME_LOCK_INTERVAL.toUInt()

/**
 * Outcome of a renewal sweep. Renewals are per-leaf and best-effort: one failing leaf
 * never aborts the rest.
 */
public data class SparkLeafRenewal(
    val checked: Int,
    val renewed: Int,
    /** "leafId: error" for each leaf that could not be renewed. */
    val failures: List<String>,
)

/**
 * Renew every leaf whose refund timelock has run low (< 200 blocks).
 *
 * Three protocol variants, chosen per leaf like the TS SDK does ([renewalVariant]):
 * - node timelock == 0, or a final node sequence → renew_node_zero_timelock (L1-deposit roots)
 * - node timelock < 200 → renew_node_timelock (splices in a zero-timelock
 *   "split node", resets node+refund to 2000)
 * - otherwise → renew_refund_timelock (decrements node by 100, resets refund to 2000)
 */
public suspend fun SparkWallet.renewExhaustedLeaves(): SparkLeafRenewal = renewLeaves(getLeaves())

/**
 * Renew the renewable leaves among [leaves] (refund timelock in [100, 200)) and report the ones
 * below the renewal minimum as failures. Each renewal is independent: one failing leaf never
 * stops the others.
 */
internal suspend fun SparkWallet.renewLeaves(leaves: List<SparkLeaf>): SparkLeafRenewal {
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

/** The renewal the coordinator accepts for a leaf. */
internal enum class RenewalVariant {
    ZERO_TIMELOCK,
    NODE_TIMELOCK,
    REFUND_TIMELOCK,
}

/**
 * The renewal the coordinator accepts for a leaf, from its node transaction's sequence:
 * zero-timelock renewal for a node timelock of 0 or a final (timelock-disabled, bit 31) sequence —
 * a legacy deposit root's, which cannot be decremented (`validateRenewZeroTimelock`) — node
 * renewal below 200, refund renewal otherwise.
 */
internal fun renewalVariant(nodeSequence: UInt): RenewalVariant {
    val timelockDisabled = nodeSequence and (1u shl 31) != 0u
    val nodeTimelock = nodeSequence and 0xFFFFu
    return when {
        nodeTimelock == 0u || timelockDisabled -> RenewalVariant.ZERO_TIMELOCK
        nodeTimelock < RENEWAL_THRESHOLD -> RenewalVariant.NODE_TIMELOCK
        else -> RenewalVariant.REFUND_TIMELOCK
    }
}

private suspend fun SparkWallet.renewLeaf(node: Spark.TreeNode, parents: Map<String, Spark.TreeNode>) {
    val variant = renewalVariant(parseSequenceFromRawTx(node.nodeTx.toByteArray()))
    if (variant == RenewalVariant.ZERO_TIMELOCK) {
        renewZeroTimelockNode(node)
        return
    }
    val parent = (if (node.hasParentNodeId()) parents[node.parentNodeId] else null)
        ?: throw SparkError.InvalidResponse("Parent node ${node.parentNodeId} not found for leaf ${node.id}")
    if (variant == RenewalVariant.NODE_TIMELOCK) {
        renewNodeTimelock(node, parent)
    } else {
        renewRefundTimelock(node, parent)
    }
}

// ── Variants ────────────────────────────────────────────────────────────────

/** Refund-only renewal: new node tx with timelock −100, fresh refunds at 2000. */
private suspend fun SparkWallet.renewRefundTimelock(node: Spark.TreeNode, parent: Spark.TreeNode) {
    val context = RenewalContext(node, signer)
    val txs = refundRenewalTransactions(node, parent, context.signingPublicKey, config)

    // Order defines which SO commitment each job consumes.
    val specs = mutableListOf(
        SigningSpec("node", txs.node.cpfp.tx, txs.node.cpfp.sighash),
        SigningSpec("directNode", txs.node.direct.tx, txs.node.direct.sighash),
        SigningSpec("cpfp", txs.refunds.cpfpRefund.tx, txs.refunds.cpfpRefund.sighash),
    )
    txs.refunds.directRefund?.let { specs.add(SigningSpec("direct", it.tx, it.sighash)) }
    specs.add(SigningSpec("directFromCpfp", txs.refunds.directFromCpfpRefund.tx, txs.refunds.directFromCpfpRefund.sighash))

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
    submitRenewal(request, node)
}

/**
 * Full node renewal: zero-timelock "split node" spliced above a fresh node tx at
 * 2000, refunds reset to 2000.
 */
private suspend fun SparkWallet.renewNodeTimelock(node: Spark.TreeNode, parent: Spark.TreeNode) {
    val context = RenewalContext(node, signer)
    val txs = nodeRenewalTransactions(node, parent, context.signingPublicKey, config)
    val split = txs.split ?: throw SparkError.InvalidResponse("Node renewal for leaf ${node.id} built no split node")

    val specs = mutableListOf(
        SigningSpec("split", split.cpfp.tx, split.cpfp.sighash),
        SigningSpec("directSplit", split.direct.tx, split.direct.sighash),
        SigningSpec("node", txs.node.cpfp.tx, txs.node.cpfp.sighash),
        SigningSpec("directNode", txs.node.direct.tx, txs.node.direct.sighash),
        SigningSpec("cpfp", txs.refunds.cpfpRefund.tx, txs.refunds.cpfpRefund.sighash),
    )
    txs.refunds.directRefund?.let { specs.add(SigningSpec("direct", it.tx, it.sighash)) }
    specs.add(SigningSpec("directFromCpfp", txs.refunds.directFromCpfpRefund.tx, txs.refunds.directFromCpfpRefund.sighash))

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
    submitRenewal(request, node)
}

/**
 * Zero-node renewal: the node tx is at timelock 0 (L1-deposit roots) — appends
 * another zero-timelock node and resets the refunds.
 */
private suspend fun SparkWallet.renewZeroTimelockNode(node: Spark.TreeNode) {
    val context = RenewalContext(node, signer)
    val txs = zeroTimelockRenewalTransactions(node, context.signingPublicKey, config)

    val specs = listOf(
        SigningSpec("node", txs.node.cpfp.tx, txs.node.cpfp.sighash),
        SigningSpec("directNode", txs.node.direct.tx, txs.node.direct.sighash),
        SigningSpec("cpfp", txs.refunds.cpfpRefund.tx, txs.refunds.cpfpRefund.sighash),
        SigningSpec("directFromCpfp", txs.refunds.directFromCpfpRefund.tx, txs.refunds.directFromCpfpRefund.sighash),
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
    submitRenewal(request, node)
}

// ── Renewal transactions (what the operators rebuild, renew_leaf_handler.go) ──

/** The transactions of one renewal: the split node (node renewal only), the new node pair and its refunds. */
internal class RenewalTransactions(val split: NodeTxPairResult?, val node: NodeTxPairResult, val refunds: RefundTxTrioResult)

/**
 * The P2TR address a leaf's node transaction pays: the leaf's verifying key with the BIP-86
 * key-path tweak (`P2TRScriptFromPubKey(leaf.VerifyingPubkey)` on the operators).
 */
internal fun leafNodeAddress(verifyingKey: ByteArray, network: SparkNetwork): String {
    val tweaked = getTaprootPubkey(verifyingKey)
    if (tweaked.size != 33) {
        throw SparkError.InvalidResponse("Unexpected taproot key length ${tweaked.size}")
    }
    return BitcoinAddress.p2trAddress(scriptPubKey = byteArrayOf(0x51, 0x20) + tweaked.copyOfRange(1, 33), network = network)
}

/**
 * Refund renewal: a new node transaction spending the parent's output `node.vout` at the node
 * timelock minus 100, paying the leaf's node address, and fresh refunds at 2000.
 */
internal fun refundRenewalTransactions(node: Spark.TreeNode, parent: Spark.TreeNode, signingPublicKey: ByteArray, config: SparkConfig,): RenewalTransactions {
    val nodeSequence = parseSequenceFromRawTx(node.nodeTx.toByteArray())
    val bit30 = nodeSequence and (1u shl 30)
    val nodeTimelock = nodeSequence and 0xFFFFu
    if (nodeTimelock < SPARK_TIME_LOCK_INTERVAL.toUInt()) {
        throw SparkError.LeafTimelockExhausted("Node timelock $nodeTimelock too low for refund renewal")
    }
    val newNodeSequence = bit30 or (nodeTimelock - SPARK_TIME_LOCK_INTERVAL.toUInt())
    val nodePair = constructNodeTxPair(
        parentTx = parent.nodeTx.toByteArray(),
        vout = node.vout.toUInt(),
        address = leafNodeAddress(node.verifyingPublicKey.toByteArray(), config.network),
        sequence = newNodeSequence,
        directSequence = newNodeSequence + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    return RenewalTransactions(split = null, node = nodePair, refunds = initialRefunds(nodePair, signingPublicKey, config))
}

/**
 * Node renewal: a zero-timelock split node spending the parent's output `node.vout`, a new node
 * transaction at 2000 spending it, both paying the leaf's node address, and fresh refunds at 2000.
 */
internal fun nodeRenewalTransactions(node: Spark.TreeNode, parent: Spark.TreeNode, signingPublicKey: ByteArray, config: SparkConfig,): RenewalTransactions {
    val address = leafNodeAddress(node.verifyingPublicKey.toByteArray(), config.network)
    val splitPair = constructNodeTxPair(
        parentTx = parent.nodeTx.toByteArray(),
        vout = node.vout.toUInt(),
        address = address,
        sequence = 0u,
        directSequence = SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    val nodePair = constructNodeTxPair(
        parentTx = splitPair.cpfp.tx,
        vout = 0u,
        address = address,
        sequence = RENEWAL_INITIAL_SEQUENCE,
        directSequence = RENEWAL_INITIAL_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    return RenewalTransactions(split = splitPair, node = nodePair, refunds = initialRefunds(nodePair, signingPublicKey, config))
}

/**
 * Zero-timelock renewal: another zero-timelock node spending the leaf's own node transaction
 * (output 0), and fresh refunds at 2000 without a direct refund.
 */
internal fun zeroTimelockRenewalTransactions(node: Spark.TreeNode, signingPublicKey: ByteArray, config: SparkConfig): RenewalTransactions {
    val nodePair = constructNodeTxPair(
        parentTx = node.nodeTx.toByteArray(),
        vout = 0u,
        address = leafNodeAddress(node.verifyingPublicKey.toByteArray(), config.network),
        sequence = 0u,
        directSequence = SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    // Zero-timelock node → no direct node context for the refunds.
    val refunds = constructRefundTxTrio(
        cpfpNodeTx = nodePair.cpfp.tx,
        directNodeTx = null,
        vout = 0u,
        receivingPubkey = signingPublicKey,
        network = config.network.networkString,
        sequence = RENEWAL_INITIAL_SEQUENCE,
        directSequence = RENEWAL_INITIAL_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    return RenewalTransactions(split = null, node = nodePair, refunds = refunds)
}

/** Refunds at the initial timelock (2000) spending a renewed node pair. */
private fun initialRefunds(nodePair: NodeTxPairResult, signingPublicKey: ByteArray, config: SparkConfig): RefundTxTrioResult = constructRefundTxTrio(
    cpfpNodeTx = nodePair.cpfp.tx,
    directNodeTx = nodePair.direct.tx,
    vout = 0u,
    receivingPubkey = signingPublicKey,
    network = config.network.networkString,
    sequence = RENEWAL_INITIAL_SEQUENCE,
    directSequence = RENEWAL_INITIAL_SEQUENCE + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt(),
    feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
)

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

/**
 * Submits a renewal under the idempotency key [renewalIdempotencyKey], so the transport's retry
 * of a renewal the operators already applied gets their answer instead of failing.
 */
private suspend fun SparkWallet.submitRenewal(request: Spark.RenewLeafRequest, renewing: Spark.TreeNode) {
    val stub = getCoordinatorStubWithIdempotency(renewalIdempotencyKey(renewing))
    val response = stub.renewLeaf(request)
    if (response.renewResultCase == Spark.RenewLeafResponse.RenewResultCase.RENEWRESULT_NOT_SET) {
        throw SparkError.InvalidResponse("renew_leaf returned no result for leaf ${renewing.id}")
    }
}

/**
 * A leaf renewal's idempotency key: the txid of the refund transaction being replaced, as the
 * reference SDK keys all three renewal variants. It changes with every renewal, so it names
 * exactly one.
 */
internal fun renewalIdempotencyKey(node: Spark.TreeNode): String = RawTransaction.parse(node.refundTx.toByteArray(), context = "refund tx").txidHex
