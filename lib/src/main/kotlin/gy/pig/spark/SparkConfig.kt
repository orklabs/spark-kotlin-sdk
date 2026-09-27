package gy.pig.spark

/**
 * Bitcoin network the SDK is targeting.
 *
 * - [MAINNET] connects to production Spark operators and the production SSP. All
 *   activity moves real Bitcoin.
 * - [REGTEST] uses the same hosted operators, which serve both networks under the same keys, as
 *   the reference SDK's REGTEST preset does. Use it for integration tests and demos; pass your
 *   own `signingOperators` for a local deployment (plain `http://` is allowed on regtest only).
 */
public enum class SparkNetwork {
    MAINNET,
    REGTEST;

    public val networkString: String
        get() = when (this) {
            MAINNET -> "mainnet"
            REGTEST -> "regtest"
        }

    public val networkGraphQL: String
        get() = when (this) {
            MAINNET -> "MAINNET"
            REGTEST -> "REGTEST"
        }
}

/**
 * Address and identity of a single Spark signing operator.
 *
 * @property address Base URL of the operator's gRPC endpoint, e.g.
 *   `https://0.spark.lightspark.com`.
 * @property identifier 32-byte operator identifier as a lowercase hex string.
 * @property identityPublicKeyHex 33-byte compressed secp256k1 public key as a
 *   lowercase hex string, used to verify operator-signed responses.
 */
public data class SigningOperatorConfig(val address: String, val identifier: String, val identityPublicKeyHex: String,)

/**
 * Wallet-level configuration: network, operator topology, and SSP endpoint.
 *
 * Default construction targets [SparkNetwork.MAINNET] with the canonical operator
 * triad and the production Lightspark SSP. Override [signingOperators] for self-hosted
 * deployments, and [sspURL] (with [sspIdentityPublicKeyHex]) to point at a different SSP.
 *
 * @property network Bitcoin network (mainnet / regtest).
 * @property signingOperators The set of Spark operators the SDK will talk to. Order
 *   matters — the first entry is treated as the coordinator. Secret shares are only ever
 *   encrypted to the identity keys listed here; a coordinator operator list that does not
 *   match this configuration is refused. On mainnet every operator must be `https://`.
 * @property sspURL The SSP's GraphQL endpoint; Lightspark's hosted SSP ([DEFAULT_SSP_URL]) by default.
 * @property sspIdentityPublicKeyHex The SSP's identity key, which transfers to the SSP (Lightning
 *   sends, leaf swaps, cooperative exits) are addressed to. Required with a custom [sspURL]:
 *   without it those operations refuse to run rather than send to Lightspark's key, as the
 *   reference SDK takes the SSP's URL and key together.
 * @property signingThreshold FROST signing threshold the operators enforce. Defaults to the
 *   reference SDK's value for the operator count (2 of 3 on mainnet).
 * @property expectedWithdrawBondSats Withdraw bond the coordinator is expected to set on token
 *   outputs (reference SDK: 10 000). A final token transaction with another value is refused.
 * @property expectedWithdrawRelativeBlockLocktime Relative block locktime the coordinator is
 *   expected to set on token outputs (reference SDK: 1 000).
 */
public data class SparkConfig(
    val network: SparkNetwork = SparkNetwork.MAINNET,
    val signingOperators: List<SigningOperatorConfig> = defaultOperators(network),
    val sspURL: String = DEFAULT_SSP_URL,
    val sspIdentityPublicKeyHex: String? = null,
    val signingThreshold: UInt = defaultThreshold(signingOperators.size),
    val expectedWithdrawBondSats: ULong = 10_000uL,
    val expectedWithdrawRelativeBlockLocktime: ULong = 1_000uL,
) {
    val signingOperatorAddresses: List<String>
        get() = signingOperators.map { it.address }

    val coordinatorAddress: String
        get() = signingOperators[0].address

    /**
     * The SSP's identity key: the one configured, else the default SSP's for the network. Empty
     * for a custom [sspURL] configured without a key (or a configured key that is not hex).
     */
    val sspIdentityPublicKey: ByteArray
        get() {
            sspIdentityPublicKeyHex?.let { return it.hexToBytesOrNull() ?: ByteArray(0) }
            if (sspURL != DEFAULT_SSP_URL) return ByteArray(0)
            return when (network) {
                SparkNetwork.MAINNET -> "023e33e2920326f64ea31058d44777442d97d7d5cbfcf54e3060bc1695e5261c93".hexToByteArray()
                SparkNetwork.REGTEST -> "022bf283544b16c0622daecb79422007d167eca6ce9f0c98c0c49833b1f7170bfe".hexToByteArray()
            }
        }

    /**
     * The SSP's identity key for a transfer to it; throws [SparkError.InvalidArgument] when there
     * is none (a custom [sspURL] without [sspIdentityPublicKeyHex]) or it is not a compressed key.
     */
    internal fun requireSspIdentityPublicKey(): ByteArray {
        val key = sspIdentityPublicKey
        if (key.size != 33 || (key[0] != 0x02.toByte() && key[0] != 0x03.toByte())) {
            throw SparkError.InvalidArgument(
                "no valid SSP identity key: a custom sspURL needs sspIdentityPublicKeyHex, the SSP's compressed public key",
            )
        }
        return key
    }

    public companion object {
        /** Lightspark's hosted SSP, the default. */
        public const val DEFAULT_SSP_URL: String = "https://api.lightspark.com/graphql/spark/2025-03-19"

        /** The threshold the Spark deployments use for a given operator count (2 of 3, 3 of 5). */
        internal fun defaultThreshold(operatorCount: Int): UInt = maxOf(2u, (maxOf(operatorCount, 0).toUInt() + 2u) / 2u)

        /**
         * The hosted operators. Regtest uses them too, as the reference SDK's REGTEST preset does:
         * they serve both networks under the same keys.
         */
        @Suppress("UNUSED_PARAMETER")
        public fun defaultOperators(network: SparkNetwork): List<SigningOperatorConfig> = listOf(
            SigningOperatorConfig(
                address = "https://0.spark.lightspark.com",
                identifier = "0000000000000000000000000000000000000000000000000000000000000001",
                identityPublicKeyHex = "03dfbdff4b6332c220f8fa2ba8ed496c698ceada563fa01b67d9983bfc5c95e763",
            ),
            SigningOperatorConfig(
                address = "https://spark-operator.breez.technology",
                identifier = "0000000000000000000000000000000000000000000000000000000000000002",
                identityPublicKeyHex = "03e625e9768651c9be268e287245cc33f96a68ce9141b0b4769205db027ee8ed77",
            ),
            SigningOperatorConfig(
                address = "https://2.spark.flashnet.xyz",
                identifier = "0000000000000000000000000000000000000000000000000000000000000003",
                identityPublicKeyHex = "022eda13465a59205413086130a65dc0ed1b8f8e51937043161f8be0c369b1a410",
            ),
        )
    }
}
