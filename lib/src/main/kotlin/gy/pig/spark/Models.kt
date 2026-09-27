package gy.pig.spark

import java.math.BigInteger
import java.util.Date

/**
 * Breakdown of the wallet's sat balance.
 *
 * Mirrors `SparkSDK/Models.SatsBalance` from the official Swift SDK so the
 * Kotlin and Swift surfaces compose into the same numbers when the same
 * wallet is queried from both platforms.
 *
 * - [available]: sats that can be sent right now — `AVAILABLE` leaves whose
 *   refund timelock is above the floor the coordinator enforces. Sending the
 *   full [available] balance always succeeds.
 * - [owned]: every sat the wallet owns: [available] plus [frozen] plus value
 *   locked in outgoing transfers/swaps (`TRANSFER_LOCKED`, `SPLIT_LOCKED`,
 *   `AGGREGATE_LOCK`, `RENEW_LOCKED`). Use this for "how much do I own right
 *   now" displays where in-flight sends should not appear to vanish.
 * - [incoming]: pending inbound transfers that have not been claimed yet,
 *   plus on-chain deposits whose nodes are still in `CREATING` state.
 *   Add to [available] to mirror what most wallet UIs label "balance"
 *   while a payment is in flight.
 * - [frozen]: sats in `AVAILABLE` leaves at the timelock floor. The
 *   coordinator will neither move nor renew them; only a unilateral on-chain
 *   exit can recover them.
 * - [locked]: sats held by an in-flight transfer, swap, renewal or exit.
 */
public data class SatsBalance(public val available: Long, public val owned: Long, public val incoming: Long, public val frozen: Long,) {
    /** Sats locked by an in-flight transfer, swap, renewal or exit (`owned - available - frozen`). */
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
    /** Sats in leaves at the timelock floor. They stay behind; only a unilateral exit moves them. */
    public val frozenSats: Long,
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
    /** Sats left in frozen leaves; only a unilateral exit can recover them. */
    public val frozenSats: Long,
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

public sealed class SparkEvent {
    public data object Connected : SparkEvent()
    public data class TransferReceived(val transfer: SparkTransfer) : SparkEvent()
    public data class TransferSent(val transfer: SparkTransfer) : SparkEvent()
    public data class DepositConfirmed(val treeID: String) : SparkEvent()
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
