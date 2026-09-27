package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark

/** Get a single transfer by its ID, from the operators' by-id lookup; the id may be in any case. */
public suspend fun SparkWallet.getTransfer(id: String): SparkTransfer = queryTransferById(id).toSparkTransfer()

/**
 * Get transfers with optional filters.
 *
 * Without [ids], only the transfers a user makes are listed, as the reference SDK lists them:
 * Spark transfers, Lightning payments (preimage swaps), cooperative exits and static deposit
 * claims (UTXO swaps). The legs of leaf swaps are left out; the operators also answer that query
 * in well under a second, where an unfiltered one took them 17 s to over a minute for a wallet
 * with a long history.
 *
 * @param ids Filter by specific transfer IDs (empty = all), of any type.
 * @param direction Filter by sent/received/both (default: both).
 * @param limit Max results (0 = server default).
 * @param offset Pagination offset.
 */
public suspend fun SparkWallet.getTransfers(
    ids: List<String> = emptyList(),
    direction: TransferDirection = TransferDirection.BOTH,
    limit: Long = 0,
    offset: Long = 0,
): List<SparkTransfer> {
    val stub = getCoordinatorStub()

    val filterBuilder = Spark.TransferFilter.newBuilder()
        .setNetwork(config.network.toProto())

    when (direction) {
        TransferDirection.SENT ->
            filterBuilder.senderIdentityPublicKey = ByteString.copyFrom(signer.identityPublicKey)
        TransferDirection.RECEIVED ->
            filterBuilder.receiverIdentityPublicKey = ByteString.copyFrom(signer.identityPublicKey)
        TransferDirection.BOTH ->
            filterBuilder.senderOrReceiverIdentityPublicKey = ByteString.copyFrom(signer.identityPublicKey)
    }

    if (ids.isEmpty()) {
        filterBuilder.addAllTypes(LISTED_TRANSFER_TYPES)
    } else {
        filterBuilder.addAllTransferIds(ids)
    }
    if (limit > 0) filterBuilder.limit = limit
    if (offset > 0) filterBuilder.offset = offset

    val response = stub.queryAllTransfers(filterBuilder.build())
    return response.transfersList.map { it.toSparkTransfer() }
}

/** The transfer types [getTransfers] lists: the reference SDK's `getTransfers` types. */
internal val LISTED_TRANSFER_TYPES: List<Spark.TransferType> = listOf(
    Spark.TransferType.COOPERATIVE_EXIT,
    Spark.TransferType.PREIMAGE_SWAP,
    Spark.TransferType.UTXO_SWAP,
    Spark.TransferType.TRANSFER,
)
