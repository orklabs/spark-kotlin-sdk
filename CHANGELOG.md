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

[Unreleased]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.1.0...v0.2.1
[0.2.0]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/compare/v0.1.0...v0.2.1
[0.1.0]: https://github.com/p-i-g-g-y/spark-kotlin-sdk/releases/tag/v0.1.0
