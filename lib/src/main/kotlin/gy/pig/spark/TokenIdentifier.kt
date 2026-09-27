package gy.pig.spark

public typealias Bech32mTokenIdentifier = String

internal object TokenIdentifierPrefix {
    fun prefix(network: SparkNetwork): String = when (network) {
        SparkNetwork.MAINNET -> "btkn"
        SparkNetwork.REGTEST -> "btknrt"
    }

    fun network(prefix: String): SparkNetwork? = when (prefix) {
        "btkn" -> SparkNetwork.MAINNET
        "btknrt" -> SparkNetwork.REGTEST
        else -> null
    }
}

public fun encodeBech32mTokenIdentifier(rawIdentifier: ByteArray, network: SparkNetwork): Bech32mTokenIdentifier {
    require(rawIdentifier.size == 32) {
        "Token identifier must be 32 bytes, got ${rawIdentifier.size}"
    }
    val hrp = TokenIdentifierPrefix.prefix(network)
    val words = Bech32.toWords(rawIdentifier)
    return Bech32m.encode(hrp, words)
}

public fun decodeBech32mTokenIdentifier(bech32mIdentifier: Bech32mTokenIdentifier, network: SparkNetwork? = null,): Pair<ByteArray, SparkNetwork> {
    val (hrp, data) = Bech32m.decodeBech32m(bech32mIdentifier)

    if (network != null) {
        val expectedPrefix = TokenIdentifierPrefix.prefix(network)
        require(hrp == expectedPrefix) {
            "Invalid token identifier prefix: expected '$expectedPrefix', got '$hrp'"
        }
    }

    val detectedNetwork = TokenIdentifierPrefix.network(hrp)
        ?: throw SparkError.InvalidResponse("Unknown token identifier prefix: '$hrp'")

    val rawBytes = Bech32.fromWords(data)
        ?: throw SparkError.InvalidResponse("Failed to decode token identifier words")

    require(rawBytes.size == 32) {
        "Token identifier must be 32 bytes, got ${rawBytes.size}"
    }

    return rawBytes to detectedNetwork
}
