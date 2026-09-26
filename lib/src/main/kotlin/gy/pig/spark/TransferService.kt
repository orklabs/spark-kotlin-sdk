package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import spark.Spark
import uniffi.spark_frost.*
import java.util.Date
import java.util.UUID

suspend fun SparkWallet.send(receiverIdentityPublicKey: ByteArray, amountSats: Long,): SparkTransfer {
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

fun selectLeaves(leaves: List<SparkLeaf>, amountSats: Long): List<SparkLeaf> {
    val sorted = leaves.sortedByDescending { it.valueSats }
    val selected = mutableListOf<SparkLeaf>()
    var total = 0L
    for (leaf in sorted) {
        selected.add(leaf)
        total += leaf.valueSats
        if (total >= amountSats) return selected
    }
    throw SparkError.InsufficientBalance(need = amountSats, have = total)
}

/** Compute next cpfp and direct sequences from a refund tx. */
fun computeNextSequences(refundTxData: ByteArray): Pair<UInt, UInt> {
    val rawSequence = parseSequenceFromRawTx(refundTxData)
    val currentTimelock = rawSequence and 0xFFFFu
    val bit30 = rawSequence and (1u shl 30)
    // At or below one interval the decrement reaches zero, which operators reject —
    // and UInt subtraction would silently wrap to a garbage sequence instead of
    // failing. The leaf is frozen until renew_leaf resets its refund timelock.
    if (currentTimelock <= SPARK_TIME_LOCK_INTERVAL.toUInt()) {
        throw SparkError.LeafTimelockExhausted(
            "Leaf timelock exhausted ($currentTimelock <= $SPARK_TIME_LOCK_INTERVAL); needs renewal before it can move"
        )
    }
    val nextTimelock = (currentTimelock - SPARK_TIME_LOCK_INTERVAL.toUInt()) and 0xFFFFu
    val directTimelock = (nextTimelock + SPARK_DIRECT_TIMELOCK_OFFSET.toUInt()) and 0xFFFFu
    return (bit30 or nextTimelock) to (bit30 or directTimelock)
}

/** Whether a leaf's refund timelock can still be decremented by one interval. */
fun timelockCanDecrement(refundTxData: ByteArray): Boolean = (parseSequenceFromRawTx(refundTxData) and 0xFFFFu) > SPARK_TIME_LOCK_INTERVAL.toUInt()

/** Parse sequence (nSequence) from the first input of a raw transaction. */
fun parseSequenceFromRawTx(rawTx: ByteArray): UInt {
    if (rawTx.size < 10) return 0u
    var offset = 4 // skip version

    // Check for segwit marker
    if (rawTx[offset] == 0x00.toByte()) {
        offset += 2 // skip marker + flag
    }

    // Read input count (varint)
    val (_, varIntSize) = readVarInt(rawTx, offset)
    offset += varIntSize

    // Skip previous outpoint (32 bytes txid + 4 bytes vout)
    offset += 36

    // Read script length (varint) and skip script
    val (scriptLen, scriptVarIntSize) = readVarInt(rawTx, offset)
    offset += scriptVarIntSize + scriptLen.toInt()

    // Read sequence (4 bytes, little-endian)
    if (offset + 4 > rawTx.size) return 0u
    return ((rawTx[offset].toInt() and 0xFF).toUInt()) or
        ((rawTx[offset + 1].toInt() and 0xFF).toUInt() shl 8) or
        ((rawTx[offset + 2].toInt() and 0xFF).toUInt() shl 16) or
        ((rawTx[offset + 3].toInt() and 0xFF).toUInt() shl 24)
}

internal fun readVarInt(data: ByteArray, offset: Int): Pair<Long, Int> {
    val first = data[offset].toInt() and 0xFF
    return when {
        first < 0xFD -> first.toLong() to 1
        first == 0xFD -> {
            val v = (
                (data[offset + 1].toInt() and 0xFF) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8)
                ).toLong()
            v to 3
        }
        first == 0xFE -> {
            val v = (
                (data[offset + 1].toInt() and 0xFF) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    ((data[offset + 3].toInt() and 0xFF) shl 16) or
                    ((data[offset + 4].toInt() and 0xFF) shl 24)
                ).toLong()
            v to 5
        }
        else -> {
            var v = 0L
            for (i in 1..8) {
                v = v or ((data[offset + i].toLong() and 0xFF) shl ((i - 1) * 8))
            }
            v to 9
        }
    }
}
