package gy.pig.spark

import spark.Spark

public suspend fun SparkWallet.setPrivacyEnabled(enabled: Boolean): WalletSettings {
    val stub = getCoordinatorStub()

    val request = Spark.UpdateWalletSettingRequest.newBuilder()
        .setPrivateEnabled(enabled)
        .build()

    val response = stub.updateWalletSetting(request)
    val setting = response.walletSetting

    return WalletSettings(
        privateEnabled = setting.privateEnabled,
        ownerIdentityPublicKey = setting.ownerIdentityPublicKey.toByteArray().toHexString(),
    )
}

public suspend fun SparkWallet.getWalletSettings(): WalletSettings {
    val stub = getCoordinatorStub()

    val request = Spark.QueryWalletSettingRequest.getDefaultInstance()
    val response = stub.queryWalletSetting(request)
    val setting = response.walletSetting

    return WalletSettings(
        privateEnabled = setting.privateEnabled,
        ownerIdentityPublicKey = setting.ownerIdentityPublicKey.toByteArray().toHexString(),
    )
}
