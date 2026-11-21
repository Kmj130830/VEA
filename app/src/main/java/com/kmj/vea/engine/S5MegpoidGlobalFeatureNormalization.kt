package com.kmj.vea.engine

/** Statistical primitives needed by the recovered c7920 normalizer. */
interface S5MegpoidStatisticsKernel {
    fun reduceSumAndSquares(values: FloatArray): Pair<Float, Float>

    /** Returns mean and inverse standard deviation in that order. */
    fun computeMeanAndInverseStd(
        sum: Float,
        sumSquares: Float,
        count: Int,
        epsilon: Float
    ): Pair<Float, Float>
}

/** Production backend reusing the already-portable S5 statistics kernels. */
object NativeS5MegpoidStatisticsKernel : S5MegpoidStatisticsKernel {
    override fun reduceSumAndSquares(values: FloatArray): Pair<Float, Float> =
        NativeS5TensorBridge.reduceSumAndSquares(values)

    override fun computeMeanAndInverseStd(
        sum: Float,
        sumSquares: Float,
        count: Int,
        epsilon: Float
    ): Pair<Float, Float> =
        NativeS5TensorBridge.computeMeanAndInverseStd(sum, sumSquares, count, epsilon)
}

/** Deterministic scalar reference for unit tests and cross-checks. */
object ReferenceS5MegpoidStatisticsKernel : S5MegpoidStatisticsKernel {
    override fun reduceSumAndSquares(values: FloatArray): Pair<Float, Float> {
        require(values.isNotEmpty())
        var sum = 0.0f
        var sumSquares = 0.0f
        for (value in values) {
            sum += value
            sumSquares += value * value
        }
        return sum to sumSquares
    }

    override fun computeMeanAndInverseStd(
        sum: Float,
        sumSquares: Float,
        count: Int,
        epsilon: Float
    ): Pair<Float, Float> {
        require(count > 0 && epsilon.isFinite() && epsilon >= 0.0f)
        val countFloat = count.toFloat()
        val mean = sum / countFloat
        var variance = sumSquares / countFloat - mean * mean
        if (variance <= 0.0f) variance = 0.0f
        variance += epsilon
        val inverseStd = 1.0f / kotlin.math.sqrt(variance)
        return mean to inverseStd
    }
}

/**
 * Global C*N statistics followed by per-feature scale and bias, matching
 * FUN_1800c7920:
 *
 *   mean = average(input over every feature and position)
 *   invStd = 1 / sqrt(max(E[x*x] - mean*mean, 0) + epsilon)
 *   output[c,n] = (input[c,n] - mean) * invStd * scale[c] + bias[c]
 *
 * The source code's final expression is evaluated as
 * scaleFactor * input + (bias - scaleFactor * mean), preserving its
 * float32 operation order.
 */
class S5MegpoidGlobalFeatureNormalization(
    val features: Int,
    scale: FloatArray,
    bias: FloatArray,
    val epsilon: Float
) {
    private val scale = scale.copyOf()
    private val bias = bias.copyOf()

    init {
        require(features > 0)
        require(this.scale.size == features && this.bias.size == features) {
            "Normalization scale and bias must each contain one value per feature."
        }
        require(epsilon.isFinite() && epsilon >= 0.0f)
    }

    /**
     * Input/output layout is [feature][position]. Statistics are global across
     * the entire feature-position tensor; affine arrays are indexed by feature.
     */
    fun apply(
        input: FloatArray,
        positions: Int,
        statistics: S5MegpoidStatisticsKernel = NativeS5MegpoidStatisticsKernel
    ): FloatArray {
        val elementCount = checkedProduct(features, positions)
        require(input.size == elementCount) {
            "Normalization input must contain $elementCount values."
        }
        val (sum, sumSquares) = statistics.reduceSumAndSquares(input)
        val (mean, inverseStd) = statistics.computeMeanAndInverseStd(
            sum = sum,
            sumSquares = sumSquares,
            count = elementCount,
            epsilon = epsilon
        )

        val output = FloatArray(elementCount)
        for (feature in 0 until features) {
            val scaleFactor = inverseStd * scale[feature]
            val offset = bias[feature] - scaleFactor * mean
            val rowOffset = feature * positions
            for (position in 0 until positions) {
                output[rowOffset + position] =
                    scaleFactor * input[rowOffset + position] + offset
            }
        }
        return output
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Normalization tensor size is invalid or exceeds the Kotlin array limit."
        }
        return product.toInt()
    }
}
