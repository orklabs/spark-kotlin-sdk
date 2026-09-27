package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import org.json.JSONObject
import spark.Spark
import uniffi.spark_frost.*
import java.util.UUID

/** Get the SSP's fee estimate (fast exit) for withdrawing [leafIds] to [onChainAddress]. */
public suspend fun SparkWallet.getWithdrawalFeeEstimate(onChainAddress: String, leafIds: List<String>,): FeeQuote {
    val result = sspClient.executeRaw(
        query = GraphQLMutations.GET_FEE_ESTIMATE,
        variables = mapOf(
            "leaf_external_ids" to leafIds,
            "withdrawal_address" to onChainAddress,
        ),
    )

    return FeeQuote(feeSats = withdrawalFeeEstimateSats(result), feeRateSatsPerVbyte = 0)
}

/**
 * The SSP's fast-exit fee in sats: its user fee plus the L1 broadcast fee. Both must be whole,
 * non-negative numbers, and a sum that would overflow is refused instead of wrapping.
 */
internal fun withdrawalFeeEstimateSats(response: JSONObject): Long {
    val fast = response.optJSONObject("coop_exit_fee_estimates")?.optJSONObject("speed_fast")
    val userFee = fast?.optJSONObject("user_fee")?.let { wholeNonNegativeLong(it.opt("original_value")) }
    val l1Fee = fast?.optJSONObject("l1_broadcast_fee")?.let { wholeNonNegativeLong(it.opt("original_value")) }
    if (userFee == null || l1Fee == null) {
        throw SparkError.InvalidResponse("Invalid fee estimate response")
    }
    return try {
        Math.addExact(userFee, l1Fee)
    } catch (_: ArithmeticException) {
        throw SparkError.UntrustedResponse("SSP withdrawal fee estimate $userFee + $l1Fee sats is out of range")
    }
}

/**
 * Withdraw funds to an on-chain Bitcoin address via cooperative exit.
 *
 * The SSP's fee is deducted from [amountSats]: the recipient receives [amountSats] minus the
 * fee. Leaves are swapped to denominations that sum to exactly [amountSats] first, so no more
 * than the requested amount ever leaves the wallet. To send everything use [withdrawAll].
 *
 * Before anything is signed the SSP's response is verified: the exit transaction must hash
 * to the txid it reports, pay [onChainAddress] at least `amountSats - fee`, and the connector
 * transaction must spend it. A response that fails these checks throws
 * [SparkError.UntrustedResponse] and no leaves are handed over.
 *
 * @param onChainAddress Destination Bitcoin address on the wallet's network (P2PKH, P2SH,
 *   P2WPKH, P2WSH or P2TR).
 * @param amountSats Amount in sats to withdraw, fee included.
 * @param maxFeeSats Highest fee the caller accepts. When `null` the SSP's fee quote for the
 *   selected leaves is used as the bound. Throws [SparkError.FeeExceedsLimit] if the quote
 *   is above the cap.
 * @return The L1 transaction ID of the cooperative exit.
 */
public suspend fun SparkWallet.withdraw(onChainAddress: String, amountSats: Long, maxFeeSats: Long? = null): String {
    if (amountSats <= 0) {
        throw SparkError.InvalidArgument("withdrawal amount must be positive, got $amountSats")
    }
    // Fail fast on a malformed or wrong-network destination, before any leaf is moved.
    BitcoinAddress.scriptPubKey(onChainAddress, config.network)

    // Select leaves that sum to exactly the requested amount (swapping via the SSP if
    // needed): the SSP exits the full value of the leaves it is given.
    val selectedLeaves = selectLeavesWithSwap(amountSats)
    val selectedTotal = selectedLeaves.sumOf { it.valueSats }
    if (selectedTotal != amountSats) {
        throw SparkError.InvalidResponse("Selected leaves sum to $selectedTotal sats, expected exactly $amountSats")
    }

    // Bound the fee before asking the SSP to build the exit.
    val quote = getWithdrawalFeeEstimate(onChainAddress, selectedLeaves.map { it.id })
    val feeCap = CoopExitValidator.resolveFeeCap(quotedFeeSats = quote.feeSats, maxFeeSats = maxFeeSats, amountSats = amountSats)
    return performCooperativeExit(
        leaves = selectedLeaves,
        amountSats = amountSats,
        feeCap = feeCap,
        onChainAddress = onChainAddress,
    ).txid
}

/**
 * Everything [withdrawAll] would do, without doing it: claims pending inbound transfers,
 * renews renewable leaves, and quotes the SSP fee for every spendable leaf. Use it to show
 * the user what will move, what it costs, and what stays behind ([WithdrawAllQuote.frozenSats]).
 */
public suspend fun SparkWallet.quoteWithdrawAll(onChainAddress: String): WithdrawAllQuote {
    val plan = drainPlan(onChainAddress)
    return WithdrawAllQuote(
        spendableSats = plan.spendableSats,
        quotedFeeSats = plan.quotedFeeSats,
        frozenSats = plan.balance.frozen,
        lockedSats = plan.balance.locked,
        incomingSats = plan.balance.incoming,
        leafCount = plan.leaves.size,
    )
}

/**
 * Send every spendable sat to [onChainAddress] in one cooperative exit.
 *
 * Pending inbound transfers are claimed first and renewable leaves renewed, then every
 * spendable leaf is exited; the SSP's fee comes out of that amount. Leaves at the timelock
 * floor cannot be included: they are reported in the result as
 * [WithdrawAllResult.frozenSats], as are sats locked by in-flight operations and inbound sats
 * that could not be claimed. The same response verification and fee bound as [withdraw] apply.
 *
 * @param onChainAddress Destination Bitcoin address on the wallet's network.
 * @param maxFeeSats Highest fee the caller accepts; `null` uses the SSP's own quote.
 * @throws SparkError.InsufficientBalance when nothing is spendable.
 * @throws SparkError.FeeExceedsLimit when the fee would consume the whole balance or exceed the cap.
 */
public suspend fun SparkWallet.withdrawAll(onChainAddress: String, maxFeeSats: Long? = null): WithdrawAllResult {
    val plan = drainPlan(onChainAddress)
    if (plan.spendableSats <= 0) {
        throw SparkError.InsufficientBalance(need = 1, have = 0)
    }
    val feeCap = CoopExitValidator.resolveFeeCap(
        quotedFeeSats = plan.quotedFeeSats,
        maxFeeSats = maxFeeSats,
        amountSats = plan.spendableSats,
    )
    val exit = performCooperativeExit(
        leaves = plan.leaves,
        amountSats = plan.spendableSats,
        feeCap = feeCap,
        onChainAddress = onChainAddress,
    )
    return WithdrawAllResult(
        txid = exit.txid,
        sentSats = plan.spendableSats,
        payoutSats = exit.payoutSats,
        frozenSats = plan.balance.frozen,
        lockedSats = plan.balance.locked,
        unclaimedSats = plan.balance.incoming,
    )
}

private class DrainPlan(val leaves: List<SparkLeaf>, val balance: SatsBalance, val spendableSats: Long, val quotedFeeSats: Long)

/**
 * Shared prelude of [quoteWithdrawAll] and [withdrawAll]: validate the destination, claim
 * what is pending, renew what the coordinator will renew, and quote the fee for the rest.
 */
private suspend fun SparkWallet.drainPlan(onChainAddress: String): DrainPlan {
    BitcoinAddress.scriptPubKey(onChainAddress, config.network)
    // Claims are independent: whatever can be claimed is, and the rest is reported as incoming.
    bestEffort { claimPendingTransfers() }
    val leaves = getSpendableLeaves()
    val balance = getBalance().satsBalance
    val spendable = leaves.sumOf { it.valueSats }
    val quotedFee = if (spendable > 0) getWithdrawalFeeEstimate(onChainAddress, leaves.map { it.id }).feeSats else 0L
    return DrainPlan(leaves = leaves, balance = balance, spendableSats = spendable, quotedFeeSats = quotedFee)
}

internal class CooperativeExit(val txid: String, val payoutSats: Long)

/** The SSP's `request_coop_exit` answer, before verification. */
private class CoopExitOffer(val connectorTxHex: String, val coopExitTxHex: String, val coopExitTxid: String)

/** The user-signed connector refunds of a cooperative exit, one set per leaf. */
private class CoopExitRefundJobs(
    val cpfp: List<Spark.UserSignedTxSigningJob>,
    val direct: List<Spark.UserSignedTxSigningJob>,
    val directFromCpfp: List<Spark.UserSignedTxSigningJob>,
)

/**
 * The cooperative exit proper: request the exit from the SSP, verify what it built, sign the
 * connector refunds, hand the leaves over in one transfer package, complete via the SSP.
 * [leaves] must sum to [amountSats]; the payout must be at least `amountSats - feeCap`.
 */
private suspend fun SparkWallet.performCooperativeExit(leaves: List<SparkLeaf>, amountSats: Long, feeCap: Long, onChainAddress: String,): CooperativeExit {
    val stub = getCoordinatorStub()
    val receiverPubKey = config.sspIdentityPublicKey

    // Step 1: Request coop exit from SSP — get the exit and connector transactions
    val transferID = UUID.randomUUID().toString().lowercase()
    val offer = requestCoopExit(leaves.map { it.id }, onChainAddress, transferID)

    // Verify what the SSP built before signing anything.
    val validated = CoopExitValidator.validate(
        rawCoopExitTransactionHex = offer.coopExitTxHex,
        rawConnectorTransactionHex = offer.connectorTxHex,
        coopExitTxidHex = offer.coopExitTxid,
        payoutAddress = onChainAddress,
        minimumPayoutSats = amountSats - feeCap,
        leafCount = leaves.size,
        network = config.network,
    )
    val connectorTxBytes = offer.connectorTxHex.hexToBytesOrNull()
        ?: throw SparkError.InvalidResponse("Invalid connector tx hex")

    // Steps 2-3: refund transactions that also spend a connector output, FROST-signed by the user
    val refundJobs = signConnectorRefunds(stub, leaves, validated.connectorTx, receiverPubKey)

    // Step 4: Key tweaks handing the leaves to the SSP, encrypted per operator and signed
    val soListResponse = stub.getSigningOperatorList(Empty.getDefaultInstance())
    val (_, tweakPackage) = KeyTweakHelper.buildSendPackage(
        transferID = transferID,
        leaves = leaves,
        receiverPubKey = receiverPubKey,
        signer = signer,
        soOperators = soListResponse.signingOperatorsMap,
        signingOperatorConfigs = config.signingOperators,
        threshold = config.signingThreshold,
    )

    val transferPackageBuilder = Spark.TransferPackage.newBuilder()
        .setHashVariant(Spark.HashVariant.HASH_VARIANT_V2)
        .addAllLeavesToSend(refundJobs.cpfp)
        .addAllDirectLeavesToSend(refundJobs.direct)
        .addAllDirectFromCpfpLeavesToSend(refundJobs.directFromCpfp)
        .setUserSignature(ByteString.copyFrom(tweakPackage.signature))
    for ((soID, cipher) in tweakPackage.keyTweakPackage) {
        transferPackageBuilder.putKeyTweakPackage(soID, ByteString.copyFrom(cipher))
    }

    // Step 5: cooperative_exit_v2 with the transfer package. The coordinator no longer accepts
    // the older form (unsigned jobs plus a separate finalize call): mainnet rejects it with
    // "transfer_package is required for cooperative exit".
    val transferRequest = Spark.StartTransferRequest.newBuilder()
        .setTransferId(transferID)
        .setOwnerIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .setReceiverIdentityPublicKey(ByteString.copyFrom(receiverPubKey))
        .setExpiryTime(
            Timestamp.newBuilder()
                .setSeconds((System.currentTimeMillis() / 1000) + 7 * 24 * 60 * 60 + 300)
                .build(),
        )
        .setTransferPackage(transferPackageBuilder.build())
        .build()

    val exitRequest = Spark.CooperativeExitRequest.newBuilder()
        .setTransfer(transferRequest)
        .setExitId(UUID.randomUUID().toString().lowercase())
        .setExitTxid(ByteString.copyFrom(validated.exitTxid))
        .setConnectorTx(ByteString.copyFrom(connectorTxBytes))
        .build()

    val exitResponse = stub.cooperativeExitV2(exitRequest)
    if (!exitResponse.hasTransfer()) {
        throw SparkError.InvalidResponse("cooperative_exit_v2 returned no transfer")
    }

    // Step 6: Complete coop exit via SSP
    sspClient.executeRaw(
        query = GraphQLMutations.COMPLETE_COOP_EXIT,
        variables = mapOf(
            "user_outbound_transfer_external_id" to exitResponse.transfer.id,
        ),
    )

    val payout = validated.payoutSats
    return CooperativeExit(
        txid = offer.coopExitTxid,
        payoutSats = if (payout > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else payout.toLong(),
    )
}

/** Ask the SSP to build the exit and connector transactions for [leafIds]. Nothing is verified here. */
private suspend fun SparkWallet.requestCoopExit(leafIds: List<String>, onChainAddress: String, transferID: String): CoopExitOffer {
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
    val connectorTxHex = request?.stringOrNull("raw_connector_transaction")
    val coopExitTxHex = request?.stringOrNull("raw_coop_exit_transaction")
    val coopExitTxid = request?.stringOrNull("coop_exit_txid")
    if (connectorTxHex == null || coopExitTxHex == null || coopExitTxid == null) {
        throw SparkError.InvalidResponse("Invalid coop exit response")
    }
    return CoopExitOffer(connectorTxHex = connectorTxHex, coopExitTxHex = coopExitTxHex, coopExitTxid = coopExitTxid)
}

/**
 * SO nonce commitments, three per leaf (cpfp, direct, directFromCpfp) laid out like the
 * transfer flow, and the user's FROST signature on each connector refund of [connectorTx].
 */
private suspend fun SparkWallet.signConnectorRefunds(
    stub: spark.SparkServiceGrpcKt.SparkServiceCoroutineStub,
    leaves: List<SparkLeaf>,
    connectorTx: RawTransaction,
    receiverPubKey: ByteArray,
): CoopExitRefundJobs {
    val commitmentsRequest = Spark.GetSigningCommitmentsRequest.newBuilder()
        .setCount(3)
        .addAllNodeIds(leaves.map { it.id })
        .build()
    val allCommitments = stub.getSigningCommitments(commitmentsRequest).signingCommitmentsList
    if (allCommitments.size < 3 * leaves.size) {
        throw SparkError.InvalidResponse("Got ${allCommitments.size} signing commitments, need ${3 * leaves.size}")
    }

    val connectorTxid = connectorTx.txid
    val cpfpJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    val directFromCpfpJobs = mutableListOf<Spark.UserSignedTxSigningJob>()
    for ((i, leaf) in leaves.withIndex()) {
        val node = leaf.node ?: throw SparkError.InvalidResponse("Leaf ${leaf.id} missing node data")
        val signingKey = signer.deriveLeafSigningKey(leaf.id)
        val verifyingKey = node.verifyingPublicKey.toByteArray()
        val refunds = buildConnectorRefunds(
            node = node,
            receiverPubKey = receiverPubKey,
            connectorTxid = connectorTxid,
            connectorTx = connectorTx,
            connectorVout = i.toUInt(),
            network = config.network.networkString,
        )
        fun job(refund: ConnectorRefund, commitmentIndex: Int) = FrostSigningHelper.buildSigningJob(
            leafID = leaf.id,
            signingKey = signingKey,
            verifyingKey = verifyingKey,
            rawTx = refund.tx,
            sighash = refund.sighash,
            soCommitments = allCommitments[commitmentIndex].signingNonceCommitmentsMap,
        )
        cpfpJobs.add(job(refunds.cpfp, i))
        refunds.direct?.let { directJobs.add(job(it, i + leaves.size)) }
        directFromCpfpJobs.add(job(refunds.directFromCpfp, i + 2 * leaves.size))
    }
    return CoopExitRefundJobs(cpfp = cpfpJobs, direct = directJobs, directFromCpfp = directFromCpfpJobs)
}

// MARK: - Connector refunds

internal class ConnectorRefund(val tx: ByteArray, val sighash: ByteArray)

internal class ConnectorRefunds(
    val cpfp: ConnectorRefund,
    /** Absent for zero-timelock nodes and leaves without a direct node transaction. */
    val direct: ConnectorRefund?,
    val directFromCpfp: ConnectorRefund,
)

/**
 * The leaf's next refund transactions with the connector output appended as a second input,
 * and their two-input sighashes (BIP-341, prevouts = node output + connector output). This is
 * what the user signs for a cooperative exit; mirrors the reference SDK's
 * `createConnectorRefundTxs` + `signRefundsForCoopExit`.
 */
internal fun buildConnectorRefunds(
    node: Spark.TreeNode,
    receiverPubKey: ByteArray,
    connectorTxid: ByteArray,
    connectorTx: RawTransaction,
    connectorVout: UInt,
    network: String,
): ConnectorRefunds {
    val (cpfpSequence, directSequence) = computeNextSequences(node.refundTx.toByteArray())
    val cpfpNodeTx = node.nodeTx.toByteArray()
    val isZeroNode = isZeroTimelockNode(cpfpNodeTx)
    val directNodeTx: ByteArray? = if (node.directTx.isEmpty || isZeroNode) null else node.directTx.toByteArray()

    val trio = constructRefundTxTrio(
        cpfpNodeTx = cpfpNodeTx,
        directNodeTx = directNodeTx,
        vout = 0u,
        receivingPubkey = receiverPubKey,
        network = network,
        sequence = cpfpSequence,
        directSequence = directSequence,
        feeSats = SPARK_DEFAULT_FEE_SATS.toULong(),
    )
    val connectorOutput = connectorTx.output(connectorVout)
    val connectorInput = RawTransaction.Input(previousTxid = connectorTxid, previousIndex = connectorVout)
    val nodeOutput = RawTransaction.parse(cpfpNodeTx, context = "node tx").output(0u)

    fun withConnector(refundTx: ByteArray, spending: RawTransaction.Output): ConnectorRefund {
        val tx = addInputToRawTx(refundTx, connectorInput)
        val sighash = computeMultiInputSighashUniffi(
            tx = tx,
            inputIndex = 0u,
            prevOutScripts = listOf(spending.scriptPubKey, connectorOutput.scriptPubKey),
            prevOutValues = listOf(spending.value, connectorOutput.value),
        )
        return ConnectorRefund(tx = tx, sighash = sighash)
    }

    val cpfp = withConnector(trio.cpfpRefund.tx, nodeOutput)
    val directRefund = trio.directRefund
    val direct = if (directRefund != null && directNodeTx != null) {
        val directOutput = RawTransaction.parse(directNodeTx, context = "direct node tx").output(0u)
        withConnector(directRefund.tx, directOutput)
    } else {
        null
    }
    val directFromCpfp = withConnector(trio.directFromCpfpRefund.tx, nodeOutput)
    return ConnectorRefunds(cpfp = cpfp, direct = direct, directFromCpfp = directFromCpfp)
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
