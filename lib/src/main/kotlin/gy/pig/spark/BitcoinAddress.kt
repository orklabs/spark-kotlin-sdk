package gy.pig.spark

/**
 * Bitcoin address parsing with network enforcement.
 *
 * Supports P2PKH and P2SH (Base58Check), P2WPKH and P2WSH (bech32, witness v0) and P2TR
 * (bech32m, witness v1), applying the BIP-173 / BIP-350 rules: v0 programs must use the bech32
 * checksum and be 20 or 32 bytes, v1 programs must use bech32m and be 32 bytes. Other witness
 * versions are refused so funds can never be sent to a script no wallet can spend today.
 */
internal object BitcoinAddress {
    enum class Kind {
        P2PKH,
        P2SH,
        P2WPKH,
        P2WSH,
        P2TR,
    }

    data class Decoded(val kind: Kind, val scriptPubKey: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return kind == other.kind && scriptPubKey.contentEquals(other.scriptPubKey)
        }

        override fun hashCode(): Int = 31 * kind.hashCode() + scriptPubKey.contentHashCode()
    }

    private fun invalidAddress(message: String): Nothing = throw SparkError.InvalidAddress(message)

    private class NetworkParams(val bech32HRP: String, val p2pkhVersion: Int, val p2shVersion: Int)

    private fun params(network: SparkNetwork): NetworkParams = when (network) {
        SparkNetwork.MAINNET -> NetworkParams(bech32HRP = "bc", p2pkhVersion = 0x00, p2shVersion = 0x05)
        SparkNetwork.REGTEST -> NetworkParams(bech32HRP = "bcrt", p2pkhVersion = 0x6f, p2shVersion = 0xc4)
    }

    /**
     * The output script an address pays to. Throws [SparkError.InvalidAddress] for anything that
     * is not a well-formed address on [network].
     */
    fun scriptPubKey(address: String, network: SparkNetwork): ByteArray = decode(address, network).scriptPubKey

    fun decode(address: String, network: SparkNetwork): Decoded {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) invalidAddress("empty address")
        val params = params(network)

        val lower = trimmed.lowercase()
        if (lower.startsWith(params.bech32HRP + "1")) {
            return decodeSegwit(trimmed, expectedHRP = params.bech32HRP)
        }
        if (lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1")) {
            invalidAddress("address '$trimmed' is not for the ${network.networkString} network")
        }
        return decodeBase58(trimmed, params, network)
    }

    private fun decodeSegwit(address: String, expectedHRP: String): Decoded {
        val decoded = try {
            Bech32.decode(address, maxLength = 90)
        } catch (e: SparkError) {
            invalidAddress("'$address': ${e.message}")
        }
        if (decoded.hrp != expectedHRP) {
            invalidAddress("'$address' is not a $expectedHRP address")
        }
        val version = decoded.data.firstOrNull()
            ?: invalidAddress("'$address' has no witness version")
        val program = Bech32.fromWords(decoded.data.drop(1))
            ?: invalidAddress("'$address' has an invalid witness program encoding")
        return when (version) {
            0 -> {
                if (decoded.encoding != Bech32.Encoding.BECH32) {
                    invalidAddress("'$address': witness v0 must use the bech32 checksum")
                }
                when (program.size) {
                    20 -> Decoded(Kind.P2WPKH, byteArrayOf(0x00, 0x14) + program)
                    32 -> Decoded(Kind.P2WSH, byteArrayOf(0x00, 0x20) + program)
                    else -> invalidAddress("'$address': witness v0 program must be 20 or 32 bytes")
                }
            }
            1 -> {
                if (decoded.encoding != Bech32.Encoding.BECH32M) {
                    invalidAddress("'$address': witness v1 must use the bech32m checksum")
                }
                if (program.size != 32) {
                    invalidAddress("'$address': taproot program must be 32 bytes")
                }
                Decoded(Kind.P2TR, byteArrayOf(0x51, 0x20) + program)
            }
            else -> invalidAddress("'$address': unsupported witness version $version")
        }
    }

    private fun decodeBase58(address: String, params: NetworkParams, network: SparkNetwork): Decoded {
        val payload = Base58.decodeCheck(address)
            ?: invalidAddress("'$address' is not a valid Base58Check address")
        if (payload.size != 21) {
            invalidAddress("'$address' has an unexpected payload length")
        }
        val version = payload[0].toInt() and 0xFF
        val hash = payload.copyOfRange(1, payload.size)
        return when (version) {
            params.p2pkhVersion -> Decoded(
                Kind.P2PKH,
                byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte()),
            )
            params.p2shVersion -> Decoded(Kind.P2SH, byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte()))
            else -> invalidAddress("'$address' is not for the ${network.networkString} network")
        }
    }

    /** bech32m P2TR address for a 32-byte x-only output key. */
    fun encodeP2TR(program: ByteArray, network: SparkNetwork): String {
        if (program.size != 32) throw SparkError.InvalidResponse("P2TR program must be 32 bytes")
        val words = Bech32.convertBits(program.map { it.toInt() and 0xFF }, fromBits = 8, toBits = 5, pad = true)
            ?: throw SparkError.InvalidResponse("Failed to encode P2TR program")
        return Bech32.encode(params(network).bech32HRP, listOf(0x01) + words, Bech32.Encoding.BECH32M)
    }

    /** P2TR address for an `OP_1 <32-byte>` output script. */
    fun p2trAddress(scriptPubKey: ByteArray, network: SparkNetwork): String {
        if (scriptPubKey.size != 34 || scriptPubKey[0] != 0x51.toByte() || scriptPubKey[1] != 0x20.toByte()) {
            throw SparkError.InvalidResponse("Output script is not P2TR (${scriptPubKey.toHexString()})")
        }
        return encodeP2TR(scriptPubKey.copyOfRange(2, 34), network)
    }
}
