package com.kmj.vea.engine

/**
 * Active AI Megpoid V7 32-to-8 post-residual output projection.
 *
 * FUN_1800ced10 folds the four shared VTB2 tensors into three render buffers:
 *   foldedWeight[o,i] = denseWeight[o,i] * inputScale[i]
 *   foldedBias[o]      = denseBias[o] + sum_i(denseWeight[o,i] * inputBias[i])
 *   foldedScale[o]     = sum_i(foldedWeight[o,i])
 *
 * FUN_1800c7ad0 computes one global mean/inverse-standard-deviation over the
 * complete [inputFeature][position] tensor, projects it through foldedWeight,
 * and applies:
 *   output[o,p] = inverseStd * projected[o,p]
 *                 + (foldedBias[o] - inverseStd * mean * foldedScale[o])
 *
 * The descriptor tensors are shared with the non-multipart fallback branch,
 * but the active D00=4/D01=true path uses this separately folded matrix and
 * eight output features. It must not call the fallback runtime descriptor at
 * +0xCE; this class builds the active +0x150/+0x154/+0x158 equivalent.
 */
class S5MegpoidActiveMultipartProjectionStage internal constructor(
    val inputFeatures: Int,
    val outputFeatures: Int,
    foldedWeights: FloatArray,
    foldedBias: FloatArray,
    foldedScale: FloatArray,
    val epsilon: Float
) {
    private val foldedWeights = foldedWeights.copyOf()
    private val foldedBias = foldedBias.copyOf()
    private val foldedScale = foldedScale.copyOf()

    init {
        require(inputFeatures > 0 && outputFeatures > 0)
        require(this.foldedWeights.size.toLong() ==
            inputFeatures.toLong() * outputFeatures.toLong()) {
            "Folded active output weights must contain outputFeatures * inputFeatures values."
        }
        require(this.foldedBias.size == outputFeatures &&
            this.foldedScale.size == outputFeatures) {
            "Folded active output bias and scale must contain one value per output feature."
        }
        require(this.foldedWeights.all { it.isFinite() } &&
            this.foldedBias.all { it.isFinite() } &&
            this.foldedScale.all { it.isFinite() }) {
            "Folded active output parameters contain a non-finite value."
        }
        require(epsilon.isFinite() && epsilon >= 0f)
    }

    /**
     * Input is feature-major [inputFeature][position]. Output is
     * feature-major [outputFeature][position] and contains D00*2 rows
     * (eight rows for the installed AI Megpoid V7 options).
     */
    fun forward(
        inputFeatureMajor: FloatArray,
        positions: Int,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): FloatArray {
        require(positions > 0)
        val expectedInput = checkedProduct(inputFeatures, positions)
        val expectedOutput = checkedProduct(outputFeatures, positions)
        require(inputFeatureMajor.size == expectedInput) {
            "Active multipart projection input must contain exactly $expectedInput values."
        }
        require(inputFeatureMajor.all { it.isFinite() }) {
            "Active multipart projection input contains a non-finite value."
        }

        val (sum, sumSquares) = statistics.reduceSumAndSquares(inputFeatureMajor)
        val (mean, inverseStd) = statistics.computeMeanAndInverseStd(
            sum = sum,
            sumSquares = sumSquares,
            count = expectedInput,
            epsilon = epsilon
        )
        require(mean.isFinite() && inverseStd.isFinite()) {
            "Active multipart projection statistics are non-finite."
        }

        val projected = gemm.multiply(
            a = foldedWeights,
            b = inputFeatureMajor,
            c = FloatArray(expectedOutput),
            m = outputFeatures,
            n = positions,
            k = inputFeatures
        )
        require(projected.size == expectedOutput && projected.all { it.isFinite() }) {
            "Active multipart GEMM returned an invalid output tensor."
        }

        val output = FloatArray(expectedOutput)
        for (feature in 0 until outputFeatures) {
            // Preserve FUN_1800c7ad0 order: (-invStd * mean) * rowScale + rowBias.
            val dynamicOffset =
                (-inverseStd * mean) * foldedScale[feature] + foldedBias[feature]
            val rowOffset = feature * positions
            for (position in 0 until positions) {
                output[rowOffset + position] =
                    inverseStd * projected[rowOffset + position] + dynamicOffset
            }
        }
        require(output.all { it.isFinite() }) {
            "Active multipart projection produced a non-finite feature tensor."
        }
        return output
    }

    fun copyFoldedWeights(): FloatArray = foldedWeights.copyOf()
    fun copyFoldedBias(): FloatArray = foldedBias.copyOf()
    fun copyFoldedScale(): FloatArray = foldedScale.copyOf()

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Active multipart output tensor size is invalid or exceeds the FloatArray limit."
        }
        return product.toInt()
    }

    companion object {
        fun build(
            inputFeatures: Int,
            outputFeatures: Int,
            denseWeights: FloatArray,
            denseBias: FloatArray,
            inputScale: FloatArray,
            inputBias: FloatArray,
            epsilon: Float = S5MegpoidPostResidualFallbackTensorKeys.NORMALIZATION_EPSILON
        ): S5MegpoidActiveMultipartProjectionStage {
            require(inputFeatures > 0 && outputFeatures > 0)
            require(denseWeights.size.toLong() ==
                inputFeatures.toLong() * outputFeatures.toLong())
            require(denseBias.size == outputFeatures)
            require(inputScale.size == inputFeatures && inputBias.size == inputFeatures)
            require(denseWeights.all { it.isFinite() } &&
                denseBias.all { it.isFinite() } &&
                inputScale.all { it.isFinite() } &&
                inputBias.all { it.isFinite() })

            val foldedWeights = FloatArray(denseWeights.size)
            val foldedBias = FloatArray(outputFeatures)
            val foldedScale = FloatArray(outputFeatures)

            for (outputFeature in 0 until outputFeatures) {
                val weightOffset = outputFeature * inputFeatures
                var biasAccumulator = 0f
                var scaleAccumulator = 0f
                for (inputFeature in 0 until inputFeatures) {
                    val index = weightOffset + inputFeature
                    val weight = denseWeights[index]
                    val scaledWeight = weight * inputScale[inputFeature]
                    foldedWeights[index] = scaledWeight
                    biasAccumulator += weight * inputBias[inputFeature]
                    scaleAccumulator += scaledWeight
                }
                // This matches the native initializer: model bias is added
                // after the dot product of Dense weights and input bias.
                foldedBias[outputFeature] = biasAccumulator + denseBias[outputFeature]
                foldedScale[outputFeature] = scaleAccumulator
            }

            return S5MegpoidActiveMultipartProjectionStage(
                inputFeatures = inputFeatures,
                outputFeatures = outputFeatures,
                foldedWeights = foldedWeights,
                foldedBias = foldedBias,
                foldedScale = foldedScale,
                epsilon = epsilon
            )
        }
    }
}
