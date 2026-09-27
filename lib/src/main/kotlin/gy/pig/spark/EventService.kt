package gy.pig.spark

import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import spark.Spark
import java.util.Date

public suspend fun SparkWallet.subscribeToEvents(): Flow<SparkEvent> {
    val stub = getCoordinatorStub()

    val request = Spark.SubscribeToEventsRequest.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(signer.identityPublicKey))
        .build()

    return stub.subscribeToEvents(request).mapNotNull { response ->
        mapEvent(response)
    }
}

private fun mapEvent(response: Spark.SubscribeToEventsResponse): SparkEvent? = when {
    response.hasConnected() -> SparkEvent.Connected
    response.hasReceiverTransfer() ->
        SparkEvent.TransferReceived(mapTransfer(response.receiverTransfer.transfer))
    response.hasSenderTransfer() ->
        SparkEvent.TransferSent(mapTransfer(response.senderTransfer.transfer))
    response.hasDeposit() ->
        SparkEvent.DepositConfirmed(response.deposit.deposit.treeId)
    else -> null
}

private fun mapTransfer(t: Spark.Transfer): SparkTransfer = SparkTransfer(
    id = t.id,
    senderIdentityPublicKey = t.senderIdentityPublicKey.toByteArray().toHexString(),
    receiverIdentityPublicKey = t.receiverIdentityPublicKey.toByteArray().toHexString(),
    totalValueSats = t.totalValue,
    status = t.status.toString(),
    type = t.type.toString(),
    createdAt = Date(t.createdTime.seconds * 1000),
    sparkInvoice = t.sparkInvoice.takeIf { it.isNotEmpty() },
)
