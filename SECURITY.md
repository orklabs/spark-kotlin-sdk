# Security Policy

`spark-kotlin-sdk` handles cryptographic keys that control real Bitcoin. We take security
seriously and appreciate responsible disclosure from the community.

## Reporting a Vulnerability

**Do not file public GitHub issues for security vulnerabilities.**

Please report security issues privately by either:

1. **Email** — `gm@orklabs.com`
2. **GitHub Security Advisories** — use the
   [private vulnerability reporting](https://github.com/p-i-g-g-y/spark-kotlin-sdk/security/advisories/new)
   form on this repository.

Include as much of the following as possible:

- A description of the vulnerability and its impact.
- A minimal proof-of-concept (code, transactions, network captures).
- The affected version(s) / commit(s).
- Your name and affiliation, if you'd like credit in the advisory.

## Response Targets

| Phase | Target |
|---|---|
| Acknowledgement | Within 3 business days |
| Initial triage  | Within 7 business days |
| Fix or mitigation | Severity-dependent; critical issues prioritised within 30 days |
| Public disclosure | Coordinated with reporter after a fix ships |

We will keep you updated through the process and credit you in the published advisory unless
you prefer otherwise.

## Scope

In scope:

- The `gy.pig.spark` Kotlin module and its public API.
- The bundled native libraries under `lib/src/main/jniLibs/` (`libspark_frost.so`,
  `libuniffi_spark_frost.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).
- The build script `build-frost-android.sh`.

Out of scope:

- Vulnerabilities in upstream dependencies (`grpc-java`, `grpc-kotlin`, `protobuf`,
  `okhttp`, `BouncyCastle`, `JNA`, and the `spark-frost` Rust crate). Please report
  those upstream; we'll bump pins as soon as a fix is released.
- Vulnerabilities in the Spark protocol itself or in Spark Service Provider (SSP)
  infrastructure — report those to the
  [Spark project](https://github.com/buildonspark/spark) directly.
- Issues that require physical device access, a rooted device, a compromised host
  application, or a malicious dependency injected by the consuming app.
- Findings against unsupported versions (anything older than the latest minor release).

## Threat Model

`spark-kotlin-sdk` assumes:

- **The host process is trusted.** The SDK does not defend against a compromised
  Android application reading process memory, hooking the JVM, intercepting JNI
  calls, or modifying APKs at runtime.
- **The user is responsible for secure storage** of mnemonics and account keys. The
  SDK does not provide Android Keystore integration, encrypted-at-rest storage, or
  hardware-key isolation (StrongBox / TEE). Apps embedding the SDK MUST take care
  of these. We recommend the
  [Android Keystore system](https://developer.android.com/training/articles/keystore)
  with `setUserAuthenticationRequired(true)` for production wallets.
- **Network transport** is gRPC over HTTP/2 + TLS to Spark operators and HTTPS
  (OkHttp) to the SSP. The SDK does **not** implement certificate pinning by default
  — apps that require it should configure an OkHttp `CertificatePinner` and pass a
  custom `OkHttpClient` through their `SparkSigner` setup, or fork the SDK to pin
  the gRPC channel. The response verification described below is what limits the
  damage an impersonated SSP or coordinator can do.
- **Cryptography** is provided by:
  - [BouncyCastle](https://www.bouncycastle.org/) (`bcprov-jdk18on`) for ECDSA,
    Schnorr, hashing, and BIP-32 / BIP-39 primitives.
  - The [`spark-frost`](https://github.com/buildonspark/spark) Rust crate (via UniFFI
    + JNI) for FROST threshold signing rounds.

  Bugs in those libraries are out of scope and tracked upstream.
- **Device integrity** is the app's responsibility. The SDK does not perform
  root/jailbreak detection, frida detection, or debugger detection. Apps targeting
  high-value users should consider Play Integrity API or equivalent attestation.

## Cryptographic Material Handling

- **Mnemonics** are accepted as `String` and converted to seed material via
  PBKDF2-HMAC-SHA512 (BIP-39). `String` cannot be reliably zeroed on the JVM — the
  underlying `char[]` is interned and managed by the GC. Apps that need explicit
  zeroization should prefer `SparkWallet.fromAccountKey(...)` with a short-lived
  `ByteArray` they control and overwrite (`buf.fill(0)`) immediately after use.
- **Account keys** never leave the device. The SDK derives identity and signing keys
  locally via BIP-32 and uses them for FROST signing rounds with operators. Only
  signature shares and public-key material are transmitted.
- **JNI/native boundary.** Key bytes do cross the JVM↔native boundary into the
  FROST signer. Native memory is **not** explicitly zeroed today; this is a known
  hardening item.
- **No telemetry.** The SDK makes no network calls beyond the configured Spark
  operators, the SSP endpoint, and a block explorer (`mempool.space` on mainnet, a
  local electrs on regtest) used to fetch raw transactions for one-time deposit
  claims and to broadcast static-deposit refunds. Transaction ids sent to the
  explorer are visible to it. There is no analytics, crash reporting, or remote
  config built in.

## What the SDK Verifies

Responses from the Spark Service Provider and the coordinator are checked on the device
before any key material is used:

- **Withdrawals** — the SSP's exit transaction must hash to the reported txid and pay
  the requested address at least `amount - fee`; the connector transaction must spend
  it with one output per leaf; the fee is bounded by the caller's `maxFeeSats` or the
  SSP's own quote. Leaves are swapped to the exact amount first so no more than
  requested leaves the wallet. The connector refunds are then FROST-signed by the user
  and handed over with the key tweaks in a single `cooperative_exit_v2` call.
- **Lightning sends** — the invoice must decode (BOLT-11 checksum, network,
  overflow-checked amount) and belong to the wallet's network; the SSP fee estimate
  must be within `maxFeeSats`.
- **Lightning receives** — the invoice the SSP returns must carry our payment hash,
  amount and network before preimage shares are stored.
- **Inbound transfers** — every leaf's sender signature over
  `sha256(leafId || transferId || secretCipher)` is verified against the sender
  identity key, and the transfer must be addressed to this wallet, before any secret is
  decrypted or refund signed.
- **Token transactions** — the coordinator's final transaction must equal the
  submitted partial transaction apart from server-set fields, with the expected
  withdraw bond and locktime and a keyshare naming the configured operators.
- **Operators** — secret shares are only ever encrypted to operator identity keys from
  the local configuration; a coordinator operator list that does not match the
  configuration is refused.
- **Raw data** — transactions from operators, the SSP and the block explorer are
  parsed with bounds checks; addresses are decoded per network (BIP-173/350).
- **Mnemonics** — validated against the BIP-39 English wordlist and checksum by default.

Anything that fails these checks throws (`SparkError.UntrustedResponse`,
`FeeExceedsLimit`, `InvalidInvoice`, `InvalidAddress`, `MalformedTransaction`, ...)
before a signature is produced.

## Native Binary Provenance

The `.so` files under `lib/src/main/jniLibs/` are compiled from the
[`spark-frost`](https://github.com/buildonspark/spark) Rust crate via UniFFI. The
build is driven by `build-frost-android.sh`, which targets four Android ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) with 16 KB ELF page alignment for
Android 15+ compatibility.

The current SHA-256 manifest of every shipped `.so`, the reviewer verification
procedure, and a description of each binary are maintained in
[NATIVE_BINARIES.md](NATIVE_BINARIES.md).

Replacing the binaries is a security-sensitive operation. PRs that update them must:

1. Be reproducible from a published `spark-frost` commit hash (record it in the
   PR description).
2. Include a SHA-256 hash of each replaced `.so` in the PR description.
3. Be reviewed by a maintainer with cryptography sign-off.
4. Update the manifest in [NATIVE_BINARIES.md](NATIVE_BINARIES.md).

## Audit Status

This SDK has **not** undergone a formal third-party security audit yet. Use at your
own risk in production. We will publish audit reports here as they become available.

## Supported Versions

We aim to ship security fixes for the latest minor release. While the SDK is in
`0.x`, only the most recent published version is officially supported.

| Version | Supported |
|---|---|
| `0.x` (latest) | Yes |
| Older `0.x`    | Best-effort |
| `pre-release`  | No |
