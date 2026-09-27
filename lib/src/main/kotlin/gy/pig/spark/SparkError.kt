package gy.pig.spark

/**
 * Typed errors thrown by every public SDK method.
 *
 * `SparkError` is a `sealed class`, so an exhaustive `when` over its subtypes is
 * checked by the compiler. Use the structured payload (e.g. [InsufficientBalance.need]
 * / [InsufficientBalance.have]) to drive user-facing messages instead of parsing the
 * `message` string.
 *
 * ```kotlin
 * try {
 *     wallet.send(receiverSparkAddress = "spark1...", amountSats = 500)
 * } catch (e: SparkError) {
 *     when (e) {
 *         is SparkError.InsufficientBalance -> ui.showLowBalance(e.need, e.have)
 *         is SparkError.AuthenticationFailed -> auth.reLogin()
 *         is SparkError.FeeExceedsLimit -> ui.showFeeTooHigh(e.feeSats, e.maxFeeSats)
 *         is SparkError.UntrustedResponse -> ui.showServiceProblem() // nothing was signed
 *         is SparkError.LightningSendIncomplete -> retryLater(e.transferId)
 *         is SparkError.GrpcError,
 *         is SparkError.GraphqlError,
 *         is SparkError.FrostSigningFailed -> ui.showTransientFailure()
 *         else -> log.warn("Unexpected SDK error", e)
 *     }
 * }
 * ```
 */
public sealed class SparkError(override val message: String) : Exception(message) {
    /** BIP-39 / BIP-32 derivation failed (invalid mnemonic, invalid path, etc.). */
    public data object KeyDerivationFailed : SparkError("Key derivation failed")

    /** The wallet doesn't have enough spendable sats to cover the requested transfer. */
    public data class InsufficientBalance(val need: Long, val have: Long) : SparkError("Insufficient balance: need $need sats, have $have sats")

    /** SSP or Spark operator rejected the auth handshake. */
    public data class AuthenticationFailed(val msg: String) : SparkError("Authentication failed: $msg")

    /** A gRPC call to a Spark operator failed (network, deadline, status code). */
    public data class GrpcError(val msg: String) : SparkError("gRPC error: $msg")

    /** A GraphQL call to the SSP failed (HTTP, GraphQL `errors[]`, schema). */
    public data class GraphqlError(val msg: String) : SparkError("GraphQL error: $msg")

    /** A response from the operator or SSP was structurally invalid or missing a field. */
    public data class InvalidResponse(val msg: String) : SparkError("Invalid response: $msg")

    /** A FROST signing round failed (operator misbehaviour, key tweak mismatch). */
    public data class FrostSigningFailed(val msg: String) : SparkError("FROST signing failed: $msg")

    /**
     * A leaf's timelock is at the decrement floor — the coordinator refuses to move it
     * (send, swap, withdraw) until `renew_leaf` resets its refund timelock. See
     * `renewExhaustedLeaves`.
     */
    public data class LeafTimelockExhausted(val msg: String) : SparkError(msg)

    /** The requested capability isn't implemented in this SDK version yet. */
    public data class NotImplemented(val msg: String) : SparkError("Not implemented: $msg")

    /** A token transaction failed validation (bad metadata, invalid amount, etc.). */
    public data class TokenValidationFailed(val msg: String) : SparkError("Token validation failed: $msg")

    /** The wallet doesn't have enough of the given token to cover the transfer. */
    public data class InsufficientTokenBalance(val token: String, val need: String, val have: String) :
        SparkError("Insufficient token balance for $token: need $need, have $have")

    /** A caller-supplied argument is invalid (non-positive amount, bad key, ...). */
    public data class InvalidArgument(val msg: String) : SparkError("Invalid argument: $msg")

    /** Transaction bytes from an operator, the SSP, or a block explorer could not be parsed. */
    public data class MalformedTransaction(val msg: String) : SparkError("Malformed transaction: $msg")

    /** A Bitcoin or Spark address is malformed or belongs to another network. */
    public data class InvalidAddress(val msg: String) : SparkError("Invalid address: $msg")

    /** A BOLT-11 invoice is malformed or belongs to another network. */
    public data class InvalidInvoice(val msg: String) : SparkError("Invalid invoice: $msg")

    /** A BIP-39 mnemonic failed wordlist or checksum validation. */
    public data class InvalidMnemonic(val msg: String) : SparkError("Invalid mnemonic: $msg")

    /** A response from the SSP or a coordinator failed client-side validation. Nothing was signed. */
    public data class UntrustedResponse(val msg: String) : SparkError("Response failed validation: $msg")

    /** A quoted fee exceeds the limit the caller allowed. */
    public data class FeeExceedsLimit(val feeSats: Long, val maxFeeSats: Long) :
        SparkError("Quoted fee of $feeSats sats exceeds the allowed maximum of $maxFeeSats sats")

    /**
     * The coordinator locked leaves for a lightning payment but the SSP request failed.
     * Retry `payLightningInvoice` with the same [transferId] to resume, or reconcile via
     * `getTransferFromSsp`.
     */
    public data class LightningSendIncomplete(val transferId: String, val reason: String) :
        SparkError("Lightning send incomplete for transfer $transferId: $reason")
}
