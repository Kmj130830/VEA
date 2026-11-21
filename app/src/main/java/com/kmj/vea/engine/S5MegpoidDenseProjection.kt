package com.kmj.vea.engine

/** Row-major GEMM kernel contract used by the recovered c5260 operator. */
fun interface S5MegpoidGemmKernel {
    /**
     * Computes C = A * B with A=[M,K], B=[K,N], C=[M,N].
     * The returned array must contain exactly M*N values.
     */
    fun multiply(
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        m: Int,
        n: Int,
        k: Int
    ): FloatArray
}

/** Production backend: the portable native row-major GEMM already in VEA. */
object NativeS5MegpoidGemmKernel : S5MegpoidGemmKernel {
    override fun multiply(
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        m: Int,
        n: Int,
        k: Int
    ): FloatArray = NativeS5TensorBridge.gemmRowMajor(
        a = a,
        b = b,
        c = c,
        m = m,
        n = n,
        k = k,
        alpha = 1.0f,
        lda = k,
        ldb = n,
        beta = 0.0f,
        ldc = n
    )
}

/** Scalar reference used to verify tensor orientation independently of JNI. */
object ReferenceS5MegpoidGemmKernel : S5MegpoidGemmKernel {
    override fun multiply(
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        m: Int,
        n: Int,
        k: Int
    ): FloatArray {
        require(m > 0 && n > 0 && k > 0)
        require(a.size == checkedProduct(m, k))
        require(b.size == checkedProduct(k, n))
        require(c.size == checkedProduct(m, n))
        for (row in 0 until m) {
            for (column in 0 until n) {
                var sum = 0.0f
                for (inner in 0 until k) {
                    sum += a[row * k + inner] * b[inner * n + column]
                }
                c[row * n + column] = sum
            }
        }
        return c
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        require(a > 0 && b > 0)
        val value = a.toLong() * b.toLong()
        require(value <= Int.MAX_VALUE)
        return value.toInt()
    }
}

/**
 * Recovered FUN_1800c5260 dense projection:
 *
 *   output[M,N] = weights[M,K] * input[K,N]
 *   output[m,n] += bias[m]    (when the descriptor enables bias)
 *
 * This is a generic operator primitive. It does not assign any of the nine
 * VTMG named layers to this call site; that model-object-to-runtime-descriptor
 * association remains a separate trace.
 */
class S5MegpoidDenseProjection(
    val inputFeatures: Int,
    val outputFeatures: Int,
    weights: FloatArray,
    bias: FloatArray? = null
) {
    private val weights = weights.copyOf()
    private val bias = bias?.copyOf()

    init {
        require(inputFeatures > 0 && outputFeatures > 0)
        require(this.weights.size == checkedProduct(outputFeatures, inputFeatures)) {
            "Dense weight matrix must contain outputFeatures * inputFeatures values."
        }
        require(this.bias == null || this.bias.size == outputFeatures) {
            "Dense bias must be null or contain one value per output feature."
        }
    }

    /**
     * Input layout is [inputFeature][position]; output layout is
     * [outputFeature][position].
     */
    fun project(
        input: FloatArray,
        positions: Int,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(positions > 0) { "Position count must be positive." }
        val expectedInput = checkedProduct(inputFeatures, positions)
        val expectedOutput = checkedProduct(outputFeatures, positions)
        require(input.size == expectedInput) {
            "Dense projection input must contain $expectedInput values."
        }

        val output = gemm.multiply(
            a = weights,
            b = input,
            c = FloatArray(expectedOutput),
            m = outputFeatures,
            n = positions,
            k = inputFeatures
        )
        require(output.size == expectedOutput) {
            "GEMM backend returned \${output.size} values; expected $expectedOutput."
        }

        val biasValues = bias
        if (biasValues != null) {
            for (feature in 0 until outputFeatures) {
                val offset = feature * positions
                val value = biasValues[feature]
                for (position in 0 until positions) {
                    output[offset + position] += value
                }
            }
        }
        return output
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Dense projection tensor size is invalid or exceeds the Kotlin array limit."
        }
        return product.toInt()
    }
}
