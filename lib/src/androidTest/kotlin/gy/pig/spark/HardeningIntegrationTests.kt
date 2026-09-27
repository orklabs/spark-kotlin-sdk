package gy.pig.spark

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import spark.Spark
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mainnet checks for the hardening changes: exact-amount Spark transfers to a Spark address,
 * fee-capped lightning payments against verified invoices, claim-side signature verification,
 * the in-flight balance model, renewals, the event stream, and argument validation that must fail
 * before any leaf is touched. Whichever test wallet can spend more (leaves above the timelock
 * floor) acts as the sender, so the suite works no matter which wallet was last funded. Port of
 * the Swift SDK's `HardeningIntegrationTests`.
 *
 * A test that needs more sats than the wallets hold is skipped (JUnit `Assume`) with the amount
 * it needs. Tests that spend on-chain or take long are opt-in through instrumentation arguments
 * (`-Pandroid.testInstrumentationRunnerArguments.<name>=…`), as the Swift suite's environment
 * variables: `SPARK_TEST_ALLOW_WITHDRAW=1` and `SPARK_TEST_ALLOW_WITHDRAW_ALL=1` (with
 * `SPARK_TEST_WITHDRAW_DESTINATION`, `SPARK_TEST_WITHDRAW_SATS`,
 * `SPARK_TEST_WITHDRAW_MAX_FEE_SATS`), `SPARK_TEST_CLAIM_STATIC=A|B` (with
 * `SPARK_TEST_CLAIM_STATIC_TXID=<txid>:<vout>`), `SPARK_TEST_ALLOW_REFUND=1` and
 * `SPARK_TEST_RENEWAL=1`.
 */
@RunWith(AndroidJUnit4::class)
class HardeningIntegrationTests {

    class WalletPair(val sender: SparkWallet, val receiver: SparkWallet, val senderLabel: String, val senderSpendable: Long) {
        suspend fun close() {
            sender.close()
            receiver.close()
        }
    }

    @Before
    fun setUp() {
        TestConfig.requireMnemonics()
    }

    companion object {
        private const val MINUTE = 60_000L

        /** Sats the wallet can send right now (renews what the coordinator will renew, skips frozen leaves). */
        suspend fun spendable(wallet: SparkWallet): Long {
            val leaves = wallet.getSpendableLeaves()
            assertTrue(leaves.all { it.isSpendable })
            return leaves.sumOf { it.valueSats }
        }

        /** The two test wallets, the one that can spend more as the sender. */
        suspend fun makePair(): WalletPair {
            val a = makeWallet(TestConfig.walletAMnemonic)
            val b = makeWallet(TestConfig.walletBMnemonic)
            val spendableA = spendable(a)
            val spendableB = spendable(b)
            return if (spendableA >= spendableB) WalletPair(a, b, "A", spendableA) else WalletPair(b, a, "B", spendableB)
        }

        suspend fun <T> withPair(block: suspend (WalletPair) -> T): T {
            val pair = makePair()
            try {
                return block(pair)
            } finally {
                pair.close()
            }
        }

        /**
         * A sender, receiver and amount for which the sender has no exact leaf combination, so the
         * send swaps for change first. Tries both wallets; `null` when neither has a gap up to
         * 2,000 sats.
         */
        suspend fun swapPair(): Pair<WalletPair, Long>? {
            val a = makeWallet(TestConfig.walletAMnemonic)
            val b = makeWallet(TestConfig.walletBMnemonic)
            for ((sender, receiver, label) in listOf(Triple(a, b, "A"), Triple(b, a, "B"))) {
                val leaves = sender.getSpendableLeaves()
                val total = leaves.sumOf { it.valueSats }
                val amount = (1..minOf(total, 2_000L)).firstOrNull { tryExactSelection(leaves, it) == null }
                if (amount != null) return WalletPair(sender, receiver, label, total) to amount
            }
            a.close()
            b.close()
            return null
        }

        fun needs(pair: WalletPair, sats: Long) {
            assumeTrue("the sender needs $sats spendable sats, has ${pair.senderSpendable}", pair.senderSpendable >= sats)
        }

        /** A testnet invoice (the BOLT-11 specification's), which a mainnet wallet must refuse. */
        const val TESTNET_INVOICE =
            "lntb20m1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygshp58yjmdan79s6qqdhdzgynm4zwqd5d7xmw5fk98klysy043l2ah" +
                "rqspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqfpp3x9et2e20v6pu37c5d9vax37wxq72un989qrsgqdj545axuxtnfemtpwk" +
                "c45hx9d2ft7x04mt8q7y6t0k2dge9e7h8kpy9p34ytyslj3yu569aalz2xdk8xkd7ltxqld94u8h2esmsmacgpghe9k8"
    }

    @Test(timeout = 5 * MINUTE)
    fun aSparkTransferToASparkAddressIsClaimedAfterSenderSignatureVerification() = liveTest {
        withPair { pair ->
            val amount = 10L
            needs(pair, amount + 5)
            val receiverBefore = pair.receiver.getBalance()
            val senderBefore = pair.sender.getBalance()

            val transfer = pair.sender.send(receiverSparkAddress = pair.receiver.getSparkAddress(), amountSats = amount)
            assertTrue(transfer.id.isNotEmpty())
            assertEquals(amount, transfer.totalValueSats)
            assertEquals(pair.receiver.identityPublicKeyHex, transfer.receiverIdentityPublicKey)
            println("[${pair.senderLabel}] sent $amount sats, transfer ${transfer.id}")

            delay(3_000)
            val claimed = pair.receiver.claimAllPendingTransfers()
            assertTrue(claimed >= 1)

            val receiverAfter = pair.receiver.getBalance()
            val senderAfter = pair.sender.getBalance()
            assertEquals(
                receiverBefore.satsBalance.owned + receiverBefore.satsBalance.incoming + amount,
                receiverAfter.satsBalance.owned,
            )
            assertEquals(senderBefore.satsBalance.owned - amount, senderAfter.satsBalance.owned)
            println(
                "receiver ${receiverBefore.satsBalance.owned} -> ${receiverAfter.satsBalance.owned}, " +
                    "sender ${senderBefore.satsBalance.owned} -> ${senderAfter.satsBalance.owned}",
            )
        }
    }

    @Test(timeout = 3 * MINUTE)
    fun badArgumentsAreRejectedBeforeAnyNetworkCallMovesALeaf() = liveTest {
        withPair { pair ->
            val before = pair.sender.getBalance()
            val address = pair.receiver.getSparkAddress()
            val sender = pair.sender

            expectSparkError { sender.send(receiverSparkAddress = address, amountSats = 0) }
            expectSparkError { sender.send(receiverSparkAddress = address, amountSats = -1) }
            expectSparkError { sender.send(receiverSparkAddress = "sparkrt1qq", amountSats = 1) }
            val invoice = satsInvoice(pair.receiver.signer.identityPublicKey, amountSats = 10, network = SparkNetwork.MAINNET)
            expectSparkError { sender.send(receiverSparkAddress = invoice, amountSats = 10) }
            expectSparkError { sender.send(receiverSparkAddress = address, amountSats = 1_000_000_000) }
            expectSparkError { sender.withdraw(onChainAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", amountSats = 0) }
            expectSparkError { sender.withdraw(onChainAddress = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080", amountSats = 1_000) }
            expectSparkError { sender.withdraw(onChainAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", amountSats = 1_000_000_000) }
            expectSparkError { sender.payLightningInvoice(paymentRequest = TESTNET_INVOICE, maxFeeSats = 10) }

            val after = pair.sender.getBalance()
            assertEquals(before.satsBalance.owned, after.satsBalance.owned)
            assertEquals(before.satsBalance.available, after.satsBalance.available)
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun oneClaimPassTakesEveryPendingTransferAndReportsNoFailures() = liveTest {
        withPair { pair ->
            val amounts = listOf(1L, 2L, 8L)
            needs(pair, amounts.sum() + 5)
            pair.receiver.claimPendingTransfers()
            val sent = amounts.map { amount -> pair.sender.send(receiverSparkAddress = pair.receiver.getSparkAddress(), amountSats = amount).id }
            delay(3_000)

            val result = pair.receiver.claimPendingTransfers()
            println("[${pair.senderLabel}] sent $sent, receiver claimed ${result.claimedTransferIds}, failures ${result.failures}")
            assertTrue(result.claimedTransferIds.containsAll(sent))
            assertTrue(result.failures.isEmpty())
            assertFalse(pair.receiver.queryPendingTransfers().any { it.id in sent })
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun claimingATransferThisWalletAlreadyClaimedCountsAsClaimed() = liveTest {
        withPair { pair ->
            needs(pair, 6)
            pair.receiver.claimPendingTransfers()
            val sent = pair.sender.send(receiverSparkAddress = pair.receiver.getSparkAddress(), amountSats = 1)
            delay(3_000)
            val pending = pair.receiver.queryPendingTransfers().firstOrNull { it.id == sent.id }
            assertNotNull("transfer ${sent.id} is not pending for the receiver", pending)

            pair.receiver.claimTransfer(pending!!)
            // The operators now answer ALREADY_EXISTS; the reference SDK treats a completed transfer
            // as claimed, and so must we (a swap and a claim pass can race for the same transfer).
            pair.receiver.claimTransfer(pending)
            assertEquals(Spark.TransferStatus.TRANSFER_STATUS_COMPLETED, pair.receiver.queryTransferById(sent.id).status)
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun aSendNoLeafCombinationCanPayExactlySwapsForChangeFirst() = liveTest {
        val (pair, amount) = swapPair()
            ?: run {
                assumeTrue("both wallets have an exact leaf combination for every amount up to 2,000 sats", false)
                return@liveTest
            }
        try {
            pair.receiver.claimPendingTransfers()
            val receiverBefore = pair.receiver.getBalance().satsBalance
            val transfer = pair.sender.send(receiverSparkAddress = pair.receiver.getSparkAddress(), amountSats = amount)
            println("[${pair.senderLabel}] sent $amount sats through a swap, transfer ${transfer.id}")
            assertEquals(amount, transfer.totalValueSats)
            delay(3_000)
            val claim = pair.receiver.claimPendingTransfers()
            assertTrue(claim.claimedTransferIds.contains(transfer.id))
            val receiverAfter = pair.receiver.getBalance().satsBalance
            assertEquals(receiverBefore.owned + receiverBefore.incoming + amount, receiverAfter.owned)
        } finally {
            pair.close()
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun sentSatsLeaveOwnedOnceTheTransferIsCommittedBeforeTheReceiverClaims() = liveTest {
        withPair { pair ->
            needs(pair, 6)
            pair.receiver.claimPendingTransfers()
            val before = pair.sender.getBalance().satsBalance
            val receiverBefore = pair.receiver.getBalance().satsBalance
            assertEquals(0L, before.locked)
            val transfer = pair.sender.send(receiverSparkAddress = pair.receiver.getSparkAddress(), amountSats = 1)
            assertEquals(Spark.TransferStatus.TRANSFER_STATUS_SENDER_KEY_TWEAKED.toString(), transfer.status)

            // The operators applied the sender's key tweak: the sat belongs to the receiver now, even
            // though its leaf stays TRANSFER_LOCKED under the sender's key until the claim.
            val sent = pair.sender.getBalance().satsBalance
            println("[${pair.senderLabel}] owned ${before.owned} -> ${sent.owned}, locked ${before.locked} -> ${sent.locked}")
            assertEquals(before.owned - 1, sent.owned)
            assertEquals(0L, sent.locked)
            val receiverPending = pair.receiver.getBalance().satsBalance
            assertEquals(receiverBefore.incoming + 1, receiverPending.incoming)
            assertEquals(receiverBefore.owned, receiverPending.owned)
            pair.receiver.claimPendingTransfers()
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun frozenSatsAreExactlyTheLeavesBelowTheRenewalMinimumAndTheDrainQuoteAgrees() = liveTest {
        withWallets { a, b ->
            for ((label, wallet, other) in listOf(Triple("A", a, b), Triple("B", b, a))) {
                val balance = wallet.getBalance()
                val frozenLeaves = balance.leaves.filter { it.isFrozen }
                assertEquals(label, frozenLeaves.sumOf { it.valueSats }, balance.satsBalance.frozen)
                assertEquals(label, balance.leaves.filter { !it.isFrozen }.sumOf { it.valueSats }, balance.satsBalance.available)
                assertTrue(label, frozenLeaves.all { it.refundTimelockBlocks < SPARK_TIME_LOCK_INTERVAL.toUInt() })

                // The quote claims, renews what the operators will renew, and fetches a fee quote;
                // nothing leaves the wallet.
                val destination = other.getStaticDepositAddress().address
                val quote = wallet.quoteWithdrawAll(onChainAddress = destination)
                val after = wallet.getBalance().satsBalance
                println(
                    "[$label] available ${balance.satsBalance.available} frozen ${balance.satsBalance.frozen} -> quote spendable " +
                        "${quote.spendableSats} frozen ${quote.frozenSats} unrenewed ${quote.unrenewedSats} locked ${quote.lockedSats}",
                )
                assertEquals(label, after.frozen, quote.frozenSats)
                assertEquals("$label: every renewable leaf should have been renewed", 0L, quote.unrenewedSats)
                assertEquals(label, after.available, quote.spendableSats + quote.unrenewedSats)
            }
        }
    }

    /**
     * Bounces one small leaf between the test wallets until a transfer delivers it in the renewal
     * range (each Spark transfer takes 100 blocks off it); the receiver's claim pass must renew it
     * to a fresh timelock right away. Opt-in (`SPARK_TEST_RENEWAL=1`) because it takes one
     * transfer per 100 blocks of timelock.
     */
    @Test(timeout = 20 * MINUTE)
    fun aLeafThatArrivesInTheRenewalRangeIsRenewedByTheClaim() = liveTest {
        TestConfig.requireFlag("SPARK_TEST_RENEWAL")
        withWallets { a, b ->
            class Candidate(val holder: SparkWallet, val other: SparkWallet, val leaf: SparkLeaf)
            val candidates = listOf(a to b, b to a).flatMap { (holder, other) ->
                holder.getLeaves().filter { it.isSpendable && it.valueSats <= 64 }.map { Candidate(holder, other, it) }
            }
            val start = candidates.minByOrNull { it.leaf.refundTimelockBlocks }
            assumeTrue("no small spendable leaf to bounce", start != null)
            var holder = start!!.holder
            var other = start.other
            var leaf = start.leaf
            val leafId = leaf.id
            println("bouncing leaf $leafId (${leaf.valueSats} sats) from refund timelock ${leaf.refundTimelockBlocks}")
            while (true) {
                val arrivesAt = roundedTimelock(leaf.refundTimelockBlocks) - SPARK_TIME_LOCK_INTERVAL.toUInt()
                holder.transferLeaves(listOf(leaf), receiverIdentityPublicKey = other.signer.identityPublicKey)
                delay(3_000)
                val claim = other.claimPendingTransfers()
                assertTrue(claim.failures.toString(), claim.failures.isEmpty())
                holder = other.also { other = holder }
                leaf = holder.getLeaves().firstOrNull { it.id == leafId } ?: throw AssertionError("leaf $leafId did not arrive")
                println("  delivered at $arrivesAt, after the claim ${leaf.refundTimelockBlocks}")
                if (arrivesAt < RENEWAL_THRESHOLD) {
                    // Delivered in the renewal range: the claim pass renewed it.
                    assertEquals(2000u, leaf.refundTimelockBlocks)
                    assertTrue(leaf.isSpendable)
                    break
                }
                assertEquals(arrivesAt, leaf.refundTimelockBlocks)
            }
        }
    }

    /**
     * The withdrawal destination: `SPARK_TEST_WITHDRAW_DESTINATION` may be an address, or
     * `receiver-static-deposit` to pay the other test wallet's static deposit address so the sats
     * stay inside the test setup and can be claimed back with `claimStaticDeposit`. Falls back to
     * the configured static-deposit withdraw address.
     */
    private suspend fun withdrawDestination(pair: WalletPair): String = when (val setting = TestConfig.argument("SPARK_TEST_WITHDRAW_DESTINATION")) {
        "receiver-static-deposit" -> pair.receiver.getStaticDepositAddress().address.also {
            println("destination: static deposit address of the receiver wallet $it")
        }
        null ->
            TestConfig.staticDepositWithdrawAddress
                ?: run {
                    assumeTrue("no withdrawal destination configured", false)
                    ""
                }
        else -> setting
    }

    @Test(timeout = 10 * MINUTE)
    fun aSmallOnChainWithdrawalWithAVerifiedPayout() = liveTest {
        TestConfig.requireFlag("SPARK_TEST_ALLOW_WITHDRAW")
        withPair { pair ->
            val destination = withdrawDestination(pair)
            val amount = TestConfig.argument("SPARK_TEST_WITHDRAW_SATS")?.toLongOrNull() ?: 3_000L
            val maxFee = TestConfig.argument("SPARK_TEST_WITHDRAW_MAX_FEE_SATS")?.toLongOrNull() ?: 2_500L
            needs(pair, amount)
            val before = pair.sender.getBalance()
            val txid = pair.sender.withdraw(onChainAddress = destination, amountSats = amount, maxFeeSats = maxFee)
            assertEquals(64, txid.length)
            println("[${pair.senderLabel}] withdrew $amount sats (fee cap $maxFee) to $destination: txid $txid")
            delay(3_000)
            // The exited leaves stay transfer-locked (still owned) until the exit transaction confirms
            // on-chain; only the spendable balance drops immediately.
            val after = pair.sender.getBalance()
            assertEquals(before.satsBalance.available - amount, after.satsBalance.available)
            assertTrue(after.satsBalance.owned >= before.satsBalance.owned - amount)
            assertTrue(after.satsBalance.owned <= before.satsBalance.owned)
        }
    }

    /** Drains the sender wallet with `withdrawAll`. Opt-in (`SPARK_TEST_ALLOW_WITHDRAW_ALL=1`). */
    @Test(timeout = 10 * MINUTE)
    fun withdrawAllDrainsEverySpendableSatWithAVerifiedPayoutAndReportsWhatStays() = liveTest {
        TestConfig.requireFlag("SPARK_TEST_ALLOW_WITHDRAW_ALL")
        withPair { pair ->
            val destination = withdrawDestination(pair)
            val quote = pair.sender.quoteWithdrawAll(onChainAddress = destination)
            println(
                "[${pair.senderLabel}] quote: spendable ${quote.spendableSats} fee ${quote.quotedFeeSats} " +
                    "payout≈${quote.estimatedPayoutSats} frozen ${quote.frozenSats} locked ${quote.lockedSats} " +
                    "incoming ${quote.incomingSats} leaves ${quote.leafCount}",
            )
            assertEquals(pair.senderSpendable, quote.spendableSats)
            assumeTrue("fee ${quote.quotedFeeSats} not covered by ${quote.spendableSats} spendable sats", quote.coversFee)

            val result = pair.sender.withdrawAll(onChainAddress = destination)
            println(
                "[${pair.senderLabel}] withdrawAll: txid ${result.txid} sent ${result.sentSats} payout ${result.payoutSats} " +
                    "fee ${result.feeSats} frozen ${result.frozenSats} locked ${result.lockedSats} unclaimed ${result.unclaimedSats}",
            )
            assertEquals(64, result.txid.length)
            assertEquals(quote.spendableSats, result.sentSats)
            assertTrue(result.payoutSats >= quote.spendableSats - quote.quotedFeeSats)
            assertTrue(result.payoutSats < result.sentSats)
            assertEquals(quote.frozenSats, result.frozenSats)

            delay(3_000)
            val after = pair.sender.getBalance().satsBalance
            assertEquals(0L, after.available)
            assertEquals(result.frozenSats, after.frozen)
            assertTrue(after.owned >= result.frozenSats)
        }
    }

    /**
     * Claims confirmed UTXOs sitting at a wallet's static deposit address back into Spark.
     * Opt-in (`SPARK_TEST_CLAIM_STATIC=A|B`) because the SSP charges a fee for the claim.
     */
    @Test(timeout = 10 * MINUTE)
    fun claimConfirmedStaticDepositsBackIntoTheWallet() = liveTest {
        val which = TestConfig.argument("SPARK_TEST_CLAIM_STATIC")
        assumeTrue("opt-in: pass SPARK_TEST_CLAIM_STATIC=A or B", which == "A" || which == "B")
        withWallet(if (which == "A") TestConfig.walletAMnemonic else TestConfig.walletBMnemonic) { wallet ->
            val address = wallet.getStaticDepositAddress().address
            var utxos = wallet.getUtxosForDepositAddress(address = address, excludeClaimed = true)
            println("[$which] static deposit address $address: ${utxos.size} unclaimed utxo(s)")
            // SPARK_TEST_CLAIM_STATIC_TXID=<txid>:<vout> names a deposit the operators have not
            // indexed yet, so the SSP quote can be tried directly.
            val explicit = TestConfig.argument("SPARK_TEST_CLAIM_STATIC_TXID")
            if (utxos.isEmpty() && explicit != null) {
                val parts = explicit.split(":")
                val vout = parts.getOrNull(1)?.toUIntOrNull()
                if (parts.size == 2 && vout != null) {
                    utxos = listOf(DepositUtxo(txid = parts[0], vout = vout))
                    println("  using explicit utxo $explicit")
                }
            }
            assumeTrue("no unclaimed utxo at $address yet (unconfirmed, or already claimed)", utxos.isNotEmpty())
            val before = wallet.getBalance()
            for (utxo in utxos) {
                val quote = wallet.getDepositFeeEstimate(transactionId = utxo.txid, outputIndex = utxo.vout)
                println("  ${utxo.txid}:${utxo.vout} credits ${quote.creditAmountSats} sats after the SSP fee")
                val transferId = wallet.claimStaticDeposit(transactionId = utxo.txid, outputIndex = utxo.vout, quote = quote)
                assertTrue(transferId.isNotEmpty())
                println("  claim transfer $transferId")
            }
            delay(5_000)
            val claimed = wallet.claimAllPendingTransfers()
            val after = wallet.getBalance()
            println("  claimed $claimed transfer(s); balance ${before.satsBalance.owned} -> ${after.satsBalance.owned}")
            assertTrue(after.satsBalance.owned > before.satsBalance.owned)
        }
    }

    // Lightning sends

    @Test(timeout = 5 * MINUTE)
    fun aLightningPaymentOfAVerifiedInvoiceUnderAFeeCapThenClaim() = liveTest {
        withPair { pair ->
            val amount = 10L
            val invoice = pair.receiver.createLightningInvoice(amountSats = amount, memo = "hardening test")
            assertEquals(amount, invoice.amountSats)
            val decoded = Bolt11Invoice.decode(invoice.paymentRequest)
            assertEquals(invoice.paymentHash, decoded.paymentHash.toHexString())
            assertEquals(amount.toULong() * 1000uL, decoded.amountMsat)
            assertEquals(Bolt11Invoice.Network.MAINNET, decoded.network)

            val fee = pair.sender.getLightningSendFeeEstimate(encodedInvoice = invoice.paymentRequest)
            println("[${pair.senderLabel}] fee estimate $fee sats for $amount sats")
            val cap = maxOf(fee, 1) + 5
            needs(pair, amount + cap)
            val receiverBefore = pair.receiver.getBalance()
            val senderBefore = pair.sender.getBalance()

            val requestId = pair.sender.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = cap)
            assertTrue(requestId.isNotEmpty())
            println("lightning send request $requestId")

            delay(5_000)
            val claimed = pair.receiver.claimAllPendingTransfers()
            println("receiver claimed $claimed transfer(s)")

            val receiverAfter = pair.receiver.getBalance()
            val senderAfter = pair.sender.getBalance()
            assertTrue(receiverAfter.satsBalance.owned >= receiverBefore.satsBalance.owned + amount)
            assertTrue(senderAfter.satsBalance.owned <= senderBefore.satsBalance.owned - amount)
            assertTrue(senderAfter.satsBalance.owned >= senderBefore.satsBalance.owned - amount - cap)
            println(
                "receiver ${receiverBefore.satsBalance.owned} -> ${receiverAfter.satsBalance.owned}, " +
                    "sender ${senderBefore.satsBalance.owned} -> ${senderAfter.satsBalance.owned}",
            )
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun anAmountlessLightningInvoiceIsPaidWithTheCallersAmount() = liveTest {
        withPair { pair ->
            val amount = 12L
            val invoice = pair.receiver.createLightningInvoice(amountSats = 0, memo = "amountless invoice test")
            assertEquals(null, Bolt11Invoice.decode(invoice.paymentRequest).amountMsat)
            val fee = pair.sender.getLightningSendFeeEstimate(encodedInvoice = invoice.paymentRequest, amountSats = amount)
            val cap = maxOf(fee, 1) + 5
            needs(pair, amount + cap)
            pair.receiver.claimPendingTransfers()
            val receiverBefore = pair.receiver.getBalance().satsBalance
            val requestId = pair.sender.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = cap, amountSats = amount)
            println("[${pair.senderLabel}] paid an amountless invoice with $amount sats (fee estimate $fee): $requestId")
            delay(5_000)
            pair.receiver.claimPendingTransfers()
            val receiverAfter = pair.receiver.getBalance().satsBalance
            assertTrue(receiverAfter.owned >= receiverBefore.owned + amount)
        }
    }

    @Test(timeout = 3 * MINUTE)
    fun aFeeCapBelowTheSspEstimateIsRefusedBeforeAnyLeafIsLocked() = liveTest {
        withPair { pair ->
            val invoice = pair.receiver.createLightningInvoice(amountSats = 10, memo = "fee cap test")
            val fee = pair.sender.getLightningSendFeeEstimate(encodedInvoice = invoice.paymentRequest)
            assumeTrue("the SSP quotes no fee for this invoice, so no cap can fall below it", fee >= 1)
            val before = pair.sender.getBalance()
            try {
                pair.sender.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = fee - 1)
                fail("payment went through with a cap of ${fee - 1} (estimate $fee)")
            } catch (e: SparkError.FeeExceedsLimit) {
                assertEquals(fee - 1, e.maxFeeSats)
                assertTrue(e.feeSats >= fee)
            }
            val after = pair.sender.getBalance()
            assertEquals(before.satsBalance.owned, after.satsBalance.owned)
            assertEquals(before.satsBalance.available, after.satsBalance.available)
        }
    }

    /** Claims until the receiver owns [expected] sats, for up to 30 s; returns what it owns. */
    private suspend fun awaitOwned(receiver: SparkWallet, expected: Long): Long {
        var owned = receiver.getBalance().satsBalance.owned
        repeat(10) {
            if (owned >= expected) return owned
            delay(3_000)
            receiver.claimPendingTransfers()
            owned = receiver.getBalance().satsBalance.owned
        }
        return owned
    }

    @Test(timeout = 5 * MINUTE)
    fun anInvoicePastedInUpperCaseWithSurroundingWhitespaceIsPaidAsValidated() = liveTest {
        withPair { pair ->
            val amount = 10L
            val invoice = pair.receiver.createLightningInvoice(amountSats = amount, memo = "pasted invoice test")
            val pasted = "  ${invoice.paymentRequest.uppercase()}\n"
            val fee = pair.sender.getLightningSendFeeEstimate(encodedInvoice = invoice.paymentRequest)
            val cap = maxOf(fee, 1) + 5
            needs(pair, amount + cap)
            pair.receiver.claimPendingTransfers()
            val receiverBefore = pair.receiver.getBalance().satsBalance
            val requestId = pair.sender.payLightningInvoice(paymentRequest = pasted, maxFeeSats = cap)
            println("[${pair.senderLabel}] paid a pasted invoice: $requestId")
            assertEquals(receiverBefore.owned + amount, awaitOwned(pair.receiver, receiverBefore.owned + amount))
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun anInterruptedLightningSendResumesFromTheTransferTheCoordinatorHoldsAndPaysOnce() = liveTest {
        withPair { pair ->
            val amount = 10L
            val invoice = pair.receiver.createLightningInvoice(amountSats = amount, memo = "resume test")
            val maxFee = maxOf(pair.sender.getLightningSendFeeEstimate(encodedInvoice = invoice.paymentRequest), 1) + 5
            needs(pair, 2 * (amount + maxFee) + 5)
            pair.receiver.claimPendingTransfers()
            val receiverBefore = pair.receiver.getBalance().satsBalance
            val payment = LightningPayment(invoice.paymentRequest, maxFee, null, null, SparkNetwork.MAINNET)
            val transferId = UUID.randomUUID().toString().lowercase()

            // The first attempt ends after the swap, as when the SSP request fails: the coordinator
            // holds the leaves under the transfer id.
            val held = pair.sender.startLightningSend(payment, transferId)
            assertEquals(transferId, held.id)
            assertTrue(held.totalValue >= amount)
            // The same swap again: the coordinator answers with the transfer it holds and locks nothing more.
            val availableBefore = pair.sender.getBalance().satsBalance.available
            val repeated = pair.sender.startLightningSend(payment, transferId)
            assertEquals(transferId, repeated.id)
            assertEquals(held.leavesList.map { it.leaf.id }.toSet(), repeated.leavesList.map { it.leaf.id }.toSet())
            assertEquals(availableBefore, pair.sender.getBalance().satsBalance.available)

            // Resuming selects no leaf: the SSP pays from the held transfer.
            val requestId = pair.sender.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = maxFee, transferId = transferId)
            assertEquals(availableBefore, pair.sender.getBalance().satsBalance.available)
            // Paying again under the same id pays nothing twice: the SSP answers with its request.
            val again = pair.sender.payLightningInvoice(paymentRequest = invoice.paymentRequest, maxFeeSats = maxFee, transferId = transferId)
            assertEquals(requestId, again)
            // Another invoice cannot be paid from that transfer.
            val other = pair.receiver.createLightningInvoice(amountSats = amount, memo = "resume test, other invoice")
            expectSparkError {
                pair.sender.payLightningInvoice(paymentRequest = other.paymentRequest, maxFeeSats = maxFee, transferId = transferId)
            }
            println("[${pair.senderLabel}] resumed transfer $transferId (${held.totalValue} sats) as $requestId")

            assertEquals(receiverBefore.owned + amount, awaitOwned(pair.receiver, receiverBefore.owned + amount))
        }
    }

    // Events

    @Test(timeout = 3 * MINUTE)
    fun theOperatorsHeartbeatsKeepASubscriptionAliveOneSilentPastTheTimeoutIsResubscribed() = liveTest {
        withWallet(TestConfig.walletBMnemonic) { wallet ->
            suspend fun reconnects(timeout: Duration, listen: Duration): List<String> = eventsFor(wallet.subscribeToEvents(heartbeatTimeout = timeout), listen)
                .filterIsInstance<SparkEvent.Reconnecting>()
                .map { it.reason }
            // Heartbeats every 5 s: an 8 s allowance is never exceeded.
            assertEquals(emptyList<String>(), reconnects(timeout = 8.seconds, listen = 30.seconds))
            // A 3 s allowance is exceeded between two heartbeats.
            val tight = reconnects(timeout = 3.seconds, listen = 20.seconds)
            assertTrue(tight.toString(), tight.any { "heartbeat" in it })
            println("with a 3 s allowance the stream was resubscribed ${tight.size} time(s)")
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun aPaymentIsReportedToItsReceiverAndASwapsCounterTransferIsNotReportedAsReceived() = liveTest {
        withPair { pair ->
            needs(pair, 15)
            val smallest = pair.sender.getSpendableLeaves().minByOrNull { it.valueSats }!!
            val senderLog = EventLog(pair.sender)
            val receiverLog = EventLog(pair.receiver)
            delay(2_000)

            // A swap of the sender's own: the SSP's counter-transfer reaches the sender.
            pair.sender.requestLeavesSwap(targetAmounts = listOf(smallest.valueSats))
            // A payment to the receiver.
            val transfer = pair.sender.send(receiverSparkAddress = pair.receiver.getSparkAddress(), amountSats = 10)
            println("[${pair.senderLabel}] swapped a ${smallest.valueSats}-sat leaf and sent 10 sats, transfer ${transfer.id}")
            delay(8_000)
            senderLog.stop()
            receiverLog.stop()

            assertTrue(receiverLog.contains { it is SparkEvent.TransferReceived && it.transfer.id == transfer.id })
            // The swap's counter-transfer is not a payment.
            assertFalse(senderLog.events.toString(), senderLog.contains { it is SparkEvent.TransferReceived })
            assertTrue(senderLog.contains { it is SparkEvent.TransferSent && it.transfer.id == transfer.id })
            println("sender events: ${senderLog.events.size}, receiver events: ${receiverLog.events.size}")
            pair.receiver.claimPendingTransfers()
        }
    }

    @Test(timeout = 5 * MINUTE)
    fun theEventStreamClaimsAPaymentThatArrivedWhileItWasDownAndAPaymentAsItArrives() = liveTest {
        withPair { pair ->
            needs(pair, 25)
            pair.receiver.claimPendingTransfers()
            val before = pair.receiver.getBalance().satsBalance
            val address = pair.receiver.getSparkAddress()
            fun received(id: String): (SparkEvent) -> Boolean = { it is SparkEvent.TransferReceived && it.transfer.id == id }

            // Sent while the receiver has no stream: claimed and reported when the stream connects.
            val whileDown = pair.sender.send(receiverSparkAddress = address, amountSats = 10)
            delay(3_000)
            val log = EventLog(pair.receiver)
            try {
                assertTrue(eventually(60.seconds) { log.contains(received(whileDown.id)) })

                // Sent while it is connected: claimed on arrival, then reported.
                val whileUp = pair.sender.send(receiverSparkAddress = address, amountSats = 11)
                assertTrue(eventually(60.seconds) { log.contains(received(whileUp.id)) })
                log.stop()

                // Nobody else claimed them.
                val completed = Spark.TransferStatus.TRANSFER_STATUS_COMPLETED.toString()
                assertEquals(completed, pair.receiver.getTransfer(id = whileDown.id).status)
                assertEquals(completed, pair.receiver.getTransfer(id = whileUp.id).status)
                val after = pair.receiver.getBalance().satsBalance
                assertEquals(before.owned + 21, after.owned)
                println("[${pair.senderLabel}] the receiver's stream claimed ${whileDown.id} on connection and ${whileUp.id} on arrival")
            } finally {
                log.stop()
            }
        }
    }

    // Static deposit refund

    /**
     * Refunds an unclaimed static deposit of either test wallet to that wallet's own static deposit
     * address, checks the operators' co-signature, and broadcasts it. Opt-in
     * (`SPARK_TEST_ALLOW_REFUND=1`): it needs a confirmed unclaimed deposit — for example from the
     * opt-in withdrawal test with `SPARK_TEST_WITHDRAW_DESTINATION=receiver-static-deposit` — and
     * pays the on-chain fee. Once refunded, the deposit can only be recovered on-chain, so the
     * refund is always broadcast; the new output can be claimed again later.
     */
    @Test(timeout = 10 * MINUTE)
    fun refundAnUnclaimedStaticDepositToTheWalletsOwnStaticAddress() = liveTest {
        TestConfig.requireFlag("SPARK_TEST_ALLOW_REFUND")
        for ((label, mnemonic) in listOf("B" to TestConfig.walletBMnemonic, "A" to TestConfig.walletAMnemonic)) {
            val refunded = withWallet(mnemonic) { wallet ->
                val address = wallet.getStaticDepositAddress().address
                val utxo = wallet.getUtxosForDepositAddress(address = address).firstOrNull() ?: return@withWallet false
                val outpoint = DepositOutpoint(utxo.txid, utxo.vout)

                val txHex = wallet.refundStaticDeposit(
                    depositTransactionId = utxo.txid,
                    outputIndex = utxo.vout,
                    destinationAddress = address,
                    satsPerVbyte = 2,
                )
                val signed = RawTransaction.parse(txHex.hexToByteArray())
                assertEquals(1, signed.inputs.size)
                assertTrue(signed.inputs[0].previousTxid.contentEquals(outpoint.internalOrderTxid))
                val destinationScript = BitcoinAddress.scriptPubKey(address, SparkNetwork.MAINNET)
                assertTrue(signed.outputs.size == 1 && signed.outputs[0].scriptPubKey.contentEquals(destinationScript))

                // The aggregated signature must verify against the deposit output's key.
                val deposit = RawTransaction.parse(wallet.fetchRawTransaction(outpoint.txid))
                val prevout = deposit.output(outpoint.vout)
                val unsigned = signed.copy(inputs = listOf(signed.inputs[0].copy(witness = emptyList())), hasWitnessSerialization = false)
                val sighash = taprootSighash(unsigned, prevoutScript = prevout.scriptPubKey, prevoutValue = prevout.value)
                val signature = signed.inputs[0].witness.first()
                assertTrue(Bip340.verify(signature, sighash, prevout.scriptPubKey.copyOfRange(2, 34)))

                val txid = wallet.broadcastTransaction(txHex)
                println("[$label] refunded ${outpoint.txid}:${outpoint.vout} (${prevout.value} sats) to its static address: $txid")
                true
            }
            if (refunded) return@liveTest
        }
        fail("neither test wallet has an unclaimed static deposit to refund")
    }
}
