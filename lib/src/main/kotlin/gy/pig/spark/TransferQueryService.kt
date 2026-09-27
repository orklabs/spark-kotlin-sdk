package gy.pig.spark

import com.google.protobuf.ByteString
import spark.Spark
import java.util.Date

public suspend fun SparkWallet.getTransfer(id: String): SparkTransfer {
    val transfers = getTransfers(ids = listOf(id))
    return transfers.firstOrNull()
        ?: throw SparkError.InvalidResponse("Transfer not found: $id")
}

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

    if (ids.isNotEmpty()) {
        filterBuilder.addAllTransferIds(ids)
    }
    if (limit > 0) filterBuilder.limit = limit
    if (offset > 0) filterBuilder.offset = offset

    val response = stub.queryAllTransfers(filterBuilder.build())

    return response.transfersList.map { transfer ->
        SparkTransfer(
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
}
