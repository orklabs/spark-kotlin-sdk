package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import spark.Spark
import uniffi.spark_frost.*
import java.util.UUID

/**
 * `AVAILABLE` leaves that can be sent right now.
 *
 * Leaves whose refund timelock is in the coordinator's renewable range are renewed first
 * (best effort, as the reference SDK's leaf manager does before every spend). Leaves at the
 * timelock floor are left out: the coordinator will neither move nor renew them, so including
 * them would only make the whole operation fail. Their sats are reported as
 * [SatsBalance.frozen]. Every spend path ([send], [payLightningInvoice], [withdraw],
 * [withdrawAll], swaps) selects from this set, so it is also the right basis for an app's
 * "send everything" amount.
 */
public suspend fun SparkWallet.getSpendableLeaves(): List<SparkLeaf> {
    var leaves = getLeaves()
    if (renewalCandidates(leaves).renewable.isNotEmpty()) {
        bestEffort { renewExhaustedLeaves() }
        leaves = getLeaves()
    }
    return movableLeaves(leaves)
}

/** Leaves whose refund timelock is above the floor and can therefore be transferred. */
internal fun movableLeaves(leaves: List<SparkLeaf>): List<SparkLeaf> = leaves.filter { it.isSpendable }

/**
 * Select leaves that exactly cover the target amount. If no exact match exists, triggers a
 * leaf swap via the SSP to split leaves into the required denominations.
 *
 * Critical: this NEVER overspends. Spark transfers spend the entire selected leaf set,
 * so picking more than `amountSats` would silently lose the difference.
 */
suspend fun SparkWallet.selectLeavesWithSwap(amountSats: Long): List<SparkLeaf> {
    val leaves = getSpendableLeaves()

    // First try exact selection (leaves that sum exactly to the target)
    tryExactSelection(leaves, amountSats)?.let { return it }

    // No exact match — swap leaves via SSP to get right denominations
    val newLeaves = requestLeavesSwap(targetAmounts = listOf(amountSats))

    // Retry selection with new leaves (must find exact match — never overspend)
    return tryExactSelection(movableLeaves(newLeaves), amountSats)
        ?: throw SparkError.InvalidResponse("Failed to select leaves for target amount $amountSats after swap")
}

/**
 * Request a leaf swap via SSP: splits existing leaves into target denominations.
 * Returns the wallet's leaves after the swap completes (with new claimed leaves).
 */
suspend fun SparkWallet.requestLeavesSwap(targetAmounts: List<Long>): List<SparkLeaf> {
    val totalTarget = targetAmounts.sum()
    val leaves = getSpendableLeaves()

    // Select leaves covering the total target (smallest first)
    val sorted = leaves.sortedBy { it.valueSats }
    val selected = mutableListOf<SparkLeaf>()
    var total = 0L
    for (leaf in sorted) {
        if (total >= totalTarget) break
        selected.add(leaf)
        total += leaf.valueSats
    }
    if (total < totalTarget) throw SparkError.InsufficientBalance(need = totalTarget, have = total)

    return processSwapBatch(leaves = selected, targetAmounts = targetAmounts)
}

private data class LeafSigningInfo(
    val leafID: String,
    val signingKey: ByteArray,
    val verifyingKey: ByteArray,
    val selfCommitment: SigningCommitment,
    val sighash: ByteArray,
)

/**
 * Process a batch of leaves through the swap flow:
 * 1. Build refund txs with adaptor signatures and send to coordinator
 * 2. Aggregate FROST signatures with adaptor pubkey
 * 3. Call SSP `request_swap` mutation
 * 4. Claim the inbound transfer the SSP creates
 */
internal suspend fun SparkWallet.processSwapBatch(leaves: List<SparkLeaf>, targetAmounts: List<Long>,): List<SparkLeaf> {
    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString
    val receiverPubKey = config.sspIdentityPublicKey

    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val soOperators = soListResponse.signingOperatorsMap

    val adaptorPrivKey = randomSecretKeyBytes()
    val adaptorPubKey = getPublicKeyBytes(adaptorPrivKey, true)

    val transferID = UUID.randomUUID().toString().lowercase()
    val expiryTime = Timestamp.newBuilder()
        .setSeconds((System.currentTimeMillis() / 1000) + 16 * 24 * 60 * 60)
        .build()

    val leafIDs = leaves.map { it.id }
    val commitmentsRequest = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .addAllNodeIds(leafIDs)
        .build()
    val commitmentsResponse = stub.getSigningCommitments(commitmentsRequest)
    val allCommitments = commitmentsResponse.signingCommitmentsList
    if (allCommitments.size < leaves.size) {
        throw SparkError.InvalidResponse("Got ${allCommitments.size} signing commitments, need ${leaves.size}")
    }

    val cpfpRefundJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val leafSigningInfos = mutableListOf<LeafSigningInfo>()

    for (i in leaves.indices) {
        val leaf = leaves[i]
        val node = leaf.node ?: throw SparkError.InvalidResponse("Leaf ${leaf.id} missing node data")
        val oldSigningKey = signer.deriveLeafSigningKey(leaf.id)
        val verifyingKey = node.verifyingPublicKey.toByteArray()

        val cpfpCommitments = allCommitments[i].signingNonceCommitmentsMap
        val (cpfpSequence, _) = computeNextSequences(node.refundTx.toByteArray())

        val cpfpNodeTx = node.nodeTx.toByteArray()
        val cpfpRefund = constructRefundTx(
            tx = cpfpNodeTx,
            vout = 0u,
            pubkey = receiverPubKey,
            network = networkStr,
            sequence = cpfpSequence,
        )

        val publicKey = getPublicKeyBytes(oldSigningKey, true)
        val keyPackage = KeyPackage(secretKey = oldSigningKey, publicKey = publicKey, verifyingKey = verifyingKey)
        val nonceResult = frostNonce(keyPackage)

        val nativeCommitments = cpfpCommitments.mapValues { (_, proto) ->
            SigningCommitment(hiding = proto.hiding.toByteArray(), binding = proto.binding.toByteArray())
        }

        val userSignature = signFrost(
            msg = cpfpRefund.sighash,
            keyPackage = keyPackage,
            nonce = nonceResult.nonce,
            selfCommitment = nonceResult.commitment,
            statechainCommitments = nativeCommitments,
            adaptorPublicKey = adaptorPubKey,
        )

        val signingCommitmentsBuilder = Spark.SigningCommitments.newBuilder()
        for ((soID, commitment) in cpfpCommitments) {
            signingCommitmentsBuilder.putSigningCommitments(soID, commitment)
        }

        val job = Spark.UserSignedTxSigningJob.newBuilder()
            .setLeafId(leaf.id)
            .setSigningPublicKey(ByteString.copyFrom(publicKey))
            .setRawTx(ByteString.copyFrom(cpfpRefund.tx))
            .setSigningNonceCommitment(
                common.Common.SigningCommitment.newBuilder()
                    .setHiding(ByteString.copyFrom(nonceResult.commitment.hiding))
                    .setBinding(ByteString.copyFrom(nonceResult.commitment.binding))
                    .build()
            )
            .setUserSignature(ByteString.copyFrom(userSignature))
            .setSigningCommitments(signingCommitmentsBuilder.build())
            .build()
        cpfpRefundJobs.add(job)

        leafSigningInfos.add(
            LeafSigningInfo(
                leafID = leaf.id,
                signingKey = oldSigningKey,
                verifyingKey = verifyingKey,
                selfCommitment = nonceResult.commitment,
                sighash = cpfpRefund.sighash,
            )
        )
    }

    // Build key tweaks
    val (_, tweakPackage) = KeyTweakHelper.buildSendPackage(
        transferID = transferID,
        leaves = leaves,
        receiverPubKey = receiverPubKey,
        signer = signer,
        soOperators = soOperators,
        signingOperatorConfigs = config.signingOperators,
        threshold = config.signingThreshold,
    )

    val transferPackageBuilder = Spark.TransferPackage.newBuilder()
        .setUserSignature(ByteString.copyFrom(tweakPackage.signature))
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToSend(cpfpRefundJobs)
    // direct/directFromCpfp cleared for swaps (matching JS/Swift SDK)
    for ((soID, cipher) in tweakPackage.keyTweakPackage) {
        transferPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }

    val transferReq = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
        .setTransferPackage(transferPackageBuilder.build())
        .build()

    val swapRequest = Spark.InitiateSwapPrimaryTransferRequest.newBuilder()
        .setTransfer(transferReq)
        .setAdaptorPublicKeys(
            Spark.AdaptorPublicKeyPackage.newBuilder()
                .setAdaptorPublicKey(ByteString.copyFrom(adaptorPubKey))
                .build()
        )
        .build()

    val swapResponse = stub.initiateSwapPrimaryTransfer(swapRequest)

    if (!swapResponse.hasTransfer()) {
        throw SparkError.InvalidResponse("No transfer in swap response")
    }

    // Aggregate FROST signatures with adaptor pubkey
    val adaptorSignatures = mutableMapOf<String, ByteArray>()
    for (signingResult in swapResponse.signingResultsList) {
        val info = leafSigningInfos.firstOrNull { it.leafID == signingResult.leafId } ?: continue
        val job = cpfpRefundJobs.firstOrNull { it.leafId == signingResult.leafId } ?: continue

        val sigResult = signingResult.refundTxSigningResult

        val soCommitments = sigResult.signingNonceCommitmentsMap.mapValues { (_, c) ->
            SigningCommitment(hiding = c.hiding.toByteArray(), binding = c.binding.toByteArray())
        }
        val soSignatures = sigResult.signatureSharesMap.mapValues { it.value.toByteArray() }
        val soPublicKeys = sigResult.publicKeysMap.mapValues { it.value.toByteArray() }

        val aggregated = aggregateFrost(
            msg = info.sighash,
            statechainCommitments = soCommitments,
            selfCommitment = info.selfCommitment,
            statechainSignatures = soSignatures,
            selfSignature = job.userSignature.toByteArray(),
            statechainPublicKeys = soPublicKeys,
            selfPublicKey = job.signingPublicKey.toByteArray(),
            verifyingKey = signingResult.verifyingKey.toByteArray(),
            adaptorPublicKey = adaptorPubKey,
        )
        adaptorSignatures[signingResult.leafId] = aggregated
    }

    // Build user_leaves for SSP request_swap mutation
    val userLeaves = mutableListOf<Map<String, String>>()
    for (signingResult in swapResponse.signingResultsList) {
        val adaptorSig = adaptorSignatures[signingResult.leafId] ?: continue
        val transferLeaf = swapResponse.transfer.leavesList.firstOrNull { it.leaf.id == signingResult.leafId }
        val intermediateRefundHex = transferLeaf?.intermediateRefundTx?.toByteArray()?.toHexString() ?: ""
        val intermediateDirectRefundHex = transferLeaf?.intermediateDirectRefundTx?.toByteArray()?.toHexString() ?: ""
        val intermediateDirectFromCpfpRefundHex =
            transferLeaf?.intermediateDirectFromCpfpRefundTx?.toByteArray()?.toHexString() ?: ""

        val adaptorSigHex = adaptorSig.toHexString()
        userLeaves.add(
            mapOf(
                "leaf_id" to signingResult.leafId,
                "raw_unsigned_refund_transaction" to intermediateRefundHex,
                "direct_raw_unsigned_refund_transaction" to intermediateDirectRefundHex,
                "direct_from_cpfp_raw_unsigned_refund_transaction" to intermediateDirectFromCpfpRefundHex,
                "adaptor_added_signature" to adaptorSigHex,
                "direct_adaptor_added_signature" to adaptorSigHex,
                "direct_from_cpfp_adaptor_added_signature" to adaptorSigHex,
            )
        )
    }

    val totalAmountSats = leaves.sumOf { it.valueSats }
    val sspResponse = sspClient.executeRaw(
        query = GraphQLMutations.REQUEST_SWAP,
        variables = mapOf(
            "adaptor_pubkey" to adaptorPubKey.toHexString(),
            "total_amount_sats" to totalAmountSats,
            "target_amount_sats" to targetAmounts,
            "fee_sats" to 0L,
            "user_leaves" to userLeaves,
            "user_outbound_transfer_external_id" to swapResponse.transfer.id,
        ),
    )

    val requestSwap = sspResponse.optJSONObject("request_swap")
        ?: throw SparkError.InvalidResponse("Leaf swap request failed: no request_swap")
    val request = requestSwap.optJSONObject("request")
        ?: throw SparkError.InvalidResponse("Leaf swap request failed: no request")
    val status = request.optString("leaves_swap_request_status", "")
    if (status == "FAILED" || status.isEmpty()) {
        throw SparkError.InvalidResponse("Leaf swap request failed: status=$status")
    }
    val inboundTransfer = request.optJSONObject("leaves_swap_request_inbound_transfer")
        ?: throw SparkError.InvalidResponse("Leaf swap missing inbound transfer")
    val sparkId = inboundTransfer.optString("transfer_spark_id", "")
    if (sparkId.isEmpty()) throw SparkError.InvalidResponse("Leaf swap missing spark id")

    // Query the inbound transfer by spark ID and claim it
    val inboundTransferProto = queryTransferById(sparkId)
    claimTransfer(inboundTransferProto)

    return getLeaves()
}

internal suspend fun SparkWallet.queryTransferById(transferId: String): Spark.Transfer =
    queryTransferByIdOrNull(transferId) ?: throw SparkError.InvalidResponse("Transfer not found: $transferId")

/** The transfer with [transferId] in which this wallet takes part, or `null` when the coordinator has none. */
internal suspend fun SparkWallet.queryTransferByIdOrNull(transferId: String): Spark.Transfer? {
    val stub = getCoordinatorStub()
    val filter = Spark.TransferFilter.newBuilder()
        .setSenderOrReceiverIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .addTransferIds(transferId)
        .setNetwork(config.network.toProto())
        .build()
    val response = stub.queryAllTransfers(filter)
    // Only the transfer that was asked for (UUIDs compare case-insensitively).
    return response.transfersList.firstOrNull { it.id.equals(transferId, ignoreCase = true) }
}

/**
 * Try to find leaves that exactly sum to the target amount.
 * Greedy descending: only adds a leaf if it fits in the remaining amount.
 * Returns null if no exact combination is found.
 */
fun tryExactSelection(leaves: List<SparkLeaf>, amountSats: Long): List<SparkLeaf>? {
    // Single-leaf exact match
    leaves.firstOrNull { it.valueSats == amountSats }?.let { return listOf(it) }

    val sorted = leaves.sortedByDescending { it.valueSats }
    val selected = mutableListOf<SparkLeaf>()
    var remaining = amountSats
    for (leaf in sorted) {
        if (leaf.valueSats <= remaining) {
            selected.add(leaf)
            remaining -= leaf.valueSats
            if (remaining == 0L) return selected
        }
    }
    return null
}
