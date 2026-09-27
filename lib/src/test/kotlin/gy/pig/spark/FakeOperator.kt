package gy.pig.spark

import com.google.protobuf.ByteString
import io.grpc.ForwardingServerCall
import io.grpc.InsecureServerCredentials
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.okhttp.OkHttpServerBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import spark.Spark
import spark.SparkServiceGrpcKt
import spark_authn.SparkAuthn
import spark_authn.SparkAuthnServiceGrpcKt
import spark_token.BroadcastTransactionRequest
import spark_token.BroadcastTransactionResponse
import spark_token.CommitStatus
import spark_token.FinalTokenOutput
import spark_token.FinalTokenTransaction
import spark_token.OutputWithPreviousTransactionData
import spark_token.PartialTokenTransaction
import spark_token.QueryTokenMetadataRequest
import spark_token.QueryTokenMetadataResponse
import spark_token.QueryTokenOutputsRequest
import spark_token.QueryTokenOutputsResponse
import spark_token.SparkTokenServiceGrpcKt
import spark_token.StartTransactionRequest
import spark_token.StartTransactionResponse
import spark_token.TokenMetadata
import spark_token.TokenTransaction
import spark_token.TokenTransferInput
import java.util.Date

/**
 * A signing-operator stand-in on a local HTTP/2 port, for exercising the SDK's real transport
 * stack (connection manager, interceptors, authenticator) without mainnet. Kotlin port of the
 * Swift SDK's `FakeOperator.swift`.
 *
 * It issues session tokens through the real authn RPCs (`session-1`, `session-2`, …) and answers
 * SparkService and SparkTokenService calls. Tokens the [rejects] predicate names are refused with
 * UNAUTHENTICATED the way an operator does: before any response headers (its auth interceptor),
 * or after them when an earlier operator interceptor already set a header such as `x-trace-id`.
 * Every successful answer carries the `date` and `x-processing-time-ms` headers the operators'
 * `TimestampHeaderInterceptor` adds. All state is guarded by the instance's monitor.
 */
internal class FakeOperatorState(
    val rejection: Rejection = Rejection.BEFORE_HEADERS,
    /** What `generate_deposit_address` and `generate_static_deposit_address` hand out. */
    val depositAddress: Spark.Address = Spark.Address.getDefaultInstance(),
    private val rejects: (token: String) -> Boolean,
) {
    enum class Rejection { BEFORE_HEADERS, AFTER_HEADERS }

    /** How the event subscription behaves after its `connected` event. */
    enum class Subscription {
        /** The stream ends. */
        END,

        /** One heartbeat, then 3 s of silence. */
        HEARTBEAT_THEN_SILENCE,

        /** 3 s of silence, without heartbeats. */
        SILENCE,
    }

    /** What `broadcast_transaction` does with a V3 transaction. */
    sealed class Broadcast {
        /** Refuses it, as `start_transaction` does. */
        object Refuse : Broadcast()

        /**
         * Finalizes it as the operators do ([FakeOperator.finalize]), then alters the answer as a
         * dishonest coordinator would.
         */
        class Finalize(val tamper: (FinalTokenTransaction.Builder) -> Unit = {}) : Broadcast()
    }

    class StartedTransaction(val transaction: TokenTransaction, val idempotencyKey: String?)

    class BroadcastRequest(val request: BroadcastTransactionRequest, val idempotencyKey: String?)

    var subscription: Subscription = Subscription.END
        @Synchronized get

        @Synchronized set

    /** How far the operator's clock is from the device's, in milliseconds; its answers carry its `date`. */
    var clockOffsetMillis: Long = 0
        @Synchronized get

        @Synchronized set

    /** How long `verify_challenge` takes, in milliseconds. */
    var verifyDelayMillis: Long = 0
        @Synchronized get

        @Synchronized set

    /** Whether `query_token_metadata` fails. */
    var failsTokenMetadata: Boolean = false
        @Synchronized get

        @Synchronized set

    var broadcast: Broadcast = Broadcast.Refuse
        @Synchronized get

        @Synchronized set

    /** What every `initiate_preimage_swap_v3` fails with. */
    var preimageSwapError: Status = Status.INTERNAL.withDescription("preimage swap failed")
        @Synchronized get

        @Synchronized set

    private val issuedTokensList = mutableListOf<String>()
    private var challenges = 0
    private val verifyFailures = ArrayDeque<Status>()
    private val callsList = mutableListOf<String>()
    private val heldSendsList = mutableListOf<Spark.PreimageRequestWithTransfer>()
    private val knownTransfersList = mutableListOf<Spark.Transfer>()
    private val transferFiltersList = mutableListOf<Spark.TransferFilter>()
    private var tokenOutputsList = listOf<OutputWithPreviousTransactionData>()
    private val metadataRequestSizesList = mutableListOf<Int>()
    private val startedSpendsList = mutableListOf<List<String>>()
    private val startedTransactionsList = mutableListOf<StartedTransaction>()
    private val broadcastsList = mutableListOf<BroadcastRequest>()
    private var nodesList = listOf<Spark.TreeNode>()
    private val nodePagesList = mutableListOf<List<Long>>()
    private val preimageSwapIdempotencyKeysList = mutableListOf<String>()

    /** Session tokens handed out by `verify_challenge`, in order. */
    val issuedTokens: List<String> @Synchronized get() = issuedTokensList.toList()

    /** Challenges handed out by `get_challenge`. */
    val challengesIssued: Int @Synchronized get() = challenges

    /** `"<method> <authorization header>"` for every SparkService and SparkTokenService call received. */
    val calls: List<String> @Synchronized get() = callsList.toList()

    /** The methods called, in order. */
    val methods: List<String> get() = calls.map { it.substringBefore(' ') }

    /** The filter of every `query_all_transfers` call, in order. */
    val transferFilters: List<Spark.TransferFilter> @Synchronized get() = transferFiltersList.toList()

    /** Token identifiers per `query_token_metadata` call; more than 500 are refused, as the operators refuse them. */
    val metadataRequestSizes: List<Int> @Synchronized get() = metadataRequestSizesList.toList()

    /** The outputs (`<prev hash hex>:<vout>`) each `start_transaction` or `broadcast_transaction` spends, in order. */
    val startedSpends: List<List<String>> @Synchronized get() = startedSpendsList.toList()

    /** The partial transaction and `x-idempotency-key` of each `start_transaction`, in order. */
    val startedTransactions: List<StartedTransaction> @Synchronized get() = startedTransactionsList.toList()

    /** Each `broadcast_transaction` request and its `x-idempotency-key`, in order. */
    val broadcasts: List<BroadcastRequest> @Synchronized get() = broadcastsList.toList()

    /** `[limit, offset]` of every `query_nodes` call. */
    val nodePages: List<List<Long>> @Synchronized get() = nodePagesList.toList()

    /** The `x-idempotency-key` of every `initiate_preimage_swap_v3`, in order. */
    val preimageSwapIdempotencyKeys: List<String> @Synchronized get() = preimageSwapIdempotencyKeysList.toList()

    @Synchronized
    fun failNextVerifications(errors: List<Status>) {
        verifyFailures.clear()
        verifyFailures.addAll(errors)
    }

    @Synchronized
    fun issueChallenge() {
        challenges++
    }

    /** The error the current `verify_challenge` fails with, if any. */
    @Synchronized
    fun nextVerifyFailure(): Status? = verifyFailures.removeFirstOrNull()

    @Synchronized
    fun issueToken(): String {
        val token = "session-${issuedTokensList.size + 1}"
        issuedTokensList.add(token)
        return token
    }

    /** The operator's current time, as its date header and token expiries state it. */
    val nowMillis: Long get() = System.currentTimeMillis() + clockOffsetMillis

    /** The headers the operators' `TimestampHeaderInterceptor` adds to every successful answer. */
    fun timeHeaders(): Metadata = Metadata().apply {
        put(ServerClock.DATE_KEY, ServerClock.formatDate(Date(nowMillis)))
        put(ServerClock.PROCESSING_TIME_KEY, "1")
    }

    @Synchronized
    fun setNodes(nodes: List<Spark.TreeNode>) {
        nodesList = nodes.sortedBy { it.id }
    }

    /** The page the operators return: every node without a limit, else at most 100 from `offset`. */
    @Synchronized
    fun nodesPage(limit: Long, offset: Long): Map<String, Spark.TreeNode> {
        nodePagesList.add(listOf(limit, offset))
        val start = minOf(offset.toInt(), nodesList.size)
        val end = if (limit > 0) minOf(start + minOf(limit, 100L).toInt(), nodesList.size) else nodesList.size
        return nodesList.subList(start, end).associateBy { it.id }
    }

    @Synchronized
    fun setTokenOutputs(outputs: List<OutputWithPreviousTransactionData>) {
        tokenOutputsList = outputs
    }

    val tokenOutputs: List<OutputWithPreviousTransactionData> @Synchronized get() = tokenOutputsList

    @Synchronized
    fun recordMetadataRequest(size: Int) {
        metadataRequestSizesList.add(size)
    }

    @Synchronized
    fun recordStart(transaction: TokenTransaction, idempotencyKey: String?) {
        startedSpendsList.add(spends(transaction.transferInput))
        startedTransactionsList.add(StartedTransaction(transaction, idempotencyKey))
    }

    @Synchronized
    fun recordBroadcast(request: BroadcastTransactionRequest, idempotencyKey: String?) {
        startedSpendsList.add(spends(request.partialTokenTransaction.transferInput))
        broadcastsList.add(BroadcastRequest(request, idempotencyKey))
    }

    private fun spends(input: TokenTransferInput): List<String> =
        input.outputsToSpendList.map { "${it.prevTokenTransactionHash.toByteArray().toHexString()}:${it.prevTokenTransactionVout}" }

    @Synchronized
    fun know(transfer: Spark.Transfer) {
        knownTransfersList.add(transfer)
    }

    val knownTransfers: List<Spark.Transfer> @Synchronized get() = knownTransfersList.toList()

    @Synchronized
    fun recordTransferFilter(filter: Spark.TransferFilter) {
        transferFiltersList.add(filter)
    }

    @Synchronized
    fun hold(send: Spark.PreimageRequestWithTransfer) {
        heldSendsList.add(send)
    }

    val heldSends: List<Spark.PreimageRequestWithTransfer> @Synchronized get() = heldSendsList.toList()

    @Synchronized
    fun recordPreimageSwap(idempotencyKey: String) {
        preimageSwapIdempotencyKeysList.add(idempotencyKey)
    }

    /** Records the call; returns whether its token is accepted. */
    @Synchronized
    fun admit(method: String, authorization: String): Boolean {
        callsList.add("$method $authorization")
        val token = authorization.removePrefix("Bearer ")
        return token.isNotEmpty() && !rejects(token)
    }
}

internal object FakeOperator {
    val UNAUTHENTICATED: Status = Status.UNAUTHENTICATED.withDescription("failed to verify token: token has expired")
    val START_REFUSAL: Status = Status.FAILED_PRECONDITION.withDescription("the fake operator starts nothing")

    /** The identifier `broadcast_transaction` reports for a created token. */
    val CREATED_TOKEN_IDENTIFIER: ByteArray = bytes(0x07, 32)

    val IDEMPOTENCY_KEY: Metadata.Key<String> = Metadata.Key.of("x-idempotency-key", Metadata.ASCII_STRING_MARSHALLER)
    private val TRACE_ID_KEY: Metadata.Key<String> = Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER)

    /**
     * What the operators answer a V3 transaction with: the partial transaction, with a revocation
     * commitment per output and, for a create, the creation entity key.
     */
    fun finalize(partial: PartialTokenTransaction): FinalTokenTransaction {
        val final = FinalTokenTransaction.newBuilder()
            .setVersion(partial.version)
            .setTokenTransactionMetadata(partial.tokenTransactionMetadata)
        when (partial.tokenInputsCase) {
            PartialTokenTransaction.TokenInputsCase.MINT_INPUT -> final.setMintInput(partial.mintInput)
            PartialTokenTransaction.TokenInputsCase.TRANSFER_INPUT -> final.setTransferInput(partial.transferInput)
            PartialTokenTransaction.TokenInputsCase.CREATE_INPUT -> final.setCreateInput(
                partial.createInput.toBuilder().setCreationEntityPublicKey((byteArrayOf(0x02) + bytes(0x0E, 32)).toByteString()),
            )
            else -> Unit
        }
        partial.partialTokenOutputsList.forEachIndexed { index, output ->
            final.addFinalTokenOutputs(
                FinalTokenOutput.newBuilder()
                    .setPartialTokenOutput(output)
                    .setRevocationCommitment((byteArrayOf(0x03) + bytes(index, 32)).toByteString()),
            )
        }
        return final.build()
    }

    /** Admission and time headers, in front of every service but the token issuer. */
    private class Gate(private val state: FakeOperatorState) : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            val authorization = headers.get(SparkAuthenticator.AUTHORIZATION_KEY).orEmpty()
            if (!state.admit(call.methodDescriptor.bareMethodName.orEmpty(), authorization)) {
                if (state.rejection == FakeOperatorState.Rejection.AFTER_HEADERS) {
                    call.sendHeaders(Metadata().apply { put(TRACE_ID_KEY, "0af7651916cd43dd8448eb211c80319c") })
                }
                call.close(UNAUTHENTICATED, Metadata())
                return object : ServerCall.Listener<ReqT>() {}
            }
            return next.startCall(TimeHeaders(call, state), headers)
        }
    }

    private class TimeHeaders<ReqT, RespT>(call: ServerCall<ReqT, RespT>, private val state: FakeOperatorState) :
        ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
        override fun sendHeaders(headers: Metadata) {
            headers.merge(state.timeHeaders())
            super.sendHeaders(headers)
        }
    }

    /** Captures the call's `x-idempotency-key` for the handler, which grpc-kotlin does not expose. */
    private class IdempotencyKeys : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            val context = io.grpc.Context.current().withValue(IDEMPOTENCY_CONTEXT, headers.get(IDEMPOTENCY_KEY))
            return io.grpc.Contexts.interceptCall(context, call, headers, next)
        }
    }

    val IDEMPOTENCY_CONTEXT: io.grpc.Context.Key<String?> = io.grpc.Context.key("x-idempotency-key")

    fun currentIdempotencyKey(): String? = IDEMPOTENCY_CONTEXT.get()

    /** The token-issuing service: every challenge verifies, and each session token is new. */
    private class Authn(private val state: FakeOperatorState) : SparkAuthnServiceGrpcKt.SparkAuthnServiceCoroutineImplBase() {
        override suspend fun getChallenge(request: SparkAuthn.GetChallengeRequest): SparkAuthn.GetChallengeResponse {
            state.issueChallenge()
            return SparkAuthn.GetChallengeResponse.newBuilder()
                .setProtectedChallenge(
                    SparkAuthn.ProtectedChallenge.newBuilder().setChallenge(
                        SparkAuthn.Challenge.newBuilder()
                            .setNonce(bytes(7, 32).toByteString())
                            .setTimestamp(state.nowMillis / 1000),
                    ),
                )
                .build()
        }

        override suspend fun verifyChallenge(request: SparkAuthn.VerifyChallengeRequest): SparkAuthn.VerifyChallengeResponse {
            delay(state.verifyDelayMillis)
            state.nextVerifyFailure()?.let { throw StatusException(it) }
            return SparkAuthn.VerifyChallengeResponse.newBuilder()
                .setSessionToken(state.issueToken())
                .setExpirationTimestamp(state.nowMillis / 1000 + 3_600)
                .build()
        }
    }

    private class SparkService(private val state: FakeOperatorState) : SparkServiceGrpcKt.SparkServiceCoroutineImplBase() {
        override suspend fun queryNodes(request: Spark.QueryNodesRequest): Spark.QueryNodesResponse = Spark.QueryNodesResponse.newBuilder()
            .putAllNodes(state.nodesPage(limit = request.limit, offset = request.offset))
            .setOffset(-1)
            .build()

        override suspend fun generateDepositAddress(request: Spark.GenerateDepositAddressRequest): Spark.GenerateDepositAddressResponse =
            Spark.GenerateDepositAddressResponse.newBuilder().setDepositAddress(state.depositAddress).build()

        override suspend fun generateStaticDepositAddress(request: Spark.GenerateStaticDepositAddressRequest,): Spark.GenerateStaticDepositAddressResponse =
            Spark.GenerateStaticDepositAddressResponse.newBuilder().setDepositAddress(state.depositAddress).build()

        override suspend fun queryHtlc(request: Spark.QueryHtlcRequest): Spark.QueryHtlcResponse = Spark.QueryHtlcResponse.newBuilder()
            .addAllPreimageRequests(state.heldSends.filter { it.transfer.id in request.transferIdsList })
            .setOffset(-1)
            .build()

        override suspend fun queryAllTransfers(request: Spark.TransferFilter): Spark.QueryTransfersResponse {
            state.recordTransferFilter(request)
            return Spark.QueryTransfersResponse.newBuilder().setOffset(-1).build()
        }

        override suspend fun queryPendingTransfers(request: Spark.TransferFilter): Spark.QueryTransfersResponse =
            Spark.QueryTransfersResponse.getDefaultInstance()

        override suspend fun queryTransfersById(request: Spark.QueryTransfersByIdRequest): Spark.QueryTransfersResponse {
            val ids = request.transferIdsList.map { it.lowercase() }.toSet()
            return Spark.QueryTransfersResponse.newBuilder()
                .addAllTransfers(state.knownTransfers.filter { it.id in ids })
                .setOffset(-1)
                .build()
        }

        override suspend fun initiatePreimageSwapV3(request: Spark.InitiatePreimageSwapRequest): Spark.InitiatePreimageSwapResponse {
            state.recordPreimageSwap(currentIdempotencyKey().orEmpty())
            throw StatusException(state.preimageSwapError)
        }

        override fun subscribeToEvents(request: Spark.SubscribeToEventsRequest): Flow<Spark.SubscribeToEventsResponse> {
            val subscription = state.subscription
            return flow {
                emit(Spark.SubscribeToEventsResponse.newBuilder().setConnected(Spark.ConnectedEvent.getDefaultInstance()).build())
                if (subscription == FakeOperatorState.Subscription.HEARTBEAT_THEN_SILENCE) {
                    emit(Spark.SubscribeToEventsResponse.newBuilder().setHeartbeat(Spark.HeartbeatEvent.getDefaultInstance()).build())
                }
                if (subscription != FakeOperatorState.Subscription.END) delay(3_000)
            }
        }
    }

    /** Token outputs and metadata, with the operators' 500-identifier limit on metadata queries, and token transactions. */
    private class TokenService(private val state: FakeOperatorState) : SparkTokenServiceGrpcKt.SparkTokenServiceCoroutineImplBase() {
        override suspend fun queryTokenOutputs(request: QueryTokenOutputsRequest): QueryTokenOutputsResponse =
            QueryTokenOutputsResponse.newBuilder().addAllOutputsWithPreviousTransactionData(state.tokenOutputs).build()

        override suspend fun queryTokenMetadata(request: QueryTokenMetadataRequest): QueryTokenMetadataResponse {
            val ids = request.tokenIdentifiersList
            state.recordMetadataRequest(ids.size)
            if (state.failsTokenMetadata) throw StatusException(Status.INTERNAL.withDescription("metadata unavailable"))
            if (ids.size > 500) {
                throw StatusException(Status.INVALID_ARGUMENT.withDescription("too many token identifiers in filter: got ${ids.size}, max 500"))
            }
            return QueryTokenMetadataResponse.newBuilder()
                .addAllTokenMetadata(
                    ids.map { id ->
                        TokenMetadata.newBuilder()
                            .setTokenIdentifier(id)
                            .setTokenName("Spam")
                            .setTokenTicker("SPM")
                            .setMaxSupply(bytes(0, 16).toByteString())
                            .build()
                    },
                )
                .build()
        }

        /** A V2 `start_transaction` records what it would spend and refuses. */
        override suspend fun startTransaction(request: StartTransactionRequest): StartTransactionResponse {
            state.recordStart(request.partialTokenTransaction, currentIdempotencyKey())
            throw StatusException(START_REFUSAL)
        }

        /** A V3 `broadcast_transaction` records the request and refuses or finalizes it. */
        override suspend fun broadcastTransaction(request: BroadcastTransactionRequest): BroadcastTransactionResponse {
            state.recordBroadcast(request, currentIdempotencyKey())
            val broadcast = state.broadcast as? FakeOperatorState.Broadcast.Finalize ?: throw StatusException(START_REFUSAL)
            val final = finalize(request.partialTokenTransaction).toBuilder()
            broadcast.tamper(final)
            val response = BroadcastTransactionResponse.newBuilder()
                .setFinalTokenTransaction(final)
                .setCommitStatus(CommitStatus.COMMIT_FINALIZED)
            if (request.partialTokenTransaction.tokenInputsCase == PartialTokenTransaction.TokenInputsCase.CREATE_INPUT) {
                response.tokenIdentifier = ByteString.copyFrom(CREATED_TOKEN_IDENTIFIER)
            }
            return response.build()
        }
    }

    /** Starts the operator on an ephemeral local port. */
    fun start(state: FakeOperatorState): Server {
        val gate = Gate(state)
        val idempotency = IdempotencyKeys()
        return OkHttpServerBuilder.forPort(0, InsecureServerCredentials.create())
            .addService(ServerInterceptors.intercept(Authn(state), TimeHeadersOnly(state)))
            .addService(ServerInterceptors.intercept(SparkService(state), idempotency, gate))
            .addService(ServerInterceptors.intercept(TokenService(state), idempotency, gate))
            .maxInboundMessageSize(GrpcConnectionManager.MAX_MESSAGE_BYTES)
            .build()
            .start()
    }

    /** The token issuer answers with time headers too, but admits every call. */
    private class TimeHeadersOnly(private val state: FakeOperatorState) : ServerInterceptor {
        override fun <ReqT, RespT> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> = next.startCall(TimeHeaders(call, state), headers)
    }
}

/** The configuration of a regtest wallet whose only operator listens on [port]. */
internal fun fakeOperatorConfig(port: Int): SparkConfig = SparkConfig(
    network = SparkNetwork.REGTEST,
    signingOperators = listOf(
        SigningOperatorConfig(
            address = "http://127.0.0.1:$port",
            identifier = "0000000000000000000000000000000000000000000000000000000000000001",
            identityPublicKeyHex = "03dfbdff4b6332c220f8fa2ba8ed496c698ceada563fa01b67d9983bfc5c95e763",
        ),
    ),
    // A scheme OkHttp cannot send: every SSP call fails at once, without retries.
    sspURL = "unreachable://127.0.0.1/graphql",
    sspIdentityPublicKeyHex = "022bf283544b16c0622daecb79422007d167eca6ce9f0c98c0c49833b1f7170bfe",
)

/**
 * Runs [body] against a regtest wallet whose only operator is a [FakeOperator] on a local port.
 * The wallet's SSP URL cannot be sent to, so any SSP call fails fast offline.
 */
internal suspend fun <T> withFakeOperator(state: FakeOperatorState, configure: (SparkConfig) -> SparkConfig = { it }, body: suspend (SparkWallet) -> T,): T {
    val server = FakeOperator.start(state)
    try {
        val wallet = SparkWallet.fromMnemonic(
            config = configure(fakeOperatorConfig(server.port)),
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            account = 0,
        )
        try {
            return body(wallet)
        } finally {
            wallet.close()
        }
    } finally {
        // Stopped abruptly rather than gracefully: a handler still writing to a stream the client has
        // reset could hold a graceful shutdown open, and nothing waits on the server.
        server.shutdownNow()
    }
}
