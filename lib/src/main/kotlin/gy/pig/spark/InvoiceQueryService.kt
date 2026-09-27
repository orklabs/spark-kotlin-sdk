package gy.pig.spark

import spark.Spark

public suspend fun SparkWallet.querySparkInvoices(invoices: List<String>, limit: Long = 0, offset: Long = 0,): List<SparkInvoiceStatus> {
    val stub = getCoordinatorStub()

    val request = Spark.QuerySparkInvoicesRequest.newBuilder()
        .addAllInvoice(invoices)
        .apply {
            if (limit > 0) setLimit(limit)
            if (offset > 0) setOffset(offset)
        }
        .build()

    val response = stub.querySparkInvoices(request)

    return response.invoiceStatusesList.map { invoiceResp ->
        var satsTransferId: String? = null
        var tokenTxHash: String? = null

        when (invoiceResp.transferTypeCase) {
            Spark.InvoiceResponse.TransferTypeCase.SATS_TRANSFER -> {
                val sats = invoiceResp.satsTransfer
                if (!sats.transferId.isEmpty) {
                    satsTransferId = sats.transferId.toByteArray().toHexString()
                }
            }
            Spark.InvoiceResponse.TransferTypeCase.TOKEN_TRANSFER -> {
                val token = invoiceResp.tokenTransfer
                if (!token.finalTokenTransactionHash.isEmpty) {
                    tokenTxHash = token.finalTokenTransactionHash.toByteArray().toHexString()
                }
            }
            else -> {}
        }

        SparkInvoiceStatus(
            invoice = invoiceResp.invoice,
            status = invoiceResp.status.toString(),
            satsTransferId = satsTransferId,
            tokenTransactionHash = tokenTxHash,
        )
    }
}
