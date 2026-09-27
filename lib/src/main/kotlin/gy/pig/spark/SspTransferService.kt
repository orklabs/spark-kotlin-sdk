package gy.pig.spark

import org.json.JSONObject

public suspend fun SparkWallet.getTransferFromSsp(id: String): TransferWithUserRequest? = getTransfersFromSsp(ids = listOf(id)).firstOrNull()

public suspend fun SparkWallet.getTransfersFromSsp(ids: List<String>): List<TransferWithUserRequest> {
    val response = sspClient.executeRaw(
        query = GraphQLQueries.GET_TRANSFERS,
        variables = mapOf("transfer_spark_ids" to ids),
    )

    val transfers = response.optJSONArray("transfers") ?: return emptyList()
    return (0 until transfers.length()).mapNotNull { i ->
        parseTransferWithUserRequest(transfers.getJSONObject(i))
    }
}

private fun parseTransferWithUserRequest(json: JSONObject): TransferWithUserRequest? {
    val sparkId = json.optString("transfer_spark_id", "") ?: return null
    if (sparkId.isEmpty()) return null

    var totalAmountSats: Long? = null
    val amountObj = json.optJSONObject("transfer_total_amount")
    if (amountObj != null) {
        val value = amountObj.optLong("currency_amount_original_value", 0)
        val unit = amountObj.optString("currency_amount_original_unit", "")
        totalAmountSats = if (unit == "MILLISATOSHI") (value + 999) / 1000 else value
    }

    var userRequest: UserRequest? = null
    val reqJson = json.optJSONObject("transfer_user_request")
    if (reqJson != null) {
        val typename = reqJson.optString("__typename", "")
        if (typename.isNotEmpty()) {
            userRequest = parseUserRequest(reqJson, typename)
        }
    }

    return TransferWithUserRequest(
        sparkId = sparkId,
        totalAmountSats = totalAmountSats,
        userRequest = userRequest,
    )
}

private fun parseUserRequest(json: JSONObject, typename: String): UserRequest = when (typename) {
    "LightningReceiveRequest" -> {
        var encodedInvoice: String? = null
        var paymentHash: String? = null
        var amountSats: Long? = null
        var memo: String? = null

        val invoiceObj = json.optJSONObject("lightning_receive_request_invoice")
        if (invoiceObj != null) {
            encodedInvoice = invoiceObj.optString("invoice_encoded_invoice", null)
            paymentHash = invoiceObj.optString("invoice_payment_hash", null)
            memo = invoiceObj.optString("invoice_memo", null)
            val amtObj = invoiceObj.optJSONObject("invoice_amount")
            if (amtObj != null) {
                val value = amtObj.optLong("currency_amount_original_value", 0)
                val unit = amtObj.optString("currency_amount_original_unit", "")
                amountSats = if (unit == "MILLISATOSHI") (value + 999) / 1000 else value
            }
        }

        UserRequest.LightningReceive(
            LightningReceiveInfo(
                id = json.optString("lightning_receive_request_id", ""),
                status = json.optString("lightning_receive_request_status", ""),
                encodedInvoice = encodedInvoice,
                paymentHash = paymentHash,
                amountSats = amountSats,
                memo = memo,
                paymentPreimage = json.optString("lightning_receive_request_payment_preimage", null),
            )
        )
    }

    "LightningSendRequest" -> {
        var feeSats: Long? = null
        val feeObj = json.optJSONObject("lightning_send_request_fee")
        if (feeObj != null) {
            val value = feeObj.optLong("currency_amount_original_value", 0)
            val unit = feeObj.optString("currency_amount_original_unit", "")
            feeSats = if (unit == "MILLISATOSHI") (value + 999) / 1000 else value
        }

        UserRequest.LightningSend(
            LightningSendInfo(
                id = json.optString("lightning_send_request_id", ""),
                status = json.optString("lightning_send_request_status", ""),
                encodedInvoice = json.optString("lightning_send_request_encoded_invoice", null),
                feeSats = feeSats,
                idempotencyKey = json.optString("lightning_send_request_idempotency_key", null),
                paymentPreimage = json.optString("lightning_send_request_payment_preimage", null),
            )
        )
    }

    "CoopExitRequest" -> UserRequest.CoopExit(
        CoopExitInfo(
            id = json.optString("coop_exit_request_id", ""),
            status = json.optString("coop_exit_request_status", ""),
            coopExitTxid = json.optString("coop_exit_request_coop_exit_txid", null),
        )
    )

    "LeavesSwapRequest" -> UserRequest.LeavesSwap(
        LeavesSwapInfo(
            id = json.optString("leaves_swap_request_id", ""),
            status = json.optString("leaves_swap_request_status", ""),
        )
    )

    "ClaimStaticDeposit" -> UserRequest.ClaimStaticDeposit(
        ClaimStaticDepositInfo(
            id = json.optString("claim_static_deposit_id", ""),
            status = json.optString("claim_static_deposit_status", ""),
            transactionId = json.optString("claim_static_deposit_transaction_id", null),
            outputIndex = if (json.has("claim_static_deposit_output_index")) {
                json.optInt("claim_static_deposit_output_index")
            } else {
                null
            },
        )
    )

    else -> UserRequest.Unknown(typename)
}
