package gy.pig.spark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BIP-39 mnemonic validation. Ported from the Swift SDK's `MnemonicValidationTests.swift`. */
class MnemonicValidationTests {

    companion object {
        const val VECTOR_12A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val VECTOR_12B = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic"
        const val VECTOR_18 = "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal will"
        const val VECTOR_24A = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        const val VECTOR_24B = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote"
    }

    @Test
    fun officialTestVectorsValidate() {
        for (m in listOf(VECTOR_12A, VECTOR_12B, VECTOR_18, VECTOR_24A, VECTOR_24B)) {
            BIP39.validate(m)
            assertTrue(BIP39.isValid(m))
        }
        assertEquals(2048, BIP39Wordlist.english.size)
        assertEquals("abandon", BIP39Wordlist.english.first())
        assertEquals("zoo", BIP39Wordlist.english.last())
        // The wordlist is the published english.txt, byte for byte.
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            sha256((BIP39Wordlist.english.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)).toHexString(),
        )
    }

    @Test
    fun typosUnknownWordsWrongCountsCaseAndSpacingAreRejected() {
        val cases = listOf(
            "checksum: last word swapped" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon",
            "checksum: one word changed" to "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnics",
            "checksum: two words swapped" to "drill ozone grab fiber curtain grace pudding thank cruise elder eight picnic",
            "unknown word" to "ozone drill grab fibre curtain grace pudding thank cruise elder eight picnic",
            "eleven words" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "thirteen words" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about abandon",
            "upper case" to "Abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "double space" to "abandon  abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "trailing space" to "$VECTOR_12A ",
            "empty" to "",
            "hex seed" to "ab".repeat(32),
        )
        for ((label, mnemonic) in cases) {
            assertFalse(label, BIP39.isValid(mnemonic))
            val error = expectSparkError(label) { BIP39.validate(mnemonic) }
            assertTrue(label, error is SparkError.InvalidMnemonic)
        }
    }

    @Test
    fun walletAndSignerRefuseAnInvalidMnemonicUnlessValidationIsExplicitlyDisabled() {
        val typo = "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnics"
        expectSparkError { SparkWallet.fromMnemonic(mnemonic = typo) }
        expectSparkError { SparkSigner.fromMnemonic(typo) }
        expectSparkError { KeyDerivation.fromMnemonic(typo) }
        // Escape hatch keeps the previous behaviour for phrases known to be non-standard.
        val lenient = SparkWallet.fromMnemonic(mnemonic = typo, validateMnemonic = false)
        assertEquals(66, lenient.identityPublicKeyHex.length)
        // Valid phrases still derive the same keys as before.
        val wallet = SparkWallet.fromMnemonic(mnemonic = VECTOR_12B)
        val keys = KeyDerivation.fromMnemonic(VECTOR_12B, account = 1)
        assertEquals(keys.identityPublicKey.toHexString(), wallet.identityPublicKeyHex)
    }

    @Test
    fun accountIndexesOutsideTheHardenedRangeAreRefused() {
        expectSparkError { KeyDerivation.fromMnemonic(VECTOR_12A, account = -1) }
        // 1 << 31 does not fit Kotlin's Int: it wraps to Int.MIN_VALUE, which is refused too.
        expectSparkError { KeyDerivation.fromMnemonic(VECTOR_12A, account = 1 shl 31) }
        expectSparkError { SparkWallet.fromMnemonic(mnemonic = VECTOR_12A, account = -7) }
        KeyDerivation.fromMnemonic(VECTOR_12A, account = Int.MAX_VALUE)
    }
}
