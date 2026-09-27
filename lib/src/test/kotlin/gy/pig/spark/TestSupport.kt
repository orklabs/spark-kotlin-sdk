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

/** [expectSparkError] for a suspending block. */
internal suspend fun expectSparkErrorSuspending(label: String = "", block: suspend () -> Unit): SparkError {
    val error = try {
        block()
        null
    } catch (e: SparkError) {
        e
    }
    return error ?: throw AssertionError("expected a SparkError${if (label.isEmpty()) "" else " ($label)"}")
}

/** A P2TR output script whose 32-byte key is [byte] repeated. */
internal fun p2trScript(byte: Int): ByteArray = byteArrayOf(0x51, 0x20) + bytes(byte, 32)

/** A minimal node transaction: one input with the given timelock (bit 30 set), one P2TR output. */
internal fun nodeTx(timelock: UInt, value: ULong = 10_000uL, tag: Int = 0x11): ByteArray = RawTransaction(
    version = 2u,
    inputs = listOf(RawTransaction.Input(previousTxid = bytes(tag, 32), previousIndex = 0u, sequence = (1u shl 30) or timelock)),
    outputs = listOf(RawTransaction.Output(value = value, scriptPubKey = p2trScript(tag))),
    locktime = 0u,
    hasWitnessSerialization = false,
).serialized(includeWitness = true)

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

/**
 * A BIP-340 Schnorr signature of [message] with [privateKey] and auxiliary randomness [aux] (the
 * specification's default signing algorithm), for tests that need a signer the SDK does not ship.
 */
internal fun bip340Sign(privateKey: ByteArray, message: ByteArray, aux: ByteArray = ByteArray(32)): ByteArray {
    val domain = KeyDerivation.ecDomainParams
    val n = domain.n
    fun bytes32(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray()
        return if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
    }
    val d0 = java.math.BigInteger(1, privateKey)
    require(d0.signum() > 0 && d0 < n) { "private key out of range" }
    val p = domain.g.multiply(d0).normalize()
    val d = if (p.affineYCoord.testBitZero()) n.subtract(d0) else d0
    val px = bytes32(p.affineXCoord.toBigInteger())
    val masked = bytes32(d).zip(Bip340.taggedHash("BIP0340/aux", aux).toList()) { a, b -> (a.toInt() xor b.toInt()).toByte() }.toByteArray()
    val k0 = java.math.BigInteger(1, Bip340.taggedHash("BIP0340/nonce", masked + px + message)).mod(n)
    require(k0.signum() != 0) { "nonce is zero" }
    val r = domain.g.multiply(k0).normalize()
    val k = if (r.affineYCoord.testBitZero()) n.subtract(k0) else k0
    val rx = bytes32(r.affineXCoord.toBigInteger())
    val e = java.math.BigInteger(1, Bip340.taggedHash("BIP0340/challenge", rx + px + message)).mod(n)
    return rx + bytes32(k.add(e.multiply(d)).mod(n))
}
