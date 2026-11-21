package com.kmj.vea.engine

/**
 * Checked tensor-size contract for the recovered S5API 7x7 residual depthwise/MLP block.
 *
 * C is the block channel count, E is the expansion multiplier, and N is the
 * number of frame positions. FUN_1800d4bf0 allocates the expansion matrix as
 * C * E * C and its two expanded-feature affine arrays as C * E.
 */
data class S5MegpoidResidualBlockShape(
    val channels: Int,
    val expansionMultiplier: Int,
    val positions: Int
) {
    init {
        require(channels > 0) { "Channel count must be positive." }
        require(expansionMultiplier > 0) { "Expansion multiplier must be positive." }
        require(positions >= KERNEL_WIDTH) {
            "At least $KERNEL_WIDTH positions are required by the 7x7 history window."
        }
    }

    val expandedFeatures: Int
        get() = checkedProduct(channels, expansionMultiplier)

    /** Input/history layout: [channel][7][position]. */
    val historyElementCount: Int
        get() = checkedProduct(channels, KERNEL_WIDTH, positions)

    /** Depthwise filter layout: [channel][7][7]. */
    val depthwiseWeightElementCount: Int
        get() = checkedProduct(channels, KERNEL_WIDTH, KERNEL_WIDTH)

    /** Expansion projection layout: [expandedFeature][channel]. */
    val expansionWeightElementCount: Int
        get() = checkedProduct(expandedFeatures, channels)

    /** Projection layout: [channel][expandedFeature]. */
    val projectionWeightElementCount: Int
        get() = checkedProduct(channels, expandedFeatures)

    /** The two normalization/GELU affine arrays each use C * E values. */
    val expandedAffineElementCount: Int
        get() = expandedFeatures

    /** Residual input/output layout: [channel][position]. */
    val residualElementCount: Int
        get() = checkedProduct(channels, positions)

    val residualVectorElementCount: Int
        get() = channels

    fun validateInputs(
        historyRows: FloatArray,
        depthwiseWeights: FloatArray,
        expandWeights: FloatArray,
        expandedBiasA: FloatArray,
        expandedBiasB: FloatArray,
        projectWeights: FloatArray,
        residualInput: FloatArray,
        residualScale: FloatArray,
        residualBias: FloatArray?,
        epsilon: Float
    ) {
        require(historyRows.size == historyElementCount) {
            "History input must contain $historyElementCount values."
        }
        require(depthwiseWeights.size == depthwiseWeightElementCount) {
            "Depthwise weights must contain $depthwiseWeightElementCount values."
        }
        require(expandWeights.size == expansionWeightElementCount) {
            "Expansion weights must contain $expansionWeightElementCount values."
        }
        require(expandedBiasA.size == expandedAffineElementCount) {
            "Expanded-feature gamma must contain $expandedAffineElementCount values."
        }
        require(expandedBiasB.size == expandedAffineElementCount) {
            "Expanded-feature beta must contain $expandedAffineElementCount values."
        }
        require(projectWeights.size == projectionWeightElementCount) {
            "Projection weights must contain $projectionWeightElementCount values."
        }
        require(residualInput.size == residualElementCount) {
            "Residual input must contain $residualElementCount values."
        }
        require(residualScale.size == residualVectorElementCount) {
            "Residual scale must contain $residualVectorElementCount values."
        }
        require(residualBias == null || residualBias.size == residualVectorElementCount) {
            "Residual bias must be null or contain $residualVectorElementCount values."
        }
        require(epsilon.isFinite() && epsilon >= 0.0f) {
            "Epsilon must be a finite non-negative value."
        }
    }

    private fun checkedProduct(vararg factors: Int): Int {
        var product = 1L
        for (factor in factors) {
            require(factor > 0) { "Tensor dimensions must be positive." }
            require(product <= Int.MAX_VALUE.toLong() / factor.toLong()) {
                "Residual block tensor size exceeds the supported Kotlin array limit."
            }
            product *= factor.toLong()
        }
        return product.toInt()
    }

    companion object {
        const val KERNEL_WIDTH = 7
        const val DEFAULT_EPSILON = 1.0e-6f
    }
}
