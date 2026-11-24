package com.kmj.vea.engine

import java.util.Base64

/** Exact VTB2 keys recovered from FUN_1800ced10 / FUN_18001acc0. */
object S5MegpoidPreResidualTensorKeys {
    const val DENSE_PROJECTION_WEIGHTS = "dd8f659ac91cbd5ef58ebd6ed786339e"
    /** Descriptor +0x08: per-feature multiplier. */
    const val NORMALIZATION_SCALE = "6121c88871cfb21dab889701aef1922a"
    /** Descriptor +0x18: per-feature offset. */
    const val NORMALIZATION_BIAS = "f860a37573c14d61e3a339d70eadd90f"
    const val NORMALIZATION_EPSILON = 0.000001f
}

/** Unique typed tensor reference resolved within the selected VTB2 subtree. */
class S5MegpoidPreResidualTensorReference internal constructor(
    val propertyName: String,
    val lookupKey: String,
    val objectPath: String,
    val marker: String,
    val elementCount: Int,
    private val encodedValue: S5ModelValue.StringValue
) {
    fun decodeFloatArray(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): FloatArray {
        val decoded = encodedValue.asFloatArray(tensorDecoder)
        check(decoded.size == elementCount) {
            "Decoded pre-residual tensor '${propertyName}' has an unexpected element count."
        }
        return decoded
    }
}

/**
 * Exact model tensors feeding the dense projection and global normalizer
 * called by FUN_1800c7ad0. Per-sequence Temporal and Banked properties are
 * indexed separately by S5MegpoidSequenceOperatorTensorIndex.
 */
data class S5MegpoidDenseProjectionDimensions(
    val inputFeatures: Int,
    val outputFeatures: Int
) {
    init {
        require(inputFeatures > 0 && outputFeatures > 0)
    }
}

class S5MegpoidPreResidualTensorIndex private constructor(
    val denseProjectionWeights: S5MegpoidPreResidualTensorReference,
    val normalizationScale: S5MegpoidPreResidualTensorReference,
    val normalizationBias: S5MegpoidPreResidualTensorReference
) {
    /**
     * Resolve output width from the actual normalization vectors, then verify
     * that the model's Dense weight count is exactly input * output.
     */
    fun resolveDenseProjectionDimensions(
        inputFeatures: Int
    ): S5MegpoidDenseProjectionDimensions {
        require(inputFeatures > 0) { "Dense input feature width must be positive." }
        val outputFeatures = normalizationScale.elementCount
        require(outputFeatures > 0) { "Dense normalization scale is empty." }
        require(normalizationBias.elementCount == outputFeatures) {
            "Dense normalization scale and bias have different feature counts."
        }
        require(denseProjectionWeights.elementCount.toLong() ==
            inputFeatures.toLong() * outputFeatures.toLong()) {
            "Dense tensor element count does not match input and normalization feature dimensions."
        }
        return S5MegpoidDenseProjectionDimensions(inputFeatures, outputFeatures)
    }

    /**
     * Derives both Dense dimensions from the actual selected model tensors.
     *
     * The normalization vectors define the output-feature count. The Dense
     * weight tensor is the row-major [outputFeature][inputFeature] matrix,
     * so its element count must divide evenly by that output width.
     */
    fun resolveDenseProjectionDimensionsFromModel(): S5MegpoidDenseProjectionDimensions {
        val outputFeatures = normalizationScale.elementCount
        require(outputFeatures > 0) {
            "Dense normalization scale is empty."
        }
        require(normalizationBias.elementCount == outputFeatures) {
            "Dense normalization scale and bias have different feature counts."
        }
        require(denseProjectionWeights.elementCount % outputFeatures == 0) {
            "Dense weight element count is not divisible by the model normalization width."
        }
        val inputFeatures = denseProjectionWeights.elementCount / outputFeatures
        require(inputFeatures > 0) {
            "Dense model weights resolve to an empty input-feature width."
        }
        return S5MegpoidDenseProjectionDimensions(
            inputFeatures = inputFeatures,
            outputFeatures = outputFeatures
        )
    }

    /** Builds the first model-backed Dense + normalization stage without guessed dimensions. */
    fun buildDenseNormalizationStageFromModel(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseNormalizationStage {
        val dimensions = resolveDenseProjectionDimensionsFromModel()
        return buildDenseNormalizationStage(
            inputFeatures = dimensions.inputFeatures,
            outputFeatures = dimensions.outputFeatures,
            tensorDecoder = tensorDecoder
        )
    }

    fun buildDenseNormalizationStageForInputFeatures(
        inputFeatures: Int,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseNormalizationStage {
        val dimensions = resolveDenseProjectionDimensions(inputFeatures)
        return buildDenseNormalizationStage(
            inputFeatures = dimensions.inputFeatures,
            outputFeatures = dimensions.outputFeatures,
            tensorDecoder = tensorDecoder
        )
    }
    /**
     * Builds the model-backed dense + global-normalization stage.
     * Feature dimensions must be supplied from the active render descriptor.
     */
    fun buildDenseNormalizationStage(
        inputFeatures: Int,
        outputFeatures: Int,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidDenseNormalizationStage {
        require(inputFeatures > 0 && outputFeatures > 0)
        val weightCount = checkedProduct(inputFeatures, outputFeatures)
        require(denseProjectionWeights.elementCount == weightCount) {
            "Dense tensor has ${denseProjectionWeights.elementCount} values; descriptor dimensions require ${weightCount}."
        }
        require(normalizationScale.elementCount == outputFeatures) {
            "Normalization scale must contain one value per dense output feature."
        }
        require(normalizationBias.elementCount == outputFeatures) {
            "Normalization bias must contain one value per dense output feature."
        }

        return S5MegpoidDenseNormalizationStage(
            projection = S5MegpoidDenseProjection(
                inputFeatures = inputFeatures,
                outputFeatures = outputFeatures,
                weights = denseProjectionWeights.decodeFloatArray(tensorDecoder),
                bias = null
            ),
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = outputFeatures,
                scale = normalizationScale.decodeFloatArray(tensorDecoder),
                bias = normalizationBias.decodeFloatArray(tensorDecoder),
                epsilon = S5MegpoidPreResidualTensorKeys.NORMALIZATION_EPSILON
            )
        )
    }

    companion object {
        fun build(modelTree: S5ModelObjectTree): S5MegpoidPreResidualTensorIndex {
            fun readTensor(
                propertyName: String,
                lookupKey: String
            ): S5MegpoidPreResidualTensorReference {
                val match = modelTree.findUniqueByLookupKeyRecursive(lookupKey)
                    ?: throw IllegalArgumentException(
                        "Pre-residual property '${propertyName}' (key ${lookupKey}) is missing."
                    )
                val value = match.value as? S5ModelValue.StringValue
                    ?: throw IllegalArgumentException(
                        "Pre-residual property '${propertyName}' at ${match.path} is not a typed string."
                    )
                val marker = value.value.take(4)
                require(marker == "*F32" || marker == "*F16") {
                    "Pre-residual property '${propertyName}' is not an *F32 or *F16 tensor."
                }
                val payload = value.value.substring(4)
                val byteCount = try {
                    Base64.getDecoder().decode(payload).size
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Pre-residual property '${propertyName}' has malformed Base64 data.",
                        error
                    )
                }
                val scalarBytes = if (marker == "*F16") 2 else 4
                require(byteCount > 0 && byteCount % scalarBytes == 0) {
                    "Pre-residual property '${propertyName}' is empty or misaligned."
                }
                return S5MegpoidPreResidualTensorReference(
                    propertyName = propertyName,
                    lookupKey = lookupKey,
                    objectPath = match.path,
                    marker = marker,
                    elementCount = byteCount / scalarBytes,
                    encodedValue = value
                )
            }

            return S5MegpoidPreResidualTensorIndex(
                denseProjectionWeights = readTensor(
                    "dense projection weights",
                    S5MegpoidPreResidualTensorKeys.DENSE_PROJECTION_WEIGHTS
                ),
                normalizationScale = readTensor(
                    "global normalization scale",
                    S5MegpoidPreResidualTensorKeys.NORMALIZATION_SCALE
                ),
                normalizationBias = readTensor(
                    "global normalization bias",
                    S5MegpoidPreResidualTensorKeys.NORMALIZATION_BIAS
                )
            )
        }

        private fun checkedProduct(a: Int, b: Int): Int {
            val product = a.toLong() * b.toLong()
            require(product in 1..Int.MAX_VALUE.toLong()) {
                "Dense tensor size is invalid or exceeds the Kotlin array limit."
            }
            return product.toInt()
        }
    }
}
