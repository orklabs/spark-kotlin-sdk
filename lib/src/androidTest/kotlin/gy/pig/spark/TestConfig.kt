package gy.pig.spark

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume

/**
 * Centralised access to integration-test secrets and live-network parameters.
 *
 * Values originate from (priority order, evaluated at Gradle configure time):
 *
 *  1. `<repo>/local.properties` (gitignored) — keys:
 *     ```
 *     spark.test.walletA.mnemonic=<bip39 mnemonic>
 *     spark.test.walletB.mnemonic=<bip39 mnemonic>
 *     spark.test.lnAddress=user@host
 *     spark.test.staticDeposit.mnemonic=<bip39 mnemonic>   # optional, with the four below
 *     spark.test.staticDeposit.account=11
 *     spark.test.staticDeposit.address=bc1p…
 *     spark.test.staticDeposit.txid=<txid>
 *     spark.test.staticDeposit.withdrawAddress=bc1…
 *     ```
 *  2. Environment variables, named as in the Swift SDK's `.env`:
 *     ```
 *     SPARK_TEST_WALLET_A_MNEMONIC
 *     SPARK_TEST_WALLET_B_MNEMONIC
 *     SPARK_TEST_LN_ADDRESS (or SPARK_TEST_LIGHTNING_ADDRESS)
 *     SPARK_TEST_STATIC_DEPOSIT_MNEMONIC, _ACCOUNT, _ADDRESS, _TXID, _WITHDRAW_ADDRESS
 *     ```
 *  3. Empty string — tests that require the value skip via [requireMnemonics]
 *     or [requireLnAddress].
 *
 * They reach the instrumented-test APK's own `BuildConfig` only, never the library's.
 * Opt-in switches for tests that spend on-chain (`SPARK_TEST_ALLOW_WITHDRAW=1`, …) are
 * instrumentation arguments: `-Pandroid.testInstrumentationRunnerArguments.SPARK_TEST_ALLOW_WITHDRAW=1`.
 *
 * **Never** log any value returned here. **Never** commit `local.properties`
 * (it is in `.gitignore`).
 */
object TestConfig {

    /** Funded test wallet — must contain ≥ [MINIMUM_BALANCE_SATS] sats on mainnet. */
    val walletAMnemonic: String get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_WALLET_A_MNEMONIC

    /** Receiver-side test wallet — does not need to be funded. */
    val walletBMnemonic: String get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_WALLET_B_MNEMONIC

    /** Lightning address (`user@host`) used by LNURL-pay test paths. */
    val lnAddress: String get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_LN_ADDRESS

    /** Wallet that owns a funded static deposit, or `null`. */
    val staticDepositMnemonic: String? get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_STATIC_DEPOSIT_MNEMONIC.ifEmpty { null }

    /** Account of [staticDepositMnemonic] (11 unless configured). */
    val staticDepositAccount: Int get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_STATIC_DEPOSIT_ACCOUNT.toIntOrNull() ?: 11

    /** Static deposit address with confirmed UTXOs, or `null`. */
    val staticDepositAddress: String? get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_STATIC_DEPOSIT_ADDRESS.ifEmpty { null }

    /** A claimable static deposit txid, or `null`. */
    val staticDepositTxid: String? get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_STATIC_DEPOSIT_TXID.ifEmpty { null }

    /** Destination for the withdrawal tests, or `null`. */
    val staticDepositWithdrawAddress: String? get() = gy.pig.spark.test.BuildConfig.SPARK_TEST_STATIC_DEPOSIT_WITHDRAW_ADDRESS.ifEmpty { null }

    /** Minimum sats expected in `walletA` for integration tests to run. */
    const val MINIMUM_BALANCE_SATS: Long = 500L

    /** Instrumentation argument [name] (`-Pandroid.testInstrumentationRunnerArguments.<name>=…`), or `null`. */
    fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)?.ifEmpty { null }

    /** Whether instrumentation argument [name] is `1`: an opt-in switch. */
    fun flag(name: String): Boolean = argument(name) == "1"

    /**
     * Skip the calling test (via JUnit [Assume]) when [walletAMnemonic] or
     * [walletBMnemonic] is empty. Call from `@Before` so the entire test class
     * is skipped consistently when secrets aren't configured.
     */
    fun requireMnemonics() {
        Assume.assumeFalse(
            "Set spark.test.walletA.mnemonic in local.properties or " +
                "SPARK_TEST_WALLET_A_MNEMONIC env var to run integration tests.",
            walletAMnemonic.isEmpty(),
        )
        Assume.assumeFalse(
            "Set spark.test.walletB.mnemonic in local.properties or " +
                "SPARK_TEST_WALLET_B_MNEMONIC env var to run integration tests.",
            walletBMnemonic.isEmpty(),
        )
    }

    /** Skip the calling test when [lnAddress] is empty. */
    fun requireLnAddress() {
        Assume.assumeFalse(
            "Set spark.test.lnAddress in local.properties or " +
                "SPARK_TEST_LN_ADDRESS env var to run Lightning-address tests.",
            lnAddress.isEmpty(),
        )
    }

    /** Skip the calling test unless the opt-in instrumentation argument [name] is `1`. */
    fun requireFlag(name: String) {
        Assume.assumeTrue("opt-in: pass -Pandroid.testInstrumentationRunnerArguments.$name=1", flag(name))
    }
}
