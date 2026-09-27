package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import spark.Spark
import uniffi.spark_frost.*

/** The outcome of one pass over the wallet's pending inbound transfers. */
public class PendingTransferClaim internal constructor(
    /** Transfers claimed in this pass, in the order they were claimed. */
    public val claimedTransferIds: List<String>,
    /**
     * Transfers that could not be claimed. They stay pending and are tried again on the next
     * pass. One the SDK refuses to claim (for example because a sender signature does not
     * verify) fails every time, but never stops the others from being claimed.
     */
    public val failures: List<Failure>,
    /** The claimed transfers, as they were claimed. */
    internal val claimedTransfers: List<Spark.Transfer> = emptyList(),
) {
    /** A pending inbound transfer that could not be claimed, and why. */
    public class Failure internal constructor(public val transferId: String, public val error: kotlin.Exception) {
        override fun toString(): String = "Failure(transferId=$transferId, error=$error)"
    }

    /** Leaves of the claimed transfers. */
    internal val claimedLeafIds: List<String>
        get() = claimedTransfers.flatMap { transfer -> transfer.leavesList.map { it.leaf.id } }

    override fun toString(): String = "PendingTransferClaim(claimedTransferIds=$claimedTransferIds, failures=$failures)"
}

/**
 * One claim pass over the pending inbound transfers, following the reference SDK's
 * `claimTransfers`: pages of 25, only transfers in a claimable status, a failure is recorded and
 * the pass moves on, and after any progress it restarts from the head (claimed transfers leave
 * the pending set, shifting later ones forward); otherwise it advances past the page. Without a
 * server-time snapshot the pass is bounded to 100 pages, as the reference SDK's fallback is. A
 * transfer that failed is not tried again within the same pass.
 */
internal object PendingTransferDrain {
    const val BATCH_SIZE: Int = 25
    const val MAX_BATCHES: Int = 100

    /** Statuses the reference SDK claims; anything else is left for a later pass. */
    val CLAIMABLE_STATUSES: Set<Spark.TransferStatus> = setOf(
        Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED,
        Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAKED,
        Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_REFUND_SIGNED,
        Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_APPLIED,
        Spark.TransferStatus.TRANSFER_STATUS_RECEIVER_KEY_TWEAK_LOCKED,
    )

    suspend fun run(fetch: suspend (limit: Int, offset: Int) -> List<Spark.Transfer>, claim: suspend (Spark.Transfer) -> Unit,): PendingTransferClaim {
        val claimed = mutableListOf<Spark.Transfer>()
        val failures = mutableListOf<PendingTransferClaim.Failure>()
        val attempted = mutableSetOf<String>()
        var offset = 0
        var batches = 0
        while (batches < MAX_BATCHES) {
            batches++
            currentCoroutineContext().ensureActive()
            val batch = fetch(BATCH_SIZE, offset)
            val claimable = batch.filter { it.status in CLAIMABLE_STATUSES && it.id !in attempted }
            var progress = false
            for (transfer in claimable) {
                currentCoroutineContext().ensureActive()
                attempted.add(transfer.id)
                try {
                    claim(transfer)
                    claimed.add(transfer)
                    progress = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: kotlin.Exception) {
                    // Spelled out: `uniffi.spark_frost.*` brings its own `Exception` (FROST errors only).
                    failures.add(PendingTransferClaim.Failure(transferId = transfer.id, error = e))
                }
            }
            // A short (or empty) page ends the pending set.
            if (batch.size < BATCH_SIZE) break
            offset = if (progress) 0 else offset + batch.size
        }
        return PendingTransferClaim(claimedTransferIds = claimed.map { it.id }, failures = failures, claimedTransfers = claimed)
    }
}

/** Query pending transfers where this wallet is the receiver. [limit] 0 asks for the server's largest page (100). */
internal suspend fun SparkWallet.queryPendingTransfers(limit: Int = 0, offset: Int = 0): List<Spark.Transfer> {
    val stub = getCoordinatorStub()

    val filter = Spark.TransferFilter.newBuilder()
        .setReceiverIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .setLimit(limit.toLong())
        .setOffset(offset.toLong())
        .build()

    val response = stub.queryPendingTransfers(filter)
    return response.transfersList
}

/**
 * Claim every pending inbound transfer (Spark transfers, Lightning receives, deposits the SSP
 * credited) and report what could not be claimed.
 *
 * Claims run one at a time, wallet-wide: a swap's claim of its counter-transfer or a concurrent
 * pass waits for this one. A transfer that cannot be claimed is recorded in
 * [PendingTransferClaim.failures] and the pass moves on to the rest, as the reference SDK does; a
 * transfer the operators already recorded as claimed by this wallet counts as claimed. Only the
 * query for pending transfers (and cancellation) can make this throw.
 *
 * Claimed leaves whose refund timelock is in the renewal range (100…199 — a transfer from a leaf
 * at 200 arrives at 100) are renewed right away, best effort, as the reference SDK does when it
 * registers claimed leaves; spend paths renew anything that is left.
 */
public suspend fun SparkWallet.claimPendingTransfers(): PendingTransferClaim {
    val result = claimLock.withLock {
        PendingTransferDrain.run(
            fetch = { limit, offset -> queryPendingTransfers(limit, offset) },
            claim = { transfer -> claimTransferTreatingDuplicatesAsClaimed(transfer) },
        )
    }
    renewClaimedLeaves(result.claimedLeafIds)
    return result
}

/** Best-effort renewal of the renewable leaves among [leafIds]. */
internal suspend fun SparkWallet.renewClaimedLeaves(leafIds: List<String>) {
    if (leafIds.isEmpty()) return
    val ids = leafIds.toSet()
    val leaves = bestEffort { getLeaves().filter { it.id in ids } } ?: return
    if (renewalCandidates(leaves).renewable.isEmpty()) return
    bestEffort { renewLeaves(leaves) }
}

/**
 * Claim every pending inbound transfer; returns how many were claimed. Transfers that cannot be
 * claimed no longer stop the rest, and are not thrown — use [claimPendingTransfers] to see them.
 */
public suspend fun SparkWallet.claimAllPendingTransfers(): Int = claimPendingTransfers().claimedTransferIds.size

/**
 * Claim one transfer under the wallet-wide claim lock (used by swaps for their counter-transfer,
 * which a concurrent claim pass may already have claimed).
 */
internal suspend fun SparkWallet.claimTransfer(transfer: Spark.Transfer) {
    claimLock.withLock { claimTransferTreatingDuplicatesAsClaimed(transfer) }
}

/**
 * The operators answer ALREADY_EXISTS once this receiver has claimed the transfer; like the
 * reference SDK, confirm this wallet's leg is complete and treat it as claimed.
 */
private suspend fun SparkWallet.claimTransferTreatingDuplicatesAsClaimed(transfer: Spark.Transfer) {
    try {
        claimTransferNow(transfer)
    } catch (e: kotlin.Exception) {
        if (e.grpcStatus?.code != Status.Code.ALREADY_EXISTS) throw e
        if (!TransferLeafVerifier.isReceiverLegComplete(queryTransferById(transfer.id), signer.identityPublicKey)) throw e
    }
}

/**
 * Claim a single pending transfer using the single-call `claim_transfer` with a ClaimPackage.
 * A multi-receiver transfer is narrowed to this wallet's own leaves, and the sender's signature
 * on every leaf is verified first; a transfer that fails verification is refused with
 * [SparkError.UntrustedResponse] before any secret is decrypted or any refund is signed. Callers
 * hold `claimLock`.
 */
private suspend fun SparkWallet.claimTransferNow(pending: Spark.Transfer) {
    val transfer = TransferLeafVerifier.scoped(pending, receiverIdentityPublicKey = signer.identityPublicKey)
    TransferLeafVerifier.verify(transfer, receiverIdentityPublicKey = signer.identityPublicKey)

    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val targets = KeyTweakHelper.matchOperators(server = soListResponse.signingOperatorsMap, config = config.signingOperators)
    val soCount = targets.size.toUInt()
    val threshold = config.signingThreshold

    val transferLeaves = transfer.leavesList

    // Get signing commitments (Count=3: cpfp, direct, directFromCpfp)
    val commitmentsRequest = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .setNodeIdCount(transferLeaves.size)
        .build()
    val commitmentsResponse = stub.getSigningCommitments(commitmentsRequest)
    val allCommitments = commitmentsResponse.signingCommitmentsList
    if (allCommitments.size < 3 * transferLeaves.size) {
        throw SparkError.InvalidResponse("Got ${allCommitments.size} signing commitments, need ${3 * transferLeaves.size}")
    }

    val cpfpRefundJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directRefundJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directFromCpfpRefundJobs = mutableListOf<Spark.UserSignedTxSigningJob>()

    val perSoTweaks = linkedMapOf<String, Spark.ClaimLeafKeyTweaks.Builder>()
    for (target in targets) {
        perSoTweaks[target.soID] = Spark.ClaimLeafKeyTweaks.newBuilder()
    }

    for (i in transferLeaves.indices) {
        val transferLeaf = transferLeaves[i]
        val node = transferLeaf.leaf

        // ECIES decrypt secret_cipher -> sender's intermediate signing key
        val oldSigningKey = decryptEcies(
            transferLeaf.secretCipher.toByteArray(),
            signer.deriveIdentityPrivateKey(),
        )

        val newSigningKey = signer.deriveLeafSigningKey(node.id)
        val newSigningPubKey = getPublicKeyBytes(newSigningKey, true)
        val verifyingKey = node.verifyingPublicKey.toByteArray()

        val keyTweak = subtractPrivateKeys(oldSigningKey, newSigningKey)
        val vssShares = splitSecretWithProofsUniffi(keyTweak, threshold, soCount)

        // Get sequence from intermediate refund tx
        val intermediateRefundTx = transferLeaf.intermediateRefundTx.toByteArray()
        val nodeRefundTx = node.refundTx.toByteArray()
        val rawSequence = when {
            intermediateRefundTx.isNotEmpty() -> parseSequenceFromRawTx(intermediateRefundTx)
            nodeRefundTx.isNotEmpty() -> parseSequenceFromRawTx(nodeRefundTx)
            else -> parseSequenceFromRawTx(node.nodeTx.toByteArray())
        }

        // Round down to nearest interval
        var currentTimelock = rawSequence and 0xFFFFu
        val remainder = currentTimelock % SPARK_TIME_LOCK_INTERVAL.toUInt()
        if (remainder != 0u) currentTimelock -= remainder
        val bit30 = rawSequence and (1u shl 30)
        val cpfpSequence = bit30 or (currentTimelock and 0xFFFFu)
        val directSequence = bit30 or ((currentTimelock + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt()) and 0xFFFFu)

        // Construct refund tx trio (no direct refund for zero-timelock nodes)
        val refundTrio = leafRefundTrio(
            node = node,
            receivingPubkey = newSigningPubKey,
            network = networkStr,
            sequence = cpfpSequence,
            directSequence = directSequence,
        )

        val cpfpCommitments = allCommitments[i].signingNonceCommitmentsMap
        val directCommitments = allCommitments[i + transferLeaves.size].signingNonceCommitmentsMap
        val directFromCpfpCommitments = allCommitments[i + 2 * transferLeaves.size].signingNonceCommitmentsMap

        cpfpRefundJobs.add(
            FrostSigningHelper.buildSigningJob(
                leafID = node.id,
                signingKey = newSigningKey,
                verifyingKey = verifyingKey,
                rawTx = refundTrio.cpfpRefund.tx,
                sighash = refundTrio.cpfpRefund.sighash,
                soCommitments = cpfpCommitments,
            )
        )

        refundTrio.directRefund?.let { directRefund ->
            directRefundJobs.add(
                FrostSigningHelper.buildSigningJob(
                    leafID = node.id,
                    signingKey = newSigningKey,
                    verifyingKey = verifyingKey,
                    rawTx = directRefund.tx,
                    sighash = directRefund.sighash,
                    soCommitments = directCommitments,
                )
            )
        }

        directFromCpfpRefundJobs.add(
            FrostSigningHelper.buildSigningJob(
                leafID = node.id,
                signingKey = newSigningKey,
                verifyingKey = verifyingKey,
                rawTx = refundTrio.directFromCpfpRefund.tx,
                sighash = refundTrio.directFromCpfpRefund.sighash,
                soCommitments = directFromCpfpCommitments,
            )
        )

        // Build pubkey shares tweak map
        val sharesByTarget = KeyTweakHelper.shares(vssShares, targets)
        val pubkeyBySOID = linkedMapOf<String, ByteArray>()
        for ((target, share) in sharesByTarget) {
            pubkeyBySOID[target.soID] = getPublicKeyBytes(share.share, true)
        }

        for ((target, share) in sharesByTarget) {
            val soID = target.soID
            val secretShareProto = Spark.SecretShare.newBuilder()
                .setSecretShare(ByteString.copyFrom(share.share))
            for (proof in share.proofs) {
                secretShareProto.addProofs(ByteString.copyFrom(proof))
            }

            val leafTweak = Spark.ClaimLeafKeyTweak.newBuilder()
                .setLeafId(node.id)
                .setSecretShareTweak(secretShareProto.build())
            for ((otherSoID, pubkey) in pubkeyBySOID) {
                leafTweak.putPubkeySharesTweak(otherSoID, ByteString.copyFrom(pubkey))
            }
            perSoTweaks[soID]?.addLeavesToReceive(leafTweak.build())
        }
    }

    val builtTweaks = perSoTweaks.mapValues { it.value.build() }
    val claimPackageResult = KeyTweakHelper.encryptAndSign(
        transferID = transfer.id,
        perSoTweaks = builtTweaks,
        targets = targets,
        signer = signer,
        tag = "claim",
    )

    val claimPackageBuilder = Spark.ClaimPackage.newBuilder()
        .setUserSignature(ByteString.copyFrom(claimPackageResult.signature))
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToClaim(cpfpRefundJobs)
        .addAllDirectLeavesToClaim(directRefundJobs)
        .addAllDirectFromCpfpLeavesToClaim(directFromCpfpRefundJobs)
    for ((soID, cipher) in claimPackageResult.keyTweakPackage) {
        claimPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }

    val claimRequest = Spark.ClaimTransferRequest.newBuilder()
        .setTransferId(transfer.id)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setClaimPackage(claimPackageBuilder.build())
        .build()

    stub.claimTransfer(claimRequest)
}
