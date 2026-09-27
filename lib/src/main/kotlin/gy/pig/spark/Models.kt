package gy.pig.spark

import java.math.BigInteger
import java.util.Date

/**
 * Breakdown of the wallet's sat balance.
 *
 * Mirrors `SparkSDK/Models.SatsBalance` from the Swift SDK so the Kotlin and Swift surfaces
 * compose into the same numbers when the same wallet is queried from both platforms.
 *
 * - [available]: sats the wallet can send — `AVAILABLE` leaves whose refund timelock is at least
 *   100. Leaves at 100…199 are renewed by every spend path before they are selected (the
 *   coordinator will not move them otherwise), as the reference SDK does.
 * - [owned]: every sat the wallet owns: [available] + [frozen] + [locked]. Locked sats are held by
 *   an in-flight operation that can still come back: an outgoing transfer, Lightning payment or
 *   cooperative exit before the operators apply the sender's key tweak (a cooperative exit until
 *   its transaction confirms), a swap the wallet started, and its counter-transfer until claimed.
 *   Sent sats leave [owned] once the sender's key tweak is applied, even before the receiver
 *   claims them.
 * - [incoming]: pending inbound transfers not yet claimed.
 * - [frozen]: sats in `AVAILABLE` leaves whose refund timelock is below 100
 *   ([SparkLeaf.isFrozen]). The coordinator will neither move nor renew them; only a unilateral
 *   on-chain exit can recover them. A leaf at exactly 100 is renewable and counts as available.
 * - [locked]: sats held by an in-flight transfer, swap or exit the wallet still owns.
 */
public data class SatsBalance(public val available: Long, public val owned: Long, public val incoming: Long, public val frozen: Long,) {
    /** Sats held by an in-flight transfer, swap or exit the wallet still owns (see [owned]). */
    public val locked: Long get() = maxOf(0L, owned - available - frozen)
}

public data class WalletBalance(public val satsBalance: SatsBalance, public val tokenBalances: List<TokenBalance>, public val leaves: List<SparkLeaf>,) {
    /**
     * Spendable balance only. Equivalent to [SatsBalance.available].
     * Mirrors the Swift SDK's deprecated `balance: Int64` accessor.
     */
    @Deprecated(
        message = "Use satsBalance.available (spendable) or satsBalance.available + .incoming (with in-flight credits).",
        replaceWith = ReplaceWith("satsBalance.available"),
    )
    public val totalSats: Long get() = satsBalance.available
}

public data class SparkLeaf(val id: String, val treeID: String, val valueSats: Long, val status: String, internal val node: spark.Spark.TreeNode? = null,)

public data class SparkTransfer(
    val id: String,
    val senderIdentityPublicKey: String,
    val receiverIdentityPublicKey: String,
    val totalValueSats: Long,
    val status: String,
    val type: String,
    val createdAt: Date,
    val sparkInvoice: String? = null,
)

/** The transfer as an operator reported it. */
internal fun spark.Spark.Transfer.toSparkTransfer(): SparkTransfer = SparkTransfer(
    id = id,
    senderIdentityPublicKey = senderIdentityPublicKey.toByteArray().toHexString(),
    receiverIdentityPublicKey = receiverIdentityPublicKey.toByteArray().toHexString(),
    totalValueSats = reportedSats(totalValue),
    status = status.toString(),
    type = type.toString(),
    createdAt = Date(createdTime.seconds * 1000 + createdTime.nanos / 1_000_000),
    sparkInvoice = sparkInvoice.takeIf { it.isNotEmpty() },
)

/** Every bitcoin there will ever be, in sats. */
internal const val MAX_SUPPLY_SATS: Long = 21_000_000L * 100_000_000L

/**
 * A sats amount an operator reported in a protobuf `uint64`, which Kotlin reads as a signed
 * `Long`: capped at the bitcoin supply, so 2^63 and above (negative here) or any hostile value
 * cannot turn into a negative amount, and sums of such amounts stay far from overflowing.
 */
internal fun reportedSats(value: Long): Long = if (value < 0 || value > MAX_SUPPLY_SATS) MAX_SUPPLY_SATS else value

/** A sats amount from a transaction output (the SSP or a block explorer), capped at the bitcoin supply. */
internal fun reportedSats(value: ULong): Long = if (value > MAX_SUPPLY_SATS.toULong()) MAX_SUPPLY_SATS else value.toLong()

public data class DepositAddress(val address: String, val leafId: String, val userPublicKey: ByteArray, val verifyingKey: ByteArray,) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepositAddress) return false
        return address == other.address && leafId == other.leafId
    }
    override fun hashCode(): Int = address.hashCode() * 31 + leafId.hashCode()
}

public data class StaticDepositAddress(val address: String, val verifyingKey: ByteArray,) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StaticDepositAddress) return false
        return address == other.address
    }
    override fun hashCode(): Int = address.hashCode()
}

public data class LightningInvoice(val paymentRequest: String, val paymentHash: String, val amountSats: Long, val expiresAt: Date,)

public data class FeeQuote(val feeSats: Long, val feeRateSatsPerVbyte: Long,)

/**
 * What [withdrawAll] would do right now. Produced by [quoteWithdrawAll] after pending inbound
 * transfers were claimed and renewable leaves renewed.
 */
public data class WithdrawAllQuote(
    /** Sats that would be handed to the SSP: every spendable leaf. */
    public val spendableSats: Long,
    /** The SSP's fee quote (fast exit) for those leaves. Zero when there is nothing to send. */
    public val quotedFeeSats: Long,
    /** Sats in frozen leaves (refund timelock below 100). They stay behind; only a unilateral exit moves them. */
    public val frozenSats: Long,
    /**
     * Sats in leaves that need a renewal (refund timelock 100…199) the operators did not
     * complete. They stay behind this time; a later attempt can renew and move them.
     */
    public val unrenewedSats: Long,
    /** Sats in leaves locked by an in-flight swap or exit. Withdraw again once they settle. */
    public val lockedSats: Long,
    /** Inbound sats that are still unclaimed after the claim attempt. */
    public val incomingSats: Long,
    /** Number of leaves that would be exited. */
    public val leafCount: Int,
) {
    /** What the destination would receive if the SSP charges exactly its quote. */
    public val estimatedPayoutSats: Long get() = spendableSats - quotedFeeSats

    /** Share of the wallet's own sats (spendable + frozen) that cannot leave off-chain, 0...1. */
    public val frozenFraction: Double
        get() {
            val total = spendableSats + frozenSats
            return if (total > 0) frozenSats.toDouble() / total.toDouble() else 0.0
        }

    /** Whether the quoted fee leaves anything to pay out. */
    public val coversFee: Boolean get() = spendableSats > quotedFeeSats && quotedFeeSats >= 0
}

/** Outcome of [withdrawAll]. */
public data class WithdrawAllResult(
    /** L1 transaction id of the cooperative exit. */
    public val txid: String,
    /** Sats handed to the SSP: every spendable sat at drain time. */
    public val sentSats: Long,
    /** Sats the verified exit transaction pays to the destination. */
    public val payoutSats: Long,
    /** Sats left in frozen leaves (refund timelock below 100); only a unilateral exit can recover them. */
    public val frozenSats: Long,
    /** Sats left in leaves whose renewal the operators did not complete; a later drain can move them. */
    public val unrenewedSats: Long,
    /** Sats left in leaves locked by an in-flight operation. */
    public val lockedSats: Long,
    /** Inbound sats that could not be claimed before the drain. */
    public val unclaimedSats: Long,
) {
    /** SSP fee actually taken. */
    public val feeSats: Long get() = sentSats - payoutSats
}

public data class UnusedDepositAddress(val address: String, val leafId: String, val userSigningPublicKey: ByteArray, val verifyingPublicKey: ByteArray,) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnusedDepositAddress) return false
        return address == other.address && leafId == other.leafId
    }
    override fun hashCode(): Int = address.hashCode() * 31 + leafId.hashCode()
}

public data class DepositFeeEstimate(val creditAmountSats: Long, val quoteSignature: String,)

public data class WalletSettings(val privateEnabled: Boolean, val ownerIdentityPublicKey: String,)

/** What [subscribeToEvents] reports. */
public sealed class SparkEvent {
    /** The event stream connected. */
    public data object Connected : SparkEvent()

    /**
     * A payment to this wallet arrived. The stream claims it first (best effort; one it cannot
     * claim yet stays pending for the next claim pass), and on every connection it claims and
     * reports the payments that arrived while it was down. The counter-transfers of the wallet's
     * own swaps and transfers to itself are not reported: the operation that made them claims them.
     */
    public data class TransferReceived(val transfer: SparkTransfer) : SparkEvent()

    /**
     * An outgoing transfer changed status — initiated, awaiting or applied the sender's key
     * tweak, or returned — so one transfer is reported several times; `status` says which.
     */
    public data class TransferSent(val transfer: SparkTransfer) : SparkEvent()

    /** A deposit's leaf became available. */
    public data class DepositConfirmed(val treeID: String) : SparkEvent()

    /**
     * The event stream failed, or the operator ended it; it subscribes again after [retryIn].
     * [attempt] counts the attempts since the stream was last connected.
     */
    public data class Reconnecting(val attempt: Int, val retryIn: kotlin.time.Duration, val reason: String) : SparkEvent()
}

public enum class TransferDirection {
    SENT,
    RECEIVED,
    BOTH,
}

// MARK: - Token Types

public data class TokenMetadataInfo(
    val tokenIdentifier: String,
    val rawTokenIdentifier: ByteArray,
    val issuerPublicKey: ByteArray,
    val tokenName: String,
    val tokenTicker: String,
    val decimals: UInt,
    val maxSupply: ByteArray, // 16-byte uint128 big-endian
    val isFreezable: Boolean,
    val extraMetadata: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenMetadataInfo) return false
        return tokenIdentifier == other.tokenIdentifier
    }
    override fun hashCode(): Int = tokenIdentifier.hashCode()
}

public data class TokenBalance(val tokenMetadata: TokenMetadataInfo, val ownedBalance: BigInteger, val availableToSendBalance: BigInteger,)

public data class TokenOutputInfo(
    val id: String?,
    val ownerPublicKey: ByteArray,
    val tokenIdentifier: ByteArray,
    val tokenAmount: BigInteger,
    val previousTransactionHash: ByteArray,
    val previousTransactionVout: UInt,
    val status: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenOutputInfo) return false
        return id == other.id && previousTransactionVout == other.previousTransactionVout
    }
    override fun hashCode(): Int = (id?.hashCode() ?: 0) * 31 + previousTransactionVout.hashCode()
}

public data class TokenCreationResult(val transactionHash: String, val tokenIdentifier: String?,)

public enum class TokenOutputSelectionStrategy {
    SMALL_FIRST,
    LARGE_FIRST,
}

// MARK: - UInt128 helpers
//
// The Spark protocol represents token amounts and max-supply as 16-byte big-endian
// unsigned 128-bit integers. Kotlin/JVM has no native UInt128, so we use BigInteger
// and validate the range explicitly at the protocol boundary.

/**
 * Decode a 16-byte big-endian unsigned 128-bit integer.
 * Throws [IllegalArgumentException] if [data] is not exactly 16 bytes.
 */
public fun decodeUInt128(data: com.google.protobuf.ByteString): BigInteger {
    require(data.size() == 16) { "UInt128 must be exactly 16 bytes, got ${data.size()}" }
    // signum=1 forces unsigned interpretation of the magnitude bytes.
    return BigInteger(1, data.toByteArray())
}

/**
 * Encode an unsigned 128-bit integer as a 16-byte big-endian byte array.
 * Throws [IllegalArgumentException] if [value] is negative or exceeds 2^128 − 1.
 */
public fun encodeUInt128(value: BigInteger): ByteArray {
    require(value.signum() >= 0) { "UInt128 must be non-negative, got $value" }
    require(value.bitLength() <= 128) { "UInt128 must fit in 128 bits, got $value" }
    val raw = value.toByteArray() // signed two's-complement, big-endian; may have leading 0x00
    val out = ByteArray(16)
    // Right-align into 16 bytes; if BigInteger added a sign byte that pushed us to 17, skip it.
    val srcOffset = if (raw.size > 16) raw.size - 16 else 0
    val length = minOf(raw.size, 16)
    System.arraycopy(raw, srcOffset, out, 16 - length, length)
    return out
}

// MARK: - SSP Transfer Types

public data class TransferWithUserRequest(val sparkId: String, val totalAmountSats: Long?, val userRequest: UserRequest?,)

public sealed class UserRequest {
    public data class LightningReceive(val info: LightningReceiveInfo) : UserRequest()
    public data class LightningSend(val info: LightningSendInfo) : UserRequest()
    public data class CoopExit(val info: CoopExitInfo) : UserRequest()
    public data class LeavesSwap(val info: LeavesSwapInfo) : UserRequest()
    public data class ClaimStaticDeposit(val info: ClaimStaticDepositInfo) : UserRequest()
    public data class Unknown(val typeName: String) : UserRequest()
}

public data class LightningReceiveInfo(
    val id: String,
    val status: String,
    val encodedInvoice: String?,
    val paymentHash: String?,
    val amountSats: Long?,
    val memo: String?,
    val paymentPreimage: String?,
)

public data class LightningSendInfo(
    val id: String,
    val status: String,
    val encodedInvoice: String?,
    val feeSats: Long?,
    val idempotencyKey: String?,
    val paymentPreimage: String?,
)

public data class CoopExitInfo(val id: String, val status: String, val coopExitTxid: String?,)

public data class LeavesSwapInfo(val id: String, val status: String,)

public data class ClaimStaticDepositInfo(val id: String, val status: String, val transactionId: String?, val outputIndex: Int?,)

// MARK: - Invoice Query Types

public data class SparkInvoiceStatus(val invoice: String, val status: String, val satsTransferId: String?, val tokenTransactionHash: String?,)
