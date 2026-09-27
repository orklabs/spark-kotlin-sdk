package gy.pig.spark

internal object GraphQLMutations {
    const val GET_CHALLENGE = """
        mutation GetChallenge(${'$'}public_key: PublicKey!) {
            get_challenge(input: { public_key: ${'$'}public_key }) {
                protected_challenge
            }
        }
    """

    const val VERIFY_CHALLENGE = """
        mutation VerifyChallenge(
            ${'$'}protected_challenge: String!,
            ${'$'}signature: String!,
            ${'$'}identity_public_key: PublicKey!
        ) {
            verify_challenge(input: {
                protected_challenge: ${'$'}protected_challenge,
                signature: ${'$'}signature,
                identity_public_key: ${'$'}identity_public_key
            }) {
                valid_until
                session_token
            }
        }
    """

    const val REQUEST_LIGHTNING_RECEIVE = """
        mutation RequestLightningReceive(
            ${'$'}network: BitcoinNetwork!,
            ${'$'}amount_sats: Long!,
            ${'$'}payment_hash: Hash32!,
            ${'$'}expiry_secs: Int,
            ${'$'}memo: String
        ) {
            request_lightning_receive(input: {
                network: ${'$'}network,
                amount_sats: ${'$'}amount_sats,
                payment_hash: ${'$'}payment_hash,
                expiry_secs: ${'$'}expiry_secs,
                memo: ${'$'}memo
            }) {
                request {
                    invoice {
                        encoded_invoice
                        payment_hash
                        expires_at
                    }
                }
            }
        }
    """

    const val REQUEST_LIGHTNING_SEND = """
        mutation RequestLightningSend(
            ${'$'}encoded_invoice: String!,
            ${'$'}idempotency_key: String,
            ${'$'}amount_sats: Long,
            ${'$'}user_outbound_transfer_external_id: UUID
        ) {
            request_lightning_send(input: {
                encoded_invoice: ${'$'}encoded_invoice,
                idempotency_key: ${'$'}idempotency_key,
                amount_sats: ${'$'}amount_sats,
                user_outbound_transfer_external_id: ${'$'}user_outbound_transfer_external_id
            }) {
                request {
                    id
                    status
                }
            }
        }
    """

    const val GET_FEE_ESTIMATE = """
        query CoopExitFeeEstimate(
            ${'$'}leaf_external_ids: [UUID!]!,
            ${'$'}withdrawal_address: String!
        ) {
            coop_exit_fee_estimates(input: {
                leaf_external_ids: ${'$'}leaf_external_ids,
                withdrawal_address: ${'$'}withdrawal_address
            }) {
                speed_fast {
                    user_fee { original_value original_unit }
                    l1_broadcast_fee { original_value original_unit }
                }
            }
        }
    """

    const val REQUEST_COOP_EXIT = """
        mutation RequestCoopExit(
            ${'$'}leaf_external_ids: [UUID!]!,
            ${'$'}withdrawal_address: String!,
            ${'$'}exit_speed: ExitSpeed!,
            ${'$'}withdraw_all: Boolean,
            ${'$'}user_outbound_transfer_external_id: UUID
        ) {
            request_coop_exit(input: {
                leaf_external_ids: ${'$'}leaf_external_ids,
                withdrawal_address: ${'$'}withdrawal_address,
                exit_speed: ${'$'}exit_speed,
                withdraw_all: ${'$'}withdraw_all,
                user_outbound_transfer_external_id: ${'$'}user_outbound_transfer_external_id
            }) {
                request {
                    id
                    raw_connector_transaction
                    raw_coop_exit_transaction
                    coop_exit_txid
                    status
                }
            }
        }
    """

    const val COMPLETE_COOP_EXIT = """
        mutation CompleteCoopExit(
            ${'$'}user_outbound_transfer_external_id: UUID!
        ) {
            complete_coop_exit(input: {
                user_outbound_transfer_external_id: ${'$'}user_outbound_transfer_external_id
            }) {
                request {
                    id
                    status
                }
            }
        }
    """

    const val CLAIM_STATIC_DEPOSIT = """
        mutation ClaimStaticDeposit(
            ${'$'}transaction_id: String!,
            ${'$'}output_index: Int!,
            ${'$'}network: BitcoinNetwork!,
            ${'$'}request_type: ClaimStaticDepositRequestType!,
            ${'$'}credit_amount_sats: Long,
            ${'$'}deposit_secret_key: String!,
            ${'$'}signature: String!,
            ${'$'}quote_signature: String!
        ) {
            claim_static_deposit(input: {
                transaction_id: ${'$'}transaction_id,
                output_index: ${'$'}output_index,
                network: ${'$'}network,
                request_type: ${'$'}request_type,
                credit_amount_sats: ${'$'}credit_amount_sats,
                max_fee_sats: null,
                deposit_secret_key: ${'$'}deposit_secret_key,
                signature: ${'$'}signature,
                quote_signature: ${'$'}quote_signature
            }) {
                transfer_id
            }
        }
    """

    const val REQUEST_SWAP = """
        mutation RequestSwap(
            ${'$'}adaptor_pubkey: PublicKey!,
            ${'$'}total_amount_sats: Long!,
            ${'$'}target_amount_sats: [Long!]!,
            ${'$'}fee_sats: Long!,
            ${'$'}user_leaves: [UserLeafInput!]!,
            ${'$'}user_outbound_transfer_external_id: UUID!
        ) {
            request_swap(input: {
                adaptor_pubkey: ${'$'}adaptor_pubkey,
                total_amount_sats: ${'$'}total_amount_sats,
                target_amount_sats: ${'$'}target_amount_sats,
                fee_sats: ${'$'}fee_sats,
                user_leaves: ${'$'}user_leaves,
                user_outbound_transfer_external_id: ${'$'}user_outbound_transfer_external_id
            }) {
                request {
                    leaves_swap_request_id: id
                    leaves_swap_request_status: status
                    leaves_swap_request_inbound_transfer: inbound_transfer {
                        transfer_spark_id: spark_id
                    }
                    leaves_swap_request_swap_leaves: swap_leaves {
                        swap_leaf_leaf_id: leaf_id
                    }
                }
            }
        }
    """
}

internal object GraphQLQueries {
    const val GET_LIGHTNING_PAYMENT_STATUS = """
        query GetLightningPaymentStatus(${'$'}paymentHash: String!) {
            spark_lightning_payment(payment_hash: ${'$'}paymentHash) {
                payment_hash
                status
                fee_sats
                preimage
            }
        }
    """

    const val STATIC_DEPOSIT_QUOTE = """
        query StaticDepositQuote(
            ${'$'}transaction_id: String!,
            ${'$'}output_index: Int!,
            ${'$'}network: BitcoinNetwork!
        ) {
            static_deposit_quote(input: {
                transaction_id: ${'$'}transaction_id,
                output_index: ${'$'}output_index,
                network: ${'$'}network
            }) {
                credit_amount_sats
                signature
            }
        }
    """

    const val LIGHTNING_SEND_FEE_ESTIMATE = """
        query LightningSendFeeEstimate(${'$'}encoded_invoice: String!, ${'$'}amount_sats: Long) {
            lightning_send_fee_estimate(input: {
                encoded_invoice: ${'$'}encoded_invoice,
                amount_sats: ${'$'}amount_sats
            }) {
                fee_estimate {
                    original_value
                    original_unit
                }
            }
        }
    """

    const val GET_TRANSFERS = """
        query Transfers(${'$'}transfer_spark_ids: [UUID!]!) {
            transfers(transfer_spark_ids: ${'$'}transfer_spark_ids) {
                transfer_spark_id: spark_id
                transfer_total_amount: total_amount {
                    currency_amount_original_value: original_value
                    currency_amount_original_unit: original_unit
                }
                transfer_user_request: user_request {
                    __typename
                    ... on LightningReceiveRequest {
                        lightning_receive_request_id: id
                        lightning_receive_request_status: status
                        lightning_receive_request_invoice: invoice {
                            invoice_encoded_invoice: encoded_invoice
                            invoice_payment_hash: payment_hash
                            invoice_amount: amount {
                                currency_amount_original_value: original_value
                                currency_amount_original_unit: original_unit
                            }
                            invoice_memo: memo
                        }
                        lightning_receive_request_payment_preimage: payment_preimage
                    }
                    ... on LightningSendRequest {
                        lightning_send_request_id: id
                        lightning_send_request_encoded_invoice: encoded_invoice
                        lightning_send_request_status: status
                        lightning_send_request_fee: fee {
                            currency_amount_original_value: original_value
                            currency_amount_original_unit: original_unit
                        }
                        lightning_send_request_idempotency_key: idempotency_key
                        lightning_send_request_payment_preimage: payment_preimage
                    }
                    ... on CoopExitRequest {
                        coop_exit_request_id: id
                        coop_exit_request_status: status
                        coop_exit_request_coop_exit_txid: coop_exit_txid
                    }
                    ... on LeavesSwapRequest {
                        leaves_swap_request_id: id
                        leaves_swap_request_status: status
                    }
                    ... on ClaimStaticDeposit {
                        claim_static_deposit_id: id
                        claim_static_deposit_status: status
                        claim_static_deposit_transaction_id: transaction_id
                        claim_static_deposit_output_index: output_index
                    }
                }
            }
        }
    """
}
