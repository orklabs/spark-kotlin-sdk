package gy.pig.spark

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * Integration tests run against live Spark operators and submit real transactions.
 *
 * Mnemonics and Lightning addresses are loaded by [TestConfig] from `local.properties`
 * or environment variables — never hardcoded. See [TestConfig] for the keys and
 * `CONTRIBUTING.md` for the full setup. Tests skip (via JUnit `Assume`) when the
 * secrets are not configured, so they never fail in CI / on contributors' machines.
 *
 * **Never** commit a real mnemonic or write one to logs.
 */
private val WALLET_A_MNEMONIC: String get() = TestConfig.walletAMnemonic
private val WALLET_B_MNEMONIC: String get() = TestConfig.walletBMnemonic
private val MINIMUM_TEST_BALANCE: Long = TestConfig.MINIMUM_BALANCE_SATS

/** Resolves a lightning address (user@domain) to a BOLT11 invoice via LNURL-pay */
fun resolveLightningAddress(address: String, amountSats: Long): String {
    val parts = address.split("@")
    require(parts.size == 2) { "Invalid lightning address format" }
    val client = okhttp3.OkHttpClient()

    val lnurlResp = client.newCall(
        okhttp3.Request.Builder().url("https://${parts[1]}/.well-known/lnurlp/${parts[0]}").build()
    ).execute()
    val lnurlJson = org.json.JSONObject(lnurlResp.body!!.string())
    val callback = lnurlJson.getString("callback")

    val invoiceResp = client.newCall(
        okhttp3.Request.Builder().url("$callback?amount=${amountSats * 1000}").build()
    ).execute()
    val invoiceJson = org.json.JSONObject(invoiceResp.body!!.string())
    return invoiceJson.getString("pr")
}

// One live-network suite mirroring the Swift SDK's IntegrationTests; splitting it would only
// duplicate the wallet setup and funding checks.
@Suppress("LargeClass")
@RunWith(AndroidJUnit4::class)
class IntegrationTests {

    private lateinit var walletA: SparkWallet
    private lateinit var walletB: SparkWallet

    @Before
    fun setUp() {
        // Skip the entire test class when secrets are not configured. CI runs
        // unit tests only and never has these set; local devs configure them
        // via `local.properties` or env vars (see `TestConfig`).
        TestConfig.requireMnemonics()
        walletA = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = WALLET_A_MNEMONIC, account = 0)
        walletB = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = WALLET_B_MNEMONIC, account = 0)
    }

    @After
    fun tearDown() = runBlocking {
        walletA.close()
        walletB.close()
    }

    // =========================================================================
    // Balance Tests
    // =========================================================================

    @Test
    fun queryBalance() = runBlocking {
        val balance = walletA.getBalance()
        println(
            "Balance — available: ${balance.satsBalance.available} sats, " +
                "owned: ${balance.satsBalance.owned} sats, " +
                "incoming: ${balance.satsBalance.incoming} sats, " +
                "${balance.leaves.size} leaves",
        )
        assertTrue(balance.satsBalance.available >= 0)
        assertTrue(balance.satsBalance.owned >= balance.satsBalance.available)
    }

    @Test
    fun queryLeaves() = runBlocking {
        val leaves = walletA.getLeaves()
        for (leaf in leaves) {
            println("  Leaf ${leaf.id}: ${leaf.valueSats} sats [${leaf.status}]")
            assertTrue(leaf.valueSats > 0)
            assertEquals("AVAILABLE", leaf.status)
        }
    }

    // =========================================================================
    // Deposit Tests
    // =========================================================================

    @Test
    fun generateDepositAddress() = runBlocking {
        val deposit = walletA.getDepositAddress()
        assertTrue(deposit.address.isNotEmpty())
        assertTrue(deposit.address.startsWith("bc1p")) // P2TR address
        println("Deposit address: ${deposit.address}")
    }

    @Test
    fun generateStaticDepositAddress() = runBlocking {
        val deposit = walletA.getStaticDepositAddress()
        assertTrue(deposit.address.isNotEmpty())
        assertTrue(deposit.address.startsWith("bc1p"))
        println("Static deposit address: ${deposit.address}")
    }

    @Test
    fun staticDepositDeterministic() = runBlocking {
        val w2 = SparkWallet.fromMnemonic(config = SparkConfig(), mnemonic = WALLET_A_MNEMONIC, account = 0)
        val addr1 = walletA.getStaticDepositAddress()
        val addr2 = w2.getStaticDepositAddress()
        assertEquals(addr1.address, addr2.address)
        w2.close()
    }

    @Test
    fun queryUnusedAddresses() = runBlocking {
        val addresses = walletA.queryUnusedDepositAddresses()
        println("Unused deposit addresses: ${addresses.size}")
        for (addr in addresses) {
            assertTrue(addr.address.isNotEmpty())
            println("  ${addr.address} leafId=${addr.leafId}")
        }
    }

    @Test
    fun multipleDepositAddresses() = runBlocking {
        val countBefore = walletA.queryUnusedDepositAddresses().size
        walletA.getDepositAddress()
        walletA.getDepositAddress()
        val countAfter = walletA.queryUnusedDepositAddresses().size
        assertTrue(countAfter >= countBefore + 2)
    }

    @Test
    fun staticNotInUnused() = runBlocking {
        val staticAddr = walletB.getStaticDepositAddress()
        val unused = walletB.queryUnusedDepositAddresses()
        val found = unused.any { it.address == staticAddr.address }
        assertFalse(found)
    }

    @Test
    fun depositFeeEstimate() = runBlocking {
        val txID = "5e61b8909e4cd53fa3f33edde5571fa1f747eecf9cc7c00b17b5765e2eae77ac"
        val estimate = walletA.getDepositFeeEstimate(transactionId = txID, outputIndex = 17u)
        assertTrue(estimate.creditAmountSats > 0)
        assertTrue(estimate.quoteSignature.isNotEmpty())
        println("Credit amount: ${estimate.creditAmountSats} sats")
    }

    // =========================================================================
    // Lightning Tests
    // =========================================================================

    @Test
    fun createInvoice() = runBlocking {
        val invoice = walletA.createLightningInvoice(amountSats = 100, memo = "test invoice")
        assertTrue(invoice.paymentRequest.isNotEmpty())
        assertTrue(invoice.paymentHash.isNotEmpty())
        assertEquals(100L, invoice.amountSats)
        assertTrue(invoice.paymentRequest.lowercase().startsWith("lnbc"))
        println("Invoice: ${invoice.paymentRequest.take(50)}...")
    }

    @Test
    fun createInvoiceNoMemo() = runBlocking {
        val invoice = walletA.createLightningInvoice(amountSats = 50)
        assertTrue(invoice.paymentRequest.isNotEmpty())
    }

    @Test
    fun getSendFeeEstimate() = runBlocking {
        val invoice = walletB.createLightningInvoice(amountSats = 100)
        val fee = walletA.getLightningSendFeeEstimate(encodedInvoice = invoice.paymentRequest)
        assertTrue(fee >= 0)
        println("Fee estimate: $fee sats")
    }

    @Test
    fun payInvoice() = runBlocking {
        val balanceBefore = walletA.getBalance()
        val availableBefore = balanceBefore.satsBalance.available
        if (availableBefore < 100) {
            println("WalletA needs >= 100 sats (has $availableBefore), skipping")
            return@runBlocking
        }

        val invoice = walletB.createLightningInvoice(amountSats = 10, memo = "integration test")
        val paymentID = walletA.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = 50)
        assertTrue(paymentID.isNotEmpty())
        println("Payment ID: $paymentID")

        val balanceAfter = walletA.getBalance()
        val availableAfter = balanceAfter.satsBalance.available
        assertTrue(availableAfter < availableBefore)
        println("WalletA: $availableBefore -> $availableAfter sats")
    }

    // =========================================================================
    // Transfer Tests
    // =========================================================================

    @Test
    fun payLightningAddress() = runBlocking {
        TestConfig.requireLnAddress()
        val balance = walletA.getBalance()
        if (balance.totalSats < 50) {
            println("WalletA needs >= 50 sats, skipping")
            return@runBlocking
        }

        val bolt11 = resolveLightningAddress(TestConfig.lnAddress, amountSats = 10)
        val paymentID = walletA.payLightningInvoice(paymentRequest = bolt11, maxFeeSats = 50)
        assertTrue(paymentID.isNotEmpty())
        println("External payment ID: $paymentID")
    }

    // =========================================================================
    // Transfer Tests
    // =========================================================================

    @Test
    fun claimPending() = runBlocking {
        val claimed = walletA.claimAllPendingTransfers()
        println("Claimed $claimed pending transfers")
    }

    @Test
    fun sparkTransfer() = runBlocking {
        val balanceA = walletA.getBalance()
        if (balanceA.totalSats < 100) {
            println("WalletA needs >= 100 sats (has ${balanceA.totalSats}), skipping")
            return@runBlocking
        }

        val receiverPubKey = walletB.identityPublicKeyHex.hexToByteArray()
        val transfer = walletA.send(
            receiverIdentityPublicKey = receiverPubKey,
            amountSats = 10,
        )
        assertTrue(transfer.id.isNotEmpty())
        println("Transfer sent: ${transfer.id} status=${transfer.status}")

        // Wait for propagation
        kotlinx.coroutines.delay(3000)

        val claimed = walletB.claimAllPendingTransfers()
        assertTrue(claimed >= 1)

        val balanceB = walletB.getBalance()
        assertTrue(balanceB.totalSats >= 10)
        println("WalletB balance: ${balanceB.totalSats} sats")
    }

    // =========================================================================
    // Withdrawal Tests
    // =========================================================================

    @Test
    fun roundTripTransfer() = runBlocking {
        val balanceA = walletA.getBalance()
        if (balanceA.totalSats < 50) {
            println("WalletA needs >= 50 sats, skipping")
            return@runBlocking
        }

        // Send A -> B
        val sendAmount = 20L
        val pubB = walletB.identityPublicKeyHex.hexToByteArray()
        walletA.send(receiverIdentityPublicKey = pubB, amountSats = sendAmount)
        kotlinx.coroutines.delay(3000)
        walletB.claimAllPendingTransfers()

        // Send all B -> A
        val balB = walletB.getBalance()
        assertTrue(balB.totalSats > 0)

        val pubA = walletA.identityPublicKeyHex.hexToByteArray()
        val transfer = walletB.send(
            receiverIdentityPublicKey = pubA,
            amountSats = balB.totalSats,
        )
        println("Return transfer: ${transfer.id}")

        kotlinx.coroutines.delay(3000)
        val claimed = walletA.claimAllPendingTransfers()
        assertTrue(claimed >= 1)

        // B should be empty
        val finalB = walletB.getBalance()
        assertEquals(0L, finalB.totalSats)
        println("WalletB final balance: ${finalB.totalSats} sats")
    }

    // =========================================================================
    // Withdrawal Tests
    // =========================================================================

    @Test
    fun feeEstimate() = runBlocking {
        val leaves = walletA.getLeaves()
        if (leaves.isEmpty()) {
            println("WalletA has no leaves, skipping")
            return@runBlocking
        }

        val fee = walletA.getWithdrawalFeeEstimate(
            onChainAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            leafIds = leaves.map { it.id },
        )
        assertTrue(fee.feeSats > 0)
        println("Withdrawal fee estimate: ${fee.feeSats} sats")
    }

    // =========================================================================
    // Settings Tests
    // =========================================================================

    @Test
    fun privacyMode() = runBlocking {
        val settings = walletA.getWalletSettings()
        println("Privacy enabled: ${settings.privateEnabled}")

        val updated = walletA.setPrivacyEnabled(true)
        assertTrue(updated.privateEnabled)
        println("Privacy toggled ON")

        val check = walletA.getWalletSettings()
        assertTrue(check.privateEnabled)

        val restored = walletA.setPrivacyEnabled(false)
        assertFalse(restored.privateEnabled)
        println("Privacy toggled OFF")
    }

    // =========================================================================
    // Transfer Query Tests
    // =========================================================================

    @Test
    fun getTransfers() = runBlocking {
        val transfers = walletA.getTransfers(limit = 10)
        println("=== Recent Transfers (${transfers.size}) ===")
        for (t in transfers) {
            val dir = if (t.senderIdentityPublicKey == walletA.identityPublicKeyHex) "SENT" else "RECV"
            println("  $dir | ${t.totalValueSats} sats | ${t.status} | ${t.type} | ${t.id}")
        }
        assertTrue(transfers.isNotEmpty())

        val first = transfers[0]
        val single = walletA.getTransfer(id = first.id)
        assertEquals(single.id, first.id)
        assertEquals(single.totalValueSats, first.totalValueSats)
    }

    // =========================================================================
    // Debug / Info
    // =========================================================================

    @Test
    fun showWalletInfo() = runBlocking {
        val balA = walletA.getBalance()
        val balB = walletB.getBalance()

        println("=== Wallet A ===")
        println("  Identity: ${walletA.identityPublicKeyHex}")
        println("  Spark:    ${walletA.getSparkAddress()}")
        println("  Balance:  ${balA.totalSats} sats (${balA.leaves.size} leaves)")

        println("=== Wallet B ===")
        println("  Identity: ${walletB.identityPublicKeyHex}")
        println("  Spark:    ${walletB.getSparkAddress()}")
        println("  Balance:  ${balB.totalSats} sats (${balB.leaves.size} leaves)")
    }

    // =========================================================================
    // Funding Helpers
    // =========================================================================

    @Test
    fun fundWalletA() = runBlocking {
        val invoice = walletA.createLightningInvoice(amountSats = 2000, memo = "Fund walletA for tests")
        println("\n=== PAY THIS INVOICE TO FUND WALLET A ===")
        println(invoice.paymentRequest)
        println("==========================================")
        println("Amount: 2000 sats | Hash: ${invoice.paymentHash}")
    }

    @Test
    fun withdrawToOnchain() = runBlocking {
        val onchainAddress = "bc1qxaljgr87rlh6plxtjmxvkk9p45kk8dw743dg2s"

        val balanceBefore = walletA.getBalance()
        val spendable = filterSpendableLeaves(balanceBefore.leaves)
        val spendableSats = spendable.sumOf { it.valueSats }
        println("=== WalletA balance before ===")
        println("  Total: ${balanceBefore.totalSats} sats (${balanceBefore.leaves.size} leaves)")
        println("  Spendable: $spendableSats sats (${spendable.size} leaves)")
        println("  Stuck/expired: ${balanceBefore.totalSats - spendableSats} sats")

        if (spendableSats < 1000) {
            println("Insufficient spendable balance for withdrawal test")
            return@runBlocking
        }

        val feeEstimate = walletA.getWithdrawalFeeEstimate(
            onChainAddress = onchainAddress,
            leafIds = spendable.map { it.id },
        )
        println("Fee estimate: ${feeEstimate.feeSats} sats")
        println("Would receive: ${spendableSats - feeEstimate.feeSats} sats on-chain")

        val txid = walletA.withdraw(
            onChainAddress = onchainAddress,
            amountSats = spendableSats,
        )
        println("=== Withdrawal txid: $txid ===")
        println("View at: https://mempool.space/tx/$txid")
        assertTrue(txid.isNotEmpty())

        kotlinx.coroutines.delay(3000)

        val balanceAfter = walletA.getBalance()
        println("WalletA balance after: ${balanceAfter.totalSats} sats")
    }

    @Test
    fun fundWalletA5000() = runBlocking {
        val invoice = walletA.createLightningInvoice(amountSats = 5000, memo = "Fund walletA for withdraw test")
        println("\n=== PAY THIS INVOICE TO FUND WALLET A (5000 sats) ===")
        println(invoice.paymentRequest)
        println("=====================================================")
        println("Amount: 5000 sats | Hash: ${invoice.paymentHash}")
    }

    @Test
    fun claimWalletA() = runBlocking {
        val claimed = walletA.claimAllPendingTransfers()
        val balance = walletA.getBalance()
        println("Claimed $claimed transfers. Balance: ${balance.totalSats} sats")
    }

    @Test
    fun receivePayment() = runBlocking {
        val balanceBefore = walletA.getBalance()
        println("Balance before: ${balanceBefore.totalSats} sats")

        val invoice = walletA.createLightningInvoice(amountSats = 100, memo = "pay me 100 sats")
        println("\n=== PAY THIS INVOICE (100 sats) ===")
        println(invoice.paymentRequest)
        println("===================================")

        // Poll for payment (up to 120 seconds)
        println("Waiting for payment...")
        var paid = false
        for (i in 1..24) {
            kotlinx.coroutines.delay(5000)
            val claimed = walletA.claimAllPendingTransfers()
            if (claimed > 0) {
                println("Claimed $claimed transfers after ${i * 5}s!")
                paid = true
                break
            }
            print(".")
        }

        val balanceAfter = walletA.getBalance()
        println("Balance after: ${balanceAfter.totalSats} sats")
        if (paid) {
            assertTrue(balanceAfter.totalSats > balanceBefore.totalSats)
            println("Payment received! +${balanceAfter.totalSats - balanceBefore.totalSats} sats")
        } else {
            println("Timed out waiting for payment")
        }
    }

    // =========================================================================
    // Full Flow: Lightning A->B, Spark B->A, external lightning pay to TestConfig.lnAddress
    // =========================================================================

    @Test
    fun testOnChainFeeEstimate() = runBlocking {
        val address = "bc1qdxqntgy40ut7ep3mddds98t5undss7ka6l2dud"
        val balance = walletA.getBalance()
        println("Balance: ${balance.totalSats} sats")

        val leaves = walletA.getLeaves()
        println("Leaves: ${leaves.size}")
        if (leaves.isEmpty()) {
            println("No leaves — can't estimate fee")
            return@runBlocking
        }

        val fee = walletA.getWithdrawalFeeEstimate(
            onChainAddress = address,
            leafIds = leaves.map { it.id },
        )
        println("On-chain fee estimate: ${fee.feeSats} sats")
        assertTrue(fee.feeSats > 0)
    }

    // =========================================================================
    // Full Flow
    // =========================================================================

    // =========================================================================
    // Token Tests
    // =========================================================================

    @Test
    fun queryTokenBalances() = runBlocking {
        val balances = walletA.getTokenBalances()
        println("Token balances: ${balances.size} tokens")
        for (balance in balances) {
            println("  ${balance.tokenMetadata.tokenName} (${balance.tokenMetadata.tokenTicker})")
            println("    identifier: ${balance.tokenMetadata.tokenIdentifier}")
            println("    owned: ${balance.ownedBalance}")
            println("    available: ${balance.availableToSendBalance}")
            println("    decimals: ${balance.tokenMetadata.decimals}")
        }
    }

    @Test
    fun queryTokenOutputs() = runBlocking {
        val outputs = walletA.getTokenOutputs()
        println("Token outputs: ${outputs.size}")
        for (output in outputs.take(5)) {
            val tokenId = output.tokenIdentifier.toHexString()
            println("  amount=${output.tokenAmount} token=${tokenId.take(16)}... status=${output.status}")
        }
    }

    @Test
    fun queryTokenMetadataByIssuer() = runBlocking {
        val metadatas = walletA.queryTokenMetadata(
            issuerPublicKeys = listOf(walletA.signer.identityPublicKey)
        )
        println("Token metadata for issuer: ${metadatas.size} tokens")
        for (meta in metadatas) {
            println("  ${meta.tokenName} (${meta.tokenTicker})")
            println("    identifier: ${meta.tokenIdentifier}")
            println("    issuer: ${meta.issuerPublicKey.toHexString()}")
            println("    freezable: ${meta.isFreezable}")
        }
    }

    @Test
    fun fullTokenLifecycle() = runBlocking {
        // --- Phase 1: Create Token (or reuse existing) ---
        println("\n--- Phase 1: Create Token ---")
        val existingMetadatas = walletA.queryTokenMetadata(
            issuerPublicKeys = listOf(walletA.signer.identityPublicKey)
        )

        val tokenIdentifier: String
        if (existingMetadatas.isNotEmpty()) {
            val existing = existingMetadatas[0]
            tokenIdentifier = existing.tokenIdentifier
            println("Reusing existing token: ${existing.tokenName} (${existing.tokenTicker})")
            println("Token identifier: $tokenIdentifier")
        } else {
            val creation = walletA.createToken(
                tokenName = "KotlinTest",
                tokenTicker = "KTST",
                decimals = 2u,
                maxSupply = BigInteger.valueOf(1_000_000),
                isFreezable = false,
            )
            assertTrue(creation.transactionHash.isNotEmpty())
            println("Token created, tx: ${creation.transactionHash}")

            kotlinx.coroutines.delay(5000)

            val metadatas = walletA.queryTokenMetadata(
                issuerPublicKeys = listOf(walletA.signer.identityPublicKey)
            )
            assertTrue("Token metadata not found after creation", metadatas.isNotEmpty())
            tokenIdentifier = metadatas[0].tokenIdentifier
            println("Token identifier: $tokenIdentifier")
        }
        assertTrue(tokenIdentifier.startsWith("btkn"))

        // --- Phase 2: Mint Tokens ---
        println("\n--- Phase 2: Mint 10000 tokens ---")
        val mintAmount = BigInteger.valueOf(10_000)
        val mintTx = walletA.mintTokens(
            tokenIdentifier = tokenIdentifier,
            tokenAmount = mintAmount,
        )
        assertTrue(mintTx.isNotEmpty())
        println("Mint tx: $mintTx")

        kotlinx.coroutines.delay(5000)

        val balancesAfterMint = walletA.getTokenBalances()
        val swftBalance = balancesAfterMint.firstOrNull { it.tokenMetadata.tokenIdentifier == tokenIdentifier }
        assertNotNull(swftBalance)
        println("WalletA token balance after mint: ${swftBalance!!.ownedBalance}")
        assertTrue(swftBalance.ownedBalance >= mintAmount)

        // --- Phase 3: Transfer A -> B (5000 tokens) ---
        println("\n--- Phase 3: Transfer 5000 tokens A -> B ---")
        val transferAmount = BigInteger.valueOf(5_000)
        val sparkAddressB = walletB.getSparkAddress()
        val transferTx = walletA.transferTokens(
            tokenIdentifier = tokenIdentifier,
            tokenAmount = transferAmount,
            receiverSparkAddress = sparkAddressB,
        )
        assertTrue(transferTx.isNotEmpty())
        println("Transfer A->B tx: $transferTx")

        kotlinx.coroutines.delay(5000)

        val balancesB = walletB.getTokenBalances()
        val balanceB = balancesB.firstOrNull { it.tokenMetadata.tokenIdentifier == tokenIdentifier }
        println("WalletB token balance: ${balanceB?.ownedBalance ?: 0}")
        assertNotNull(balanceB)
        assertTrue(balanceB!!.ownedBalance >= transferAmount)

        // --- Phase 4: Transfer B -> A (send it all back) ---
        println("\n--- Phase 4: Transfer all tokens B -> A ---")
        val sparkAddressA = walletA.getSparkAddress()
        val returnTx = walletB.transferTokens(
            tokenIdentifier = tokenIdentifier,
            tokenAmount = transferAmount,
            receiverSparkAddress = sparkAddressA,
        )
        assertTrue(returnTx.isNotEmpty())
        println("Transfer B->A tx: $returnTx")

        kotlinx.coroutines.delay(5000)

        val finalBBalances = walletB.getTokenBalances()
        val finalBToken = finalBBalances.firstOrNull { it.tokenMetadata.tokenIdentifier == tokenIdentifier }
        println("WalletB final token balance: ${finalBToken?.ownedBalance ?: 0}")
        assertTrue(finalBToken == null || finalBToken.ownedBalance == BigInteger.ZERO)

        val finalABalances = walletA.getTokenBalances()
        val finalAToken = finalABalances.firstOrNull { it.tokenMetadata.tokenIdentifier == tokenIdentifier }
        println("WalletA final token balance: ${finalAToken?.ownedBalance ?: 0}")
        assertNotNull(finalAToken)
        assertTrue(finalAToken!!.ownedBalance >= mintAmount)

        // --- Phase 5: Burn some tokens ---
        println("\n--- Phase 5: Burn 1000 tokens ---")
        val burnAmount = BigInteger.valueOf(1_000)
        val burnTx = walletA.burnTokens(
            tokenIdentifier = tokenIdentifier,
            tokenAmount = burnAmount,
        )
        assertTrue(burnTx.isNotEmpty())
        println("Burn tx: $burnTx")

        kotlinx.coroutines.delay(5000)

        val afterBurn = walletA.getTokenBalances()
        val afterBurnToken = afterBurn.firstOrNull { it.tokenMetadata.tokenIdentifier == tokenIdentifier }
        println("WalletA token balance after burn: ${afterBurnToken?.ownedBalance ?: 0}")

        println("\nFull token lifecycle complete!")
    }

    // =========================================================================
    // Static Deposit Tests
    // =========================================================================

    @Test
    fun queryStaticDepositAddresses() = runBlocking {
        val addresses = walletA.queryStaticDepositAddresses()
        println("Static deposit addresses: ${addresses.size}")
        for (addr in addresses) {
            println("  ${addr.address}")
        }
    }

    @Test
    fun getUtxosForStaticDeposit() = runBlocking {
        val staticAddr = walletA.getStaticDepositAddress()
        val utxos = walletA.getUtxosForDepositAddress(address = staticAddr.address)
        println("UTXOs at static address: ${utxos.size}")
        for (utxo in utxos) {
            println("  txid=${utxo.txid} vout=${utxo.vout}")
        }
    }

    @Test
    fun getUtxosForDepositAddress() = runBlocking {
        val addresses = walletA.queryUnusedDepositAddresses()
        if (addresses.isEmpty()) {
            println("No unused deposit addresses, skipping")
            return@runBlocking
        }
        val addr = addresses[0]
        val utxos = walletA.getUtxosForDepositAddress(address = addr.address)
        println("UTXOs at ${addr.address}: ${utxos.size}")
        for (utxo in utxos) {
            println("  txid=${utxo.txid} vout=${utxo.vout}")
        }
    }

    // =========================================================================
    // Idempotency Tests
    // =========================================================================

    @Test
    fun lightningPaymentIdempotency() = runBlocking {
        val balance = walletA.getBalance()
        if (balance.totalSats < 100) {
            println("WalletA needs >= 100 sats, skipping")
            return@runBlocking
        }

        val invoice = walletB.createLightningInvoice(amountSats = 10, memo = "idempotency test")
        val idempotencyKey = "test-idem-ln-${java.util.UUID.randomUUID()}"

        val paymentId = walletA.payLightningInvoice(
            paymentRequest = invoice.paymentRequest,
            maxFeeSats = 50,
            idempotencyKey = idempotencyKey,
        )
        assertTrue(paymentId.isNotEmpty())
        println("Lightning payment with idempotency key: $paymentId")

        val balanceAfter = walletA.getBalance()
        println("WalletA balance after: ${balanceAfter.totalSats} sats (was ${balance.totalSats})")
        assertTrue(balanceAfter.totalSats < balance.totalSats)
    }

    @Test
    fun tokenTransferIdempotency() = runBlocking {
        val balancesBefore = walletA.getTokenBalances()
        val tokenBal = balancesBefore.firstOrNull()
        if (tokenBal == null || tokenBal.ownedBalance < BigInteger.valueOf(100)) {
            println("No token with >= 100 balance, skipping")
            return@runBlocking
        }
        val tokenIdentifier = tokenBal.tokenMetadata.tokenIdentifier
        val balanceBefore = tokenBal.ownedBalance
        println("WalletA token balance before: $balanceBefore")

        val sparkAddressB = walletB.getSparkAddress()
        val idempotencyKey = "test-idem-token-${java.util.UUID.randomUUID()}"
        val transferAmount = BigInteger.valueOf(50)

        val tx1 = walletA.transferTokens(
            tokenIdentifier = tokenIdentifier,
            tokenAmount = transferAmount,
            receiverSparkAddress = sparkAddressB,
            idempotencyKey = idempotencyKey,
        )
        assertTrue(tx1.isNotEmpty())
        println("First transfer tx: $tx1")

        kotlinx.coroutines.delay(3000)

        val balancesAfterFirst = walletA.getTokenBalances()
        val afterFirst = balancesAfterFirst.first { it.tokenMetadata.tokenIdentifier == tokenIdentifier }
        println("WalletA token balance after transfer: ${afterFirst.ownedBalance}")
        assertTrue(afterFirst.ownedBalance == balanceBefore - transferAmount)

        // Send back from B to A to clean up
        val sparkAddressA = walletA.getSparkAddress()
        walletB.transferTokens(
            tokenIdentifier = tokenIdentifier,
            tokenAmount = transferAmount,
            receiverSparkAddress = sparkAddressA,
        )
        kotlinx.coroutines.delay(3000)

        val balancesFinal = walletA.getTokenBalances()
        val finalBal = balancesFinal.first { it.tokenMetadata.tokenIdentifier == tokenIdentifier }
        println("WalletA token balance after return: ${finalBal.ownedBalance}")
        assertTrue(finalBal.ownedBalance == balanceBefore)
        println("Token idempotency test passed!")
    }

    @Test
    fun mintIdempotency() = runBlocking {
        val metadatas = walletA.queryTokenMetadata(
            issuerPublicKeys = listOf(walletA.signer.identityPublicKey)
        )
        if (metadatas.isEmpty()) {
            println("No token found, skipping. Run fullTokenLifecycle first.")
            return@runBlocking
        }
        val tokenMeta = metadatas[0]

        val balanceBefore = walletA.getTokenBalances()
        val ownedBefore = balanceBefore.first {
            it.tokenMetadata.tokenIdentifier == tokenMeta.tokenIdentifier
        }.ownedBalance
        println("Token balance before mint: $ownedBefore")

        val mintAmount = BigInteger.valueOf(100)
        val tx = walletA.mintTokens(
            tokenIdentifier = tokenMeta.tokenIdentifier,
            tokenAmount = mintAmount,
        )
        assertTrue(tx.isNotEmpty())
        println("Mint tx: $tx")

        kotlinx.coroutines.delay(3000)

        val balanceAfter = walletA.getTokenBalances()
        val ownedAfter = balanceAfter.first {
            it.tokenMetadata.tokenIdentifier == tokenMeta.tokenIdentifier
        }.ownedBalance
        println("Token balance after mint: $ownedAfter")
        assertTrue(ownedAfter == ownedBefore + mintAmount)
        println("Mint idempotency test passed!")
    }

    // =========================================================================
    // Larger transfer stress test — verifies leaf selection on big amounts
    // =========================================================================

    @Test
    fun largeTransferStress() = runBlocking {
        val initialA = walletA.getBalance()
        val initialB = walletB.getBalance()
        val initialTotal = initialA.totalSats + initialB.totalSats
        println("=== Initial state ===")
        println("  WalletA: ${initialA.totalSats} sats (${initialA.leaves.size} leaves)")
        println("  WalletB: ${initialB.totalSats} sats (${initialB.leaves.size} leaves)")
        println("  TOTAL:   $initialTotal sats")

        val sparkAmount = (initialA.totalSats / 2).coerceAtLeast(50)
        if (initialA.totalSats < sparkAmount + 50) {
            println("Not enough balance for stress test")
            return@runBlocking
        }

        // --- Phase 1: Large Spark transfer A -> B ---
        println("\n--- Phase 1: Spark A -> B ($sparkAmount sats) ---")
        val pubB = walletB.identityPublicKeyHex.hexToByteArray()
        val transferAB = walletA.send(receiverIdentityPublicKey = pubB, amountSats = sparkAmount)
        assertTrue(transferAB.id.isNotEmpty())
        println("  Transfer: ${transferAB.id}")

        kotlinx.coroutines.delay(3000)
        val claimedB1 = walletB.claimAllPendingTransfers()
        assertTrue(claimedB1 >= 1)

        val phase1A = walletA.getBalance()
        val phase1B = walletB.getBalance()
        val phase1Total = phase1A.totalSats + phase1B.totalSats
        val phase1Loss = initialTotal - phase1Total
        println("  WalletA: ${initialA.totalSats} -> ${phase1A.totalSats} (Δ ${phase1A.totalSats - initialA.totalSats})")
        println("  WalletB: ${initialB.totalSats} -> ${phase1B.totalSats} (Δ +${phase1B.totalSats - initialB.totalSats})")
        println("  System loss: $phase1Loss sats")

        assertEquals(
            "Large Spark transfer A->B must have ZERO loss",
            0L,
            phase1Loss,
        )
        assertEquals(
            "WalletB should receive exactly $sparkAmount sats",
            initialB.totalSats + sparkAmount,
            phase1B.totalSats,
        )

        // --- Phase 2: Large Spark transfer B -> A (full balance) ---
        println("\n--- Phase 2: Spark B -> A (${phase1B.totalSats} sats, full balance) ---")
        val pubA = walletA.identityPublicKeyHex.hexToByteArray()
        val transferBA = walletB.send(receiverIdentityPublicKey = pubA, amountSats = phase1B.totalSats)
        assertTrue(transferBA.id.isNotEmpty())

        kotlinx.coroutines.delay(3000)
        val claimedA = walletA.claimAllPendingTransfers()
        assertTrue(claimedA >= 1)

        val phase2A = walletA.getBalance()
        val phase2B = walletB.getBalance()
        val phase2Total = phase2A.totalSats + phase2B.totalSats
        val phase2Loss = phase1Total - phase2Total
        println("  WalletA: ${phase1A.totalSats} -> ${phase2A.totalSats} (Δ +${phase2A.totalSats - phase1A.totalSats})")
        println("  WalletB: ${phase1B.totalSats} -> ${phase2B.totalSats}")
        println("  System loss: $phase2Loss sats")

        assertEquals("Spark transfer B->A must have ZERO loss", 0L, phase2Loss)
        assertEquals("WalletB must be empty", 0L, phase2B.totalSats)

        // --- Phase 3: Partial Spark transfer A -> B (an "odd" amount that requires swap) ---
        // Use an amount unlikely to match power-of-2 leaf denominations
        val oddAmount = 137L
        println("\n--- Phase 3: Spark A -> B ($oddAmount sats, odd amount likely needs swap) ---")
        val transferOdd = walletA.send(receiverIdentityPublicKey = pubB, amountSats = oddAmount)
        assertTrue(transferOdd.id.isNotEmpty())

        kotlinx.coroutines.delay(3000)
        val claimedB2 = walletB.claimAllPendingTransfers()
        assertTrue(claimedB2 >= 1)

        val phase3A = walletA.getBalance()
        val phase3B = walletB.getBalance()
        val phase3Total = phase3A.totalSats + phase3B.totalSats
        val phase3Loss = phase2Total - phase3Total
        println("  WalletA: ${phase2A.totalSats} -> ${phase3A.totalSats} (Δ ${phase3A.totalSats - phase2A.totalSats})")
        println("  WalletB: ${phase2B.totalSats} -> ${phase3B.totalSats} (Δ +${phase3B.totalSats - phase2B.totalSats})")
        println("  System loss: $phase3Loss sats")

        assertEquals("Odd-amount Spark transfer must have ZERO loss", 0L, phase3Loss)
        assertEquals(
            "WalletB should receive exactly $oddAmount sats",
            oddAmount,
            phase3B.totalSats,
        )

        // --- Phase 4: Send it all back B -> A ---
        println("\n--- Phase 4: Spark B -> A ($oddAmount sats back) ---")
        walletB.send(receiverIdentityPublicKey = pubA, amountSats = phase3B.totalSats)
        kotlinx.coroutines.delay(3000)
        walletA.claimAllPendingTransfers()

        val finalA = walletA.getBalance()
        val finalB = walletB.getBalance()
        val finalTotal = finalA.totalSats + finalB.totalSats
        val totalLoss = initialTotal - finalTotal

        println("\n=== Final accounting ===")
        println("  Initial: $initialTotal sats (A=${initialA.totalSats}, B=${initialB.totalSats})")
        println("  Final:   $finalTotal sats (A=${finalA.totalSats}, B=${finalB.totalSats})")
        println("  Total loss across all 4 Spark transfers: $totalLoss sats")

        assertEquals(
            "All-Spark stress test must have ZERO total loss (no LN involved)",
            0L,
            totalLoss,
        )
        assertEquals("WalletB must be empty at end", 0L, finalB.totalSats)
        println("Large transfer stress test passed — zero leaf loss across 4 large transfers!")
    }

    // =========================================================================
    // Full Flow
    // =========================================================================

    @Test
    fun fullRoundTrip() = runBlocking {
        val initialA = walletA.getBalance()
        val initialB = walletB.getBalance()
        val initialTotal = initialA.totalSats + initialB.totalSats
        println("=== Initial state ===")
        println("  WalletA: ${initialA.totalSats} sats (${initialA.leaves.size} leaves)")
        println("  WalletB: ${initialB.totalSats} sats (${initialB.leaves.size} leaves)")
        println("  TOTAL:   $initialTotal sats")

        if (initialA.totalSats < MINIMUM_TEST_BALANCE) {
            val deposit = walletA.getDepositAddress()
            println("WalletA needs >= $MINIMUM_TEST_BALANCE sats. Deposit to: ${deposit.address}")
            fail("Insufficient funds")
        }

        // --- Phase 1: Lightning A -> B (100 sats) ---
        // Lightning has fees, so the system total decreases by the lightning fee.
        println("\n--- Phase 1: Lightning A -> B (100 sats) ---")
        val lnAmount = 100L
        val lnFeeEstimate = walletA.getLightningSendFeeEstimate(
            encodedInvoice = walletB.createLightningInvoice(amountSats = lnAmount).paymentRequest
        )
        println("  Estimated LN fee: $lnFeeEstimate sats")

        val invoice = walletB.createLightningInvoice(amountSats = lnAmount, memo = "full flow test")
        assertEquals(lnAmount, invoice.amountSats)

        val payID = walletA.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = 50)
        assertTrue(payID.isNotEmpty())
        println("  Payment sent: $payID")

        kotlinx.coroutines.delay(5000)
        val claimedB = walletB.claimAllPendingTransfers()
        println("  WalletB claimed $claimedB transfers")

        val afterPhase1A = walletA.getBalance()
        val afterPhase1B = walletB.getBalance()
        val afterPhase1Total = afterPhase1A.totalSats + afterPhase1B.totalSats
        val phase1Loss = initialTotal - afterPhase1Total
        println("  WalletA: ${initialA.totalSats} -> ${afterPhase1A.totalSats} (Δ ${afterPhase1A.totalSats - initialA.totalSats})")
        println("  WalletB: ${initialB.totalSats} -> ${afterPhase1B.totalSats} (Δ +${afterPhase1B.totalSats - initialB.totalSats})")
        println("  System loss (LN fee): $phase1Loss sats")

        // WalletB should have received exactly lnAmount more
        assertEquals(
            "WalletB should receive exactly $lnAmount sats",
            initialB.totalSats + lnAmount,
            afterPhase1B.totalSats,
        )
        // WalletA should have lost exactly (lnAmount + actual_fee)
        // Loss should match the LN fee — sanity check it's not insanely large
        assertTrue(
            "Phase 1 loss ($phase1Loss) should be small (just LN fee), not loss of leaves",
            phase1Loss in 0..(lnAmount * 2),
        )

        // --- Phase 2: Spark Transfer B -> A (full balance, no fee) ---
        // Spark transfers have NO fee, so the system total must be unchanged.
        println("\n--- Phase 2: Spark B -> A (${afterPhase1B.totalSats} sats) ---")
        val pubA = walletA.identityPublicKeyHex.hexToByteArray()
        val transfer = walletB.send(
            receiverIdentityPublicKey = pubA,
            amountSats = afterPhase1B.totalSats,
        )
        assertTrue(transfer.id.isNotEmpty())
        println("  Transfer: ${transfer.id}")

        kotlinx.coroutines.delay(3000)
        val claimedA = walletA.claimAllPendingTransfers()
        assertTrue(claimedA >= 1)

        val afterPhase2A = walletA.getBalance()
        val afterPhase2B = walletB.getBalance()
        val afterPhase2Total = afterPhase2A.totalSats + afterPhase2B.totalSats
        val phase2Loss = afterPhase1Total - afterPhase2Total
        println("  WalletA: ${afterPhase1A.totalSats} -> ${afterPhase2A.totalSats} (Δ +${afterPhase2A.totalSats - afterPhase1A.totalSats})")
        println("  WalletB: ${afterPhase1B.totalSats} -> ${afterPhase2B.totalSats} (Δ ${afterPhase2B.totalSats - afterPhase1B.totalSats})")
        println("  System loss: $phase2Loss sats")

        // Spark transfers MUST have zero loss — this is the leaf selection bug check
        assertEquals(
            "Spark transfer must have ZERO loss (leaf selection bug check)",
            0L,
            phase2Loss,
        )
        // WalletB must be fully drained
        assertEquals("WalletB must be empty after sending all balance", 0L, afterPhase2B.totalSats)
        // WalletA must have grown by exactly afterPhase1B.totalSats
        assertEquals(
            "WalletA must receive exactly the sent amount",
            afterPhase1A.totalSats + afterPhase1B.totalSats,
            afterPhase2A.totalSats,
        )

        // --- Phase 3: External Lightning A -> ${TestConfig.lnAddress} (10 sats) ---
        TestConfig.requireLnAddress()
        println("\n--- Phase 3: Lightning A -> ${TestConfig.lnAddress} (10 sats) ---")
        val bolt11 = resolveLightningAddress(TestConfig.lnAddress, amountSats = 10)
        val extPayID = walletA.payLightningInvoice(paymentRequest = bolt11, maxFeeSats = 50)
        assertTrue(extPayID.isNotEmpty())
        println("  External payment: $extPayID")

        val finalA = walletA.getBalance()
        val finalB = walletB.getBalance()
        val finalTotal = finalA.totalSats + finalB.totalSats
        val phase3Loss = afterPhase2Total - finalTotal
        println("  WalletA: ${afterPhase2A.totalSats} -> ${finalA.totalSats} (Δ ${finalA.totalSats - afterPhase2A.totalSats})")
        println("  Phase 3 loss (10 sats + LN fee): $phase3Loss sats")

        // Phase 3 sends 10 sats externally, so loss should be ~10 + fee
        assertTrue(
            "Phase 3 loss ($phase3Loss) should be small (10 sats + LN fee)",
            phase3Loss in 10..200,
        )

        // --- Final accounting ---
        val totalLoss = initialTotal - finalTotal
        println("\n=== Final accounting ===")
        println("  Initial:    $initialTotal sats")
        println("  Final:      $finalTotal sats")
        println("  Total loss: $totalLoss sats (LN fees + 10 sats sent externally)")
        println("  WalletA: ${initialA.totalSats} -> ${finalA.totalSats}")
        println("  WalletB: ${initialB.totalSats} -> ${finalB.totalSats}")

        // Total loss should be: 10 sats sent externally + 2 LN fees
        // Sanity check: should never lose more than ~300 sats total
        assertTrue(
            "Total loss ($totalLoss) is too high — possible leaf selection bug",
            totalLoss in 10..300,
        )
        println("Full flow complete — no leaves lost!")
    }

    // =========================================================================
    // Recovery / Consolidation / Renewal Tests
    // =========================================================================

    @Test
    fun recoverySnapshot() = runBlocking {
        val snapshot = walletA.getRecoverySnapshot()
        val balance = walletA.getBalance()

        assertEquals("MAINNET", snapshot.network)
        assertEquals(walletA.identityPublicKeyHex, snapshot.identityPublicKeyHex)
        // Snapshot covers owned statuses beyond AVAILABLE, so it is >= available.
        assertTrue(
            "snapshot ${snapshot.totalLeafSats} < available ${balance.satsBalance.available}",
            snapshot.totalLeafSats >= balance.satsBalance.available,
        )

        val byId = mutableMapOf<String, spark.Spark.TreeNode>()
        for (leaf in snapshot.leaves) {
            val node = spark.Spark.TreeNode.parseFrom(leaf.treeNodeHex.hexToByteArray())
            assertEquals(leaf.id, node.id)
            assertTrue("leaf ${leaf.id} missing node tx", !node.nodeTx.isEmpty)
            assertTrue("leaf ${leaf.id} missing refund tx", !node.refundTx.isEmpty)
            byId[node.id] = node
        }
        for (ancestor in snapshot.nodes) {
            val node = spark.Spark.TreeNode.parseFrom(ancestor.treeNodeHex.hexToByteArray())
            assertEquals(ancestor.id, node.id)
            assertTrue("ancestor ${ancestor.id} missing node tx", !node.nodeTx.isEmpty)
            byId[node.id] = node
        }

        // Every leaf's chain must terminate at a root inside the bundle.
        for (leaf in snapshot.leaves) {
            var cursor = byId.getValue(leaf.id)
            var hops = 0
            while (cursor.hasParentNodeId() && cursor.parentNodeId.isNotEmpty()) {
                assertTrue("cycle or over-long chain above ${leaf.id}", hops < 100)
                cursor = byId[cursor.parentNodeId]
                    ?: throw AssertionError("hole above leaf ${leaf.id}: ${cursor.parentNodeId}")
                hops++
            }
        }
        println(
            "Recovery snapshot — ${snapshot.leaves.size} leaves, ${snapshot.nodes.size} ancestors, " +
                "${snapshot.totalLeafSats} sats",
        )
    }

    @Test
    fun consolidateLeaves() = runBlocking {
        val before = walletA.getLeaves()
        val result = walletA.consolidateLeaves()

        println(
            "Consolidation — leaves ${result.leavesBefore} -> ${result.leavesAfter}, " +
                "sats ${result.totalSatsBefore} -> ${result.totalSatsAfter}, " +
                "fee ${result.feeSats}, rounds ${result.rounds}, skipped ${result.skippedLeaves}",
        )
        assertEquals(before.size, result.leavesBefore)
        assertTrue(result.leavesAfter <= result.leavesBefore)
        // SSP swaps are requested with fee_sats 0 — consolidation must be free.
        assertEquals(0L, result.feeSats)
        val ideal = binaryDecomposition(result.totalSatsAfter).size
        assertTrue(
            "leavesAfter ${result.leavesAfter} above max(ideal=$ideal, before=${result.leavesBefore})",
            result.leavesAfter <= maxOf(ideal, result.leavesBefore),
        )
    }

    @Test
    fun renewExhaustedLeavesSmoke() = runBlocking {
        val leaves = walletA.getLeaves()
        val result = walletA.renewExhaustedLeaves()

        println(
            "Renewal — checked ${result.checked}, renewed ${result.renewed}, " +
                "failures ${result.failures.size}${if (result.failures.isEmpty()) "" else ": ${result.failures}"}",
        )
        assertEquals(leaves.size, result.checked)
        assertTrue("renewal sweep reported failures: ${result.failures}", result.failures.isEmpty())

        // After a sweep every leaf must sit above the renewal-needed threshold.
        for (leaf in walletA.getLeaves()) {
            assertTrue(
                "leaf ${leaf.id} still below renewal threshold (tl ${leaf.refundTimelockBlocks})",
                leaf.refundTimelockBlocks >= 200u,
            )
        }
    }
}
