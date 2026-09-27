package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import spark.Spark
import uniffi.spark_frost.*
import java.util.Date
import java.util.UUID

/**
 * Send sats to another Spark wallet identified by its bech32m Spark address
 * (`spark1...` on mainnet, `sparkrt1...` on regtest). The address must be for the wallet's
 * network.
 *
 * @throws SparkError.InvalidAddress for a malformed address or one for another network.
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

        val cpfpNodeTx = node.nodeTx.toByteArray()
        val directNodeTx = if (node.directTx.isEmpty) null else node.directTx.toByteArray()

        val refundTrio = constructRefundTxTrio(
            cpfpNodeTx = cpfpNodeTx,
            directNodeTx = directNodeTx,
            vout = 0u,
            receivingPubkey = receiverIdentityPublicKey,
            network = networkStr,
            sequence = cpfpSequence,
            directSequence = directSequence,
            feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
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
    val transfer = response.transfer
    return SparkTransfer(
        id = transfer.id,
        senderIdentityPublicKey = transfer.senderIdentityPublicKey.toByteArray().toHexString(),
        receiverIdentityPublicKey = transfer.receiverIdentityPublicKey.toByteArray().toHexString(),
        totalValueSats = transfer.totalValue,
        status = transfer.status.toString(),
        type = transfer.type.toString(),
        createdAt = Date(transfer.createdTime.seconds * 1000),
        sparkInvoice = transfer.sparkInvoice.takeIf { it.isNotEmpty() },
    )
}

/** Compute next cpfp and direct sequences from a refund tx. */
internal fun computeNextSequences(refundTxData: ByteArray): Pair<UInt, UInt> {
    val rawSequence = parseSequenceFromRawTx(refundTxData)
    val currentTimelock = rawSequence and 0xFFFFu
    val bit30 = rawSequence and (1u shl 30)
    // A leaf at the timelock floor cannot be moved again until it is renewed by the
    // operators, and UInt subtraction would silently wrap to a garbage sequence instead of
    // failing. Strictly greater: the coordinator rejects a decrement that reaches zero
    // ("too small to subtract TimeLockInterval without reaching zero").
    if (currentTimelock <= SPARK_TIME_LOCK_INTERVAL.toUInt()) {
        throw SparkError.LeafTimelockExhausted(
            "Leaf timelock exhausted ($currentTimelock <= $SPARK_TIME_LOCK_INTERVAL); needs renewal before it can move"
        )
    }
    val nextTimelock = currentTimelock - SPARK_TIME_LOCK_INTERVAL.toUInt()
    return (bit30 or nextTimelock) to (bit30 or (nextTimelock + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt()))
}

/**
 * Whether the leaf's refund timelock still has room to decrement — i.e. the leaf can be
 * transferred/swapped without operator renewal. Strictly greater: the coordinator rejects
 * decrements that reach zero.
 */
internal fun timelockCanDecrement(refundTxData: ByteArray): Boolean {
    // An unparseable refund tx is treated as exhausted: the leaf is skipped rather than
    // crashing the caller or being handed to the coordinator with a bogus sequence.
    val sequence = try {
        parseSequenceFromRawTx(refundTxData)
    } catch (_: SparkError) {
        return false
    }
    return (sequence and 0xFFFFu) > SPARK_TIME_LOCK_INTERVAL.toUInt()
}

/**
 * nSequence of the first input of a raw Bitcoin transaction (where Spark keeps leaf timelocks).
 *
 * @throws SparkError.MalformedTransaction when the bytes are not a well-formed transaction.
 */
internal fun parseSequenceFromRawTx(rawTx: ByteArray): UInt = RawTransaction.parse(rawTx, context = "leaf tx").firstInputSequence
