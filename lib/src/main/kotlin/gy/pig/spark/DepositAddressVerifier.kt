package gy.pig.spark

import spark.Spark
import uniffi.spark_frost.getTaprootPubkey

/**
 * Checks a deposit address the coordinator generated before the wallet hands it to anyone, as the
 * reference SDK does (`DepositService.validateDepositAddress`) — port of the Swift SDK's
 * `DepositAddressVerifier`. Without these checks a coordinator — or anyone impersonating it —
 * could hand out an address it alone controls; a static address is reused for every deposit.
 *
 * - The proof of possession: a BIP-340 signature by the operators' share of the key (verifying
 *   key minus the wallet's signing key, BIP-86 tweaked) over the tagged hash
 *   `["spark", "deposit", "proof_of_possession"]` of the wallet's identity key, that operator key
 *   and the address (`ProofOfPossessionMessageHashForDepositAddress`, hash variant V2).
 * - Every operator's ECDSA signature over sha256(address), by its configured identity key. The
 *   coordinator's own signature is required for static addresses only.
 * - The address pays P2TR of the verifying key (`P2TRAddressFromPublicKey`), so the proof covers
 *   the key the funds actually go to.
 */
internal object DepositAddressVerifier {
    private fun untrusted(message: String): Nothing = throw SparkError.UntrustedResponse(message)

    /**
     * [config] supplies the operators (their identifiers and identity keys, the first one being
     * the coordinator) and the network.
     */
    fun verify(address: Spark.Address, userSigningPublicKey: ByteArray, identityPublicKey: ByteArray, isStatic: Boolean, config: SparkConfig) {
        val proof = address.depositAddressProof
        if (!address.hasDepositAddressProof() || proof.proofOfPossessionSignature.isEmpty || proof.addressSignaturesMap.isEmpty()) {
            untrusted("deposit address ${address.address} comes without a proof of possession and operator signatures")
        }
        val verifyingKey = address.verifyingKey.toByteArray()
        val paysVerifyingKey = try {
            leafNodeAddress(verifyingKey, config.network) == address.address
        } catch (_: kotlin.Exception) {
            // Spelled out: FROST errors are also called `Exception`; any failure means no match.
            false
        }
        if (!paysVerifyingKey) {
            untrusted("deposit address ${address.address} does not pay the reported verifying key")
        }

        val operatorPublicKey = subtractPublicKeys(verifyingKey, userSigningPublicKey)
        val hasher = SparkHasher(listOf("spark", "deposit", "proof_of_possession"))
        hasher.addBytes(identityPublicKey)
        hasher.addBytes(operatorPublicKey)
        hasher.addBytes(address.address.toByteArray(Charsets.UTF_8))
        if (!verifySchnorr(proof.proofOfPossessionSignature.toByteArray(), hasher.hash(), taprootInternalKey = operatorPublicKey)) {
            untrusted("deposit address ${address.address} has an invalid proof of possession")
        }

        val addressHash = sha256(address.address.toByteArray(Charsets.UTF_8))
        val coordinatorIdentifier = config.signingOperators.firstOrNull()?.identifier
        for (signingOperator in config.signingOperators) {
            if (!isStatic && signingOperator.identifier == coordinatorIdentifier) continue
            val signature = proof.addressSignaturesMap[signingOperator.identifier]?.toByteArray()
            val operatorKey = signingOperator.identityPublicKeyHex.hexToBytesOrNull()
            if (signature == null || operatorKey == null || !TransferLeafVerifier.verifyECDSA(signature, addressHash, operatorKey)) {
                untrusted("deposit address ${address.address} lacks a valid signature from operator ${signingOperator.identifier}")
            }
        }
    }

    /** `a - b` for two compressed secp256k1 public keys. */
    fun subtractPublicKeys(a: ByteArray, b: ByteArray): ByteArray {
        val curve = KeyDerivation.ecDomainParams.curve
        val difference = try {
            curve.decodePoint(a).add(curve.decodePoint(b).negate()).normalize()
        } catch (_: IllegalArgumentException) {
            untrusted("invalid deposit address key material")
        }
        if (difference.isInfinity) untrusted("invalid deposit address key material")
        return difference.getEncoded(true)
    }

    /** BIP-340 verification of [signature] over the 32-byte [message] under the BIP-86 output key of [taprootInternalKey]. */
    fun verifySchnorr(signature: ByteArray, message: ByteArray, taprootInternalKey: ByteArray): Boolean {
        if (signature.size != 64 || message.size != 32) return false
        val tweaked = try {
            getTaprootPubkey(taprootInternalKey)
        } catch (_: kotlin.Exception) {
            return false
        }
        if (tweaked.size != 33) return false
        return Bip340.verify(signature, message, tweaked.copyOfRange(1, 33))
    }
}
