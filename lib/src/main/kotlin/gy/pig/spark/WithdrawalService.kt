package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import spark.Spark
import uniffi.spark_frost.*
import java.util.UUID

suspend fun SparkWallet.getWithdrawalFeeEstimate(onChainAddress: String, leafIds: List<String>,): FeeQuote {
    val result = sspClient.executeRaw(
        query = GraphQLMutations.GET_FEE_ESTIMATE,
        variables = mapOf(
            "leaf_external_ids" to leafIds,
            "withdrawal_address" to onChainAddress,
        ),
    )

    val speedFast = result.getJSONObject("coop_exit_fee_estimates").getJSONObject("speed_fast")
    val userFee = speedFast.getJSONObject("user_fee").getLong("original_value")
    val l1Fee = speedFast.getJSONObject("l1_broadcast_fee").getLong("original_value")

    return FeeQuote(
        feeSats = userFee + l1Fee,
        feeRateSatsPerVbyte = 0,
    )
}

private data class WithdrawLeafSigningData(
    val leafId: String,
    val signingKey: ByteArray,
    val verifyingKey: ByteArray,
    val cpfpRefundTx: ByteArray,
    val directRefundTx: ByteArray?,
    val directFromCpfpRefundTx: ByteArray,
    val cpfpNonce: NonceResult,
    val directNonce: NonceResult,
    val directFromCpfpNonce: NonceResult,
    val cpfpNodeTx: ByteArray,
    val directNodeTx: ByteArray?,
    val connectorOutputIndex: Int,
)

/**
 * Withdraw funds to an on-chain Bitcoin address via cooperative exit.
 * Fee is deducted from the withdrawal amount.
 *
 * @param onChainAddress Bitcoin address to withdraw to
 * @param amountSats Amount in sats to withdraw (fee will be deducted from this)
 * @return The L1 transaction ID
 */
suspend fun SparkWallet.withdraw(onChainAddress: String, amountSats: Long,): String {
    val stub = getCoordinatorStub()
    val networkStr = config.network.networkString

    // Step 0: Select leaves (with swap if needed — never overspend)
    val selectedLeaves = selectLeavesWithSwap(amountSats)
    val leafIds = selectedLeaves.map { it.id }

    // Step 1: Request coop exit from SSP — get connector tx
    val transferID = UUID.randomUUID().toString().lowercase()

    val sspResponse = sspClient.executeRaw(
        query = GraphQLMutations.REQUEST_COOP_EXIT,
        variables = mapOf(
            "leaf_external_ids" to leafIds,
            "withdrawal_address" to onChainAddress,
            "exit_speed" to "FAST",
            "withdraw_all" to true,
            "user_outbound_transfer_external_id" to transferID,
        ),
    )

    val request = sspResponse.optJSONObject("request_coop_exit")?.optJSONObject("request")
        ?: throw SparkError.InvalidResponse("Invalid coop exit response")
    val connectorTxHex = request.optString("raw_connector_transaction", "")
    val coopExitTxid = request.optString("coop_exit_txid", "")
    val connectorTxBytes = connectorTxHex.hexToBytesOrNull()
    if (connectorTxBytes == null || connectorTxBytes.isEmpty() || coopExitTxid.isEmpty()) {
        throw SparkError.InvalidResponse("Missing or malformed connector tx or coop exit txid")
    }
    val connectorTxId = computeTxId(connectorTxBytes)

    // Step 2: Build LeafRefundTxSigningJobs with connector inputs
    val receiverPubKey = config.sspIdentityPublicKey
    val expiryTime = Timestamp.newBuilder()
        .setSeconds((System.currentTimeMillis() / 1000) + 7 * 24 * 60 * 60 + 300)
        .build()

    val signingJobs = mutableListOf<Spark.LeafRefundTxSigningJob>()
    val leafDataList = mutableListOf<WithdrawLeafSigningData>()

    for (i in selectedLeaves.indices) {
        val leaf = selectedLeaves[i]
        val node = leaf.node ?: throw SparkError.InvalidResponse("Leaf ${leaf.id} missing node data")
        val signingKey = signer.deriveLeafSigningKey(leaf.id)
        val signingPubKey = getPublicKeyBytes(signingKey, true)
        val verifyingKey = node.verifyingPublicKey.toByteArray()

        val (cpfpSequence, directSequence) = computeNextSequences(node.refundTx.toByteArray())

        val cpfpNodeTx = node.nodeTx.toByteArray()
        val directNodeTx = if (node.directTx.isEmpty) null else node.directTx.toByteArray()

        val isZeroNode = isZeroTimelockNode(cpfpNodeTx)

        val refundTrio = constructRefundTxTrio(
            cpfpNodeTx = cpfpNodeTx,
            directNodeTx = directNodeTx,
            vout = 0u,
            receivingPubkey = receiverPubKey,
            network = networkStr,
            sequence = cpfpSequence,
            directSequence = directSequence,
            feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
        )

        // Add connector input to each refund tx
        val connectorInput = RawTransaction.Input(previousTxid = connectorTxId, previousIndex = i.toUInt())
        val cpfpRefundWithConnector = addInputToRawTx(refundTrio.cpfpRefund.tx, connectorInput)

        var directRefundWithConnector: ByteArray? = null
        if (refundTrio.directRefund != null && !isZeroNode) {
            directRefundWithConnector = addInputToRawTx(refundTrio.directRefund!!.tx, connectorInput)
        }

        val directFromCpfpRefundWithConnector = addInputToRawTx(refundTrio.directFromCpfpRefund.tx, connectorInput)

        // Generate FROST nonce commitments
        val keyPackage = KeyPackage(secretKey = signingKey, publicKey = signingPubKey, verifyingKey = verifyingKey)
        val cpfpNonce = frostNonce(keyPackage)
        val directNonce = frostNonce(keyPackage)
        val directFromCpfpNonce = frostNonce(keyPackage)

        // Build SigningJob for each refund tx
        val cpfpSigningJob = Spark.SigningJob.newBuilder()
            .setSigningPublicKey(ByteString.copyFrom(signingPubKey))
            .setRawTx(ByteString.copyFrom(cpfpRefundWithConnector))
            .setSigningNonceCommitment(
                common.Common.SigningCommitment.newBuilder()
                    .setHiding(ByteString.copyFrom(cpfpNonce.commitment.hiding))
                    .setBinding(ByteString.copyFrom(cpfpNonce.commitment.binding))
                    .build()
            )
            .build()

        val directFromCpfpSigningJob = Spark.SigningJob.newBuilder()
            .setSigningPublicKey(ByteString.copyFrom(signingPubKey))
            .setRawTx(ByteString.copyFrom(directFromCpfpRefundWithConnector))
            .setSigningNonceCommitment(
                common.Common.SigningCommitment.newBuilder()
                    .setHiding(ByteString.copyFrom(directFromCpfpNonce.commitment.hiding))
                    .setBinding(ByteString.copyFrom(directFromCpfpNonce.commitment.binding))
                    .build()
            )
            .build()

        val leafJobBuilder = Spark.LeafRefundTxSigningJob.newBuilder()
            .setLeafId(leaf.id)
            .setRefundTxSigningJob(cpfpSigningJob)
            .setDirectFromCpfpRefundTxSigningJob(directFromCpfpSigningJob)

        if (directRefundWithConnector != null) {
            val directSigningJob = Spark.SigningJob.newBuilder()
                .setSigningPublicKey(ByteString.copyFrom(signingPubKey))
                .setRawTx(ByteString.copyFrom(directRefundWithConnector))
                .setSigningNonceCommitment(
                    common.Common.SigningCommitment.newBuilder()
                        .setHiding(ByteString.copyFrom(directNonce.commitment.hiding))
                        .setBinding(ByteString.copyFrom(directNonce.commitment.binding))
                        .build()
                )
                .build()
            leafJobBuilder.setDirectRefundTxSigningJob(directSigningJob)
        }

        signingJobs.add(leafJobBuilder.build())

        leafDataList.add(
            WithdrawLeafSigningData(
                leafId = leaf.id,
                signingKey = signingKey,
                verifyingKey = verifyingKey,
                cpfpRefundTx = cpfpRefundWithConnector,
                directRefundTx = directRefundWithConnector,
                directFromCpfpRefundTx = directFromCpfpRefundWithConnector,
                cpfpNonce = cpfpNonce,
                directNonce = directNonce,
                directFromCpfpNonce = directFromCpfpNonce,
                cpfpNodeTx = cpfpNodeTx,
                directNodeTx = directNodeTx,
                connectorOutputIndex = i,
            )
        )
    }

    // Step 3: Call cooperative_exit_v2 with unsigned refund txs
    val coopExitTxidBytes = txidBytesFromDisplayHex(coopExitTxid)

    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(expiryTime)
        .addAllLeavesToSend(signingJobs)
        .build()

    val exitReq = Spark.CooperativeExitRequest.newBuilder()
        .setTransfer(transferRequest)
        .setExitId(UUID.randomUUID().toString().lowercase())
        .setExitTxid(ByteString.copyFrom(coopExitTxidBytes))
        .setConnectorTx(ByteString.copyFrom(connectorTxBytes))
        .build()

    val exitResponse = stub.cooperativeExitV2(exitReq)

    // Step 4: Sign FROST with SO signing results and aggregate
    val cpfpSignatures = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directSignatures = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directFromCpfpSignatures = mutableListOf<Spark.UserSignedTxSigningJob>()

    for (result in exitResponse.signingResultsList) {
        val leafData = leafDataList.firstOrNull { it.leafId == result.leafId }
            ?: throw SparkError.InvalidResponse("Signing result for unknown leaf ${result.leafId}")

        val connectorPrevOut = parseTxOutput(connectorTxBytes, leafData.connectorOutputIndex.toUInt())

        // Sign CPFP refund
        val cpfpNodeOutput = parseTxOutput(leafData.cpfpNodeTx, 0u)
        val cpfpSighash = computeMultiInputSighashUniffi(
            tx = leafData.cpfpRefundTx,
            inputIndex = 0u,
            prevOutScripts = listOf(cpfpNodeOutput.scriptPubKey, connectorPrevOut.scriptPubKey),
            prevOutValues = listOf(cpfpNodeOutput.value, connectorPrevOut.value),
        )

        val cpfpAgg = signAndAggregateFrost(
            sighash = cpfpSighash,
            signingKey = leafData.signingKey,
            verifyingKey = leafData.verifyingKey,
            nonce = leafData.cpfpNonce,
            signingResult = result.refundTxSigningResult,
        )

        cpfpSignatures.add(
            Spark.UserSignedTxSigningJob.newBuilder()
                .setLeafId(result.leafId)
                .setSigningPublicKey(ByteString.copyFrom(getPublicKeyBytes(leafData.signingKey, true)))
                .setRawTx(ByteString.copyFrom(leafData.cpfpRefundTx))
                .setUserSignature(ByteString.copyFrom(cpfpAgg))
                .build()
        )

        // Sign direct refund (if exists)
        if (leafData.directRefundTx != null && leafData.directNodeTx != null && result.hasDirectRefundTxSigningResult()) {
            val directNodeOutput = parseTxOutput(leafData.directNodeTx, 0u)
            val directSighash = computeMultiInputSighashUniffi(
                tx = leafData.directRefundTx,
                inputIndex = 0u,
                prevOutScripts = listOf(directNodeOutput.scriptPubKey, connectorPrevOut.scriptPubKey),
                prevOutValues = listOf(directNodeOutput.value, connectorPrevOut.value),
            )

            val directAgg = signAndAggregateFrost(
                sighash = directSighash,
                signingKey = leafData.signingKey,
                verifyingKey = leafData.verifyingKey,
                nonce = leafData.directNonce,
                signingResult = result.directRefundTxSigningResult,
            )

            directSignatures.add(
                Spark.UserSignedTxSigningJob.newBuilder()
                    .setLeafId(result.leafId)
                    .setSigningPublicKey(ByteString.copyFrom(getPublicKeyBytes(leafData.signingKey, true)))
                    .setRawTx(ByteString.copyFrom(leafData.directRefundTx))
                    .setUserSignature(ByteString.copyFrom(directAgg))
                    .build()
            )
        }

        // Sign directFromCpfp refund
        val dcfpSighash = computeMultiInputSighashUniffi(
            tx = leafData.directFromCpfpRefundTx,
            inputIndex = 0u,
            prevOutScripts = listOf(cpfpNodeOutput.scriptPubKey, connectorPrevOut.scriptPubKey),
            prevOutValues = listOf(cpfpNodeOutput.value, connectorPrevOut.value),
        )

        val dcfpAgg = signAndAggregateFrost(
            sighash = dcfpSighash,
            signingKey = leafData.signingKey,
            verifyingKey = leafData.verifyingKey,
            nonce = leafData.directFromCpfpNonce,
            signingResult = result.directFromCpfpRefundTxSigningResult,
        )

        directFromCpfpSignatures.add(
            Spark.UserSignedTxSigningJob.newBuilder()
                .setLeafId(result.leafId)
                .setSigningPublicKey(ByteString.copyFrom(getPublicKeyBytes(leafData.signingKey, true)))
                .setRawTx(ByteString.copyFrom(leafData.directFromCpfpRefundTx))
                .setUserSignature(ByteString.copyFrom(dcfpAgg))
                .build()
        )
    }

    // Step 5: Prepare key tweaks (transfer leaves to SSP)
    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val soOperators = soListResponse.signingOperatorsMap

    val (_, tweakPackage) = KeyTweakHelper.buildSendPackage(
        transferID = transferID,
        leaves = selectedLeaves,
        receiverPubKey = receiverPubKey,
        signer = signer,
        soOperators = soOperators,
        signingOperatorConfigs = config.signingOperators,
    )

    val transferPackageBuilder = Spark.TransferPackage.newBuilder()
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToSend(cpfpSignatures)
        .addAllDirectLeavesToSend(directSignatures)
        .addAllDirectFromCpfpLeavesToSend(directFromCpfpSignatures)
        .setUserSignature(ByteString.copyFrom(tweakPackage.signature))
    for ((soID, cipher) in tweakPackage.keyTweakPackage) {
        transferPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }

    // Step 6: Finalize transfer with transfer package
    val finalizeReq = Spark.FinalizeTransferWithTransferPackageRequest.newBuilder()
        .setTransferId(exitResponse.transfer.id)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setTransferPackage(transferPackageBuilder.build())
        .build()

    stub.finalizeTransferWithTransferPackage(finalizeReq)

    // Step 7: Complete coop exit via SSP
    sspClient.executeRaw(
        query = GraphQLMutations.COMPLETE_COOP_EXIT,
        variables = mapOf(
            "user_outbound_transfer_external_id" to exitResponse.transfer.id,
        ),
    )

    return coopExitTxid
}

// MARK: - FROST signing helpers

private fun signAndAggregateFrost(
    sighash: ByteArray,
    signingKey: ByteArray,
    verifyingKey: ByteArray,
    nonce: NonceResult,
    signingResult: Spark.SigningResult,
): ByteArray {
    val selfPublicKey = getPublicKeyBytes(signingKey, true)
    val keyPackage = KeyPackage(
        secretKey = signingKey,
        publicKey = selfPublicKey,
        verifyingKey = verifyingKey,
    )

    val nativeCommitments = signingResult.signingNonceCommitmentsMap.mapValues { (_, c) ->
        SigningCommitment(hiding = c.hiding.toByteArray(), binding = c.binding.toByteArray())
    }

    val selfSignature = signFrost(
        msg = sighash,
        keyPackage = keyPackage,
        nonce = nonce.nonce,
        selfCommitment = nonce.commitment,
        statechainCommitments = nativeCommitments,
        adaptorPublicKey = null,
    )

    val soSignatures = signingResult.signatureSharesMap.mapValues { it.value.toByteArray() }
    val soPublicKeys = signingResult.publicKeysMap.mapValues { it.value.toByteArray() }

    return aggregateFrost(
        msg = sighash,
        statechainCommitments = nativeCommitments,
        selfCommitment = nonce.commitment,
        statechainSignatures = soSignatures,
        selfSignature = selfSignature,
        statechainPublicKeys = soPublicKeys,
        selfPublicKey = selfPublicKey,
        verifyingKey = verifyingKey,
        adaptorPublicKey = null,
    )
}

// MARK: - Raw tx helpers (bounds-checked, see RawTransaction)

/** Transaction id in internal byte order (the form used in input prevouts). */
internal fun computeTxId(rawTx: ByteArray): ByteArray = RawTransaction.parse(rawTx).txid

/** The output (script + value) of a raw transaction at [vout]. */
internal fun parseTxOutput(rawTx: ByteArray, vout: UInt): RawTransaction.Output = RawTransaction.parse(rawTx).output(vout)

/** Check if a node tx has zero timelock (sequence & 0xFFFF == 0). */
internal fun isZeroTimelockNode(nodeTx: ByteArray): Boolean = (parseSequenceFromRawTx(nodeTx) and 0xFFFFu) == 0u

/**
 * Append an input to a raw transaction, preserving its serialisation format. A witness
 * transaction gets an empty witness stack for the new input.
 */
internal fun addInputToRawTx(rawTx: ByteArray, input: RawTransaction.Input): ByteArray {
    val tx = RawTransaction.parse(rawTx, context = "refund tx")
    return tx.copy(inputs = tx.inputs + input).serialized(includeWitness = true)
}
