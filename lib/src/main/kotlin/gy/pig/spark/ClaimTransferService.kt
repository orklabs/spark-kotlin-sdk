package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import kotlinx.coroutines.CancellationException
import spark.Spark
import uniffi.spark_frost.*

suspend fun SparkWallet.queryPendingTransfers(): List<Spark.Transfer> {
    val stub = getCoordinatorStub()

    val filter = Spark.TransferFilter.newBuilder()
        .setReceiverIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setNetwork(config.network.toProto())
        .build()

    val response = stub.queryPendingTransfers(filter)
    return response.transfersList
}

/** A pending inbound transfer that could not be claimed. It stays pending; its sats stay in `incoming`. */
public data class SparkTransferClaimFailure(public val transferId: String, public val error: kotlin.Exception)

/**
 * Outcome of [claimPendingTransfers]. Claims are per transfer: one failing transfer never
 * blocks the rest.
 */
public data class SparkTransferClaim(
    /** Pending inbound transfers found. */
    public val pending: Int,
    /** Transfers claimed. */
    public val claimed: Int,
    /** Transfers that could not be claimed, in the order they were attempted. */
    public val failures: List<SparkTransferClaimFailure>,
)

/**
 * Claim every pending inbound transfer (lightning receives, Spark transfers, swap and deposit
 * settlements) and return how many were claimed.
 *
 * Each transfer is claimed independently, so one malformed or unverifiable transfer no longer
 * leaves every later one unclaimed. If any failed, the first failure is rethrown once all of
 * them were attempted, with further failures attached as suppressed exceptions — the same
 * exception types callers caught before. Use [claimPendingTransfers] for a per-transfer report
 * without an exception.
 */
public suspend fun SparkWallet.claimAllPendingTransfers(): Int = claimPendingTransfers().claimedOrThrow()

/**
 * Claim every pending inbound transfer independently and report each outcome. Only the query
 * for pending transfers (and coroutine cancellation) can make this throw; a transfer that
 * cannot be claimed is recorded in [SparkTransferClaim.failures] and the rest are still claimed.
 *
 * The Swift SDK's `claimAllPendingTransfers` still stops at the first failure.
 */
public suspend fun SparkWallet.claimPendingTransfers(): SparkTransferClaim = claimEach(queryPendingTransfers()) { claimTransfer(it) }

/** Claim [transfers] one by one; a failure is recorded and the loop moves on. Cancellation is never swallowed. */
internal suspend fun claimEach(transfers: List<Spark.Transfer>, claim: suspend (Spark.Transfer) -> Unit): SparkTransferClaim {
    var claimed = 0
    val failures = mutableListOf<SparkTransferClaimFailure>()
    for (transfer in transfers) {
        try {
            claim(transfer)
            claimed++
        } catch (e: CancellationException) {
            throw e
        } catch (e: kotlin.Exception) {
            // Spelled out: `uniffi.spark_frost.*` brings its own `Exception` (FROST errors only).
            failures.add(SparkTransferClaimFailure(transferId = transfer.id, error = e))
        }
    }
    return SparkTransferClaim(pending = transfers.size, claimed = claimed, failures = failures)
}

/** [SparkTransferClaim.claimed], or the first failure (later ones attached as suppressed) when any claim failed. */
internal fun SparkTransferClaim.claimedOrThrow(): Int {
    val first = failures.firstOrNull()?.error ?: return claimed
    for (other in failures.drop(1)) {
        if (other.error !== first) first.addSuppressed(other.error)
    }
    throw first
}

/**
 * Claim a single pending transfer using the single-call `claim_transfer` with a ClaimPackage.
 * The sender's signature on every leaf is verified first; a transfer that fails verification
 * is refused with [SparkError.UntrustedResponse] before any secret is decrypted or any refund
 * is signed.
 */
suspend fun SparkWallet.claimTransfer(transfer: Spark.Transfer) {
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

        val cpfpNodeTx = node.nodeTx.toByteArray()
        val directNodeTx = if (node.directTx.isEmpty) null else node.directTx.toByteArray()

        val refundTrio = constructRefundTxTrio(
            cpfpNodeTx = cpfpNodeTx,
            directNodeTx = directNodeTx,
            vout = 0u,
            receivingPubkey = newSigningPubKey,
            network = networkStr,
            sequence = cpfpSequence,
            directSequence = directSequence,
            feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
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
