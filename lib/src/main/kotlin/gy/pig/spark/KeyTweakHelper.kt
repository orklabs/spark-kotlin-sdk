package gy.pig.spark

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import spark.Spark
import uniffi.spark_frost.*

/**
 * Shared logic for building encrypted key tweak packages used in transfers, swaps, lightning
 * sends, withdrawals and claims.
 */
internal object KeyTweakHelper {

    private fun untrusted(message: String): Nothing = throw SparkError.UntrustedResponse(message)

    private fun invalidArgument(message: String): Nothing = throw SparkError.InvalidArgument(message)

    data class Package(val keyTweakPackage: Map<String, ByteArray>, val signature: ByteArray,)

    /** One operator as the coordinator lists it, reconciled with the wallet configuration. */
    data class OperatorTarget(
        val soID: String,
        /** 1-based VSS share index (the coordinator's 0-based `index` + 1). */
        val shareIndex: UInt,
        /** Identity key from the wallet configuration — never from the coordinator. */
        val identityPublicKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OperatorTarget) return false
            return soID == other.soID && shareIndex == other.shareIndex && identityPublicKey.contentEquals(other.identityPublicKey)
        }

        override fun hashCode(): Int = (soID.hashCode() * 31 + shareIndex.hashCode()) * 31 + identityPublicKey.contentHashCode()
    }

    /**
     * Reconcile the coordinator's operator list with the configured operators. Every listed
     * operator must be configured, the sets must be the same size, and the indices must be a
     * permutation of 0 until n. Secret shares are only ever encrypted to configured keys.
     */
    fun matchOperators(server: Map<String, Spark.SigningOperatorInfo>, config: List<SigningOperatorConfig>): List<OperatorTarget> {
        if (server.isEmpty()) {
            untrusted("coordinator returned an empty signing operator list")
        }
        if (server.size != config.size) {
            untrusted(
                "coordinator lists ${server.size} signing operators, wallet is configured for ${config.size}"
            )
        }
        val targets = mutableListOf<OperatorTarget>()
        val seenIndices = mutableSetOf<ULong>()
        for ((soID, info) in server) {
            val operatorConfig = config.firstOrNull { it.identifier == soID }
                ?: untrusted("coordinator listed operator $soID which is not in the wallet configuration")
            // uint64 on the wire: read it unsigned so a huge value cannot pass as negative.
            val index = info.index.toULong()
            if (index >= config.size.toULong() || !seenIndices.add(index)) {
                untrusted("coordinator reported an invalid or duplicate index $index for operator $soID")
            }
            val key = operatorConfig.identityPublicKeyHex.hexToBytesOrNull()
            if (key == null || key.size != 33) {
                invalidArgument("operator $soID has no valid identity public key configured")
            }
            targets.add(OperatorTarget(soID = soID, shareIndex = index.toUInt() + 1u, identityPublicKey = key))
        }
        return targets.sortedBy { it.shareIndex }
    }

    /**
     * Build and encrypt a key tweak package for sending leaves to a receiver.
     * Used by the transfer, lightning, withdrawal and swap flows.
     */
    fun buildSendPackage(
        transferID: String,
        leaves: List<SparkLeaf>,
        receiverPubKey: ByteArray,
        signer: SparkSignerProtocol,
        soOperators: Map<String, Spark.SigningOperatorInfo>,
        signingOperatorConfigs: List<SigningOperatorConfig>,
        threshold: UInt,
    ): Pair<Map<String, Spark.SendLeafKeyTweaks>, Package> {
        val targets = matchOperators(server = soOperators, config = signingOperatorConfigs)
        val soCount = targets.size.toUInt()
        if (threshold < 1u || threshold > soCount) {
            throw SparkError.InvalidArgument("signing threshold $threshold is not valid for $soCount operators")
        }

        val perSoTweaks = linkedMapOf<String, Spark.SendLeafKeyTweaks.Builder>()
        for (target in targets) {
            perSoTweaks[target.soID] = Spark.SendLeafKeyTweaks.newBuilder()
        }

        for (leaf in leaves) {
            val oldSigningKey = signer.deriveLeafSigningKey(leaf.id)
            val newRandomKey = randomSecretKeyBytes()
            val keyTweak = subtractPrivateKeys(oldSigningKey, newRandomKey)
            val vssShares = splitSecretWithProofsUniffi(keyTweak, threshold, soCount)
            val secretCipher = encryptEcies(newRandomKey, receiverPubKey)

            val sigPayload = leaf.id.toByteArray(Charsets.UTF_8) +
                transferID.toByteArray(Charsets.UTF_8) +
                secretCipher
            val tweakSig = signer.signCompactWithIdentityKey(sha256(sigPayload))

            val sharesByTarget = shares(vssShares, targets)
            val pubkeyBySOID = linkedMapOf<String, ByteArray>()
            for ((target, share) in sharesByTarget) {
                pubkeyBySOID[target.soID] = getPublicKeyBytes(share.share, true)
            }

            for ((target, share) in sharesByTarget) {
                val secretShareProto = Spark.SecretShare.newBuilder()
                    .setSecretShare(ByteString.copyFrom(share.share))
                for (proof in share.proofs) {
                    secretShareProto.addProofs(ByteString.copyFrom(proof))
                }
                val leafTweak = Spark.SendLeafKeyTweak.newBuilder()
                    .setLeafId(leaf.id)
                    .setSecretShareTweak(secretShareProto.build())
                    .setSecretCipher(ByteString.copyFrom(secretCipher))
                    .setSignature(ByteString.copyFrom(tweakSig))
                for ((otherSoID, pubkey) in pubkeyBySOID) {
                    leafTweak.putPubkeySharesTweak(otherSoID, ByteString.copyFrom(pubkey))
                }
                perSoTweaks[target.soID]?.addLeavesToSend(leafTweak.build())
            }
        }

        val builtTweaks = perSoTweaks.mapValues { it.value.build() }
        val pkg = encryptAndSign(
            transferID = transferID,
            perSoTweaks = builtTweaks,
            targets = targets,
            signer = signer,
            tag = "transfer",
        )

        return builtTweaks to pkg
    }

    /** Pair each operator with its VSS share by share index, in target order. */
    fun shares(vssShares: List<VerifiableSecretShareResult>, targets: List<OperatorTarget>,): List<Pair<OperatorTarget, VerifiableSecretShareResult>> =
        targets.map { target ->
            val share = vssShares.firstOrNull { it.index == target.shareIndex }
                ?: throw SparkError.FrostSigningFailed("no secret share produced for operator ${target.soID} (index ${target.shareIndex})")
            target to share
        }

    /** Encrypt per-SO tweak data to each operator's configured identity key and sign the package. */
    fun <T : MessageLite> encryptAndSign(
        transferID: String,
        perSoTweaks: Map<String, T>,
        targets: List<OperatorTarget>,
        signer: SparkSignerProtocol,
        tag: String,
    ): Package {
        val keyTweakPackage = linkedMapOf<String, ByteArray>()
        for (target in targets) {
            val tweaks = perSoTweaks[target.soID]
                ?: throw SparkError.FrostSigningFailed("no key tweaks prepared for operator ${target.soID}")
            keyTweakPackage[target.soID] = encryptEcies(tweaks.toByteArray(), target.identityPublicKey)
        }

        val transferIdBytes = transferID.replace("-", "").hexToBytesOrNull()
        if (transferIdBytes == null || transferIdBytes.size != 16) {
            throw SparkError.InvalidArgument("transfer id '$transferID' is not a UUID")
        }
        val hasher = SparkHasher(listOf("spark", tag, "signing payload"))
        hasher.addBytes(transferIdBytes)
        hasher.addMapStringToBytes(keyTweakPackage)
        val packagePayload = hasher.hash()
        val packageSignature = signer.signWithIdentityKey(packagePayload)

        return Package(keyTweakPackage = keyTweakPackage, signature = packageSignature)
    }
}
