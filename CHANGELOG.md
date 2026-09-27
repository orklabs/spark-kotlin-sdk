# Changelog

All notable changes to `spark-kotlin-sdk` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the SDK is in `0.x`, the public API is considered unstable and minor releases may
contain breaking changes. Each breaking change will be documented under "Changed" with a
migration note.

---

## [Unreleased]

Nothing yet.

---

## [0.3.0] — 2026-09-27

Brings the Kotlin SDK level with spark-swift-sdk 0.3.0 (its `ts-parity-fixes` work, which
follows the reference TypeScript SDK). Items marked *(Kotlin only)* have no Swift counterpart.

### Security
- Token commits check the coordinator's final transaction as the reference SDK does. It must
  carry the wallet's client timestamp (to the millisecond, which the transaction hash covers)
  and keyshare info naming the configured operators. The keyshare checks used to be skipped when
  the coordinator left the info out, and the timestamp was not compared.
- A custom `sspURL` no longer sends the SSP's side of Lightning payments, leaf swaps and
  cooperative exits to Lightspark's SSP key. The key now comes with the SSP
  (`SparkConfig.sspIdentityPublicKeyHex`), as in the reference SDK; the default applies only to
  the default SSP, and without one those operations throw `InvalidArgument` before any leaf moves.
- On mainnet, operators are reached over TLS only: an `http://` operator address (or any scheme
  but `https`) is refused with `InvalidArgument`, where it silently got a plaintext channel that
  carried session tokens and signing material. Regtest still allows `http://` for local operators.
- Deposit addresses are verified before they are returned, as the reference SDK does.
  `getDepositAddress` and `getStaticDepositAddress` check the operators' BIP-340 proof of
  possession, every operator's signature over the address (the coordinator's too for static
  addresses) against the configured keys, and that the address pays the verifying key, and throw
  `SparkError.UntrustedResponse` otherwise. A coordinator — or anyone impersonating it — could
  otherwise hand out an address it alone controls, and a static address is reused for every
  deposit.
- `createLightningInvoice` refuses an SSP-created invoice that carries a Spark fallback — a Spark
  identity in the sentinel route hint (`f42400f424000001`) or a Spark invoice in a version-31
  fallback field — which the wallet never asks for; payers preferring Spark would otherwise pay
  the SSP.
- Integration-test secrets no longer reach the library's `BuildConfig` *(Kotlin only)*. The test
  wallets' mnemonics and Lightning address from `local.properties` were compiled into
  `gy.pig.spark.BuildConfig` for every variant, so an AAR built or published (for example to
  Maven Local) on a machine with them configured carried them. JitPack builds, which have no
  `local.properties`, did not. They now go only into the instrumented-test APK's own
  `BuildConfig`. Delete such locally built or published AARs.

### Added
- `SparkConfig(tokenTransactionVersion = ...)` and `TokenTransactionVersion`: `V3` by default,
  and `V2` for the older two-step flow while the operators accept it.
- `SparkConfig(sspIdentityPublicKeyHex = ...)` and `SparkConfig.DEFAULT_SSP_URL`: the identity
  key of a custom SSP.
- `claimStaticDeposit(transactionId, outputIndex, quote)`: claims a static deposit for exactly
  the credit of a quote from `getDepositFeeEstimate`, as the reference SDK's `claimStaticDeposit`
  does.
- `SparkEvent.Reconnecting(attempt, retryIn, reason)`, reported before each wait of the
  self-reconnecting event stream (see Changed).
- `SparkLeaf.isFrozen`, and `unrenewedSats` on `WithdrawAllQuote` and `WithdrawAllResult`
  (after `frozenSats`): renewable sats a drain leaves behind because the operators did not
  renew them.
- Live suites ported from the Swift SDK *(tests)*: `HardeningIntegrationTests` and every suite
  of its `IntegrationTests` (wallet, address, balance, recovery, consolidation, deposits,
  Lightning, transfers, withdrawals, static deposits, settings, debug and ledger, funding,
  tokens, idempotency, invoice matching, full flow). On-chain spending tests are opt-in
  through instrumentation arguments; see `CONTRIBUTING.md`.

### Changed
- Token transactions (`transferTokens`, `burnTokens`, `mintTokens`, `createToken`) use the
  operators' V3 format by default, as the reference SDK has since 0.5.1; the operators are moving
  to require it. A V3 transaction is one `broadcast_transaction` call, signed over the protohash
  of the partial transaction, which binds its inputs, outputs and amounts. Its outputs carry the
  network's withdraw bond and locktime, and it stays valid for 180 s. The SDK checks that the
  final transaction the operators answer with is the one it signed, and returns that
  transaction's protohash. `SparkConfig(tokenTransactionVersion = TokenTransactionVersion.V2)`
  keeps the two-step V2 flow. protobuf-lite has no descriptors, so the protohash of each message
  involved is written out field by field *(Kotlin only)*; the operators' 29 cross-language
  vectors and a test that compares the fields with `spark_token.proto` keep it exact.
- `getTransfer(id)` and the SDK's own lookups by transfer id use the operators' by-id query
  (`query_transfers_by_id`), as the reference SDK does since 0.9.0. It returns the whole transfer
  and takes the id in any case.
- The protos are re-vendored from `buildonspark/spark` at `0b3a32a` (2026-08-24). They carry the
  event-stream heartbeat, typed leaf signatures, transfer receivers, `query_transfers_by_id`,
  watchtower tree-node statuses and invoice statuses 5–7, and reserve the legacy
  `InitiatePreimageSwapRequest.transfer` and `StorePreimageShareV2Request.user_signature` fields.
  `multisig.proto` gets `java_package = "spark_multisig"` *(Kotlin only)*: protoc's Kotlin DSL
  otherwise generates code that does not compile.
- Static-deposit calls take `outputIndex: UInt? = null`: without an index,
  `getDepositFeeEstimate`, `claimStaticDeposit`, `claimStaticDepositWithMaxFee` and
  `refundStaticDeposit` use the output that pays the wallet's static deposit address, as the
  reference SDK does, instead of output 0. Calls that pass an index are unchanged.
- `claimStaticDeposit(transactionId, outputIndex)` is deprecated: it signs whatever credit the SSP
  quotes. Use `claimStaticDepositWithMaxFee` or the `quote` overload.
- **Breaking:** `subscribeToEvents()` runs until the collector stops or the wallet is closed: it
  reconnects by itself, reporting `SparkEvent.Reconnecting` before each wait, and it claims
  incoming payments itself (see Fixed). `close()` ends running collections normally and refuses
  new subscriptions. *Migration:* an exhaustive `when` over `SparkEvent` must handle
  `Reconnecting`, and a loop that resubscribed after the flow completed can simply keep
  collecting.
- **Breaking:** `claimPendingTransfers()` returns `PendingTransferClaim` (`claimedTransferIds`,
  `failures` of `transferId` + `error`), the Swift SDK's type, in place of 0.2.2's
  `SparkTransferClaim`; `claimAllPendingTransfers()` still returns the number claimed but, as in
  Swift, no longer throws for transfers it could not claim — they are retried by the next pass.
- **Breaking:** token parameter errors are `SparkError.TokenValidationFailed`, as in Swift, where
  `createToken`, `mintTokens` and output selection threw `IllegalArgumentException`
  *(Kotlin only)*.
- `SatsBalance.owned` and `locked` follow the reference SDK: available + frozen + leaves an
  in-flight operation still holds for the wallet (outgoing transfers, Lightning payments and
  cooperative exits before the operators apply the sender's key tweak, swaps the wallet started
  and their counter-transfers until claimed). Sent sats leave `owned` as soon as the transfer is
  committed instead of when the receiver claims it, and `getBalance`/`getLeaves` query only
  AVAILABLE nodes.

### Fixed
- `getStaticDepositAddress` asks for the static-deposit key (`m/8797555'/account'/3'/0'`) with
  hash variant V2, as the Swift and reference SDKs do *(Kotlin only)*. Up to 0.2.2 it sent the
  account's deposit key (`2'`), while static-deposit claims and refunds sign with the
  static-deposit key, so deposits to an address made that way could not be claimed or refunded by
  this SDK. The operators keep returning such an address for the wallet; it is now refused with a
  specific `InvalidResponse` instead of being handed out. Wallets whose static address was made
  by the Swift SDK, or by the reference SDK, are unaffected.
- `burnTokens` pays the burn key the Swift and C# SDKs use, 33 bytes of `0x02`, not `0x02`
  followed by 32 zero bytes *(Kotlin only)*.
- `getTransfers()` lists only the transfers a user makes, as the reference SDK does: Spark
  transfers, Lightning payments, cooperative exits and static deposit claims. It asked the
  operators for every type, leaf-swap legs included, which appeared as sends and receives, and on
  mainnet that query took up to a minute for a wallet with a long history. A lookup by `ids` still
  returns those transfers whatever their type.
- Token transaction hashes order invoice attachments as the operators and the reference SDK do:
  by each invoice's id (its 16 UUID bytes), not by the invoice string. An attachment that is not
  a Spark invoice is refused. The SDK does not attach invoices yet, so no transaction was
  affected.
- `transferTokens(idempotencyKey = ...)` makes a retry safe. A retry built a new transaction, but
  the operators answer a key with the first transaction, so the SDK refused the answer ("final
  token transaction rejected: input 0 changed") after the first transfer had gone through. The
  wallet now remembers each key's transaction (the last 1,000 keys) and resends it unchanged. A
  key used for another token, amount or receiver is refused with `SparkError.InvalidArgument`.
- `createToken` checks the name and ticker as the operators and the reference SDK do: names of
  3–20 UTF-8 bytes and tickers of 3–6, both in Unicode normalization form C. The operators refused
  other tokens with only INTERNAL "Something went wrong."; the SDK now says which rule failed.
- Token sends (`transferTokens`, `burnTokens`) no longer collide. A send could pick outputs of a
  signed transaction that had not finalized (PENDING_OUTBOUND), and two concurrent sends from one
  wallet picked the same outputs, so one failed. As in the reference SDK, a send picks only
  AVAILABLE outputs and locks the ones it picks for 30 s, or until the operators report them
  pending.
- Anyone can send a wallet tokens, and unwanted ones could break its balance. Token metadata is
  asked for 500 tokens at a time (the operators refuse more per query), and `getBalance()`'s
  `tokenBalances` are best effort (empty on failure) so an unreadable token no longer takes the
  sats balance with it; `getTokenBalances()` still throws the error.
- The regtest preset (`SparkConfig(network = SparkNetwork.REGTEST)`) uses the hosted operators
  under their keys, as the reference SDK's REGTEST preset does, instead of localhost with empty
  identity keys.
- SSP requests are retried as in the reference SDK: up to 5 more attempts, 1 s doubling to 10 s,
  on HTTP 502, 503 and 504 and on a failed connection (not on a timeout or cancellation).
- Authentication is shared and retried as in the reference SDK. Concurrent calls share one
  authentication per operator, the auth service gets no transport retries (a retried
  `verify_challenge` re-sends a consumed challenge), and up to 8 challenge exchanges are made: a
  fresh challenge at once when one expired or was already used, after 250 ms on a connection
  failure. A rejected session token is dropped only if it is still the cached one. Unlike
  grpc-swift, grpc-java re-sends the original headers on a transport retry, so the Kotlin
  interceptor keeps re-issuing a rejected call itself (up to 3 attempts, each on a new call); the
  Swift crash on replaying a stream has no Kotlin counterpart.
- The SDK keeps time by the operators' clock, estimated from the `date` and
  `x-processing-time-ms` headers of their answers and advanced on the monotonic clock. Session
  expiry and token-transaction timestamps used the device clock, which re-authenticated every call
  on a device running ahead, and stamped token transactions the operators refuse.
- Leaf renewals carry an idempotency key, the txid of the refund transaction being replaced, so a
  transport retry of a renewal already applied no longer reports a renewed leaf as not renewed.
- Responses over 4 MiB no longer fail: messages up to 20 MB are accepted both ways, and node
  queries are paged at the operators' 100 per page.
- A multi-receiver transfer can be claimed by every receiver: it is narrowed to this wallet's
  receiver edge and leaves before it is verified and claimed, it counts as claimed once this
  wallet's own leg is complete, and `SatsBalance.incoming` counts only this wallet's leaves of it.
- Transfers whose leaves carry a scheme-tagged sender signature can be claimed. Signatures are
  verified by their scheme — ECDSA in strict DER, or BIP-340 Schnorr (a new verifier on
  BouncyCastle, checked against all of libsecp256k1's BIP-340 vectors) — and an unspecified or
  unknown scheme is refused.
- The event stream stays up, as the reference SDK's background stream does. Any error, or the
  operator ending the subscription, completed the flow, and payments that arrived in the meantime
  waited until something else claimed them. It now resubscribes forever — 1 s doubling to 15 s —
  claims the wallet's pending transfers on every connection and reports those payments as
  `TransferReceived`, and claims each payment that arrives while connected before reporting it.
  Once the operators send heartbeats (every 5 s), 15 s of silence outside handling an event drops
  the subscription and resubscribes.
- The event stream no longer reports the counter-transfer of the wallet's own swap, or a
  self-transfer, as a received payment, and reports a deposit once its leaf is available.
- A transfer or leaf value of 2^63 sats or more from an operator — a `uint64` Kotlin reads as a
  negative `Long` — or an output value that large from the block explorer is capped at the
  bitcoin supply instead of turning into a negative amount.
- `send(receiverSparkAddress, ...)` and `transferTokens` refuse Spark invoices with
  `SparkError.InvalidAddress`, as the reference SDK does; addresses are decoded whole, the
  identity key must be a valid curve point, and `transferTokens` checks the receiver before
  fetching outputs. Paying Spark invoices is not supported yet.
- `payLightningInvoice` offers the SSP its fee estimate as is, without a 1-sat floor, and sends
  on the invoice it validated (trimmed and lower case; mixed case is still refused).
- BOLT-11 invoices without a payment secret are refused, as BOLT-11 readers must.
- A Lightning send whose preimage swap fails without a clear refusal (a connection lost after the
  request went out, a deadline, a cancellation, an internal error) throws
  `LightningSendIncomplete` with the transfer id; a swap the operators refused before committing
  still throws its own error.
- Resuming a Lightning send asks the coordinator for the send it holds under the transfer id
  (`query_htlc`), checks that it is this wallet's HTLC to the SSP for this invoice, neither
  returned nor expired, with at most `maxFeeSats` beyond the amount, and has the SSP pay from it.
  The SSP answers a repeated request with the request it already has, so a send that went through
  returns its request id instead of paying twice.
- A Lightning send's preimage swap always carries an idempotency key — the caller's
  `idempotencyKey`, else the transfer id.
- SSP fee amounts are read in the unit the SSP reports (SATOSHI, MILLISATOSHI rounded up; any
  other unit is refused).
- Lightning preimage shares go to the operator that validates them, matched by the index in its
  identifier rather than by the configured order.
- Lightning receives no longer sign the preimage-share request, and Lightning sends no longer fill
  the legacy `transfer` field of `initiate_preimage_swap_v3` (one signing round fewer); the
  current protocol reserves both.
- Amountless Lightning invoices can be paid: the SSP's `request_lightning_send` gets
  `amount_sats` for them (and only for them).
- Renewals: legacy deposit roots with a final (timelock-disabled) node sequence renew with the
  zero-timelock variant; claimed leaves and swap outputs in the renewal range are renewed right
  away; renewals spend the parent's output at the leaf's `vout` and pay P2TR of the leaf's
  verifying key, as the operators rebuild them.
- `SatsBalance.incoming` no longer counts a swap's counter-transfer and sums the leaves of every
  page of pending transfers; `owned` and `locked` no longer grow with every node-level renewal
  (SPLIT_LOCKED split nodes); `frozen` counts only leaves the operators will not renew (a refund
  timelock below 100) — leaves at 100–199 are available, as every spend path renews them first.
- Leaves whose refund timelock is not a multiple of 100 can be spent again: the next refund
  timelock is the current one rounded down to the 100-block interval, minus 100, and a leaf is
  spendable (`SparkLeaf.isSpendable`) when that rounded value is above 100.
- Leaves on a zero-timelock node can be sent and claimed again: no direct refund is built for a
  zero node, which the operators reject.
- One pending transfer the SDK cannot claim no longer blocks the others: claims follow the
  reference SDK's claim pass (pages of 25 until drained, claimable statuses only, failures
  recorded and skipped) and are serialised per wallet; a transfer the operators already recorded
  as claimed by this wallet counts as claimed.
- Static deposits: `claimStaticDepositWithMaxFee` claims the quote it checked, against the value
  of a transaction that hashes to the txid; `refundStaticDeposit` works (the unsigned spend is the
  non-witness serialisation the operators rebuild, the txid goes in display order, and the refund
  statement ends with the raw 32-byte sighash); txids are validated before any request.

---

## [0.2.2] — 2026-09-26

Robustness fixes from an independent review of the 0.2.1 port. The withdraw path was not
affected: it verifies the SSP's exit before signing, exactly like Swift.

### Fixed
- **One bad pending transfer no longer blocks every later claim** (also present in Swift).
  `claimAllPendingTransfers` stopped at the first transfer it could not claim, so a malformed
  or unverifiable inbound transfer left the rest unclaimed and their sats in `incoming`. Every
  transfer is now attempted independently; cancellation still propagates at once.
- **`LightningSendIncomplete` is actually raised.** In 0.2.1 the catch around the SSP step named
  `Exception` in a file that star-imports `uniffi.spark_frost`, whose FROST error class is also
  called `Exception`, so SSP and transport failures escaped without the transfer id. The same
  shadowing is avoided in the new claim loop.
- **Cancelling a lightning send no longer loses the transfer id.** From
  `initiate_preimage_swap_v3` through the SSP request the send runs under `NonCancellable`
  (both calls are bounded by the 60 s RPC deadline and OkHttp timeouts), after checking the
  caller is still active. It ends with the SSP request id or with
  `LightningSendIncomplete(transferId)`, which also becomes the failure of a cancelled
  coroutine — Swift reports a cancelled SSP request the same way.
- **Resuming a lightning send with `transferId` no longer selects leaves again** (also present
  in Swift). Selection ran first, and failed or swapped other leaves because the originals are
  locked in the transfer. The coordinator is now asked for the transfer under that id: an
  outgoing preimage swap from this wallet to the SSP, not expired or returned, covering the
  invoice amount plus a fee within `maxFeeSats`, goes straight back to `request_lightning_send`;
  an id that belongs to anything else is refused (`InvalidArgument` / `FeeExceedsLimit`); no
  transfer under the id means a new send.
- **SSP amounts are parsed strictly** (Kotlin only). Fee estimates went through org.json's
  `getLong`, which truncates `12.5`, parses the string `"12"` and saturates `1e19`, and
  `(msat + 999) / 1000` could wrap to a negative fee that `maxOf(fee, 1)` turned into a 1-sat fee
  slipping under `maxFeeSats`. Lightning and withdrawal fees, and the static-deposit quote's
  `credit_amount_sats`, must now be whole non-negative JSON numbers (`InvalidResponse`
  otherwise; a whole value written as `1500.0` counts, as with Swift's exact `as? Int64`), with
  `Math.addExact` for rounding and sums (`UntrustedResponse` on overflow).
- **DER signatures with an integer missing its 0x00 sign pad are rejected** when verifying
  inbound leaves, as libsecp256k1 does (BouncyCastle's `positiveValue` read them as valid).

### Added
- `claimPendingTransfers(): SparkTransferClaim` — `pending`, `claimed` and
  `failures: List<SparkTransferClaimFailure>` (`transferId`, `error`) — for a per-transfer
  report without an exception. `withdrawAll` / `quoteWithdrawAll` claim through it.
- `PublicApiHygieneTests`, which fails when a hand-written top-level declaration has no
  visibility modifier, and pins the spend internals as internal.

### Changed
- `claimAllPendingTransfers(): Int` keeps its signature. When any claim failed it rethrows the
  first failure *after* attempting every transfer (later failures attached as suppressed
  exceptions), so existing catch blocks see the same exception types.
- **Breaking (API hygiene):** declarations the Swift SDK keeps internal are internal:
  `queryPendingTransfers`, `claimTransfer`, `selectLeavesWithSwap`, `requestLeavesSwap`,
  `tryExactSelection`, `computeNextSequences`, `timelockCanDecrement`, `parseSequenceFromRawTx`,
  `FrostSigningHelper`, `KeyDerivation` / `DerivedKey`, `GrpcConnectionManager`,
  `SparkAuthenticator`, `SspAuthenticator`, `SspGraphQLClient` / `executeGraphQL`,
  `GraphQLMutations` / `GraphQLQueries`, `SparkHasher`, `subtractPrivateKeys`, `sha256`,
  `hmacSHA256`, `hmacSHA512`, `decodeBase64URL`, `parseISODate`, `String.hexToByteArray`,
  `ByteArray.toHexString`, `hashTokenTransactionV2`, `hashOperatorSpecificPayload`,
  `TokenIdentifierPrefix`, the `SPARK_*` constants and `SparkConfig.defaultThreshold`.
  *Migration:* use the documented operations (`claimAllPendingTransfers` /
  `claimPendingTransfers`, `send`, `withdraw`, `payLightningInvoice`, ...) and your own hex
  helper. piggy-android uses none of these.
- Every hand-written declaration states its visibility. Explicit API mode stays at `Warning`:
  it is module-wide and the generated UniFFI bindings declare no visibility, so `Strict` would
  fail the build; the only warnings left are in `spark_frost.kt`.
- `org.json:json` is a test-only dependency (Android's `org.json` is a throwing stub in JVM unit
  tests); it is not part of the published POM.

---

## [0.2.1] — 2026-09-26

Brings the Kotlin SDK level with spark-swift-sdk 0.2.1. The Kotlin SDK was not tagged at
0.2.0; everything listed under [0.2.0] below ships for the first time in this release.

### Added
- `SatsBalance.locked`: sats held by an in-flight transfer, swap, renewal or exit
  (`owned - available - frozen`), the figure `WithdrawAllQuote.lockedSats` and
  `WithdrawAllResult.lockedSats` already report.
- Unit tests ported from the Swift SDK's offline suites (`BitcoinPrimitivesTests`,
  `ClaimVerificationTests`, `CoopExitFlowTests`, `LightningValidationTests`,
  `MnemonicValidationTests`, `RobustnessTests`, `TokenTransactionValidationTests`,
  `TransportHardeningTests`, `WithdrawalValidationTests`). The few that exercise the
  FROST library run when `SPARK_FROST_HOST_LIBRARY` points at a host build of
  `spark-frost` (the bundled `.so` files are Android-only) and are skipped otherwise.

### Changed
- Internal cleanup matching the Swift 0.2.1 tidy-up: the unused greedy `selectLeaves()`
  and `decodeBolt11PaymentHash()` are removed, `Bech32m` is a thin bech32m-only layer over
  `Bech32`, key-tweak shares are paired with operators by index, and invoice creation uses
  the configured signing threshold.
- `SparkWallet.close()` only drops the gRPC channels; it no longer cancels an (unused)
  internal scope, and the wallet stays usable afterwards, as in Swift.

### Fixed
- `spotlessCheck` and `detekt` failures already on `main` (formatting only).

---

## [0.2.0] — 2026-09-26 (not tagged separately; ships in 0.2.1)

### Security
- `withdraw` verifies the SSP's cooperative-exit response before signing: the raw exit
  transaction must hash to the reported txid, pay the destination at least
  `amount - fee`, and the connector transaction must spend it. Leaves are swapped to
  exactly `amountSats` first, so a partial withdrawal can no longer send the full value of
  an oversized leaf. Fees are bounded by a new optional `maxFeeSats` (default: the SSP's
  own quote).
- `withdraw` speaks the cooperative-exit protocol the coordinator requires today: the
  connector-input refund transactions are FROST-signed by the user and sent together with
  the key-tweak package in a single `cooperative_exit_v2` call. The previous two-step form
  (unsigned jobs, then `finalize_transfer_with_transfer_package`) is rejected by mainnet
  with "transfer_package is required for cooperative exit".
- `payLightningInvoice` now requires `maxFeeSats` and refuses a higher SSP estimate.
  Invoices are decoded by a BOLT-11 parser that verifies the checksum, enforces the
  wallet's network and handles hostile amounts without overflowing. A caller amount is
  only accepted for amountless invoices. Failures after the coordinator locked leaves
  throw `SparkError.LightningSendIncomplete(transferId, reason)`; pass `transferId` to
  resume.
- `createLightningInvoice` verifies the SSP-returned invoice (payment hash, amount,
  network) before storing preimage shares.
- Inbound transfer claims verify the sender's signature on every leaf, and that the
  transfer is addressed to this wallet, before any secret is decrypted.
- Token commits verify the coordinator's final transaction against the submitted partial
  transaction (inputs, outputs, amounts, owners, operator keys, withdraw bond and
  locktime, keyshare info).
- The coordinator's operator list is reconciled with the local configuration; secret
  shares are encrypted only to configured operator keys.
- Mnemonics are validated against the BIP-39 English wordlist and checksum
  (`SparkError.InvalidMnemonic`); pass `validateMnemonic = false` to opt out.
- Raw transactions from operators, the SSP and the block explorer are parsed with bounds
  checks (`SparkError.MalformedTransaction`) instead of unchecked indexing.
- Spending paths (`send`, `payLightningInvoice`, `withdraw`, swaps) renew leaves whose
  refund timelock is in [100, 200) before selecting, and never select leaves at the
  timelock floor, so one stuck leaf cannot fail a payment other leaves could cover.
  `renewExhaustedLeaves` reports leaves below the coordinator's renewal minimum (100)
  without a round trip; those can only be recovered by a unilateral exit.
- Transport: every RPC carries a 60 s deadline and the official retry policy (3 attempts,
  1 s → 10 s, UNAVAILABLE and CANCELLED; the event stream is exempt). An operator that
  answers UNAUTHENTICATED gets a fresh session and the call is replayed once; an SSP auth
  rejection drops the cached SSP token and retries once.

### Added
- `SatsBalance.frozen`: sats in AVAILABLE leaves at the timelock floor. They are no longer
  counted in `available`, which now means "can be sent right now", so sending the full
  `available` balance always succeeds.
- `withdrawAll(onChainAddress, maxFeeSats = null): WithdrawAllResult` and
  `quoteWithdrawAll(onChainAddress): WithdrawAllQuote`: claim pending transfers, renew
  renewable leaves, and exit every spendable leaf in one cooperative exit. The result
  reports the verified payout, the fee the SSP took, and the frozen, locked and unclaimed
  sats that stayed behind; the quote reports the same up front with `frozenFraction` for
  product decisions.
- `getSpendableLeaves()`: the leaves every spend path selects from (renews what the
  coordinator will renew, excludes frozen leaves), plus `SparkLeaf.isSpendable` and
  `SparkLeaf.isRenewable` (extension properties, like `refundTimelockBlocks`). Use it, or
  `satsBalance.available`, as the basis for a "send everything" amount.
- `send(receiverSparkAddress, amountSats)` with network-checked Spark address decoding;
  `send` validates the amount and receiver key before touching a leaf.
- `SparkConfig.signingThreshold`, `expectedWithdrawBondSats`,
  `expectedWithdrawRelativeBlockLocktime`.
- `SparkError` cases: `InvalidArgument`, `MalformedTransaction`, `InvalidAddress`,
  `InvalidInvoice`, `InvalidMnemonic`, `UntrustedResponse`,
  `FeeExceedsLimit(feeSats, maxFeeSats)`, `LightningSendIncomplete(transferId, reason)`.
- Bitcoin address decoding for P2PKH, P2SH, P2WPKH, P2WSH and P2TR with BIP-173/350 rules
  and network enforcement (static-deposit refunds previously rejected `bc1q`
  destinations).
- `claimDeposit(txID, vout: UInt? = null)` checks the explorer's bytes hash to `txID` and
  claims the output that pays one of the wallet's unused deposit addresses; `vout` is now
  optional.

### Changed
- **Breaking:** `payLightningInvoice(paymentRequest, maxFeeSats, amountSats = null,
  idempotencyKey = null, transferId = null)` takes a required `maxFeeSats`.
  *Migration:* pass the most you are willing to pay, e.g. the result of
  `getLightningSendFeeEstimate(invoice)` plus a margin; handle `SparkError.FeeExceedsLimit`
  and `SparkError.LightningSendIncomplete`. `amountSats` must be omitted (or equal) for an
  invoice that carries an amount.
- **Breaking:** `exportAccountKey()` throws `SparkError.InvalidArgument` instead of a
  `ClassCastException` when the wallet uses a custom signer.
- **Breaking:** `SatsBalance` has a fourth constructor parameter, `frozen`, and
  `available` excludes frozen leaves. *Migration:* show `owned` (or `available + frozen`)
  where the old `available` meant "everything I have"; use `available` for "send max".
- **Breaking:** `SparkWallet.fromMnemonic` / `SparkSigner.fromMnemonic` /
  `KeyDerivation.fromMnemonic` reject phrases that are not valid BIP-39 English with
  `SparkError.InvalidMnemonic`. *Migration:* pass `validateMnemonic = false` only for
  stored phrases known to be non-standard.
- `withdraw` amount semantics: exactly `amountSats` is withdrawn, fee deducted from it.
  `withdraw(onChainAddress, amountSats, maxFeeSats = null)` — existing two-argument calls
  compile unchanged.
- `claimDeposit`'s `vout` defaults to `null` (locate the output) instead of `0`.
- `getLeaves()` still returns every AVAILABLE leaf, frozen ones included; spend paths use
  `getSpendableLeaves()`. Spendability follows the Swift rule everywhere (refund timelock
  strictly above 100, the same test `SatsBalance.available` uses); the Kotlin-only
  rounded-down swap filter is gone.
- Removed APIs that were public by accident: `decodeBolt11PaymentHash`, `selectLeaves`,
  and the `Bech32m` helpers (`Bech32`/`Bech32m` and `KeyTweakHelper` are internal now).

---

## [0.1.0]

Initial public release.

### Added
- `SparkWallet` core API with deposits, transfers, withdrawals, Lightning send / receive,
  tokens, swaps, settings, and event streaming via `Flow<SparkEvent>`.
- `SparkConfig` with mainnet and regtest defaults plus pluggable operator / SSP override.
- `SparkError` typed `sealed class` covering key derivation, balance, auth, gRPC,
  GraphQL, FROST signing, token validation, and not-implemented cases.
- gRPC transport via `grpc-okhttp` + `grpc-kotlin-stub` and SSP GraphQL client built on
  OkHttp.
- FROST threshold signing via the bundled `libspark_frost.so` /
  `libuniffi_spark_frost.so` native libraries (Rust UniFFI bindings) for `arm64-v8a`,
  `armeabi-v7a`, `x86`, `x86_64`, all built with 16 KB ELF page alignment.
- `WalletBalance` carries a structured `satsBalance: SatsBalance` breakdown
  (`available` / `owned` / `incoming`) and a `tokenBalances: List<TokenBalance>` list,
  matching the official Swift SDK shape.
- `getBalance()` computes buckets locally from a single `query_nodes` round-trip plus
  `queryPendingTransfers()`, mirroring the Swift SDK algorithm so the same wallet queried
  from iOS and Android composes into identical numbers. Statuses are compared against the
  same constant set the Swift SDK uses (`AVAILABLE`, `TRANSFER_LOCKED`, `SPLIT_LOCKED`,
  `AGGREGATE_LOCK`, `RENEW_LOCKED`, `CREATING`).
- `WalletBalance.totalSats` is retained as a `@Deprecated` accessor returning
  `satsBalance.available`, equivalent to the Swift SDK's deprecated `balance: Int64`.
- `LICENSE` (MIT), `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `NOTICE`,
  and a comprehensive `README.md`.
- `maven-publish` + `signing` configuration with a complete POM (description, license,
  SCM, developer, issue tracker), sources JAR, and Sonatype OSSRH staging repository
  wired for both snapshot and release lanes.
- Publishing coordinates surfaced as Gradle properties
  (`sdk.groupId`, `sdk.artifactId`, `sdk.version`) so the version can be bumped without
  touching `lib/build.gradle.kts`.
- Consumer ProGuard / R8 rules covering the public `gy.pig.spark.**` API, protobuf-lite,
  gRPC stubs, UniFFI / JNA, BouncyCastle, and native JNI methods. Apps minifying their
  release builds no longer need manual keep rules.
- GitHub Actions CI workflow (`.github/workflows/ci.yml`) running build, unit tests, and
  lint on every PR; release workflow (`.github/workflows/release.yml`) for tagged
  publications; Dependabot config; issue / PR templates; `CODEOWNERS`; `FUNDING.yml`.
- Spotless + ktlint formatting (`./gradlew :lib:spotlessCheck`) and Dokka API
  documentation (`./gradlew :lib:dokkaHtml`).
- Kotlin explicit API mode (`explicitApi()`) so public surface cannot leak by accident.
- `samples/quickstart/` minimal CLI demonstrating wallet creation, deposit address,
  invoice, transfer, withdraw.
- `jitpack.yml` so JitPack builds the SDK with JDK 17 (Gradle 9.3.1 requirement).
- Documentation of the native FROST build pipeline (`build-frost-android.sh`) and
  binary provenance requirements in `CONTRIBUTING.md` and `SECURITY.md`.
- Test suite: BIP-39 vectors, key derivation, hex parsing, token validation
  (`SparkSDKTests`, `TokenTests`), and live-network integration coverage
  (`IntegrationTests`) gated on a connected device / emulator.

### Security
- Documented threat model, scope, and reporting channel in `SECURITY.md`.
- Documented the supply chain for the bundled `libspark_frost.so` /
  `libuniffi_spark_frost.so` native libraries: reproducible from the published
  [`buildonspark/spark`](https://github.com/buildonspark/spark) commit hash via
  `build-frost-android.sh`. PRs that update the binaries must include a SHA-256 hash
  and the upstream commit they were built from.

[Unreleased]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.2.2...v0.3.0
[0.2.2]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.1.0...v0.2.1
[0.2.0]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.1.0...v0.2.1
[0.1.0]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/releases/tag/v0.1.0
