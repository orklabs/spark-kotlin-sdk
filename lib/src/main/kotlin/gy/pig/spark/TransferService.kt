package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import spark.Spark
import uniffi.spark_frost.*
import java.util.UUID

/**
 * Send sats to another Spark wallet identified by its bech32m Spark address
 * (`spark1...` on mainnet, `sparkrt1...` on regtest). The address must be for the wallet's
 * network.
 *
 * @throws SparkError.InvalidAddress for a malformed address, one for another network, or a
 *   Spark invoice: sending to it as an address would ignore its amount, expiry and sender, and
 *   the payee would not see it paid.
 */
public suspend fun SparkWallet.send(receiverSparkAddress: String, amountSats: Long): SparkTransfer {
    val receiver = SparkAddress.decode(receiverSparkAddress, config.network)
    return send(receiverIdentityPublicKey = receiver, amountSats = amountSats)
}

/** Validate the arguments of a Spark transfer before any leaf is selected or swapped. */
internal fun validateSendArguments(receiverIdentityPublicKey: ByteArray, amountSats: Long) {
    if (amountSats <= 0) {
        throw SparkError.InvalidArgument("amountSats must be positive, got $amountSats")
    }
    if (receiverIdentityPublicKey.size != 33 ||
        (receiverIdentityPublicKey[0] != 0x02.toByte() && receiverIdentityPublicKey[0] != 0x03.toByte())
    ) {
        throw SparkError.InvalidArgument("receiverIdentityPublicKey must be a 33-byte compressed secp256k1 public key")
    }
}

/**
 * Send sats to another Spark wallet identified by its 33-byte compressed identity public key.
 * Leaves are selected from [getSpendableLeaves] and swapped to exactly [amountSats] first.
 */
public suspend fun SparkWallet.send(receiverIdentityPublicKey: ByteArray, amountSats: Long,): SparkTransfer {
    validateSendArguments(receiverIdentityPublicKey, amountSats)
    val selectedLeaves = selectLeavesWithSwap(amountSats)
    return transferLeaves(selectedLeaves, receiverIdentityPublicKey)
}

/** Transfer exactly [selectedLeaves] to the receiver in one Spark transfer. */
internal suspend fun SparkWallet.transferLeaves(selectedLeaves: List<SparkLeaf>, receiverIdentityPublicKey: ByteArray): SparkTransfer {
    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val soOperators = soListResponse.signingOperatorsMap

    // Get SO signing commitments (count=3: cpfp, direct, directFromCpfp)
    val leafIDs = selectedLeaves.map { it.id }
    val commitmentsRequest = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .addAllNodeIds(leafIDs)
        .build()
    val commitmentsResponse = stub.getSigningCommitments(commitmentsRequest)
    val allCommitments = commitmentsResponse.signingCommitmentsList
    if (allCommitments.size < 3 * selectedLeaves.size) {
        throw SparkError.InvalidResponse("Got ${allCommitments.size} signing commitments, need ${3 * selectedLeaves.size}")
    }

    val cpfpRefundJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directRefundJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directFromCpfpRefundJobs = mutableListOf<Spark.UserSignedTxSigningJob>()

    val transferID = UUID.randomUUID().toString().lowercase()
    val expiryTime = Timestamp.newBuilder()
        .setSeconds((System.currentTimeMillis() / 1000) + 16 * 24 * 60 * 60)
        .build()

    for (i in selectedLeaves.indices) {
        val leaf = selectedLeaves[i]
        val node = leaf.node ?: throw SparkError.InvalidResponse("Leaf ${leaf.id} missing node data")
        val oldSigningKey = signer.deriveLeafSigningKey(leaf.id)
        val verifyingKey = node.verifyingPublicKey.toByteArray()

        val cpfpCommitments = allCommitments[i].signingNonceCommitmentsMap
        val directCommitments = allCommitments[i + selectedLeaves.size].signingNonceCommitmentsMap
        val directFromCpfpCommitments = allCommitments[i + 2 * selectedLeaves.size].signingNonceCommitmentsMap

        val (cpfpSequence, directSequence) = computeNextSequences(node.refundTx.toByteArray())

        val refundTrio = leafRefundTrio(
            node = node,
            receivingPubkey = receiverIdentityPublicKey,
            network = networkStr,
            sequence = cpfpSequence,
            directSequence = directSequence,
        )

        cpfpRefundJobs.add(
            FrostSigningHelper.buildSigningJob(
                leafID = leaf.id,
                signingKey = oldSigningKey,
                verifyingKey = verifyingKey,
                rawTx = refundTrio.cpfpRefund.tx,
                sighash = refundTrio.cpfpRefund.sighash,
                soCommitments = cpfpCommitments,
            )
        )

        refundTrio.directRefund?.let { directRefund ->
            directRefundJobs.add(
                FrostSigningHelper.buildSigningJob(
                    leafID = leaf.id,
                    signingKey = oldSigningKey,
                    verifyingKey = verifyingKey,
                    rawTx = directRefund.tx,
                    sighash = directRefund.sighash,
                    soCommitments = directCommitments,
                )
            )
        }

        directFromCpfpRefundJobs.add(
            FrostSigningHelper.buildSigningJob(
                leafID = leaf.id,
                signingKey = oldSigningKey,
                verifyingKey = verifyingKey,
                rawTx = refundTrio.directFromCpfpRefund.tx,
                sighash = refundTrio.directFromCpfpRefund.sighash,
                soCommitments = directFromCpfpCommitments,
            )
        )
    }

    val (_, tweakPackage) = KeyTweakHelper.buildSendPackage(
        transferID = transferID,
        leaves = selectedLeaves,
        receiverPubKey = receiverIdentityPublicKey,
        signer = signer,
        soOperators = soOperators,
        signingOperatorConfigs = config.signingOperators,
        threshold = config.signingThreshold,
    )

    val transferPackageBuilder = Spark.TransferPackage.newBuilder()
        .setUserSignature(ByteString.copyFrom(tweakPackage.signature))
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToSend(cpfpRefundJobs)
        .addAllDirectLeavesToSend(directRefundJobs)
        .addAllDirectFromCpfpLeavesToSend(directFromCpfpRefundJobs)
    for ((soID, cipher) in tweakPackage.keyTweakPackage) {
        transferPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }

    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverIdentityPublicKey))
        .setExpiryTime(expiryTime)
        .setTransferPackage(transferPackageBuilder.build())
        .build()

    val response = stub.startTransferV2(transferRequest)
    return response.transfer.toSparkTransfer()
}

/**
 * A leaf's refund transactions paying [receivingPubkey] at the given sequences: the CPFP refund,
 * the direct-from-CPFP refund, and a direct refund when [directNodeTxForRefund] allows one.
 */
internal fun leafRefundTrio(node: Spark.TreeNode, receivingPubkey: ByteArray, network: String, sequence: UInt, directSequence: UInt,): RefundTxTrioResult =
    constructRefundTxTrio(
        cpfpNodeTx = node.nodeTx.toByteArray(),
        directNodeTx = directNodeTxForRefund(node),
        vout = 0u,
        receivingPubkey = receivingPubkey,
        network = network,
        sequence = sequence,
        directSequence = directSequence,
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )

/**
 * The direct node transaction a leaf's direct refund spends, or `null` when the leaf has none or
 * is a zero-timelock node. The operators reject a direct refund for a zero node ("zero nodes must
 * not have a direct refund tx"), and zero-timelock renewal leaves exactly that shape: a
 * timelock-0 node transaction together with a direct one. Mirrors the reference SDK's
 * `isZeroNode` check in its refund builders. (Lightning HTLC refunds follow a different rule:
 * the operators expect a direct HTLC refund whenever a direct node transaction exists.)
 */
internal fun directNodeTxForRefund(node: Spark.TreeNode): ByteArray? {
    if (node.directTx.isEmpty || isZeroTimelockNode(node.nodeTx.toByteArray())) return null
    return node.directTx.toByteArray()
}

/**
 * A refund timelock rounded down to the 100-block interval. The operators validate every
 * successor refund against the rounded value (`RoundDownToTimelockInterval`), so a leaf whose
 * timelock is not a multiple of 100 — 740, left by older SDKs — counts as 700.
 */
internal fun roundedTimelock(timelock: UInt): UInt = timelock - timelock % SPARK_TIME_LOCK_INTERVAL.toUInt()

/**
 * Whether a leaf with this refund timelock can be transferred, swapped or exited without a
 * renewal first. The operators require the rounded timelock to stay above 100 so the next refund
 * does not reach zero (`ValidateRenewalTimelockFloor`): a refund timelock of at least 200. Leaves
 * at 100…199 need renewing; below 100 they cannot be renewed either.
 */
internal fun isTransferableRefundTimelock(timelock: UInt): Boolean = roundedTimelock(timelock) > SPARK_TIME_LOCK_INTERVAL.toUInt()

/**
 * The next CPFP and direct refund sequences for a transfer, swap or cooperative exit: the current
 * refund timelock rounded down to the interval, minus 100, and the direct refunds 50 above that —
 * exactly what the operators expect (`ValidateSequence`), and what the reference SDK builds
 * (`createDecrementedTimelockRefundTxs` with `enforceTimelocks`). A raw decrement produced 640
 * for a leaf at 740 where the operators require 600. Bit 30 is kept. Lightning HTLC refunds use
 * [htlcSequences] instead: they are not rounded.
 */
internal fun computeNextSequences(refundTxData: ByteArray): Pair<UInt, UInt> {
    val rawSequence = parseSequenceFromRawTx(refundTxData)
    val currentTimelock = rawSequence and 0xFFFFu
    val bit30 = rawSequence and (1u shl 30)
    // Checked before subtracting: UInt subtraction would silently wrap to a garbage sequence.
    if (!isTransferableRefundTimelock(currentTimelock)) {
        throw SparkError.LeafTimelockExhausted(
            "Leaf timelock exhausted ($currentTimelock, rounded ${roundedTimelock(currentTimelock)} <= " +
                "$SPARK_TIME_LOCK_INTERVAL); needs renewal before it can move",
        )
    }
    val nextTimelock = roundedTimelock(currentTimelock) - SPARK_TIME_LOCK_INTERVAL.toUInt()
    return (bit30 or nextTimelock) to (bit30 or (nextTimelock + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt()))
}

/**
 * Whether the leaf can be transferred, swapped or exited without an operator renewal
 * ([isTransferableRefundTimelock] on its refund transaction).
 */
internal fun timelockCanDecrement(refundTxData: ByteArray): Boolean {
    // An unparseable refund tx is treated as exhausted: the leaf is skipped rather than
    // crashing the caller or being handed to the coordinator with a bogus sequence.
    val sequence = try {
        parseSequenceFromRawTx(refundTxData)
    } catch (_: SparkError) {
        return false
    }
    return isTransferableRefundTimelock(sequence and 0xFFFFu)
}

/**
 * nSequence of the first input of a raw Bitcoin transaction (where Spark keeps leaf timelocks).
 *
 * @throws SparkError.MalformedTransaction when the bytes are not a well-formed transaction.
 */
internal fun parseSequenceFromRawTx(rawTx: ByteArray): UInt = RawTransaction.parse(rawTx, context = "leaf tx").firstInputSequence
