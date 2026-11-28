package com.kmj.vea.engine

import java.util.Base64

/** Shared VTB2 normalizer and Dense tensors used by both output-head branches. */
object S5MegpoidPostResidualFallbackTensorKeys {
    /** FUN_1800e9ac0: +0xC4 normalizer scale, metadata [32]. */
    const val NORMALIZATION_SCALE = "d55956a69b51b6f6dd43bd0cc2467bcf"
    /** FUN_1800e9900: +0xC4 normalizer bias, metadata [32]. */
    const val NORMALIZATION_BIAS = "bf96a6e83ab8bb7d33509f2d3a1a348f"
    /** FUN_1800e9e40: +0xCE Dense matrix, metadata [8,32,1,1]. */
    const val DENSE_WEIGHTS = "051fe4a0e8e7e92c3c8791bd28f24da8"
    /** FUN_1800e9c80: +0xCE Dense bias, metadata [8]. */
    const val DENSE_BIAS = "3a9561654ca2e128021a59d14813a88f"

    const val SHAPE_METADATA_KEY = "4ba18a32e4fa1a2f3b293713692a3a2e"
    const val TYPED_PAYLOAD_KEY = "0ed3f4c15ca6d021d59b2b1038226a7f"
    const val NORMALIZATION_EPSILON = 0.000001f
}

/** Exact tensor object reference, retaining the native metadata and typed payload. */
class S5MegpoidPostResidualTensorReference internal constructor(
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
            "Decoded post-residual tensor '$propertyName' has an unexpected element count."
        }
        return decoded
    }
}

/**
 * The ordinary fallback output head from FUN_1800c7ad0:
 *
 *   normalize 32 input features globally -> Dense 32-to-8 projection.
 *
 * The direct +0xC4/+0xCE descriptor is selected only when D01 is false or
 * D00 < 2. The same four source tensors are also folded during initialization
 * of the active D00=4/D01=true branch into +0x150/+0x154/+0x158; see
 * S5MegpoidActiveMultipartProjectionStage.
 */
class S5MegpoidPostResidualFallbackStage internal constructor(
    val inputFeatures: Int,
    val outputFeatures: Int,
    private val normalization: S5MegpoidGlobalFeatureNormalization,
    private val denseProjection: S5MegpoidDenseProjection
) {
    init {
        require(inputFeatures == normalization.features)
        require(denseProjection.inputFeatures == inputFeatures)
        require(denseProjection.outputFeatures == outputFeatures)
    }

    /** Native order is global normalization first, then Dense projection. */
    fun forward(
        inputFeatureMajor: FloatArray,
        positions: Int,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): FloatArray {
        require(positions > 0)
        require(inputFeatureMajor.size.toLong() ==
            inputFeatures.toLong() * positions.toLong()) {
            "Post-residual fallback input must contain inputFeatures * positions values."
        }
        val normalized = normalization.apply(
            input = inputFeatureMajor,
            positions = positions,
            statistics = statistics
        )
        return denseProjection.project(
            input = normalized,
            positions = positions,
            gemm = gemm
        )
    }
}

/**
 * Shared model-backed VTB2 tensor index for the output normalizer/Dense pair.
 * The native fallback retains direct +0xC4/+0xCE descriptors; the active
 * multipart path folds these same tensors into +0x150/+0x154/+0x158 buffers.
 */
class S5MegpoidPostResidualFallbackTensorIndex private constructor(
    val normalizationScale: S5MegpoidPostResidualTensorReference,
    val normalizationBias: S5MegpoidPostResidualTensorReference,
    val denseWeights: S5MegpoidPostResidualTensorReference,
    val denseBias: S5MegpoidPostResidualTensorReference
) {
    fun resolveDimensions(): S5MegpoidDenseProjectionDimensions {
        require(normalizationScale.descriptorMetadata == listOf(32) &&
            normalizationScale.elementCount == 32) {
            "Post-residual fallback normalizer scale must contain 32 values."
        }
        require(normalizationBias.descriptorMetadata == listOf(32) &&
            normalizationBias.elementCount == 32) {
            "Post-residual fallback normalizer bias must contain 32 values."
        }
        require(denseWeights.descriptorMetadata == listOf(8, 32, 1, 1) &&
            denseWeights.elementCount == 8 * 32) {
            "Post-residual fallback Dense weights must use the native [8,32,1,1] layout."
        }
        require(denseBias.descriptorMetadata == listOf(8) &&
            denseBias.elementCount == 8) {
            "Post-residual fallback Dense bias must contain eight values."
        }
        return S5MegpoidDenseProjectionDimensions(
            inputFeatures = 32,
            outputFeatures = 8
        )
    }

    /**
     * Builds the active D00*2 output projection from the same four VTB2
     * tensors. The active native path folds them into render buffers
     * +0x150/+0x154/+0x158 rather than invoking the fallback +0xCE descriptor.
     */
    fun buildActiveMultipartProjectionStage(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidActiveMultipartProjectionStage {
        val dimensions = resolveDimensions()
        return S5MegpoidActiveMultipartProjectionStage.build(
            inputFeatures = dimensions.inputFeatures,
            outputFeatures = dimensions.outputFeatures,
            denseWeights = denseWeights.decodeFloatArray(tensorDecoder),
            denseBias = denseBias.decodeFloatArray(tensorDecoder),
            inputScale = normalizationScale.decodeFloatArray(tensorDecoder),
            inputBias = normalizationBias.decodeFloatArray(tensorDecoder),
            epsilon = S5MegpoidPostResidualFallbackTensorKeys.NORMALIZATION_EPSILON
        )
    }

    fun buildStage(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidPostResidualFallbackStage {
        val dimensions = resolveDimensions()
        return S5MegpoidPostResidualFallbackStage(
            inputFeatures = dimensions.inputFeatures,
            outputFeatures = dimensions.outputFeatures,
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = dimensions.inputFeatures,
                scale = normalizationScale.decodeFloatArray(tensorDecoder),
                bias = normalizationBias.decodeFloatArray(tensorDecoder),
                epsilon = S5MegpoidPostResidualFallbackTensorKeys.NORMALIZATION_EPSILON
            ),
            denseProjection = S5MegpoidDenseProjection(
                inputFeatures = dimensions.inputFeatures,
                outputFeatures = dimensions.outputFeatures,
                weights = denseWeights.decodeFloatArray(tensorDecoder),
                bias = denseBias.decodeFloatArray(tensorDecoder)
            )
        )
    }

    companion object {
        fun build(modelTree: S5ModelObjectTree): S5MegpoidPostResidualFallbackTensorIndex {
            fun read(
                propertyName: String,
                lookupKey: String
            ): S5MegpoidPostResidualTensorReference {
                val match = modelTree.findUniqueByLookupKeyRecursive(lookupKey)
                    ?: throw IllegalArgumentException(
                        "Post-residual fallback property '$propertyName' (key $lookupKey) is missing."
                    )
                val obj = match.value as? S5ModelValue.ObjectValue
                    ?: throw IllegalArgumentException(
                        "Post-residual fallback property '$propertyName' at ${match.path} is not an object."
                    )
                require(obj.fields.keys == setOf(
                    S5MegpoidPostResidualFallbackTensorKeys.SHAPE_METADATA_KEY,
                    S5MegpoidPostResidualFallbackTensorKeys.TYPED_PAYLOAD_KEY
                )) {
                    "Post-residual fallback property '$propertyName' has an unexpected object layout."
                }

                val metadata = (obj.fields[
                    S5MegpoidPostResidualFallbackTensorKeys.SHAPE_METADATA_KEY
                ] as? S5ModelValue.ArrayValue)?.values?.mapIndexed { index, item ->
                    val number = (item as? S5ModelValue.NumberValue)?.value
                        ?: throw IllegalArgumentException(
                            "Post-residual fallback metadata '$propertyName' has a non-numeric dimension at $index."
                        )
                    require(number.isFinite() && number % 1.0 == 0.0 &&
                        number in 1.0..Int.MAX_VALUE.toDouble()) {
                        "Post-residual fallback metadata '$propertyName' has an invalid dimension at $index."
                    }
                    number.toInt()
                } ?: throw IllegalArgumentException(
                    "Post-residual fallback property '$propertyName' has no metadata array."
                )
                require(metadata.isNotEmpty())

                val metadataCount = metadata.fold(1L) { product, dimension ->
                    require(product <= Int.MAX_VALUE.toLong() / dimension.toLong()) {
                        "Post-residual fallback metadata '$propertyName' exceeds supported tensor size."
                    }
                    product * dimension.toLong()
                }.toInt()

                val encoded = obj.fields[
                    S5MegpoidPostResidualFallbackTensorKeys.TYPED_PAYLOAD_KEY
                ] as? S5ModelValue.StringValue
                    ?: throw IllegalArgumentException(
                        "Post-residual fallback property '$propertyName' has no typed payload."
                    )
                val marker = encoded.value.take(4)
                require(marker == "*F32" || marker == "*F16") {
                    "Post-residual fallback property '$propertyName' must be *F32 or *F16."
                }
                val payloadBytes = try {
                    Base64.getDecoder().decode(encoded.value.substring(4)).size
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Post-residual fallback property '$propertyName' has malformed Base64.",
                        error
                    )
                }
                val scalarBytes = if (marker == "*F16") 2 else 4
                require(payloadBytes > 0 && payloadBytes % scalarBytes == 0) {
                    "Post-residual fallback property '$propertyName' has an empty or misaligned payload."
                }
                val elementCount = payloadBytes / scalarBytes
                require(elementCount == metadataCount) {
                    "Post-residual fallback property '$propertyName' declares $metadataCount values but stores $elementCount."
                }

                return S5MegpoidPostResidualTensorReference(
                    propertyName = propertyName,
                    lookupKey = lookupKey,
                    objectPath = match.path,
                    marker = marker,
                    descriptorMetadata = metadata,
                    elementCount = elementCount,
                    encodedValue = encoded
                )
            }

            return S5MegpoidPostResidualFallbackTensorIndex(
                normalizationScale = read(
                    "fallback normalization scale",
                    S5MegpoidPostResidualFallbackTensorKeys.NORMALIZATION_SCALE
                ),
                normalizationBias = read(
                    "fallback normalization bias",
                    S5MegpoidPostResidualFallbackTensorKeys.NORMALIZATION_BIAS
                ),
                denseWeights = read(
                    "fallback Dense weights",
                    S5MegpoidPostResidualFallbackTensorKeys.DENSE_WEIGHTS
                ),
                denseBias = read(
                    "fallback Dense bias",
                    S5MegpoidPostResidualFallbackTensorKeys.DENSE_BIAS
                )
            )
        }
    }
}
