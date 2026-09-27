package gy.pig.spark

/**
 * Get the Spark address for this wallet (bech32m-encoded identity public key).
 * Format: `spark1...` for mainnet, `sparkrt1...` for regtest.
 */
public fun SparkWallet.getSparkAddress(): String = SparkAddress.encode(signer.identityPublicKey, config.network)
