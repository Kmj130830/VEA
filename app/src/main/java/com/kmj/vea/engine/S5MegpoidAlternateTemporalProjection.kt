package com.kmj.vea.engine

/**
 * Ports the scalar semantics of S5API FUN_1800c72d0.
 *
 * Input is channel-major [inputFeature][position]. The operator first
 * interpolates every input position to two output positions, using 0.75 of
 * the current sample and 0.25 of the adjacent sample. It then builds
 * zero-padded [left, centre, right] windows for each input channel and
 * performs one row-major GEMM:
 *
 *   weights[outputFeature][inputFeature * 3]
 *   windows[inputFeature * 3][position * 2]
 *   output[outputFeature][position * 2]
 *
 * The model-to-descriptor loader is responsible for supplying weights in
 * that exact row-major order. This class deliberately does not transpose
 * model tensor payloads.
 */
class S5MegpoidAlternateTemporalProjection(
    val inputFeatures: Int,
    val outputFeatures: Int,
    weights: FloatArray,
    bias: FloatArray
) {
    private val weights = weights.copyOf()
    private val bias = bias.copyOf()
    val kernelSize: Int = 3
    val flattenedInputFeatures: Int = checkedProduct(inputFeatures, kernelSize)

    init {
        require(inputFeatures > 0 && outputFeatures > 0) {
            "Alternate transition feature dimensions must be positive."
        }
        require(this.weights.size == checkedProduct(outputFeatures, flattenedInputFeatures)) {
            "Alternate transition weights must contain outputFeatures * inputFeatures * 3 values."
        }
        require(this.bias.size == outputFeatures) {
            "Alternate transition bias must contain one value per output feature."
        }
        require(this.weights.all { it.isFinite() } && this.bias.all { it.isFinite() }) {
            "Alternate transition parameters contain a non-finite value."
        }
    }

    /** Native FUN_1800c72d0 doubles the sequence length. */
    fun outputPositions(inputPositions: Int): Int =
        checkedProduct(inputPositions, 2)

    /**
     * Input layout: [inputFeature][inputPosition].
     * Output layout: [outputFeature][inputPosition * 2].
     */
    fun project(
        input: FloatArray,
        inputPositions: Int,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(inputPositions > 0) { "Alternate transition requires at least one input position." }
        val expectedInput = checkedProduct(inputFeatures, inputPositions)
        require(input.size == expectedInput) {
            "Alternate transition input must contain $expectedInput values."
        }
        require(input.all { it.isFinite() }) {
            "Alternate transition input contains a non-finite value."
        }

        val outputPositionCount = outputPositions(inputPositions)
        val expandedInput = FloatArray(checkedProduct(inputFeatures, outputPositionCount))

        // Recovered interpolation:
        // even = .75 * x[j] + .25 * x[max(j-1, 0)]
        // odd  = .75 * x[j] + .25 * x[min(j+1, N-1)]
        for (feature in 0 until inputFeatures) {
            val sourceOffset = feature * inputPositions
            val outputOffset = feature * outputPositionCount
            for (position in 0 until inputPositions) {
                val current = input[sourceOffset + position]
                val previous = input[sourceOffset + (position - 1).coerceAtLeast(0)]
                val next = input[sourceOffset + (position + 1).coerceAtMost(inputPositions - 1)]
                expandedInput[outputOffset + position * 2] =
                    current * 0.75f + previous * 0.25f
                expandedInput[outputOffset + position * 2 + 1] =
                    current * 0.75f + next * 0.25f
            }
        }

        // Three contiguous feature rows per input channel: left, centre, right.
        // At each sequence boundary, the missing neighbouring value is zero.
        val windowCount = checkedProduct(flattenedInputFeatures, outputPositionCount)
        val windows = FloatArray(windowCount)
        for (feature in 0 until inputFeatures) {
            val sourceOffset = feature * outputPositionCount
            for (position in 0 until outputPositionCount) {
                val centre = expandedInput[sourceOffset + position]
                val left = if (position == 0) 0.0f
                    else expandedInput[sourceOffset + position - 1]
                val right = if (position == outputPositionCount - 1) 0.0f
                    else expandedInput[sourceOffset + position + 1]

                windows[(feature * kernelSize) * outputPositionCount + position] = left
                windows[(feature * kernelSize + 1) * outputPositionCount + position] = centre
                windows[(feature * kernelSize + 2) * outputPositionCount + position] = right
            }
        }

        val outputCount = checkedProduct(outputFeatures, outputPositionCount)
        val output = gemm.multiply(
            a = weights,
            b = windows,
            c = FloatArray(outputCount),
            m = outputFeatures,
            n = outputPositionCount,
            k = flattenedInputFeatures
        )
        require(output.size == outputCount) {
            "GEMM backend returned ${output.size} values; expected $outputCount."
        }
        require(output.all { it.isFinite() }) {
            "Alternate transition GEMM returned a non-finite value."
        }

        // FUN_1800c72d0 unconditionally adds one bias per output feature.
        for (feature in 0 until outputFeatures) {
            val offset = feature * outputPositionCount
            val value = bias[feature]
            for (position in 0 until outputPositionCount) {
                output[offset + position] += value
            }
        }
        return output
    }

    fun copyWeights(): FloatArray = weights.copyOf()
    fun copyBias(): FloatArray = bias.copyOf()

    private fun checkedProduct(a: Int, b: Int): Int {
        require(a > 0 && b > 0) {
            "Alternate transition tensor dimensions must be positive."
        }
        val product = a.toLong() * b.toLong()
        require(product <= Int.MAX_VALUE) {
            "Alternate transition tensor exceeds the Kotlin array limit."
        }
        return product.toInt()
    }
}
