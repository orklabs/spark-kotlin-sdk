package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark
import spark.SparkServiceGrpcKt

/**
 * Compute the wallet's full sats balance.
 *
 * Direct port of the official Swift SDK's [`BalanceService.getBalance()`](
 * https://github.com/buildonspark/spark-swift-sdk). Buckets the wallet's
 * leaves locally from a single `query_nodes` round-trip, then adds pending
 * inbound transfers and `CREATING` deposits to the incoming bucket.
 *
 * - **available** = sum of `AVAILABLE` leaves whose refund timelock is above
 *   the floor. Sending the full amount always succeeds.
 * - **frozen**    = sum of `AVAILABLE` leaves at the timelock floor. The
 *   coordinator will neither move nor renew them.
 * - **owned**     = available + frozen + sum of values whose status is in
 *   `{TRANSFER_LOCKED, SPLIT_LOCKED, AGGREGATE_LOCK, RENEW_LOCKED}`.
 *   These are leaves locked behind in-flight outgoing operations the
 *   wallet itself initiated; the user still owns them.
 * - **incoming**  = `queryPendingTransfers()` totals + sum of `CREATING`
 *   node values (on-chain deposits the coordinator hasn't finalized yet,
 *   matching the TS SDK).
 *
 * Failures in `queryPendingTransfers()` or `getTokenBalances()` propagate
 * to the caller — same contract as the Swift SDK. Callers that want
 * best-effort behavior should wrap this in their own `try/catch`.
 *
 * @return [WalletBalance] with the breakdown, token balances, and every `AVAILABLE` leaf
 *   (frozen ones included; see [SparkLeaf.isSpendable]).
 */
public suspend fun SparkWallet.getBalance(): WalletBalance {
    val stub = getCoordinatorStub()

    // Query all nodes (no status filter) so we can compute owned + available
    // locally — mirrors Swift exactly.
    val nodesRequest = Spark.QueryNodesRequest.newBuilder()
        .setOwnerIdentityPubkey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .build()

    val summary = summarizeNodes(queryAllNodes(nodesRequest, stub))

    // Incoming: pending inbound transfers + deposits still being created
    // (matches TS SDK which tracks CREATING deposit nodes as incoming).
    // Errors propagate — Swift parity.
    val incomingSats = queryPendingTransfers().sumOf { it.totalValue } + summary.creating

    val tokenBalances = getTokenBalances()

    return WalletBalance(
        satsBalance = SatsBalance(
            available = summary.available,
            owned = summary.owned,
            incoming = incomingSats,
            frozen = summary.frozen,
        ),
        tokenBalances = tokenBalances,
        leaves = summary.leaves,
    )
}

internal data class NodeSummary(val available: Long, val owned: Long, val frozen: Long, val creating: Long, val leaves: List<SparkLeaf>,)

/**
 * Pure classification of the coordinator's nodes into the balance figures.
 * Owned = AVAILABLE + locked (transfer, split, aggregate, renew). Available excludes AVAILABLE
 * leaves at the timelock floor, which are reported as frozen instead.
 */
internal fun summarizeNodes(nodes: Map<String, Spark.TreeNode>): NodeSummary {
    var available = 0L
    var owned = 0L
    var frozen = 0L
    var creating = 0L
    val leaves = mutableListOf<SparkLeaf>()
    for ((id, node) in nodes) {
        val value = node.value
        when (val status = node.status) {
            "AVAILABLE" -> {
                owned += value
                if (timelockCanDecrement(node.refundTx.toByteArray())) available += value else frozen += value
                leaves.add(SparkLeaf(id = id, treeID = node.treeId, valueSats = value, status = status, node = node))
            }
            in LOCKED_STATUSES -> owned += value
            "CREATING" -> creating += value
        }
    }
    return NodeSummary(available = available, owned = owned, frozen = frozen, creating = creating, leaves = leaves)
}

/**
 * Every leaf node with status `AVAILABLE` owned by this wallet, in coordinator-defined order —
 * including leaves at the timelock floor, which cannot move ([SparkLeaf.isSpendable] is
 * `false`). Spend paths select from [getSpendableLeaves] instead.
 */
public suspend fun SparkWallet.getLeaves(): List<SparkLeaf> {
    val request = Spark.QueryNodesRequest.newBuilder()
        .setOwnerIdentityPubkey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .build()

    return queryAllNodes(request).mapNotNull { (id, node) ->
        if (node.status.toString() != "AVAILABLE") return@mapNotNull null
        SparkLeaf(
            id = id,
            treeID = node.treeId,
            valueSats = node.value,
            status = node.status.toString(),
            node = node,
        )
    }
}

/** Nodes per `query_nodes` page: the operators' maximum. */
internal const val NODE_PAGE_SIZE: Long = 100

/**
 * Nodes the operators return for [request], a page of [NODE_PAGE_SIZE] at a time (their
 * maximum), as the reference SDK pages them: without a limit the whole set comes back in one
 * response, which outgrows the message-size limit for a wallet with many leaves. Pages are
 * counted here rather than following the response's offset (proto3 cannot tell "0" from unset);
 * with `include_parents` the parents pad the pages, which costs at most one extra request.
 */
internal suspend fun SparkWallet.queryAllNodes(
    request: Spark.QueryNodesRequest,
    stub: SparkServiceGrpcKt.SparkServiceCoroutineStub? = null,
): Map<String, Spark.TreeNode> {
    val client = stub ?: getCoordinatorStub()
    val nodes = LinkedHashMap<String, Spark.TreeNode>()
    var offset = 0L
    while (true) {
        val response = client.queryNodes(request.toBuilder().setLimit(NODE_PAGE_SIZE).setOffset(offset).build())
        val countBefore = nodes.size
        nodes.putAll(response.nodesMap)
        // A short page ends the set; a page that adds nothing means the operator is not paging,
        // and asking again would never end.
        if (response.nodesCount < NODE_PAGE_SIZE || nodes.size <= countBefore) return nodes
        offset += NODE_PAGE_SIZE
    }
}

private val LOCKED_STATUSES = setOf(
    "TRANSFER_LOCKED",
    "SPLIT_LOCKED",
    "AGGREGATE_LOCK",
    "RENEW_LOCKED",
)

internal fun SparkNetwork.toProto(): Spark.Network = when (this) {
    SparkNetwork.MAINNET -> Spark.Network.MAINNET
    SparkNetwork.REGTEST -> Spark.Network.REGTEST
}
