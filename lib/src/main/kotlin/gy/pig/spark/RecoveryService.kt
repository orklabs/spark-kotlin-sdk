package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark

/**
 * Unilateral-exit recovery snapshots — direct port of the Swift SDK's
 * `RecoveryService.swift`.
 */

/**
 * A leaf currently owned by the wallet, with its full TreeNode encoded for offline
 * use (raw node tx, pre-signed refund txs, verifying key, parent id).
 */
public data class SparkRecoveryLeaf(
    val id: String,
    val status: String,
    val valueSats: Long,
    /** Hex of the protobuf-serialized TreeNode (`TreeNode.encode().finish()` bytes). */
    val treeNodeHex: String,
)

/** An ancestor node on the path from a leaf to its tree root. */
public data class SparkRecoveryNode(val id: String, val treeNodeHex: String,)

/**
 * Everything besides the seed needed to unilaterally exit the wallet's funds while
 * Spark operators are offline. Leaves cannot be re-discovered from the seed once
 * operators are down, so this snapshot must be captured while they are online and
 * refreshed whenever the leaf set changes.
 */
public data class SparkRecoverySnapshot(
    /** "MAINNET" or "REGTEST" */
    val network: String,
    val identityPublicKeyHex: String,
    val leaves: List<SparkRecoveryLeaf>,
    val nodes: List<SparkRecoveryNode>,
) {
    val totalLeafSats: Long get() = leaves.sumOf { it.valueSats }
}

/**
 * query_nodes with include_parents returns every leaf's full ancestor chain in ONE
 * message, and long-lived wallets exceed the transport's 4 MiB default cap (seen
 * live: 6.9 MB → resourceExhausted). Raise both size limits for the recovery
 * queries only.
 */
private const val RECOVERY_MAX_MESSAGE_BYTES: Int = 128 * 1024 * 1024

/**
 * Fetch the wallet's leaves plus the complete ancestor chain of every leaf.
 *
 * The bulk include-parents query can omit nodes (notably legacy tree roots), so
 * referenced-but-missing parents are re-fetched by node id until every chain
 * terminates at a root. Throws if a chain still cannot be completed — callers must
 * NOT persist a snapshot from a failed call over a previous good one, because an
 * incomplete snapshot is useless for a unilateral exit.
 */
public suspend fun SparkWallet.getRecoverySnapshot(): SparkRecoverySnapshot {
    val stub = getCoordinatorStub()
        .withMaxInboundMessageSize(RECOVERY_MAX_MESSAGE_BYTES)
        .withMaxOutboundMessageSize(RECOVERY_MAX_MESSAGE_BYTES)

    val all = mutableMapOf<String, Spark.TreeNode>()

    val request = Spark.QueryNodesRequest.newBuilder()
        .setOwnerIdentityPubkey(ByteString.copyFrom(signer.identityPublicKey))
        .setIncludeParents(true)
        .setNetwork(config.network.toProto())
        .build()
    val response = stub.queryNodes(request)
    all.putAll(response.nodesMap)

    // Repair pass: fetch any parent referenced by a node in the map but not present
    // in it. Bounded so a coordinator that keeps returning nothing can't loop us
    // forever; no-progress also exits. Best-effort here — the build step is the
    // arbiter of whether the chains that MATTER are whole.
    var missing = missingParentIds(all)
    var attempts = 0
    while (missing.isNotEmpty() && attempts < 10) {
        attempts++
        val repairRequest = Spark.QueryNodesRequest.newBuilder()
            .setNodeIds(Spark.TreeNodeIds.newBuilder().addAllNodeIds(missing.sorted()).build())
            .setIncludeParents(true)
            .build()
        val repairResponse = stub.queryNodes(repairRequest)
        val countBefore = all.size
        all.putAll(repairResponse.nodesMap)
        if (all.size <= countBefore) break
        missing = missingParentIds(all)
    }

    return buildRecoverySnapshot(
        all = all,
        identityPublicKey = signer.identityPublicKey,
        network = config.network.networkGraphQL,
    )
}

/** Parent ids referenced by nodes in the map but absent from it. */
internal fun missingParentIds(all: Map<String, Spark.TreeNode>): Set<String> {
    val missing = mutableSetOf<String>()
    for (node in all.values) {
        if (node.hasParentNodeId() && node.parentNodeId.isNotEmpty() && all[node.parentNodeId] == null) {
            missing.add(node.parentNodeId)
        }
    }
    return missing
}

/**
 * Pure classification of a complete node map into snapshot leaves + ancestors.
 * A leaf is a node we own, in a spendable/locked status, that no other node claims
 * as parent. Ancestors are PRUNED to the union of the current leaves' parent
 * chains — the owner query also returns historical nodes (old splits, spent
 * intermediates) that no exit package will ever use, and keeping them bloats the
 * bundle severalfold. Throws if a needed chain has a hole.
 */
internal fun buildRecoverySnapshot(all: Map<String, Spark.TreeNode>, identityPublicKey: ByteArray, network: String,): SparkRecoverySnapshot {
    // Same set getBalance() counts as owned.
    val ownedStatuses = setOf(
        "AVAILABLE",
        "TRANSFER_LOCKED",
        "SPLIT_LOCKED",
        "AGGREGATE_LOCK",
        "RENEW_LOCKED",
    )
    val referencedAsParent = all.values
        .filter { it.hasParentNodeId() && it.parentNodeId.isNotEmpty() }
        .map { it.parentNodeId }
        .toSet()

    val identityKey = ByteString.copyFrom(identityPublicKey)
    val leaves = mutableListOf<SparkRecoveryLeaf>()
    val leafIds = mutableListOf<String>()
    for ((id, node) in all) {
        val isLeaf = node.ownerIdentityPublicKey == identityKey &&
            node.status in ownedStatuses &&
            id !in referencedAsParent
        if (!isLeaf) continue
        leaves.add(
            SparkRecoveryLeaf(
                id = id,
                status = node.status,
                valueSats = node.value,
                treeNodeHex = node.toByteArray().toHexString(),
            )
        )
        leafIds.add(id)
    }

    // Walk each leaf's chain to its root, collecting exactly the ancestors an exit
    // package needs. A hole in a needed chain makes the snapshot useless for that
    // leaf — refuse to produce one (callers then keep their previous good file).
    val neededIds = mutableSetOf<String>()
    for (leafId in leafIds) {
        var cursor = all[leafId]
        while (cursor != null && cursor.hasParentNodeId() && cursor.parentNodeId.isNotEmpty()) {
            val parentId = cursor.parentNodeId
            val parent = all[parentId]
                ?: throw SparkError.InvalidResponse(
                    "Recovery snapshot incomplete: missing ancestor $parentId above leaf $leafId"
                )
            if (!neededIds.add(parentId)) break // chain already walked
            cursor = parent
        }
    }

    val nodes = neededIds.sorted().map { id ->
        SparkRecoveryNode(id = id, treeNodeHex = all.getValue(id).toByteArray().toHexString())
    }

    // Deterministic ordering so identical wallet state yields identical bytes.
    leaves.sortBy { it.id }

    return SparkRecoverySnapshot(
        network = network,
        identityPublicKeyHex = identityPublicKey.toHexString(),
        leaves = leaves,
        nodes = nodes,
    )
}
