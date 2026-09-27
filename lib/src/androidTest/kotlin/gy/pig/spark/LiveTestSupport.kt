package gy.pig.spark

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import spark.Spark
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.TimeSource

// Helpers shared by the live-network suites (the Swift SDK's IntegrationTests helpers and the
// unit-test helpers its live tests borrow).

/** A test body on the live network: JUnit test methods must return void. */
internal fun liveTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking(block = block)
}

/** A mainnet wallet for [mnemonic] at [account] (0, as the Swift SDK's tests use). */
internal fun makeWallet(mnemonic: String, config: SparkConfig = SparkConfig(), account: Int = 0): SparkWallet =
    SparkWallet.fromMnemonic(config = config, mnemonic = mnemonic, account = account)

/** Runs [block] on a wallet for [mnemonic], closing it afterwards. */
internal suspend fun <T> withWallet(mnemonic: String, config: SparkConfig = SparkConfig(), account: Int = 0, block: suspend (SparkWallet) -> T,): T {
    val wallet = makeWallet(mnemonic, config, account)
    try {
        return block(wallet)
    } finally {
        wallet.close()
    }
}

/** Runs [block] on the two test wallets, closing them afterwards. */
internal suspend fun <T> withWallets(block: suspend (a: SparkWallet, b: SparkWallet) -> T): T =
    withWallet(TestConfig.walletAMnemonic) { a -> withWallet(TestConfig.walletBMnemonic) { b -> block(a, b) } }

/** Resolves a lightning address (user@domain) to a BOLT11 invoice via LNURL-pay. */
internal fun resolveLightningAddress(address: String, amountSats: Long): String {
    val parts = address.split("@")
    require(parts.size == 2) { "Invalid lightning address format" }
    val client = okhttp3.OkHttpClient()
    val lnurl = client.newCall(okhttp3.Request.Builder().url("https://${parts[1]}/.well-known/lnurlp/${parts[0]}").build()).execute()
    val callback = JSONObject(lnurl.body!!.string()).getString("callback")
    val invoice = client.newCall(okhttp3.Request.Builder().url("$callback?amount=${amountSats * 1000}").build()).execute()
    return JSONObject(invoice.body!!.string()).getString("pr")
}

/** A Spark invoice for [amountSats] to [identityPublicKey] (the Swift SDK's `SparkInvoiceTests.satsInvoice`). */
internal fun satsInvoice(identityPublicKey: ByteArray, amountSats: Long, network: SparkNetwork): String {
    val uuid = UUID.randomUUID()
    val id = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    val payload = Spark.SparkAddress.newBuilder()
        .setIdentityPublicKey(com.google.protobuf.ByteString.copyFrom(identityPublicKey))
        .setSparkInvoiceFields(
            Spark.SparkInvoiceFields.newBuilder()
                .setVersion(1)
                .setId(com.google.protobuf.ByteString.copyFrom(id))
                .setSatsPayment(Spark.SatsPayment.newBuilder().setAmount(amountSats)),
        )
        .build()
    return Bech32m.encode(SparkAddress.hrp(network), Bech32.toWords(payload.toByteArray()))
}

private fun le32(value: UInt): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

private fun le64(value: ULong): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong()).array()

/**
 * BIP-341 key-path sighash with SIGHASH_DEFAULT for input 0 of a one-input transaction — what
 * the operators compute (`sighash.FromTx`), written out independently (the Swift SDK's
 * `StaticDepositRefundTests.taprootSighash`).
 */
internal fun taprootSighash(tx: RawTransaction, prevoutScript: ByteArray, prevoutValue: ULong): ByteArray {
    var prevouts = ByteArray(0)
    var sequences = ByteArray(0)
    for (input in tx.inputs) {
        prevouts += input.previousTxid + le32(input.previousIndex)
        sequences += le32(input.sequence)
    }
    var outputs = ByteArray(0)
    for (output in tx.outputs) {
        outputs += le64(output.value) + byteArrayOf(output.scriptPubKey.size.toByte()) + output.scriptPubKey
    }
    val scripts = byteArrayOf(prevoutScript.size.toByte()) + prevoutScript
    val message = byteArrayOf(0x00, 0x00) + le32(tx.version) + le32(tx.locktime) + sha256(prevouts) + sha256(le64(prevoutValue)) +
        sha256(scripts) + sha256(sequences) + sha256(outputs) + byteArrayOf(0x00) + le32(0u)
    return Bip340.taggedHash("TapSighash", message)
}

/** The events [flow] produces in [duration] (the Swift SDK's `EventStreamConnectionTests.events`). */
internal suspend fun <T> eventsFor(flow: Flow<T>, duration: Duration): List<T> {
    val events = mutableListOf<T>()
    withTimeoutOrNull(duration) { flow.collect { events.add(it) } }
    return events
}

/** A wallet's events, collected in the background until [stop]. */
internal class EventLog(wallet: SparkWallet) {
    private val collected = mutableListOf<SparkEvent>()
    private val job: Job = CoroutineScope(Dispatchers.IO).launch {
        wallet.subscribeToEvents().collect { event -> synchronized(collected) { collected.add(event) } }
    }

    val events: List<SparkEvent> get() = synchronized(collected) { collected.toList() }

    fun contains(predicate: (SparkEvent) -> Boolean): Boolean = events.any(predicate)

    fun stop() = job.cancel()
}

/** Polls [condition] every half second until it holds or [timeout] passes. */
internal suspend fun eventually(timeout: Duration, condition: suspend () -> Boolean): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (deadline.hasNotPassedNow()) {
        if (condition()) return true
        delay(500)
    }
    return condition()
}

/** [block] fails with a [SparkError], which is returned (Swift's `#expect(throws: SparkError.self)`). */
internal suspend fun expectSparkError(label: String = "", block: suspend () -> Unit): SparkError {
    val error = try {
        block()
        null
    } catch (e: SparkError) {
        e
    }
    return error ?: throw AssertionError("expected a SparkError${if (label.isEmpty()) "" else " ($label)"}")
}

/**
 * Steps 1–3 of a Lightning send, ending with the transfer the coordinator holds — the Swift
 * SDK's `startLightningSend`, which its resume test drives directly.
 */
internal suspend fun SparkWallet.startLightningSend(payment: LightningPayment, transferId: String): Spark.Transfer =
    submitPreimageSwap(prepareLightningSend(payment, transferId), preimageSwapIdempotencyKey(payment.idempotencyKey, transferId))
