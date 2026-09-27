# Contributing to spark-kotlin-sdk

Thanks for your interest in contributing! This document describes how to set up a
development environment, the workflow we follow, and the standards we expect.

By participating in this project you agree to abide by our
[Code of Conduct](CODE_OF_CONDUCT.md). For security issues, please follow
[SECURITY.md](SECURITY.md) instead of opening a public issue.

---

## Table of Contents

- [Quick Start](#quick-start)
- [Project Layout](#project-layout)
- [Building](#building)
- [Testing](#testing)
- [Regenerating Protobufs](#regenerating-protobufs)
- [Rebuilding the FROST native libraries](#rebuilding-the-frost-native-libraries)
- [Coding Standards](#coding-standards)
- [Commit & PR Guidelines](#commit--pr-guidelines)
- [Release Process](#release-process)

---

## Quick Start

```bash
git clone https://github.com/p-i-g-g-y/spark-kotlin-sdk.git
cd spark-kotlin-sdk

# Point Gradle at your Android SDK (this file is gitignored)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :lib:assembleRelease
./gradlew :lib:testDebugUnitTest    # unit tests, no network
```

**Requirements**

- JDK 17 (Gradle toolchain will auto-provision via Foojay if needed)
- Android SDK with `cmdline-tools` + `platform-tools`
- AGP 9.1 (managed via `gradle/libs.versions.toml`)
- Kotlin 2.2 (managed via AGP's built-in Kotlin support)
- For regenerating native bindings: Docker + ~6 GB of disk (the Rust build runs in a
  Linux container)

## Project Layout

```
spark-kotlin-sdk/
├── build.gradle.kts                  # Root project
├── settings.gradle.kts
├── gradle.properties                 # Publishing coordinates (sdk.* properties)
├── gradle/libs.versions.toml         # Version catalog
├── build-frost-android.sh            # Docker-based Rust → .so build
├── lib/
│   ├── build.gradle.kts              # Android library + publishing
│   ├── consumer-rules.pro            # Ships with the AAR (R8 keep rules)
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── kotlin/gy/pig/spark/  # Public API + service files
│       │   │   └── frost/uniffi/spark_frost/  # Generated UniFFI bindings — DO NOT EDIT
│       │   ├── proto/                # .proto source-of-truth (mirrored from upstream)
│       │   └── jniLibs/              # Precompiled FROST native libraries (4 ABIs)
│       ├── test/kotlin/              # Unit tests — no network
│       └── androidTest/kotlin/       # Integration tests — live network, real funds
└── samples/
    └── quickstart/                   # Minimal usage example
```

## Building

```bash
./gradlew :lib:assembleDebug             # debug AAR
./gradlew :lib:assembleRelease           # release AAR
./gradlew :lib:publishToMavenLocal       # publish to ~/.m2 for local consumption
```

To verify a clean configuration:

```bash
./gradlew --stop
rm -rf .gradle build lib/build
./gradlew :lib:assembleRelease
```

## Testing

### Unit tests (no network, no funds)

```bash
./gradlew :lib:testDebugUnitTest
./gradlew :lib:testReleaseUnitTest       # same suite against release config
```

These cover key derivation, BIP-39 vectors, hex parsing, token validation, and
deterministic helpers. They use the canonical `abandon abandon … about` BIP-39 test
vector and require no configuration.

### Integration tests (live network, real funds)

Integration tests connect to live Spark operators and submit real transactions.
**They require funded test wallets and a connected device or emulator.**

```bash
adb devices                              # confirm a device is connected
./gradlew :lib:connectedAndroidTest
```

Funded mnemonics and other live-network inputs are supplied through **either**
`local.properties` (preferred) **or** environment variables. Both are loaded at
Gradle configure time and exposed to instrumented tests via `BuildConfig` fields,
which are read through the [`TestConfig`](lib/src/androidTest/kotlin/gy/pig/spark/TestConfig.kt)
helper. See `lib/src/androidTest/kotlin/gy/pig/spark/IntegrationTests.kt` for the
call sites.

**Option A — `local.properties` (recommended for local dev).** Already gitignored.
Add the entries below alongside `sdk.dir=…`:

```properties
spark.test.walletA.mnemonic=word1 word2 ... word12
spark.test.walletB.mnemonic=word1 word2 ... word12
spark.test.lnAddress=user@host
```

**Option B — environment variables (recommended for CI / one-off runs).** Copy
`.env.example` to `.env.local` (gitignored) and either `source` it before invoking
Gradle, or export the variables in your shell profile:

```bash
cp .env.example .env.local
# edit .env.local
set -a; source .env.local; set +a
./gradlew :lib:connectedAndroidTest
```

| Gradle key (`local.properties`) | Env var | Required | Purpose |
|---|---|---|---|
| `spark.test.walletA.mnemonic` | `SPARK_TEST_WALLET_A_MNEMONIC` | for any test | Funded with ≥ 500 sats |
| `spark.test.walletB.mnemonic` | `SPARK_TEST_WALLET_B_MNEMONIC` | for any test | Does not need funds |
| `spark.test.lnAddress`        | `SPARK_TEST_LN_ADDRESS`        | for LN tests | LNURL-pay recipient |

Tests that need a value the runner did not supply **skip** via JUnit's `Assume`
mechanism — they never fail. Run the full suite end-to-end only after populating
both wallet entries.

> ⚠️ **Never commit mnemonics.** A mnemonic committed to git history is compromised
> forever — rotate the funds immediately. `.env.local`, `local.properties`, `.env`,
> `*.keystore`, and `secring.gpg` are all in `.gitignore`; check `git status` before
> every commit.

## Regenerating Protobufs

The protobuf and gRPC stubs in `lib/build/generated/source/proto/` are generated by the
`com.google.protobuf` Gradle plugin from `lib/src/main/proto/*.proto`. The `.proto` files
themselves are the source of truth and are mirrored from
[`buildonspark/spark`](https://github.com/buildonspark/spark) (`protos/`, currently at
`0b3a32a`, 2026-08-24 — the same revision as spark-swift-sdk's `Protos/`). Two Kotlin-only
`java_package` options are added on top: `spark_token.proto` (with `java_multiple_files`) and
`multisig.proto`, whose `SigningAuthority.multisig` field would otherwise shadow the `multisig`
package in protoc's generated Kotlin DSL.

To regenerate after editing a `.proto`:

```bash
./gradlew :lib:generateDebugProto
./gradlew :lib:generateReleaseProto
```

If you change a `.proto`, commit the `.proto` change — the generated Java/Kotlin output
is regenerated on every build and is not in version control.

## Rebuilding the FROST native libraries

The bundled `.so` libraries under `lib/src/main/jniLibs/` come from the `spark-frost-uniffi`
Rust crate. The build is driven by `build-frost-android.sh`, which runs a Linux Docker
container with the Android NDK and the Rust toolchain so the result is reproducible
regardless of host OS.

```bash
./build-frost-android.sh
```

**Prerequisites:** Docker Desktop (or Docker Engine on Linux) with at least 6 GB of free
disk space. The first run is slow (downloads NDK r27c, ~1 GB) but cached afterwards.

**What it does:**

1. Pulls `rust:1.88-bookworm`, installs Android NDK r27c, protoc, and the four Android
   Rust targets (`aarch64-`, `armv7-`, `x86_64-`, `i686-linux-android`).
2. Clones [`buildonspark/spark`](https://github.com/buildonspark/spark) at depth 1.
3. Runs `uniffi-bindgen` to regenerate the Kotlin bindings from `spark_frost.udl`.
4. Builds `libspark_frost.so` for each ABI with
   `RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"` so the binaries pass the
   Android 15 / Play Store 16 KB ELF alignment check (mandatory Nov 1, 2025).
5. Copies the four `.so` files into `lib/src/main/jniLibs/<abi>/libspark_frost.so` and
   alias-copies each as `libuniffi_spark_frost.so` (the UniFFI bindings call
   `Native.load("uniffi_spark_frost")`, while the cdylib name is `spark_frost`).
6. Drops regenerated Kotlin bindings into
   `lib/src/main/kotlin/gy/pig/spark/frost/`.

**Native binary provenance.** Replacing the `.so` files is security-sensitive. PRs that
update the binaries MUST:

1. Pin a specific `buildonspark/spark` commit hash (record it in the PR description).
2. Include the SHA-256 hash of each replaced `.so`:
   ```bash
   shasum -a 256 lib/src/main/jniLibs/*/lib*.so
   ```
3. Be reviewed by a maintainer with cryptography sign-off.

## Coding Standards

- **Kotlin 2.2 idioms** — prefer `suspend`, `Flow`, `sealed class` for errors, `data
  class` for value types.
- **Public API gets KDoc** (`/** ... */`) describing parameters, throws, and return
  values. Dokka renders these to HTML.
- **No `println` outside samples and tests.** Use `android.util.Log` or surface
  diagnostics through the public API.
- **Errors must be `SparkError` subclasses** when thrown across the public boundary.
- **No new force unwraps** (`!!`) in production code without a comment explaining why
  the invariant holds. Prefer `requireNotNull`, `checkNotNull`, or explicit
  `SparkError` throws.
- **Explicit API mode** (`kotlin.explicitApi()`) is enabled — every public declaration
  must have an explicit visibility modifier and an explicit return type.
- **Format with Spotless / ktlint**:
  ```bash
  ./gradlew :lib:spotlessCheck            # CI gate
  ./gradlew :lib:spotlessApply            # auto-fix
  ```
- **Generated code is exempt.** Files under `lib/src/main/kotlin/gy/pig/spark/frost/`
  and the protobuf output are excluded from formatting and linting.

## Commit & PR Guidelines

- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`,
  `docs:`, `refactor:`, `test:`, `chore:`. Breaking changes use `feat!:` or include a
  `BREAKING CHANGE:` footer.
- Keep PRs focused. Bundle `.proto` changes with the code that consumes them.
- Each PR should:
  - Pass CI (`build`, `testDebugUnitTest`, `spotlessCheck`).
  - Add or update tests for behaviour changes.
  - Update `CHANGELOG.md` under the `## [Unreleased]` heading.
  - Update relevant KDoc and `README.md` sections.

## Release Process

1. Update `CHANGELOG.md` — move `## [Unreleased]` items under a new `## [x.y.z]` heading
   with the date.
2. Bump `sdk.version` in `gradle.properties` (drop `-SNAPSHOT` for a final release).
3. Run the full check locally:
   ```bash
   ./gradlew :lib:spotlessCheck :lib:testDebugUnitTest :lib:assembleRelease
   ./gradlew :lib:dokkaHtml
   ./gradlew :lib:generatePomFileForReleasePublication
   ```
4. Tag the release: `git tag -s vX.Y.Z -m "Release vX.Y.Z"` (signed tags preferred).
5. Push: `git push origin main vX.Y.Z`. The `release.yml` workflow stages the artifact
   to Sonatype OSSRH and creates the GitHub Release with auto-generated notes.
6. Close the staging repository on OSS Sonatype and release to Maven Central (or use
   `./gradlew :lib:publishReleasePublicationToOssrhRepository closeAndReleaseRepository`
   if you've configured the nexus-publish plugin).
7. Reset `sdk.version` back to the next `x.y.z-SNAPSHOT` and announce.

---

Thanks again for contributing!
