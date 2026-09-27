package gy.pig.spark

import com.google.protobuf.InvalidProtocolBufferException
import spark.Spark
import spark_token.TokenTransaction
import spark_token.TokenTransactionType
import java.nio.ByteBuffer

/**
 * Hash a V2 token transaction. When [partialHash] is true, server-set fields
 * (output id, revocation commitment, withdraw bond/locktime, expiry) are omitted.
 */
internal fun hashTokenTransactionV2(tx: TokenTransaction, partialHash: Boolean): ByteArray {
    val allHashes = mutableListOf<ByteArray>()

    // Hash version
    allHashes.add(sha256(uint32BE(tx.version.toUInt())))

    // Hash transaction type
    val txType: UInt = when (tx.tokenInputsCase) {
        TokenTransaction.TokenInputsCase.MINT_INPUT -> TokenTransactionType.TOKEN_TRANSACTION_TYPE_MINT.number.toUInt()
        TokenTransaction.TokenInputsCase.TRANSFER_INPUT -> TokenTransactionType.TOKEN_TRANSACTION_TYPE_TRANSFER.number.toUInt()
        TokenTransaction.TokenInputsCase.CREATE_INPUT -> TokenTransactionType.TOKEN_TRANSACTION_TYPE_CREATE.number.toUInt()
        else -> throw SparkError.InvalidResponse("Token transaction must have exactly one input type")
    }
    allHashes.add(sha256(uint32BE(txType)))

    // Hash token inputs based on type
    when (tx.tokenInputsCase) {
        TokenTransaction.TokenInputsCase.TRANSFER_INPUT -> {
            val transferInput = tx.transferInput
            require(transferInput.outputsToSpendCount > 0) { "Outputs to spend cannot be empty" }

            allHashes.add(sha256(uint32BE(transferInput.outputsToSpendCount.toUInt())))
            for (output in transferInput.outputsToSpendList) {
                val data = java.io.ByteArrayOutputStream()
                if (!output.prevTokenTransactionHash.isEmpty) {
                    require(output.prevTokenTransactionHash.size() == 32) { "Invalid previous transaction hash length" }
                    data.write(output.prevTokenTransactionHash.toByteArray())
                }
                data.write(uint32BE(output.prevTokenTransactionVout.toUInt()))
                allHashes.add(sha256(data.toByteArray()))
            }
        }

        TokenTransaction.TokenInputsCase.MINT_INPUT -> {
            val mintInput = tx.mintInput
            require(!mintInput.issuerPublicKey.isEmpty) { "Issuer public key cannot be empty" }
            allHashes.add(sha256(mintInput.issuerPublicKey.toByteArray()))
            if (mintInput.hasTokenIdentifier()) {
                allHashes.add(sha256(mintInput.tokenIdentifier.toByteArray()))
            } else {
                allHashes.add(sha256(ByteArray(32)))
            }
        }

        TokenTransaction.TokenInputsCase.CREATE_INPUT -> {
            val createInput = tx.createInput
            require(!createInput.issuerPublicKey.isEmpty) { "Issuer public key cannot be empty" }
            allHashes.add(sha256(createInput.issuerPublicKey.toByteArray()))

            val nameBytes = createInput.tokenName.toByteArray(Charsets.UTF_8)
            require(nameBytes.isNotEmpty() && nameBytes.size <= 20) { "Token name must be 1-20 bytes" }
            allHashes.add(sha256(nameBytes))

            val tickerBytes = createInput.tokenTicker.toByteArray(Charsets.UTF_8)
            require(tickerBytes.isNotEmpty() && tickerBytes.size <= 6) { "Token ticker must be 1-6 bytes" }
            allHashes.add(sha256(tickerBytes))

            allHashes.add(sha256(uint32BE(createInput.decimals.toUInt())))

            require(createInput.maxSupply.size() == 16) { "Max supply must be exactly 16 bytes" }
            allHashes.add(sha256(createInput.maxSupply.toByteArray()))

            allHashes.add(sha256(byteArrayOf(if (createInput.isFreezable) 1 else 0)))

            // Creation entity public key (only for final hash)
            if (!partialHash && createInput.hasCreationEntityPublicKey()) {
                allHashes.add(sha256(createInput.creationEntityPublicKey.toByteArray()))
            } else {
                allHashes.add(sha256(ByteArray(0)))
            }
        }

        else -> throw SparkError.InvalidResponse("Token transaction must have exactly one input type")
    }

    // Hash token outputs
    allHashes.add(sha256(uint32BE(tx.tokenOutputsCount.toUInt())))

    for (output in tx.tokenOutputsList) {
        val data = java.io.ByteArrayOutputStream()

        // Hash ID (only for final hash)
        if (!partialHash && output.id.isNotEmpty()) {
            data.write(output.id.toByteArray(Charsets.UTF_8))
        }

        if (!output.ownerPublicKey.isEmpty) {
            data.write(output.ownerPublicKey.toByteArray())
        }

        if (!partialHash) {
            if (output.hasRevocationCommitment() && !output.revocationCommitment.isEmpty) {
                data.write(output.revocationCommitment.toByteArray())
            }
            data.write(uint64BE(output.withdrawBondSats.toULong()))
            data.write(uint64BE(output.withdrawRelativeBlockLocktime.toULong()))
        }

        // Token public key (33 zero bytes if absent)
        if (output.hasTokenPublicKey() && !output.tokenPublicKey.isEmpty) {
            data.write(output.tokenPublicKey.toByteArray())
        } else {
            data.write(ByteArray(33))
        }

        // Token identifier (32 zero bytes if absent)
        if (output.hasTokenIdentifier() && !output.tokenIdentifier.isEmpty) {
            data.write(output.tokenIdentifier.toByteArray())
        } else {
            data.write(ByteArray(32))
        }

        if (!output.tokenAmount.isEmpty) {
            data.write(output.tokenAmount.toByteArray())
        }

        allHashes.add(sha256(data.toByteArray()))
    }

    // Hash sorted operator identity public keys
    val sortedPubKeys = tx.sparkOperatorIdentityPublicKeysList.sortedWith(
        Comparator { a, b ->
            val aBytes = a.toByteArray()
            val bBytes = b.toByteArray()
            for (i in 0 until minOf(aBytes.size, bBytes.size)) {
                val cmp = (aBytes[i].toInt() and 0xFF) - (bBytes[i].toInt() and 0xFF)
                if (cmp != 0) return@Comparator cmp
            }
            aBytes.size - bBytes.size
        }
    )
    allHashes.add(sha256(uint32BE(sortedPubKeys.size.toUInt())))
    for (pubKey in sortedPubKeys) {
        allHashes.add(sha256(pubKey.toByteArray()))
    }

    // Hash network
    allHashes.add(sha256(uint32BE(tx.network.number.toUInt())))

    // Hash client created timestamp (milliseconds as uint64 BE)
    val clientMs = tx.clientCreatedTimestamp.seconds.toULong() * 1000uL +
        (tx.clientCreatedTimestamp.nanos / 1_000_000).toULong()
    allHashes.add(sha256(uint64BE(clientMs)))

    if (!partialHash) {
        // Hash expiry time (seconds as uint64 BE)
        val expirySecs = if (tx.hasExpiryTime()) tx.expiryTime.seconds.toULong() else 0uL
        allHashes.add(sha256(uint64BE(expirySecs)))
    }

    // Hash invoice attachments (V2): each raw invoice string, ordered by the invoice's id (its 16
    // UUID bytes) as the operators and the reference SDK order them.
    val attachments = tx.invoiceAttachmentsList
    allHashes.add(sha256(uint32BE(attachments.size.toUInt())))
    val keyed = attachments.mapIndexed { index, attachment -> sparkInvoiceId(attachment.sparkInvoice, index) to attachment.sparkInvoice }
    for ((_, invoice) in keyed.sortedWith { a, b -> compareUnsigned(a.first, b.first) }) {
        allHashes.add(sha256(invoice.toByteArray(Charsets.UTF_8)))
    }

    // Final hash of all concatenated hashes
    val concatenated = java.io.ByteArrayOutputStream()
    for (h in allHashes) concatenated.write(h)
    return sha256(concatenated.toByteArray())
}

/**
 * The id of the Spark invoice in invoice attachment [index]: the 16 UUID bytes of its
 * `SparkInvoiceFields.id`. On any network: the hash does not check it, as the operators' and the
 * reference SDK's do not.
 */
private fun sparkInvoiceId(invoice: String, index: Int): ByteArray {
    val fields = try {
        val (_, words) = Bech32m.decodeBech32m(invoice)
        Bech32.fromWords(words)?.let { Spark.SparkAddress.parseFrom(it) }?.takeIf { it.hasSparkInvoiceFields() }?.sparkInvoiceFields
    } catch (_: SparkError) {
        null
    } catch (_: InvalidProtocolBufferException) {
        null
    }
    if (fields == null || fields.id.size() != 16) {
        throw SparkError.InvalidArgument("invoice attachment $index is not a Spark invoice with a 16-byte id")
    }
    return fields.id.toByteArray()
}

/** Lexicographic order of byte strings, bytes compared unsigned. */
private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (cmp != 0) return cmp
    }
    return a.size - b.size
}

/**
 * Hash an operator-specific token transaction signable payload.
 */
internal fun hashOperatorSpecificPayload(finalTokenTransactionHash: ByteArray, operatorIdentityPublicKey: ByteArray,): ByteArray {
    require(finalTokenTransactionHash.size == 32) { "Final token transaction hash must be 32 bytes" }
    require(operatorIdentityPublicKey.isNotEmpty()) { "Operator identity public key cannot be empty" }

    val allHashes = mutableListOf<ByteArray>()
    allHashes.add(sha256(finalTokenTransactionHash))
    allHashes.add(sha256(operatorIdentityPublicKey))

    val concatenated = java.io.ByteArrayOutputStream()
    for (h in allHashes) concatenated.write(h)
    return sha256(concatenated.toByteArray())
}

// MARK: - Helpers

private fun uint32BE(value: UInt): ByteArray = ByteBuffer.allocate(4).putInt(value.toInt()).array()

private fun uint64BE(value: ULong): ByteArray = ByteBuffer.allocate(8).putLong(value.toLong()).array()
