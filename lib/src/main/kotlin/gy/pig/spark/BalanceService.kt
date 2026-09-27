package gy.pig.spark

import com.google.protobuf.ByteString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import spark.Spark
import spark.SparkServiceGrpcKt

/**
 * The wallet's balance, modelled on the reference SDK's leaf manager — direct port of the Swift
 * SDK's `BalanceService.getBalance()`:
 *
 * - **available** / **frozen**: `AVAILABLE` leaves, split by [SparkLeaf.isFrozen]. Leaves at
 *   100…199 count as available: every spend path renews them first.
 * - **owned**: those plus the leaves an in-flight operation still holds for the wallet — an
 *   outgoing transfer, Lightning payment or cooperative exit before the operators apply the
 *   sender's key tweak, a swap the wallet started, and its counter-transfer until claimed. Once
 *   the sender's key tweak is applied the sats belong to the receiver.
 * - **incoming**: the leaves of pending inbound transfers, except counter-transfers of the
 *   wallet's own swaps (already counted as locked) and leaves counted above.
 * - **tokenBalances**: best effort — empty when the wallet's tokens cannot be read, since anyone
 *   can send a wallet tokens; [getTokenBalances] throws the error instead.
 *
 * @return [WalletBalance] with the breakdown, token balances, and every `AVAILABLE` leaf
 *   (frozen ones included; see [SparkLeaf.isSpendable]).
 */
public suspend fun SparkWallet.getBalance(): WalletBalance {
    val satsBalance = coroutineScope {
        val nodes = async { queryAvailableNodes() }
        val inFlight = async { queryInFlightTransfers() }
        val pending = async { queryAllPendingTransfers() }
        val summary = summarizeNodes(nodes.await())
        val availableIds = summary.leaves.map { it.id }.toSet()
        val inFlightTransfers = inFlight.await()
        val lockedSats = leafSats(inFlightTransfers, excludedLeafIds = availableIds)
        val incoming = incomingSats(
            pending.await(),
            excludedLeafIds = availableIds + inFlightTransfers.flatMap { transfer -> transfer.leavesList.map { it.leaf.id } },
            receiver = signer.identityPublicKey,
        )
        summary to SatsBalance(
            available = summary.available,
            owned = summary.available + summary.frozen + lockedSats,
            incoming = incoming,
            frozen = summary.frozen,
        )
    }

    // Best effort: tokens anyone can send must not cost the wallet its sats balance.
    // getTokenBalances() reports what went wrong. A cancelled call still throws rather than
    // report no tokens.
    val tokenBalances = bestEffort { getTokenBalances() } ?: emptyList()
    currentCoroutineContext().ensureActive()

    return WalletBalance(satsBalance = satsBalance.second, tokenBalances = tokenBalances, leaves = satsBalance.first.leaves)
}

internal data class NodeSummary(val available: Long, val frozen: Long, val leaves: List<SparkLeaf>)

/**
 * Pure classification of the wallet's `AVAILABLE` leaves into the balance figures. A leaf is
 * frozen when its refund timelock is below 100 ([SparkLeaf.isFrozen]) and available otherwise:
 * leaves at 100…199 count as available because every spend path renews them first, as the
 * reference SDK does before counting them. Nodes in any other status are ignored — in-flight sats
 * come from the transfers holding them ([leafSats]).
 */
internal fun summarizeNodes(nodes: Map<String, Spark.TreeNode>): NodeSummary {
    var available = 0L
    var frozen = 0L
    val leaves = mutableListOf<SparkLeaf>()
    for ((id, node) in nodes) {
        if (node.status != "AVAILABLE") continue
        val value = reportedSats(node.value)
        val leaf = SparkLeaf(id = id, treeID = node.treeId, valueSats = value, status = node.status, node = node)
        if (leaf.isFrozen) frozen += value else available += value
        leaves.add(leaf)
    }
    return NodeSummary(available = available, frozen = frozen, leaves = leaves)
}

/** Transfer statuses before the sender key tweak is applied: the sender still owns the leaves. */
internal val SENDER_PENDING_STATUSES: List<Spark.TransferStatus> = listOf(
    Spark.TransferStatus.TRANSFER_STATUS_SENDER_INITIATED,
    Spark.TransferStatus.TRANSFER_STATUS_SENDER_INITIATED_COORDINATOR,
    Spark.TransferStatus.TRANSFER_STATUS_APPLYING_SENDER_KEY_TWEAK,
    Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAK_PENDING,
)

/** A counter-transfer's statuses until it completes. */
internal val ACTIVE_COUNTER_SWAP_STATUSES: List<Spark.TransferStatus> = SENDER_PENDING_STATUSES + listOf(
    Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED,
    Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_LOCKED,
    Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_APPLIED,
    Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAKED,
    Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_REFUND_SIGNED,
)

internal val OUTGOING_TRANSFER_TYPES: List<Spark.TransferType> = listOf(
    Spark.TransferType.COOPERATIVE_EXIT,
    Spark.TransferType.UTXO_SWAP,
    Spark.TransferType.PREIMAGE_SWAP,
    Spark.TransferType.TRANSFER,
)
internal val PRIMARY_SWAP_TYPES: List<Spark.TransferType> = listOf(Spark.TransferType.PRIMARY_SWAP_V3, Spark.TransferType.SWAP)
internal val COUNTER_SWAP_TYPES: List<Spark.TransferType> = listOf(Spark.TransferType.COUNTER_SWAP_V3, Spark.TransferType.COUNTER_SWAP)

/**
 * The transfers that hold leaves the wallet still owns, as the reference SDK queries them:
 * outgoing transfers and swaps it sent that are still before the sender key tweak, and
 * counter-transfers of its swaps until they complete.
 */
internal suspend fun SparkWallet.queryInFlightTransfers(): List<Spark.Transfer> = coroutineScope {
    val outgoing = async { queryAllTransferPages(senderOnly = true, types = OUTGOING_TRANSFER_TYPES, statuses = SENDER_PENDING_STATUSES) }
    val primarySwaps = async { queryAllTransferPages(senderOnly = true, types = PRIMARY_SWAP_TYPES, statuses = SENDER_PENDING_STATUSES) }
    val counterSwaps = async { queryAllTransferPages(senderOnly = false, types = COUNTER_SWAP_TYPES, statuses = ACTIVE_COUNTER_SWAP_STATUSES) }
    outgoing.await() + primarySwaps.await() + counterSwaps.await()
}

/**
 * Sats in the leaves of [transfers], each leaf counted once and none in [excludedLeafIds]
 * (leaves already counted, e.g. the wallet's `AVAILABLE` leaves).
 */
internal fun leafSats(transfers: List<Spark.Transfer>, excludedLeafIds: Set<String>): Long {
    val values = mutableMapOf<String, Long>()
    for (transfer in transfers) {
        for (transferLeaf in transfer.leavesList) {
            if (!transferLeaf.hasLeaf() || transferLeaf.leaf.id in excludedLeafIds) continue
            values[transferLeaf.leaf.id] = reportedSats(transferLeaf.leaf.value)
        }
    }
    return values.values.sum()
}

/**
 * Sats pending inbound: [receiver]'s own leaves of [pending] (a multi-receiver transfer also
 * carries the other receivers'), except counter-transfers of the wallet's own swaps — the
 * reference SDK leaves those out of incoming because the swap already counts them — and leaves in
 * [excludedLeafIds] (a self-transfer shows up as outgoing too).
 */
internal fun incomingSats(pending: List<Spark.Transfer>, excludedLeafIds: Set<String>, receiver: ByteArray): Long {
    val own = pending
        .filter { it.type !in COUNTER_SWAP_TYPES }
        .mapNotNull { transfer ->
            try {
                TransferLeafVerifier.scoped(transfer, receiver)
            } catch (_: SparkError) {
                null
            }
        }
    return leafSats(own, excludedLeafIds)
}

/** Every page of the wallet's pending inbound transfers. */
internal suspend fun SparkWallet.queryAllPendingTransfers(): List<Spark.Transfer> {
    val pageSize = 100
    val transfers = mutableListOf<Spark.Transfer>()
    var offset = 0
    while (true) {
        val page = queryPendingTransfers(limit = pageSize, offset = offset)
        transfers += page
        if (page.size < pageSize) return transfers
        offset += page.size
    }
}

/**
 * Every page of the wallet's transfers of [types] in [statuses] (100 per page, the server's
 * maximum), as the sender or as either party.
 */
internal suspend fun SparkWallet.queryAllTransferPages(
    senderOnly: Boolean,
    types: List<Spark.TransferType>,
    statuses: List<Spark.TransferStatus>,
): List<Spark.Transfer> {
    val stub = getCoordinatorStub()
    val pageSize = 100L
    val transfers = mutableListOf<Spark.Transfer>()
    var offset = 0L
    var previousOffset = -1L
    do {
        val filter = Spark.TransferFilter.newBuilder()
        val identity = ByteString.copyFrom(signer.identityPublicKey)
        if (senderOnly) filter.senderIdentityPublicKey = identity else filter.senderOrReceiverIdentityPublicKey = identity
        filter.addAllTypes(types)
            .addAllStatuses(statuses)
            .setNetwork(config.network.toProto())
            .setLimit(pageSize)
            .setOffset(offset)
        val response = stub.queryAllTransfers(filter.build())
        transfers += response.transfersList
        if (response.transfersCount < pageSize || response.offset == previousOffset) break
        previousOffset = response.offset
        offset = response.offset
    } while (offset >= 0)
    return transfers
}

/** The wallet's `AVAILABLE` leaves on the coordinator. */
internal suspend fun SparkWallet.queryAvailableNodes(): Map<String, Spark.TreeNode> {
    val request = Spark.QueryNodesRequest.newBuilder()
        .setOwnerIdentityPubkey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .addStatuses(Spark.TreeNodeStatus.TREE_NODE_STATUS_AVAILABLE)
        .build()
    return queryAllNodes(request)
}

/**
 * Every leaf node with status `AVAILABLE` owned by this wallet, in coordinator-defined order —
 * including frozen leaves, which cannot move ([SparkLeaf.isSpendable] is `false`). Spend paths
 * select from [getSpendableLeaves] instead.
 */
public suspend fun SparkWallet.getLeaves(): List<SparkLeaf> = queryAvailableNodes().mapNotNull { (id, node) ->
    if (node.status != "AVAILABLE") return@mapNotNull null
    SparkLeaf(id = id, treeID = node.treeId, valueSats = reportedSats(node.value), status = node.status, node = node)
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

internal fun SparkNetwork.toProto(): Spark.Network = when (this) {
    SparkNetwork.MAINNET -> Spark.Network.MAINNET
    SparkNetwork.REGTEST -> Spark.Network.REGTEST
}
