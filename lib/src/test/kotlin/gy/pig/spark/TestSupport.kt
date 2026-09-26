package gy.pig.spark

import com.google.protobuf.ByteString
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue

/** Hex literal for test vectors (strict: a typo fails the test instead of producing garbage). */
internal fun hex(s: String): ByteArray = requireNotNull(s.hexToBytesOrNull()) { "bad hex in test vector: $s" }

/** [count] bytes of [value] — Swift's `Data(repeating:count:)`. */
internal fun bytes(value: Int, count: Int): ByteArray = ByteArray(count) { value.toByte() }

internal fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)

/** Swift's `#expect(throws: SparkError.self) { ... }`. */
internal fun expectSparkError(label: String = "", block: () -> Unit): SparkError = assertThrows(label, SparkError::class.java) { block() }

/** A leaf whose refund transaction's first input carries `(1 << 30) | timelock`. */
internal fun leafWithRefundTimelock(id: String, sats: Long, timelock: UInt): SparkLeaf {
    val refund = RawTransaction(
        version = 2u,
        inputs = listOf(RawTransaction.Input(previousTxid = bytes(1, 32), previousIndex = 0u, sequence = (1u shl 30) or timelock)),
        outputs = listOf(RawTransaction.Output(value = sats.toULong(), scriptPubKey = byteArrayOf(0x51, 0x20) + bytes(2, 32))),
        locktime = 0u,
        hasWitnessSerialization = false,
    )
    val node = spark.Spark.TreeNode.newBuilder()
        .setId(id)
        .setRefundTx(refund.serialized(includeWitness = true).toByteString())
        .build()
    return SparkLeaf(id = id, treeID = "t", valueSats = sats, status = "AVAILABLE", node = node)
}

/**
 * The UniFFI FROST library, when it can be loaded on this host. The bundled `.so` files are
 * Android-only; point `SPARK_FROST_HOST_LIBRARY` at a host build of spark-frost to run the tests
 * that need it (see `lib/build.gradle.kts`). Everything else runs without it.
 */
internal object NativeFrost {
    val available: Boolean by lazy {
        try {
            uniffi.spark_frost.randomSecretKeyBytes().size == 32
        } catch (_: Throwable) {
            false
        }
    }

    fun assume() {
        assumeTrue("FROST native library not available on this host; set SPARK_FROST_HOST_LIBRARY to run", available)
    }
}
