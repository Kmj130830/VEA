package com.kmj.vea.engine

import java.util.Base64

/** Exact selected VTB2 subtree used by the carrier-fusion Dense descriptor. */
object S5MegpoidCarrierFusionModelPath {
    const val CONFIG_KEY = "43726e4ce752ea6cc300dc2fca3e8372"
    /** FUN_1800e9740: [outputFeature=2, inputFeature=32, 1, 1] weights. */
    const val WEIGHTS_KEY = "61ef9b49f8fe353fe0d586220366ce92"
    /** FUN_1800e9580: two output-feature bias values. */
    const val BIAS_KEY = "0128c19d610695f96c546cb8e04839fd"
    const val SHAPE_METADATA_KEY = "4ba18a32e4fa1a2f3b293713692a3a2e"
    const val TYPED_PAYLOAD_KEY = "0ed3f4c15ca6d021d59b2b1038226a7f"
}

/**
 * One carrier-fusion tensor object exactly as stored in VTB2.
 *
 * [descriptorMetadata] is retained exactly as stored. For the carrier
 * descriptor, FUN_1800bbf10 supplies inputFeatures then outputFeatures=2,
 * while FUN_1800d4610 receives the 64-element matrix tensor before the
 * two-element bias tensor. That establishes a row-major [2][32] projection.
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
 * This index preserves the vendor's metadata/data pairs, validates the
 * native [output=2][input=32] matrix and two-value bias, and can assemble
 * the corresponding model-backed Dense operator.
 */
class S5MegpoidCarrierFusionTensorIndex private constructor(
    val weights: S5MegpoidCarrierFusionTensorReference,
    val bias: S5MegpoidCarrierFusionTensorReference
) {
    /** Dense descriptor contract confirmed by FUN_1800bbf10 / FUN_1800d4610. */
    fun resolveDenseDimensions(): S5MegpoidDenseProjectionDimensions {
        require(weights.descriptorMetadata.size == 4) {
            "Carrier Dense weights must retain the native four-axis descriptor."
        }
        val metadata = weights.descriptorMetadata
        require(metadata[2] == 1 && metadata[3] == 1) {
            "Carrier Dense weights must use the native [output][input][1][1] tensor layout."
        }
        val outputFeatures = metadata[0]
        val inputFeatures = metadata[1]
        require(outputFeatures == 2) {
            "The recovered carrier Dense descriptor requires two output features."
        }
        require(inputFeatures > 0)
        require(bias.descriptorMetadata == listOf(outputFeatures) &&
            bias.elementCount == outputFeatures) {
            "Carrier Dense bias must contain one value per output feature."
        }
        require(weights.elementCount.toLong() ==
            outputFeatures.toLong() * inputFeatures.toLong()) {
            "Carrier Dense matrix element count does not match its native dimensions."
        }
        return S5MegpoidDenseProjectionDimensions(inputFeatures, outputFeatures)
    }

    /** Builds the exact [output=2][input=32] carrier Dense operator from VTB2. */
    fun buildDenseProjection(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseProjection {
        val dimensions = resolveDenseDimensions()
        return S5MegpoidDenseProjection(
            inputFeatures = dimensions.inputFeatures,
            outputFeatures = dimensions.outputFeatures,
            weights = weights.decodeFloatArray(tensorDecoder),
            bias = bias.decodeFloatArray(tensorDecoder)
        )
    }

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
                    require(product <= Int.MAX_VALUE.toLong() / dimension.toLong()) {
                        "Carrier-fusion metadata '$propertyName' exceeds the supported tensor size."
                    }
                    product * dimension.toLong()
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
