package gy.pig.spark

import com.google.protobuf.ByteString
import common.Common
import spark.Spark
import uniffi.spark_frost.*

internal object FrostSigningHelper {

    fun buildSigningJob(
        leafID: String,
        signingKey: ByteArray,
        verifyingKey: ByteArray,
        rawTx: ByteArray,
        sighash: ByteArray,
        soCommitments: Map<String, Common.SigningCommitment>,
    ): Spark.UserSignedTxSigningJob {
        val publicKey = getPublicKeyBytes(signingKey, true)
        val keyPackage = KeyPackage(
            secretKey = signingKey,
            publicKey = publicKey,
            verifyingKey = verifyingKey,
        )

        val nonceResult = frostNonce(keyPackage)

        // Convert proto commitments to native type
        val nativeCommitments = soCommitments.mapValues { (_, proto) ->
            SigningCommitment(
                hiding = proto.hiding.toByteArray(),
                binding = proto.binding.toByteArray(),
            )
        }

        val userSignature = signFrost(
            msg = sighash,
            keyPackage = keyPackage,
            nonce = nonceResult.nonce,
            selfCommitment = nonceResult.commitment,
            statechainCommitments = nativeCommitments,
            adaptorPublicKey = null,
        )

        val signingCommitmentsBuilder = Spark.SigningCommitments.newBuilder()
        for ((soID, commitment) in soCommitments) {
            signingCommitmentsBuilder.putSigningCommitments(soID, commitment)
        }

        return Spark.UserSignedTxSigningJob.newBuilder()
            .setLeafId(leafID)
            .setSigningPublicKey(ByteString.copyFrom(publicKey))
            .setRawTx(ByteString.copyFrom(rawTx))
            .setSigningNonceCommitment(
                Common.SigningCommitment.newBuilder()
                    .setHiding(ByteString.copyFrom(nonceResult.commitment.hiding))
                    .setBinding(ByteString.copyFrom(nonceResult.commitment.binding))
                    .build()
            )
            .setUserSignature(ByteString.copyFrom(userSignature))
            .setSigningCommitments(signingCommitmentsBuilder.build())
            .build()
    }

    fun buildUnsignedJob(signingPublicKey: ByteArray, rawTx: ByteArray, hidingNonce: ByteArray, bindingNonce: ByteArray,): Spark.SigningJob =
        Spark.SigningJob.newBuilder()
            .setSigningPublicKey(ByteString.copyFrom(signingPublicKey))
            .setRawTx(ByteString.copyFrom(rawTx))
            .setSigningNonceCommitment(
                Common.SigningCommitment.newBuilder()
                    .setHiding(ByteString.copyFrom(hidingNonce))
                    .setBinding(ByteString.copyFrom(bindingNonce))
                    .build()
            )
            .build()
}
