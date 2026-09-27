package gy.pig.spark

import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import spark.Spark

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
        SparkEvent.TransferReceived(response.receiverTransfer.transfer.toSparkTransfer())
    response.hasSenderTransfer() ->
        SparkEvent.TransferSent(response.senderTransfer.transfer.toSparkTransfer())
    response.hasDeposit() ->
        SparkEvent.DepositConfirmed(response.deposit.deposit.treeId)
    else -> null
}
