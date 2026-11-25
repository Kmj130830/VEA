package com.kmj.vea.engine

import java.util.Base64

/** Exact selected VTB2 subtree used by the carrier-fusion Dense descriptor. */
object S5MegpoidCarrierFusionModelPath {
    const val CONFIG_KEY = "43726e4ce752ea6cc300dc2fca3e8372"
    const val WEIGHTS_KEY = "0128c19d610695f96c546cb8e04839fd"
    const val BIAS_KEY = "61ef9b49f8fe353fe0d586220366ce92"
    const val SHAPE_METADATA_KEY = "4ba18a32e4fa1a2f3b293713692a3a2e"
    const val TYPED_PAYLOAD_KEY = "0ed3f4c15ca6d021d59b2b1038226a7f"
}

/**
 * One carrier-fusion tensor object exactly as stored in VTB2.
 *
 * [descriptorMetadata] is retained as native model metadata and is not
 * renamed to a guessed matrix shape. The native carrier descriptor builder
 * FUN_1800bbf10 / FUN_1800d4610 still needs to be ported before these
 * tensors can be passed to a Dense operator.
 */
class S5MegpoidCarrierFusionTensorReference internal constructor(
    val propertyName: String,
    val lookupKey: String,
    val objectPath: String,
    val marker: String,
    val descriptorMetadata: List<Int>,
    val elementCount: Int,
    private val encodedValue: S5ModelValue.StringValue
) {
    fun decodeFloatArray(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): FloatArray {
        val decoded = encodedValue.asFloatArray(tensorDecoder)
        check(decoded.size == elementCount) {
            "Decoded carrier-fusion tensor '$propertyName' has an unexpected element count."
        }
        return decoded
    }
}

/**
 * Exact model references for the optional +0xD60 carrier Dense descriptor.
 *
 * This index preserves the vendor's metadata/data pair and checks it against
 * the encoded tensor length. It deliberately does not yet reinterpret the
 * weights [2] and bias [2,32,1,1] records as a conventional Dense matrix.
 */
class S5MegpoidCarrierFusionTensorIndex private constructor(
    val weights: S5MegpoidCarrierFusionTensorReference,
    val bias: S5MegpoidCarrierFusionTensorReference
) {
    companion object {
        fun build(modelTree: S5ModelObjectTree): S5MegpoidCarrierFusionTensorIndex {
            fun read(
                propertyName: String,
                lookupKey: String
            ): S5MegpoidCarrierFusionTensorReference {
                val match = modelTree.findUniqueByLookupKeyRecursive(lookupKey)
                    ?: throw IllegalArgumentException(
                        "Carrier-fusion property '$propertyName' (key $lookupKey) is missing."
                    )
                val objectValue = match.value as? S5ModelValue.ObjectValue
                    ?: throw IllegalArgumentException(
                        "Carrier-fusion property '$propertyName' at ${match.path} is not an object."
                    )
                require(objectValue.fields.keys == setOf(
                    S5MegpoidCarrierFusionModelPath.SHAPE_METADATA_KEY,
                    S5MegpoidCarrierFusionModelPath.TYPED_PAYLOAD_KEY
                )) {
                    "Carrier-fusion property '$propertyName' has an unexpected metadata/payload object layout."
                }

                val metadata = (objectValue.fields[
                    S5MegpoidCarrierFusionModelPath.SHAPE_METADATA_KEY
                ] as? S5ModelValue.ArrayValue)?.values?.mapIndexed { index, value ->
                    val number = (value as? S5ModelValue.NumberValue)?.value
                        ?: throw IllegalArgumentException(
                            "Carrier-fusion metadata '$propertyName' contains a non-numeric dimension at $index."
                        )
                    require(number.isFinite() && number % 1.0 == 0.0 &&
                        number > 0.0 && number <= Int.MAX_VALUE.toDouble()) {
                        "Carrier-fusion metadata '$propertyName' has an invalid dimension at $index."
                    }
                    number.toInt()
                } ?: throw IllegalArgumentException(
                    "Carrier-fusion property '$propertyName' is missing its dimension metadata array."
                )

                require(metadata.isNotEmpty()) {
                    "Carrier-fusion property '$propertyName' has empty dimension metadata."
                }
                val metadataCount = metadata.fold(1L) { product, dimension ->
                    val next = product * dimension.toLong()
                    require(next <= Int.MAX_VALUE.toLong()) {
                        "Carrier-fusion metadata '$propertyName' exceeds the supported tensor size."
                    }
                    next
                }.toInt()

                val encoded = objectValue.fields.getValue(
                    S5MegpoidCarrierFusionModelPath.TYPED_PAYLOAD_KEY
                ) as? S5ModelValue.StringValue
                    ?: throw IllegalArgumentException(
                        "Carrier-fusion property '$propertyName' is missing its typed tensor payload."
                    )
                val marker = encoded.value.take(4)
                require(marker == "*F32" || marker == "*F16") {
                    "Carrier-fusion property '$propertyName' must use *F32 or *F16."
                }
                val bytes = try {
                    Base64.getDecoder().decode(encoded.value.substring(4)).size
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Carrier-fusion property '$propertyName' has malformed Base64 data.",
                        error
                    )
                }
                val scalarBytes = if (marker == "*F16") 2 else 4
                require(bytes > 0 && bytes % scalarBytes == 0) {
                    "Carrier-fusion property '$propertyName' has an empty or misaligned tensor payload."
                }
                val elementCount = bytes / scalarBytes
                require(elementCount == metadataCount) {
                    "Carrier-fusion property '$propertyName' declares $metadataCount values but stores $elementCount."
                }

                return S5MegpoidCarrierFusionTensorReference(
                    propertyName = propertyName,
                    lookupKey = lookupKey,
                    objectPath = match.path,
                    marker = marker,
                    descriptorMetadata = metadata,
                    elementCount = elementCount,
                    encodedValue = encoded
                )
            }

            return S5MegpoidCarrierFusionTensorIndex(
                weights = read(
                    "carrier Dense weights",
                    S5MegpoidCarrierFusionModelPath.WEIGHTS_KEY
                ),
                bias = read(
                    "carrier Dense bias",
                    S5MegpoidCarrierFusionModelPath.BIAS_KEY
                )
            )
        }
    }
}
