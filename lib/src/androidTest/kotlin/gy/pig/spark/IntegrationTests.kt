package gy.pig.spark

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import spark.Spark
import spark_token.QueryTokenTransactionsByTxHash
import spark_token.QueryTokenTransactionsRequest
import spark_token.TokenTransactionStatus
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// Integration tests run against live Spark operators and submit real transactions. A port of the
// Swift SDK's `IntegrationTests.swift`, suite for suite, plus a few Kotlin-only checks.
//
// Mnemonics and Lightning addresses are loaded by [TestConfig] from `local.properties` or
// environment variables — never hardcoded. See [TestConfig] for the keys and `CONTRIBUTING.md`
// for the full setup. Tests skip (via JUnit `Assume`) when the secrets are not configured, so they
// never fail in CI / on contributors' machines, and when the wallets hold fewer sats than a test
// needs (the Swift suite records an issue there).
//
// **Never** commit a real mnemonic or write one to logs.

private val WALLET_A_MNEMONIC: String get() = TestConfig.walletAMnemonic
private val WALLET_B_MNEMONIC: String get() = TestConfig.walletBMnemonic
private const val MINIMUM_TEST_BALANCE: Long = TestConfig.MINIMUM_BALANCE_SATS
private const val MINUTE = 60_000L

/** Skips the calling test unless [balance] is at least [sats]. */
private fun needsSats(label: String, balance: Long, sats: Long) {
    assumeTrue("$label needs >= $sats sats (has $balance)", balance >= sats)
}

/** Base of the live suites: skips everything without configured wallets. */
abstract class LiveSuite {
    @Before
    fun requireWallets() {
        TestConfig.requireMnemonics()
    }
}

// =============================================================================
// Wallet Tests (matching JS: wallet.test.ts)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class WalletTests : LiveSuite() {
    @Test
    fun shouldInitializeAWalletFromMnemonic() {
        assertTrue(makeWallet(WALLET_A_MNEMONIC).identityPublicKeyHex.isNotEmpty())
    }

    @Test
    fun differentAccountsProduceDifferentIdentityKeys() {
        assertNotEquals(makeWallet(WALLET_A_MNEMONIC, account = 0).identityPublicKeyHex, makeWallet(WALLET_A_MNEMONIC, account = 1).identityPublicKeyHex)
    }

    @Test
    fun sameMnemonicAndAccountProduceTheSameIdentityKey() {
        assertEquals(makeWallet(WALLET_A_MNEMONIC).identityPublicKeyHex, makeWallet(WALLET_A_MNEMONIC).identityPublicKeyHex)
    }

    @Test
    fun twoDifferentMnemonicsProduceDifferentIdentityKeys() {
        assertNotEquals(makeWallet(WALLET_A_MNEMONIC).identityPublicKeyHex, makeWallet(WALLET_B_MNEMONIC).identityPublicKeyHex)
    }
}

// =============================================================================
// Spark Address Tests (matching JS: address.test.ts)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class SparkAddressTests : LiveSuite() {
    @Test
    fun shouldGenerateASparkAddressWithTheCorrectPrefix() {
        val address = makeWallet(WALLET_A_MNEMONIC).getSparkAddress()
        assertTrue(address.startsWith("spark1"))
        assertTrue(address.length > 10)
        println("Spark address: $address")
    }

    @Test
    fun sameWalletProducesTheSameSparkAddress() {
        assertEquals(makeWallet(WALLET_A_MNEMONIC).getSparkAddress(), makeWallet(WALLET_A_MNEMONIC).getSparkAddress())
    }

    @Test
    fun differentWalletsProduceDifferentSparkAddresses() {
        assertNotEquals(makeWallet(WALLET_A_MNEMONIC).getSparkAddress(), makeWallet(WALLET_B_MNEMONIC).getSparkAddress())
    }
}

// =============================================================================
// Balance Tests
// =============================================================================

@RunWith(AndroidJUnit4::class)
class BalanceTests : LiveSuite() {
    @Test
    fun shouldQueryBalance() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val balance = wallet.getBalance()
            val sats = balance.satsBalance
            println(
                "Balance — available: ${sats.available}, owned: ${sats.owned}, incoming: ${sats.incoming}, " +
                    "frozen: ${sats.frozen}, locked: ${sats.locked} sats, ${balance.leaves.size} leaves",
            )
            assertTrue(sats.available >= 0)
            assertTrue(sats.owned >= sats.available)
        }
    }

    @Test
    fun shouldQueryLeaves() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            for (leaf in wallet.getLeaves()) {
                println("  Leaf ${leaf.id}: ${leaf.valueSats} sats [${leaf.status}]")
                assertTrue(leaf.valueSats > 0)
                assertEquals("AVAILABLE", leaf.status)
            }
        }
    }
}

// =============================================================================
// Recovery Snapshot Tests (unilateral-exit bundle material)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class RecoveryTests : LiveSuite() {
    @Test
    fun theRecoverySnapshotCoversTheBalanceWithCompleteAncestorChains() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val balance = wallet.getBalance()
            val snapshot = wallet.getRecoverySnapshot()
            println("Snapshot: ${snapshot.leaves.size} leaves (${snapshot.totalLeafSats} sats), ${snapshot.nodes.size} ancestor nodes")

            assertEquals("MAINNET", snapshot.network)
            assertEquals(wallet.identityPublicKeyHex, snapshot.identityPublicKeyHex)
            // Every available sat must be covered by the snapshot's leaves.
            assertTrue(snapshot.totalLeafSats >= balance.satsBalance.available)

            // Decode every entry and verify each leaf chain walks to a root.
            val byId = mutableMapOf<String, Spark.TreeNode>()
            for (entry in snapshot.leaves) {
                val node = Spark.TreeNode.parseFrom(entry.treeNodeHex.hexToByteArray())
                assertEquals(entry.id, node.id)
                assertFalse("leaf ${entry.id} missing refund tx", node.refundTx.isEmpty)
                assertFalse("leaf ${entry.id} missing node tx", node.nodeTx.isEmpty)
                byId[entry.id] = node
            }
            for (entry in snapshot.nodes) {
                byId[entry.id] = Spark.TreeNode.parseFrom(entry.treeNodeHex.hexToByteArray())
            }
            for (leaf in snapshot.leaves) {
                var cursor = byId.getValue(leaf.id)
                var hops = 0
                while (cursor.hasParentNodeId() && cursor.parentNodeId.isNotEmpty()) {
                    cursor = byId[cursor.parentNodeId] ?: throw AssertionError("broken chain above leaf ${leaf.id}")
                    hops++
                    assertTrue("chain too deep — cycle?", hops < 100)
                }
            }
        }
    }
}

// =============================================================================
// Consolidation Tests
// =============================================================================

@RunWith(AndroidJUnit4::class)
class ConsolidationTests : LiveSuite() {
    @Test(timeout = 5 * MINUTE)
    fun consolidationReducesTheLeafCountWithoutLosingSats() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val before = wallet.getLeaves()
            val result = wallet.consolidateLeaves()
            println(
                "Consolidation: ${result.leavesBefore} -> ${result.leavesAfter} leaves in ${result.rounds} round(s), " +
                    "fee ${result.feeSats} sats, skipped ${result.skippedLeaves}",
            )
            assertEquals(before.size, result.leavesBefore)
            assertTrue(result.leavesAfter <= result.leavesBefore)
            assertEquals("SSP swap unexpectedly charged ${result.feeSats} sats", 0L, result.feeSats)
            // Already-minimal wallets are a no-op; fragmented ones must shrink.
            val ideal = binaryDecomposition(result.totalSatsBefore).size
            assertTrue(result.leavesAfter <= maxOf(ideal, result.leavesBefore))
        }
    }
}

// =============================================================================
// Deposit Tests (matching JS: deposit.test.ts)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class DepositTests : LiveSuite() {
    @Test
    fun shouldGenerateASingleUseDepositAddress() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val deposit = wallet.getDepositAddress()
            assertTrue(deposit.address.isNotEmpty())
            assertTrue(deposit.leafId.isNotEmpty())
            assertTrue(deposit.address.startsWith("bc1p")) // P2TR address
            println("Deposit address: ${deposit.address}")
        }
    }

    @Test
    fun shouldGenerateAStaticDepositAddress() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val deposit = wallet.getStaticDepositAddress()
            assertTrue(deposit.address.isNotEmpty())
            assertTrue(deposit.address.startsWith("bc1p"))
            println("Static deposit address: ${deposit.address}")
        }
    }

    @Test
    fun theStaticDepositAddressIsDeterministic() = liveTest {
        val first = withWallet(WALLET_A_MNEMONIC) { it.getStaticDepositAddress() }
        val second = withWallet(WALLET_A_MNEMONIC) { it.getStaticDepositAddress() }
        assertEquals(first.address, second.address)
    }

    @Test
    fun shouldQueryUnusedDepositAddresses() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val addresses = wallet.queryUnusedDepositAddresses()
            println("Unused deposit addresses: ${addresses.size}")
            for (address in addresses) {
                assertTrue(address.address.isNotEmpty())
                assertTrue(address.leafId.isNotEmpty())
                println("  ${address.address} leafId=${address.leafId}")
            }
        }
    }

    @Test
    fun shouldGenerateMultipleDepositAddressesAndQueryThem() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            // Every page: the test wallet accumulates unused addresses across runs, well past one page.
            suspend fun unusedCount(): Int {
                var count = 0
                var offset = 0
                while (true) {
                    val page = wallet.queryUnusedDepositAddresses(limit = 100, offset = offset)
                    count += page.size
                    if (page.size < 100) return count
                    offset += page.size
                }
            }
            val countBefore = unusedCount()
            wallet.getDepositAddress()
            wallet.getDepositAddress()
            val countAfter = unusedCount()
            assertTrue("$countBefore -> $countAfter", countAfter >= countBefore + 2)
        }
    }

    @Test
    fun aGeneratedDepositAddressAppearsInTheUnusedDepositAddresses() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val deposit = wallet.getDepositAddress()
            val unused = wallet.queryUnusedDepositAddresses()
            assertTrue(
                "Generated deposit address should appear in unused list",
                unused.any { it.leafId == deposit.leafId && it.address == deposit.address },
            )
            println("Confirmed deposit ${deposit.address} (leafId=${deposit.leafId}) is in unused list")
        }
    }

    @Test
    fun staticDepositAddressesDoNotAppearInTheUnusedDepositAddresses() = liveTest {
        // Static deposits use a different query endpoint.
        withWallet(WALLET_B_MNEMONIC) { wallet ->
            val staticAddress = wallet.getStaticDepositAddress()
            assertFalse(wallet.queryUnusedDepositAddresses().any { it.address == staticAddress.address })
        }
    }

    @Test
    fun shouldQueryStaticDepositAddresses() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            // Ensure at least one static address exists.
            val generated = wallet.getStaticDepositAddress()
            val addresses = wallet.queryStaticDepositAddresses()
            assertTrue(addresses.isNotEmpty())
            assertTrue(addresses.any { it.address == generated.address })
            println("Static deposit addresses: ${addresses.size}")
            for (address in addresses) {
                assertTrue(address.address.startsWith("bc1p"))
                println("  ${address.address}")
            }
        }
    }

    @Test
    fun queryStaticDepositAddressesReturnsDeterministicResults() = liveTest {
        val first = withWallet(WALLET_A_MNEMONIC) { it.queryStaticDepositAddresses() }
        val second = withWallet(WALLET_A_MNEMONIC) { it.queryStaticDepositAddresses() }
        assertEquals(first.map { it.address }, second.map { it.address })
    }

    @Test
    fun shouldGetUtxosForADepositAddress() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            // A fresh single-use address: no deposits sent, so no UTXOs.
            val deposit = wallet.getDepositAddress()
            val utxos = wallet.getUtxosForDepositAddress(address = deposit.address)
            println("UTXOs for ${deposit.address}: ${utxos.size}")
            assertTrue("Fresh deposit address should have no UTXOs", utxos.isEmpty())
        }
    }

    @Test
    fun shouldGetUtxosForAStaticDepositAddress() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val staticAddress = wallet.getStaticDepositAddress()
            val utxos = wallet.getUtxosForDepositAddress(address = staticAddress.address)
            println("UTXOs for static address ${staticAddress.address}: ${utxos.size}")
            for (utxo in utxos) {
                assertTrue(utxo.txid.isNotEmpty())
                println("  txid=${utxo.txid} vout=${utxo.vout}")
            }
        }
    }

    @Test
    fun claimStaticDepositWithMaxFeeRefusesAnUnknownTxid() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            // A fake txid: neither its transaction nor a quote can be fetched.
            expectSparkError {
                wallet.claimStaticDepositWithMaxFee(transactionId = "0".repeat(64), maxFee = 1000)
            }
        }
    }
}

// =============================================================================
// Lightning Tests (matching JS: lightning.test.ts)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class LightningTests : LiveSuite() {
    @Test
    fun shouldCreateALightningInvoice() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val invoice = wallet.createLightningInvoice(amountSats = 100, memo = "test invoice")
            assertTrue(invoice.paymentRequest.isNotEmpty())
            assertTrue(invoice.paymentHash.isNotEmpty())
            assertEquals(100L, invoice.amountSats)
            assertTrue(invoice.expiresAt.after(Date()))
            assertTrue(invoice.paymentRequest.lowercase().startsWith("lnbc"))
            println("Invoice: ${invoice.paymentRequest.take(50)}...")
        }
    }

    @Test
    fun shouldCreateAnInvoiceWithoutMemo() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            assertTrue(wallet.createLightningInvoice(amountSats = 50).paymentRequest.isNotEmpty())
        }
    }

    @Test
    fun shouldGetALightningSendFeeEstimate() = liveTest {
        withWallets { walletA, walletB ->
            val invoice = walletB.createLightningInvoice(amountSats = 100)
            val fee = walletA.getLightningSendFeeEstimate(encodedInvoice = invoice.paymentRequest)
            assertTrue(fee >= 0)
            println("Fee estimate: $fee sats")
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun shouldPayALightningInvoiceBetweenWallets() = liveTest {
        withWallets { walletA, walletB ->
            val before = walletA.getBalance().satsBalance.available
            needsSats("WalletA", before, 100)
            val invoice = walletB.createLightningInvoice(amountSats = 10, memo = "integration test")
            val paymentId = walletA.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = 50)
            assertTrue(paymentId.isNotEmpty())
            println("Payment ID: $paymentId")
            // The sender's balance decreased.
            val after = walletA.getBalance().satsBalance.available
            assertTrue(after < before)
            println("WalletA: $before -> $after sats")
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun shouldPayAnExternalLightningAddress() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            needsSats("WalletA", wallet.getBalance().satsBalance.available, 50)
            TestConfig.requireLnAddress()
            val bolt11 = resolveLightningAddress(TestConfig.lnAddress, amountSats = 10)
            val paymentId = wallet.payLightningInvoice(paymentRequest = bolt11, maxFeeSats = 50)
            assertTrue(paymentId.isNotEmpty())
            println("External payment ID: $paymentId")
        }
    }
}

// =============================================================================
// Transfer Tests (matching JS: transfer.test.ts)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class TransferTests : LiveSuite() {
    @Test
    fun shouldClaimPendingTransfers() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            println("Claimed ${wallet.claimAllPendingTransfers()} pending transfers")
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun shouldTransferBetweenWalletsViaSpark() = liveTest {
        withWallets { walletA, walletB ->
            needsSats("WalletA", walletA.getBalance().satsBalance.available, 100)
            val amount = 10L
            val transfer = walletA.send(receiverIdentityPublicKey = walletB.identityPublicKeyHex.hexToByteArray(), amountSats = amount)
            assertTrue(transfer.id.isNotEmpty())
            println("Transfer sent: ${transfer.id} status=${transfer.status}")
            delay(3_000)
            assertTrue(walletB.claimAllPendingTransfers() >= 1)
            val balanceB = walletB.getBalance().satsBalance.available
            assertTrue(balanceB >= amount)
            println("WalletB balance: $balanceB sats")
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun shouldTransferAllTheBalanceAndReturnIt() = liveTest {
        withWallets { walletA, walletB ->
            // Ensure B has a balance (send A -> B first).
            needsSats("WalletA", walletA.getBalance().satsBalance.available, 50)
            walletA.send(receiverIdentityPublicKey = walletB.identityPublicKeyHex.hexToByteArray(), amountSats = 20)
            delay(3_000)
            walletB.claimAllPendingTransfers()

            // Now send all B -> A.
            val balanceB = walletB.getBalance().satsBalance.available
            assertTrue(balanceB > 0)
            val transfer = walletB.send(receiverIdentityPublicKey = walletA.identityPublicKeyHex.hexToByteArray(), amountSats = balanceB)
            println("Return transfer: ${transfer.id}")
            delay(3_000)
            assertTrue(walletA.claimAllPendingTransfers() >= 1)

            // B's spendable balance is empty now.
            val finalB = walletB.getBalance().satsBalance.available
            assertEquals(0L, finalB)
            println("WalletB final balance: $finalB sats")
        }
    }

    /** Kotlin-only: large and odd-amount transfers lose no sat. */
    @Test(timeout = 10 * MINUTE)
    fun largeTransfersLoseNoSat() = liveTest {
        withWallets { walletA, walletB ->
            fun owned(balance: WalletBalance) = balance.satsBalance.owned + balance.satsBalance.incoming
            walletB.claimPendingTransfers()
            val initialA = walletA.getBalance()
            val initialB = walletB.getBalance()
            val initialTotal = owned(initialA) + owned(initialB)
            val available = initialA.satsBalance.available
            val sparkAmount = (available / 2).coerceAtLeast(50)
            needsSats("WalletA", available, sparkAmount + 150)

            // Phase 1: a large Spark transfer A -> B.
            val pubB = walletB.identityPublicKeyHex.hexToByteArray()
            assertTrue(walletA.send(receiverIdentityPublicKey = pubB, amountSats = sparkAmount).id.isNotEmpty())
            delay(3_000)
            assertTrue(walletB.claimAllPendingTransfers() >= 1)
            val phase1B = walletB.getBalance()
            assertEquals("no sat lost A -> B", initialTotal, owned(walletA.getBalance()) + owned(phase1B))
            assertEquals(owned(initialB) + sparkAmount, owned(phase1B))

            // Phase 2: an odd amount, likely needing a swap, A -> B.
            val oddAmount = 137L
            assertTrue(walletA.send(receiverIdentityPublicKey = pubB, amountSats = oddAmount).id.isNotEmpty())
            delay(3_000)
            assertTrue(walletB.claimAllPendingTransfers() >= 1)
            assertEquals("no sat lost on the odd amount", initialTotal, owned(walletA.getBalance()) + owned(walletB.getBalance()))

            // Phase 3: everything B received back to A.
            val back = walletB.getBalance().satsBalance.available
            walletB.send(receiverIdentityPublicKey = walletA.identityPublicKeyHex.hexToByteArray(), amountSats = back)
            delay(3_000)
            walletA.claimAllPendingTransfers()
            val finalTotal = owned(walletA.getBalance()) + owned(walletB.getBalance())
            println("Initial $initialTotal sats, final $finalTotal sats")
            assertEquals("no sat lost across the Spark transfers", initialTotal, finalTotal)
        }
    }
}

// =============================================================================
// Cooperative Exit (Withdrawal) Tests (matching JS: coop-exit.test.ts)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class WithdrawalTests : LiveSuite() {
    @Test
    fun shouldGetAWithdrawalFeeEstimate() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val leaves = wallet.getLeaves()
            assumeTrue("WalletA has no leaves", leaves.isNotEmpty())
            val fee = wallet.getWithdrawalFeeEstimate(onChainAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", leafIds = leaves.map { it.id })
            assertTrue(fee.feeSats > 0)
            println("Withdrawal fee estimate: ${fee.feeSats} sats")
        }
    }

    // The actual withdrawal is destructive (spends the whole balance): run it by hand only.
    @Ignore("Destructive: spends balance")
    @Test(timeout = 10 * MINUTE)
    fun shouldWithdrawToAnOnChainAddress() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val available = wallet.getBalance().satsBalance.available
            assumeTrue("No balance to withdraw", available > 0)
            val txid = wallet.withdraw(onChainAddress = "bc1qhta0uu4a7yt0jp3vmzasl3srelx9v46ncl9x89", amountSats = available)
            assertTrue(txid.isNotEmpty())
            println("Withdrawal txid: $txid")
        }
    }
}

// =============================================================================
// Static Deposit Tests (matching JS: static_deposit.test.ts)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class StaticDepositTests : LiveSuite() {
    private fun requireStaticDepositWallet(): String {
        val mnemonic = TestConfig.staticDepositMnemonic
        assumeTrue("SPARK_TEST_STATIC_DEPOSIT_MNEMONIC is not set", mnemonic != null)
        return mnemonic!!
    }

    @Test
    fun shouldGetUtxosForAStaticDepositAddressClaimedOnesIncluded() = liveTest {
        val mnemonic = requireStaticDepositWallet()
        val targetAddress = TestConfig.staticDepositAddress
        assumeTrue("SPARK_TEST_STATIC_DEPOSIT_ADDRESS is not set", targetAddress != null)
        withWallet(mnemonic, account = TestConfig.staticDepositAccount) { wallet ->
            // The fixture address has received deposits; once they are claimed only the query that
            // includes claimed ones still lists them.
            val utxos = wallet.getUtxosForDepositAddress(address = targetAddress!!, excludeClaimed = false)
            val unclaimed = wallet.getUtxosForDepositAddress(address = targetAddress)
            println("UTXOs at $targetAddress: ${utxos.size}, ${unclaimed.size} unclaimed")
            for (utxo in utxos) println("  txid=${utxo.txid} vout=${utxo.vout}")
            assertTrue("Expected at least one UTXO", utxos.isNotEmpty())
            assertTrue(unclaimed.all { candidate -> utxos.any { it.txid == candidate.txid && it.vout == candidate.vout } })
        }
    }

    @Test
    fun shouldEstimateTheWithdrawalFeeForTheWholeBalance() = liveTest {
        val mnemonic = requireStaticDepositWallet()
        val withdrawAddress = TestConfig.staticDepositWithdrawAddress
        assumeTrue("SPARK_TEST_STATIC_DEPOSIT_WITHDRAW_ADDRESS is not set", withdrawAddress != null)
        withWallet(mnemonic, account = TestConfig.staticDepositAccount) { wallet ->
            val balance = wallet.getBalance()
            println("Balance: ${balance.satsBalance.available} sats, leaves: ${balance.leaves.size}")
            for (leaf in balance.leaves) println("  leaf ${leaf.id}: ${leaf.valueSats} sats [${leaf.status}]")
            if (balance.leaves.isEmpty()) {
                println("No leaves to estimate withdrawal for")
                return@withWallet
            }
            val fee = wallet.getWithdrawalFeeEstimate(onChainAddress = withdrawAddress!!, leafIds = balance.leaves.map { it.id })
            println("Withdrawal fee estimate: ${fee.feeSats} sats; would receive ${balance.satsBalance.available - fee.feeSats} sats")
            assertTrue(fee.feeSats > 0)
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun shouldClaimAStaticDeposit() = liveTest {
        val mnemonic = requireStaticDepositWallet()
        val txId = TestConfig.staticDepositTxid
        assumeTrue("SPARK_TEST_STATIC_DEPOSIT_TXID is not set", txId != null)
        withWallet(mnemonic, account = TestConfig.staticDepositAccount) { wallet ->
            // A deposit can be claimed once: after that the SSP answers "Transaction not found." for
            // it. Skip only when the operators report this one claimed.
            val address = wallet.getStaticDepositAddress().address
            val isDeposit = { utxo: DepositUtxo -> utxo.txid.equals(txId!!, ignoreCase = true) }
            val alreadyClaimed = wallet.getUtxosForDepositAddress(address = address).none(isDeposit) &&
                wallet.getUtxosForDepositAddress(address = address, excludeClaimed = false).any(isDeposit)
            assumeTrue(
                "the configured deposit $txId is already claimed; set SPARK_TEST_STATIC_DEPOSIT_TXID to an unclaimed one",
                !alreadyClaimed,
            )

            val before = wallet.getBalance().satsBalance.available
            println("Balance before claim: $before sats")
            val quote = wallet.getDepositFeeEstimate(transactionId = txId!!, outputIndex = 0u)
            val transferId = wallet.claimStaticDeposit(transactionId = txId, outputIndex = 0u, quote = quote)
            println("Claim transfer ID: $transferId")
            delay(3_000)
            wallet.claimAllPendingTransfers()
            val after = wallet.getBalance().satsBalance.available
            println("Balance after claim: $after sats")
            assertTrue(after > before)
        }
    }
}

// =============================================================================
// Settings Tests
// =============================================================================

@RunWith(AndroidJUnit4::class)
class SettingsTests : LiveSuite() {
    @Test
    fun shouldTogglePrivacyMode() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            println("Privacy enabled: ${wallet.getWalletSettings().privateEnabled}")
            assertTrue(wallet.setPrivacyEnabled(true).privateEnabled)
            assertTrue(wallet.getWalletSettings().privateEnabled)
            assertFalse(wallet.setPrivacyEnabled(false).privateEnabled)
        }
    }
}

// =============================================================================
// Debug / Utility Tests
// =============================================================================

@RunWith(AndroidJUnit4::class)
class DebugTests : LiveSuite() {
    @Test
    fun showAllNodesAndBalances() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val request = Spark.QueryNodesRequest.newBuilder()
                .setOwnerIdentityPubkey(ByteString.copyFrom(wallet.signer.identityPublicKey))
                .setNetwork(wallet.config.network.toProto())
                .build()
            val nodes = wallet.getCoordinatorStub().queryNodes(request).nodesMap
            println("=== All Nodes (${nodes.size}) ===")
            for ((id, node) in nodes) println("  $id: value=${node.value} status=${node.status}")
            val balance = wallet.getBalance()
            println("Balance: ${balance.satsBalance.available} sats (${balance.leaves.size} available leaves)")
        }
    }

    @Test
    fun shouldGetTransfers() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val transfers = wallet.getTransfers(limit = 10)
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            println("=== Recent Transfers (${transfers.size}) ===")
            for (t in transfers) {
                val direction = if (t.senderIdentityPublicKey == wallet.identityPublicKeyHex) "SENT" else "RECV"
                println("  ${format.format(t.createdAt)} | $direction | ${t.totalValueSats} sats | ${t.status} | ${t.type} | ${t.id}")
            }
            assertTrue(transfers.isNotEmpty())

            val first = transfers[0]
            val single = wallet.getTransfer(id = first.id)
            assertEquals(first.id, single.id)
            assertEquals(first.totalValueSats, single.totalValueSats)
            println("Single transfer lookup OK: ${single.id}")
        }
    }

    /**
     * One `LEDGER` line per test wallet (A, B, and the static-deposit wallet when configured), to
     * compare before and after a run: every sat the wallets hold is in `owned` or `incoming`.
     */
    @Test
    fun ledgerOfEveryTestWalletsSatsAndTokens() = liveTest {
        val wallets = mutableListOf("A" to makeWallet(WALLET_A_MNEMONIC), "B" to makeWallet(WALLET_B_MNEMONIC))
        TestConfig.staticDepositMnemonic?.let { wallets.add("static" to makeWallet(it, account = TestConfig.staticDepositAccount)) }
        var total = 0L
        for ((name, wallet) in wallets) {
            try {
                val balance = wallet.getBalance()
                val sats = balance.satsBalance
                val tokens = balance.tokenBalances.map { "${it.tokenMetadata.tokenTicker}=${it.ownedBalance}" }.sorted().joinToString(",")
                println(
                    "LEDGER $name owned=${sats.owned} available=${sats.available} incoming=${sats.incoming} " +
                        "frozen=${sats.frozen} leaves=${balance.leaves.size} tokens=[$tokens]",
                )
                total += sats.owned + sats.incoming
            } finally {
                wallet.close()
            }
        }
        println("LEDGER total owned+incoming=$total")
    }

    @Test
    fun showWalletInfo() = liveTest {
        withWallets { walletA, walletB ->
            for ((name, wallet) in listOf("A" to walletA, "B" to walletB)) {
                val balance = wallet.getBalance()
                println("=== Wallet $name ===")
                println("  Identity: ${wallet.identityPublicKeyHex}")
                println("  Spark:    ${wallet.getSparkAddress()}")
                println("  Balance:  ${balance.satsBalance.available} sats (${balance.leaves.size} leaves)")
            }
        }
    }
}

// =============================================================================
// Funding Helpers (run manually)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class FundingTests : LiveSuite() {
    @Test
    fun createALightningInvoiceToFundWalletA() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val invoice = wallet.createLightningInvoice(amountSats = 2000, memo = "Fund walletA for tests")
            println("\n=== PAY THIS INVOICE TO FUND WALLET A ===")
            println(invoice.paymentRequest)
            println("==========================================")
            println("Amount: 2000 sats | Hash: ${invoice.paymentHash}")
        }
    }

    @Test
    fun claimAllPendingTransfersForWalletA() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val claimed = wallet.claimAllPendingTransfers()
            println("Claimed $claimed transfers. Balance: ${wallet.getBalance().satsBalance.available} sats")
        }
    }

    /** Kotlin-only: waits up to two minutes for someone to pay a 100-sat invoice. */
    @Ignore("Manual: waits for an external payment")
    @Test(timeout = 5 * MINUTE)
    fun receiveAPayment() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val before = wallet.getBalance().satsBalance.owned
            val invoice = wallet.createLightningInvoice(amountSats = 100, memo = "pay me 100 sats")
            println("\n=== PAY THIS INVOICE (100 sats) ===\n${invoice.paymentRequest}\n===================================")
            val paid = eventually(kotlin.time.Duration.parse("2m")) { wallet.claimAllPendingTransfers() > 0 }
            val after = wallet.getBalance().satsBalance.owned
            println(if (paid) "Payment received: +${after - before} sats" else "Timed out waiting for payment")
            if (paid) assertTrue(after > before)
        }
    }
}

// =============================================================================
// Token (BTKN) Integration Tests
// =============================================================================

/** The tokens [wallet] has issued. */
private suspend fun issued(wallet: SparkWallet): List<TokenMetadataInfo> = wallet.queryTokenMetadata(issuerPublicKeys = listOf(wallet.signer.identityPublicKey))

/**
 * Runs [block] on the token issuer and the other test wallet. The Swift suite's wallet A issued
 * its token; the issuer is whichever test wallet has issued one (wallet A when neither has), so
 * the suite never issues a second token because the wallets are configured the other way round.
 */
private suspend fun <T> withTokenWallets(issuerConfig: SparkConfig = SparkConfig(), block: suspend (issuer: SparkWallet, other: SparkWallet) -> T): T {
    val issuerIsB = withWallet(WALLET_A_MNEMONIC) { issued(it).isEmpty() } && withWallet(WALLET_B_MNEMONIC) { issued(it).isNotEmpty() }
    println("token issuer: wallet ${if (issuerIsB) "B" else "A"}")
    val (issuer, other) = if (issuerIsB) WALLET_B_MNEMONIC to WALLET_A_MNEMONIC else WALLET_A_MNEMONIC to WALLET_B_MNEMONIC
    return withWallet(issuer, issuerConfig) { issuerWallet -> withWallet(other) { otherWallet -> block(issuerWallet, otherWallet) } }
}

/** [wallet]'s owned balance of [token], zero when it holds none. */
private suspend fun tokenBalance(wallet: SparkWallet, token: String): BigInteger =
    wallet.getTokenBalances().firstOrNull { it.tokenMetadata.tokenIdentifier == token }?.ownedBalance ?: BigInteger.ZERO

/** The operators hold a finalized transaction of [version] under [hash], the hash the SDK reported for it. */
private suspend fun expectOperatorsKnow(hash: String, wallet: SparkWallet, version: Int = 3) {
    val request = QueryTokenTransactionsRequest.newBuilder()
        .setByTxHash(QueryTokenTransactionsByTxHash.newBuilder().addTokenTransactionHashes(ByteString.copyFrom(hash.hexToByteArray())))
        .build()
    val found = wallet.getTokenStub().queryTokenTransactions(request).tokenTransactionsWithStatusList
    assertEquals("the operators hold no transaction $hash", listOf(hash), found.map { it.tokenTransactionHash.toByteArray().toHexString() })
    assertEquals(TokenTransactionStatus.TOKEN_TRANSACTION_FINALIZED, found.first().status)
    assertEquals(version, found.first().tokenTransaction.version)
}

@RunWith(AndroidJUnit4::class)
class TokenIntegrationTests : LiveSuite() {
    @Test(timeout = 10 * MINUTE)
    fun shouldCreateATokenMintTransferAToBTransferBToAAndBurn() = liveTest {
        withTokenWallets { walletA, walletB ->
            // --- Phase 1: Create Token (or reuse existing) ---
            val existing = issued(walletA).firstOrNull()
            val tokenIdentifier = if (existing != null) {
                println("Reusing existing token: ${existing.tokenName} (${existing.tokenTicker}) ${existing.tokenIdentifier}")
                existing.tokenIdentifier
            } else {
                val creation = walletA.createToken(
                    tokenName = "KotlinTest",
                    tokenTicker = "KTST",
                    decimals = 2u,
                    maxSupply = BigInteger.valueOf(1_000_000),
                    isFreezable = false,
                )
                assertTrue(creation.transactionHash.isNotEmpty())
                expectOperatorsKnow(creation.transactionHash, walletA)
                println("Token created, tx: ${creation.transactionHash}")
                delay(5_000)
                val created = issued(walletA).firstOrNull()
                assertNotNull("Token metadata not found after creation", created)
                created!!.tokenIdentifier
            }
            assertTrue(tokenIdentifier.startsWith("btkn1"))

            // --- Phase 2: Mint Tokens ---
            val mintAmount = BigInteger.valueOf(10_000)
            val mintTx = walletA.mintTokens(tokenIdentifier = tokenIdentifier, tokenAmount = mintAmount)
            assertTrue(mintTx.isNotEmpty())
            expectOperatorsKnow(mintTx, walletA)
            println("Mint tx: $mintTx")
            delay(5_000)
            assertTrue(tokenBalance(walletA, tokenIdentifier) >= mintAmount)

            // --- Phase 3: Transfer A -> B ---
            val transferAmount = BigInteger.valueOf(5_000)
            val transferTx = walletA.transferTokens(
                tokenIdentifier = tokenIdentifier,
                tokenAmount = transferAmount,
                receiverSparkAddress = walletB.getSparkAddress()
            )
            assertTrue(transferTx.isNotEmpty())
            expectOperatorsKnow(transferTx, walletA)
            println("Transfer A->B tx: $transferTx")
            delay(5_000)
            val balanceB = tokenBalance(walletB, tokenIdentifier)
            println("WalletB token balance: $balanceB")
            assertTrue(balanceB >= transferAmount)

            // --- Phase 4: Transfer B -> A (send it all back) ---
            val returnTx = walletB.transferTokens(
                tokenIdentifier = tokenIdentifier,
                tokenAmount = transferAmount,
                receiverSparkAddress = walletA.getSparkAddress()
            )
            assertTrue(returnTx.isNotEmpty())
            expectOperatorsKnow(returnTx, walletB)
            println("Transfer B->A tx: $returnTx")
            delay(5_000)
            // B is back to what it held before the round trip (it may keep tokens from earlier runs).
            assertEquals(balanceB - transferAmount, tokenBalance(walletB, tokenIdentifier))
            assertTrue(tokenBalance(walletA, tokenIdentifier) >= mintAmount)

            // --- Phase 5: Burn some tokens ---
            val burnTx = walletA.burnTokens(tokenIdentifier = tokenIdentifier, tokenAmount = BigInteger.valueOf(1_000))
            assertTrue(burnTx.isNotEmpty())
            expectOperatorsKnow(burnTx, walletA)
            println("Burn tx: $burnTx")
            delay(5_000)
            println("WalletA token balance after burn: ${tokenBalance(walletA, tokenIdentifier)}")
        }
    }

    @Test(timeout = 10 * MINUTE)
    fun twoConcurrentSendsFromOneWalletBothLandOnDifferentOutputs() = liveTest {
        withTokenWallets { walletA, walletB ->
            val token = issued(walletA).firstOrNull()?.tokenIdentifier
            assumeTrue("the issuer has issued no token; the lifecycle test creates one", token != null)
            val rawToken = decodeBech32mTokenIdentifier(token!!, walletA.config.network).first
            var available = walletA.fetchTokenOutputs(tokenIdentifiers = listOf(rawToken)).filter { TokenOutputLocks.isAvailable(it) }
            if (available.size < 2) {
                repeat(2 - available.size) { walletA.mintTokens(tokenIdentifier = token, tokenAmount = BigInteger.TEN) }
                delay(5_000)
                available = walletA.fetchTokenOutputs(tokenIdentifiers = listOf(rawToken)).filter { TokenOutputLocks.isAvailable(it) }
            }
            // The smallest output's amount: each send then spends a single output, and without locks
            // both would pick the same one and the operators would refuse one as pre-empted.
            val amount = available.minOf { decodeUInt128(it.output.tokenAmount) }
            val before = tokenBalance(walletB, token)

            val addressB = walletB.getSparkAddress()
            val first = async { walletA.transferTokens(tokenIdentifier = token, tokenAmount = amount, receiverSparkAddress = addressB) }
            val second = async { walletA.transferTokens(tokenIdentifier = token, tokenAmount = amount, receiverSparkAddress = addressB) }
            val hashes = listOf(first.await(), second.await())
            println("Concurrent sends of $amount: $hashes")
            assertEquals(2, hashes.toSet().size)
            for (hash in hashes) expectOperatorsKnow(hash, walletA)

            delay(5_000)
            assertEquals(before + amount * BigInteger.TWO, tokenBalance(walletB, token))

            // Back to A.
            walletB.transferTokens(tokenIdentifier = token, tokenAmount = amount * BigInteger.TWO, receiverSparkAddress = walletA.getSparkAddress())
            delay(5_000)
            assertEquals(before, tokenBalance(walletB, token))
        }
    }

    @Test(timeout = 10 * MINUTE)
    fun aSendRetriedWithItsIdempotencyKeyIsMadeOnceAndReturnsTheSameHash() = liveTest {
        withTokenWallets { walletA, walletB ->
            val token = issued(walletA).firstOrNull()?.tokenIdentifier
            assumeTrue("the issuer has issued no token; the lifecycle test creates one", token != null)
            val before = tokenBalance(walletB, token!!)

            val key = UUID.randomUUID().toString()
            val addressB = walletB.getSparkAddress()
            suspend fun send() =
                walletA.transferTokens(tokenIdentifier = token, tokenAmount = BigInteger.valueOf(7), receiverSparkAddress = addressB, idempotencyKey = key)
            val first = send()
            val retry = send()
            println("Keyed send: $first, retried: $retry")
            assertEquals(first, retry)
            expectOperatorsKnow(first, walletA)

            delay(5_000)
            assertEquals(before + BigInteger.valueOf(7), tokenBalance(walletB, token))

            // Back to A.
            walletB.transferTokens(tokenIdentifier = token, tokenAmount = BigInteger.valueOf(7), receiverSparkAddress = walletA.getSparkAddress())
            delay(5_000)
            assertEquals(before, tokenBalance(walletB, token))
        }
    }

    @Test(timeout = 10 * MINUTE)
    fun v2TokenTransactionsStillWorkWhenConfigured() = liveTest {
        withTokenWallets(issuerConfig = SparkConfig(tokenTransactionVersion = TokenTransactionVersion.V2)) { walletA, walletB ->
            val token = issued(walletA).firstOrNull()?.tokenIdentifier
            assumeTrue("the issuer has issued no token; the lifecycle test creates one", token != null)
            val before = tokenBalance(walletB, token!!)

            val hash = walletA.transferTokens(tokenIdentifier = token, tokenAmount = BigInteger.valueOf(3), receiverSparkAddress = walletB.getSparkAddress())
            println("V2 send: $hash")
            expectOperatorsKnow(hash, walletA, version = 2)
            delay(5_000)
            assertEquals(before + BigInteger.valueOf(3), tokenBalance(walletB, token))

            // Back to A, as V3.
            walletB.transferTokens(tokenIdentifier = token, tokenAmount = BigInteger.valueOf(3), receiverSparkAddress = walletA.getSparkAddress())
            delay(5_000)
            assertEquals(before, tokenBalance(walletB, token))
        }
    }

    @Test
    fun shouldQueryTokenOutputs() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val outputs = wallet.getTokenOutputs()
            println("Token outputs: ${outputs.size}")
            for (output in outputs.take(5)) {
                println("  amount=${output.tokenAmount} token=${output.tokenIdentifier.toHexString().take(16)}... status=${output.status}")
            }
        }
    }

    @Test
    fun shouldQueryTokenBalances() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val balances = wallet.getTokenBalances()
            println("Token balances: ${balances.size} tokens")
            for (balance in balances) {
                println(
                    "  ${balance.tokenMetadata.tokenName} (${balance.tokenMetadata.tokenTicker}) ${balance.tokenMetadata.tokenIdentifier}: " +
                        "owned ${balance.ownedBalance}, available ${balance.availableToSendBalance}, decimals ${balance.tokenMetadata.decimals}",
                )
            }
        }
    }

    @Test
    fun shouldQueryTokenMetadataByIssuer() = liveTest {
        withTokenWallets { issuer, _ ->
            val metadatas = issued(issuer)
            println("Token metadata for issuer: ${metadatas.size} tokens")
            for (meta in metadatas) {
                println(
                    "  ${meta.tokenName} (${meta.tokenTicker}) ${meta.tokenIdentifier}: issuer ${meta.issuerPublicKey.toHexString()}, " +
                        "maxSupply ${decodeUInt128(ByteString.copyFrom(meta.maxSupply))}, freezable ${meta.isFreezable}",
                )
                assertEquals(issuer.identityPublicKeyHex, meta.issuerPublicKey.toHexString())
            }
        }
    }
}

// =============================================================================
// Idempotency Tests
// =============================================================================

@RunWith(AndroidJUnit4::class)
class IdempotencyTests : LiveSuite() {
    @Test(timeout = 5 * MINUTE)
    fun aTokenTransferWithAnIdempotencyKeyDoesNotDoubleSpend() = liveTest {
        withTokenWallets { walletA, walletB ->
            val token = issued(walletA).firstOrNull()?.tokenIdentifier
            assumeTrue("No token found. Run the token lifecycle test first.", token != null)
            val before = tokenBalance(walletA, token!!)
            assumeTrue("Need >= 100 tokens (have $before)", before >= BigInteger.valueOf(100))

            val transferAmount = BigInteger.valueOf(50)
            val tx = walletA.transferTokens(
                tokenIdentifier = token,
                tokenAmount = transferAmount,
                receiverSparkAddress = walletB.getSparkAddress(),
                idempotencyKey = "test-idem-token-${UUID.randomUUID()}",
            )
            assertTrue(tx.isNotEmpty())
            println("First transfer tx: $tx")
            delay(3_000)
            assertEquals(before - transferAmount, tokenBalance(walletA, token))

            // Send back from B to A to clean up.
            walletB.transferTokens(tokenIdentifier = token, tokenAmount = transferAmount, receiverSparkAddress = walletA.getSparkAddress())
            delay(3_000)
            assertEquals(before, tokenBalance(walletA, token))
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun aLightningPaymentWithAnIdempotencyKeyWorks() = liveTest {
        withWallets { walletA, walletB ->
            val before = walletA.getBalance().satsBalance.available
            needsSats("WalletA", before, 100)
            val invoice = walletB.createLightningInvoice(amountSats = 10, memo = "idempotency test")
            val paymentId = walletA.payLightningInvoice(
                paymentRequest = invoice.paymentRequest,
                maxFeeSats = 50,
                idempotencyKey = "test-idem-ln-${UUID.randomUUID()}",
            )
            assertTrue(paymentId.isNotEmpty())
            val after = walletA.getBalance().satsBalance.available
            println("WalletA balance after: $after sats (was $before)")
            assertTrue(after < before)
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun aMintWithAnIdempotencyKeyWorks() = liveTest {
        withTokenWallets { walletA, _ ->
            val token = issued(walletA).firstOrNull()?.tokenIdentifier
            assumeTrue("No token found. Run the token lifecycle test first.", token != null)
            val before = tokenBalance(walletA, token!!)
            val mintAmount = BigInteger.valueOf(100)
            val tx = walletA.mintTokens(tokenIdentifier = token, tokenAmount = mintAmount, idempotencyKey = "test-idem-mint-${UUID.randomUUID()}")
            assertTrue(tx.isNotEmpty())
            println("Mint tx: $tx")
            delay(3_000)
            assertEquals(before + mintAmount, tokenBalance(walletA, token))
        }
    }
}

// =============================================================================
// Invoice-to-Transfer Matching Tests
// =============================================================================

@RunWith(AndroidJUnit4::class)
class InvoiceMatchingTests : LiveSuite() {
    @Test(timeout = 5 * MINUTE)
    fun aRegularSparkTransferHasNoSparkInvoice() = liveTest {
        withWallets { walletA, walletB ->
            needsSats("WalletA", walletA.getBalance().satsBalance.available, 20)
            // Direct Spark transfer (no invoice).
            val transfer = walletA.send(receiverIdentityPublicKey = walletB.identityPublicKeyHex.hexToByteArray(), amountSats = 10)
            println("Direct transfer: ${transfer.id} sparkInvoice=${transfer.sparkInvoice}")
            assertEquals("Direct Spark transfers should not have a sparkInvoice", null, transfer.sparkInvoice)
            delay(3_000)
            assertEquals(null, walletA.getTransfer(id = transfer.id).sparkInvoice)
            // Claim on B so the transfer completes.
            walletB.claimAllPendingTransfers()
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun getTransferFromSspMatchesALightningInvoiceToItsTransferByPaymentHash() = liveTest {
        withWallets { walletA, walletB ->
            needsSats("WalletA", walletA.getBalance().satsBalance.available, 50)
            // Step 1: WalletB creates a lightning invoice; WalletA pays it.
            val invoice = walletB.createLightningInvoice(amountSats = 10, memo = "ssp match test")
            println("Invoice: ${invoice.paymentRequest.take(40)}... payment hash ${invoice.paymentHash}")
            val paymentId = walletA.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = 50)
            println("Payment ID: $paymentId")
            delay(5_000)
            walletB.claimAllPendingTransfers()

            // Step 2: the sender's preimage swap, as the SSP knows it.
            val preimageSwap = Spark.TransferType.PREIMAGE_SWAP.toString()
            val sent = walletA.getTransfers(direction = TransferDirection.SENT, limit = 3).firstOrNull { it.type == preimageSwap }
            assertNotNull("Should find a sent preimage swap transfer", sent)
            val sentRequest = walletA.getTransferFromSsp(id = sent!!.id)?.userRequest
            println("Sender SSP request: $sentRequest")
            if (sentRequest is UserRequest.LightningSend) {
                assertEquals("SSP send request should reference the original invoice", invoice.paymentRequest, sentRequest.info.encodedInvoice)
            }

            // Step 3: the receiver's preimage swap, as the SSP knows it.
            val received = walletB.getTransfers(direction = TransferDirection.RECEIVED, limit = 3).firstOrNull { it.type == preimageSwap }
            assertNotNull("Should find a received preimage swap transfer", received)
            val receivedRequest = walletB.getTransferFromSsp(id = received!!.id)?.userRequest
            println("Receiver SSP request: $receivedRequest")
            if (receivedRequest is UserRequest.LightningReceive) {
                // The key match: the payment hash from the SSP is the invoice's.
                assertEquals(invoice.paymentHash, receivedRequest.info.paymentHash)
                println("MATCH CONFIRMED: payment_hash from SSP == invoice payment_hash")
            }
        }
    }
}

// =============================================================================
// Full Integration Flow (matching JS: end-to-end patterns)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class FullFlowTests : LiveSuite() {
    @Test(timeout = 10 * MINUTE)
    fun lightningAToBSparkTransferBToAAndAnExternalLightningPayment() = liveTest {
        withWallets { walletA, walletB ->
            val initial = walletA.getBalance().satsBalance.available
            println("WalletA initial balance: $initial sats")
            if (initial < MINIMUM_TEST_BALANCE) println("WalletA needs >= $MINIMUM_TEST_BALANCE sats. Deposit to: ${walletA.getDepositAddress().address}")
            needsSats("WalletA", initial, MINIMUM_TEST_BALANCE)

            // Phase 1: Lightning A -> B.
            val invoice = walletB.createLightningInvoice(amountSats = 100, memo = "full flow test")
            assertEquals(100L, invoice.amountSats)
            val payId = walletA.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = 50)
            assertTrue(payId.isNotEmpty())
            println("Payment sent: $payId")
            delay(5_000)
            println("WalletB claimed ${walletB.claimAllPendingTransfers()} transfers")
            val balanceB = walletB.getBalance().satsBalance.available
            assertTrue(balanceB >= 100)

            // Phase 2: Spark transfer B -> A.
            val transfer = walletB.send(receiverIdentityPublicKey = walletA.identityPublicKeyHex.hexToByteArray(), amountSats = balanceB)
            assertTrue(transfer.id.isNotEmpty())
            delay(3_000)
            assertTrue(walletA.claimAllPendingTransfers() >= 1)
            assertEquals(0L, walletB.getBalance().satsBalance.available)

            // Phase 3: external Lightning A -> the configured address.
            if (TestConfig.lnAddress.isEmpty()) {
                println("Skipping Phase 3: no Lightning address configured")
                return@withWallets
            }
            val bolt11 = resolveLightningAddress(TestConfig.lnAddress, amountSats = 10)
            val externalId = walletA.payLightningInvoice(paymentRequest = bolt11, maxFeeSats = 50)
            assertTrue(externalId.isNotEmpty())
            val final = walletA.getBalance().satsBalance.available
            println("Final WalletA balance: $final sats")
            assertTrue("Should only lose ~15 sats in routing fees, not hundreds", final >= initial - 120)
        }
    }

    @Test
    fun anOnChainWithdrawalFeeEstimate() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val leaves = wallet.getLeaves()
            println("Balance: ${wallet.getBalance().satsBalance.available} sats, ${leaves.size} leaves")
            assumeTrue("No leaves — can't estimate fee", leaves.isNotEmpty())
            val fee = wallet.getWithdrawalFeeEstimate(onChainAddress = "bc1qdxqntgy40ut7ep3mddds98t5undss7ka6l2dud", leafIds = leaves.map { it.id })
            println("On-chain fee estimate: ${fee.feeSats} sats")
            assertTrue(fee.feeSats > 0)
        }
    }
}

// =============================================================================
// Renewal (Kotlin-only)
// =============================================================================

@RunWith(AndroidJUnit4::class)
class RenewalTests : LiveSuite() {
    /** A sweep renews every renewable leaf; the frozen ones (refund timelock below 100) are reported, never renewed. */
    @Test(timeout = 5 * MINUTE)
    fun aRenewalSweepLeavesNoRenewableLeafBelowTheThreshold() = liveTest {
        withWallet(WALLET_A_MNEMONIC) { wallet ->
            val leaves = wallet.getLeaves()
            val result = wallet.renewExhaustedLeaves()
            println("Renewal — checked ${result.checked}, renewed ${result.renewed}, failures ${result.failures}")
            assertEquals(leaves.size, result.checked)
            val frozen = leaves.filter { it.isFrozen }.map { it.id }.toSet()
            assertEquals(frozen, result.failures.map { it.substringBefore(":") }.toSet())
            for (leaf in wallet.getLeaves().filter { !it.isFrozen }) {
                assertTrue("leaf ${leaf.id} still below the renewal threshold (${leaf.refundTimelockBlocks})", leaf.refundTimelockBlocks >= RENEWAL_THRESHOLD)
            }
        }
    }
}
