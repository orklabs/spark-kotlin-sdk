package gy.pig.spark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Explicit API mode is module-wide, and the generated UniFFI bindings under `frost/` declare no
 * visibility, so the build cannot use `ExplicitApiMode.Strict`. These tests keep the hand-written
 * sources explicit instead: in 0.2.1, spend internals such as `selectLeavesWithSwap` and
 * `claimTransfer` became public API simply by omitting a modifier.
 */
class PublicApiHygieneTests {

    private val sources: List<File> = File("src/main/kotlin/gy/pig/spark")
        .listFiles { f -> f.isFile && f.name.endsWith(".kt") }
        .orEmpty()
        .sortedBy { it.name }

    private val declarationStart = Regex(
        "^(?:(?:suspend|inline|data|sealed|enum|abstract|open|const|lateinit|operator|infix|tailrec|external|annotation|value)\\s+)*" +
            "(?:fun|class|object|interface|val|var|typealias)\\b",
    )

    @Test
    fun everyHandWrittenTopLevelDeclarationStatesItsVisibility() {
        assertTrue("no sources found from ${File(".").absolutePath}", sources.size > 30)
        val missing = sources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> declarationStart.containsMatchIn(line) && declarationStart.find(line)?.range?.first == 0 }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertEquals("top-level declarations without public/internal/private:\n" + missing.joinToString("\n"), emptyList<String>(), missing)
    }

    @Test
    fun spendAndSigningInternalsStayInternal() {
        val text = sources.associate { it.name to it.readText() }
        val internals = mapOf(
            "ClaimTransferService.kt" to listOf("suspend fun SparkWallet.queryPendingTransfers(", "suspend fun SparkWallet.claimTransfer("),
            "SwapService.kt" to listOf(
                "suspend fun SparkWallet.selectLeavesWithSwap(",
                "suspend fun SparkWallet.requestLeavesSwap(",
                "suspend fun SparkWallet.processSwapBatch(",
                "fun tryExactSelection(",
            ),
            "FrostSigningHelper.kt" to listOf("object FrostSigningHelper"),
            "KeyTweakHelper.kt" to listOf("object KeyTweakHelper"),
            "KeyDerivation.kt" to listOf("class KeyDerivation"),
            "TransferService.kt" to listOf("fun computeNextSequences(", "fun parseSequenceFromRawTx("),
            "GrpcConnectionManager.kt" to listOf("class GrpcConnectionManager("),
            "SspGraphQLClient.kt" to listOf("class SspGraphQLClient(", "suspend fun executeGraphQL("),
        )
        for ((file, declarations) in internals) {
            val source = text.getValue(file)
            for (declaration in declarations) {
                assertTrue("$file: $declaration must be internal", source.contains("\ninternal $declaration"))
            }
        }
    }
}
