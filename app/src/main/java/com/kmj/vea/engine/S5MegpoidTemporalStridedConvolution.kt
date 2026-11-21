package com.kmj.vea.engine

/**
 * Recovered FUN_1800c5440 temporal projection.
 *
 * The native routine materializes a stride-2 temporal window with zero padding,
 * lays it out as [inputFeature * kernelTap][outputPosition], and applies the
 * descriptor's row-major weight matrix with GEMM. It then broadcasts the
 * optional output-feature bias over every output position.
 *
 * This class ports that operator contract. It does not claim that a particular
 * VTMG named layer is the descriptor used at any given callsite.
 */
class S5MegpoidTemporalStridedConvolution(
    val inputFeatures: Int,
    val outputFeatures: Int,
    val kernelSize: Int,
    weights: FloatArray,
    bias: FloatArray? = null
) {
    private val weights = weights.copyOf()
    private val bias = bias?.copyOf()
    val flattenedInputFeatures: Int

    init {
        require(inputFeatures > 0 && outputFeatures > 0 && kernelSize > 0)
        flattenedInputFeatures = checkedProduct(inputFeatures, kernelSize)
        require(this.weights.size == checkedProduct(outputFeatures, flattenedInputFeatures)) {
            "Temporal convolution weights must contain outputFeatures * inputFeatures * kernelSize values."
        }
        require(this.bias == null || this.bias.size == outputFeatures) {
            "Temporal convolution bias must be null or contain one value per output feature."
        }
    }

    /**
     * Number of output positions for the native stride-2/padding convention:
     * floor((N + 2*floor(K/2) - K) / 2) + 1.
     */
    fun outputPositions(inputPositions: Int): Int {
        require(inputPositions > 0)
        val leftPadding = kernelSize / 2
        val numerator = inputPositions.toLong() + 2L * leftPadding - kernelSize
        val result = numerator / 2L + 1L
        require(result > 0L && result <= Int.MAX_VALUE) {
            "Temporal convolution has no output positions or its output size overflows."
        }
        return result.toInt()
    }

    /**
     * Input: [inputFeature][inputPosition].
     * Output: [outputFeature][outputPosition].
     *
     * The convolution is centered with left padding floor(kernelSize/2),
     * samples input positions at stride 2, and inserts zero for out-of-range
     * taps. The generated column matrix is consumed by row-major GEMM.
     */
    fun project(
        input: FloatArray,
        inputPositions: Int,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(inputPositions > 0)
        val expectedInput = checkedProduct(inputFeatures, inputPositions)
        require(input.size == expectedInput) {
            "Temporal convolution input must contain $expectedInput values."
        }
        val outputPositionCount = outputPositions(inputPositions)
        val columnCount = checkedProduct(flattenedInputFeatures, outputPositionCount)
        val columns = FloatArray(columnCount)
        val leftPadding = kernelSize / 2

        for (feature in 0 until inputFeatures) {
            for (tap in 0 until kernelSize) {
                val columnRow = feature * kernelSize + tap
                val rowOffset = columnRow * outputPositionCount
                for (position in 0 until outputPositionCount) {
                    val sourcePosition =
                        position.toLong() * 2L - leftPadding.toLong() + tap.toLong()
                    if (sourcePosition >= 0L && sourcePosition < inputPositions.toLong()) {
                        columns[rowOffset + position] =
                            input[feature * inputPositions + sourcePosition.toInt()]
                    }
                }
            }
        }

        val outputCount = checkedProduct(outputFeatures, outputPositionCount)
        val output = gemm.multiply(
            a = weights,
            b = columns,
            c = FloatArray(outputCount),
            m = outputFeatures,
            n = outputPositionCount,
            k = flattenedInputFeatures
        )
        require(output.size == outputCount) {
            "GEMM backend returned \${output.size} values; expected $outputCount."
        }

        val biasValues = bias
        if (biasValues != null) {
            for (feature in 0 until outputFeatures) {
                val offset = feature * outputPositionCount
                val value = biasValues[feature]
                for (position in 0 until outputPositionCount) {
                    output[offset + position] += value
                }
            }
        }
        return output
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Temporal convolution tensor size is invalid or exceeds the Kotlin array limit."
        }
        return product.toInt()
    }
}
