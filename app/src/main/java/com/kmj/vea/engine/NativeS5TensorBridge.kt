package com.kmj.vea.engine

data class S5ContainerHeaderInfo(
    val kind: String,
    val payloadSize: Long,
    val formatWord: Long
)

/**
 * Native implementation of the recovered S5API tensor and container boundary.
 *
 * This is not a VOCALOID neural renderer. It validates the fixed outer header
 * and packs already-decoded tensor values into the recovered matrix layout.
 */
object NativeS5TensorBridge {
    private val loaded: Boolean = runCatching {
        System.loadLibrary("veas5tensor")
        true
    }.getOrDefault(false)

    val isLoaded: Boolean
        get() = loaded

    val abiVersion: Int
        get() = if (loaded) nativeAbiVersion() else 0

    /** Parses exactly the first 136 bytes; the opaque header bytes are preserved natively but not interpreted. */
    fun parseContainerHeader(header: ByteArray, totalFileSize: Long): S5ContainerHeaderInfo? {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(header.size == 136) { "The V7 container header is exactly 136 bytes." }
        require(totalFileSize >= 136L)
        val fields = nativeParseContainerHeader(header, totalFileSize) ?: return null
        if (fields.size != 3) return null
        val kind = when (fields[0].toInt()) {
            1 -> "VTBR"
            2 -> "VPIT"
            3 -> "VHNV"
            4 -> "VTMG"
            else -> return null
        }
        return S5ContainerHeaderInfo(kind, fields[1], fields[2])
    }

    /**
     * Returns matrices concatenated in matrix-index order. Each matrix is
     * axis1Count rows by alignUp(axis0Count, 8) columns. The unused padded
     * columns are zero-filled.
     *
     * Input is shaped [axis0][axis1][matrix], with matrix as the innermost axis.
     */
    fun packMatrixBank(
        interleavedValues: FloatArray,
        axis0Count: Int,
        axis1Count: Int,
        matrixCount: Int
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(axis0Count > 0 && axis1Count > 0 && matrixCount > 0)
        val expected = axis0Count.toLong() * axis1Count.toLong() * matrixCount.toLong()
        require(expected == interleavedValues.size.toLong()) {
            "Expected $expected values, received ${interleavedValues.size}."
        }
        return nativePackMatrixBank(
            interleavedValues,
            axis0Count,
            axis1Count,
            matrixCount
        ) ?: throw IllegalStateException("S5 matrix packing failed.")
    }


    /**
     * Decodes an S5API *F32 or *F16 Base64-tagged tensor string to FloatArray.
     * String is restricted to ASCII, as required by the binary encoding.
     */
    fun decodeTypedFloatArray(encoded: String): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(encoded.length >= 4 && encoded.all { it.code in 0x00..0x7f }) {
            "Typed S5 tensor data must be ASCII."
        }
        return nativeDecodeTypedFloatArray(encoded.toByteArray(Charsets.US_ASCII))
            ?: throw IllegalArgumentException("Malformed or unsupported S5 float tensor.")
    }

    /** Decodes an S5API *S32 Base64-tagged tensor string to IntArray. */
    fun decodeTypedInt32Array(encoded: String): IntArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(encoded.length >= 4 && encoded.all { it.code in 0x00..0x7f }) {
            "Typed S5 tensor data must be ASCII."
        }
        return nativeDecodeTypedInt32Array(encoded.toByteArray(Charsets.US_ASCII))
            ?: throw IllegalArgumentException("Malformed or unsupported S5 int32 tensor.")
    }


    /**
     * Applies the recovered S5API two-plane ACT operator.
     * Input is [valuePlane(count), gatePlane(count)]; both are clamped to [-8, 8].
     * The returned first plane contains the activation result; the second is clamped input.
     */
    fun applyGatedActivation(planes: FloatArray, count: Int, mode: Int = 0): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(count > 0 && count.toLong() * 2L == planes.size.toLong())
        return nativeApplyGatedActivation(planes, count, mode)
            ?: throw IllegalStateException("S5 ACT activation failed.")
    }


    /**
     * Applies the recovered 7x7 depthwise-convolution scalar kernel.
     * inputRows contains seven consecutive row-major rows, each exactly width samples.
     * weights contains 49 row-major coefficients. The result contains width samples.
     */
    fun depthwiseConv7x7Reflect(inputRows: FloatArray, weights: FloatArray, width: Int): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(width >= 7)
        require(width.toLong() * 7L == inputRows.size.toLong()) {
            "Expected " + (width.toLong() * 7L) + " input samples, received " + inputRows.size + "."
        }
        require(weights.size == 49) { "A 7x7 depthwise kernel requires 49 weights." }
        return nativeDepthwiseConv7x7Reflect(inputRows, weights, width)
            ?: throw IllegalStateException("S5 7x7 depthwise convolution failed.")
    }


    /**
     * Applies S5API FUN_1800d8720: dst = (dst + bias) * scale + residual.
     * Both arrays must have the same non-zero length.
     */
    fun residualAffineAdd(
        dst: FloatArray,
        residual: FloatArray,
        scale: Float,
        bias: Float
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(dst.isNotEmpty() && dst.size == residual.size)
        return nativeResidualAffineAdd(dst, residual, scale, bias)
            ?: throw IllegalStateException("S5 residual affine operation failed.")
    }


    /** Returns [sum, sumSquares] for the input vector (MRW_LNSTATS primitive). */
    fun reduceSumAndSquares(values: FloatArray): Pair<Float, Float> {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(values.isNotEmpty())
        val result = nativeReduceSumAndSquares(values)
            ?: throw IllegalStateException("S5 stats reduction failed.")
        check(result.size == 2)
        return result[0] to result[1]
    }

    private external fun nativeDepthwiseConv7x7Reflect(inputRows: FloatArray, weights: FloatArray, width: Int): FloatArray?
    private external fun nativeReduceSumAndSquares(values: FloatArray): FloatArray?
    private external fun nativeApplyGatedActivation(planes: FloatArray, count: Int, mode: Int): FloatArray?
    private external fun nativeDecodeTypedFloatArray(encoded: ByteArray): FloatArray?
    private external fun nativeDecodeTypedInt32Array(encoded: ByteArray): IntArray?
    private external fun nativeAbiVersion(): Int
    private external fun nativePackMatrixBank(
        source: FloatArray,
        axis0Count: Int,
        axis1Count: Int,
        matrixCount: Int
    ): FloatArray?

    /** Packs one contiguous [axis1][axis0] matrix into [axis1][alignUp(axis0, 8)]. */
    fun packRowMajor(values: FloatArray, axis0Count: Int, axis1Count: Int): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(axis0Count > 0 && axis1Count > 0)
        val expected = axis0Count.toLong() * axis1Count.toLong()
        require(expected == values.size.toLong()) {
            "Expected $expected values, received ${values.size}."
        }
        return nativePackRowMajor(values, axis0Count, axis1Count)
            ?: throw IllegalStateException("S5 row-major packing failed.")
    }


    /** Packs one contiguous [axis0][axis1] matrix as [axis1][alignUp(axis0, 8)]. */
    fun packTransposed(values: FloatArray, axis0Count: Int, axis1Count: Int): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(axis0Count > 0 && axis1Count > 0)
        val expected = axis0Count.toLong() * axis1Count.toLong()
        require(expected == values.size.toLong()) {
            "Expected $expected values, received ${values.size}."
        }
        return nativePackTransposed(values, axis0Count, axis1Count)
            ?: throw IllegalStateException("S5 transposed packing failed.")
    }

    private external fun nativePackTransposed(
        source: FloatArray,
        axis0Count: Int,
        axis1Count: Int
    ): FloatArray?
    private external fun nativePackRowMajor(
        source: FloatArray,
        axis0Count: Int,
        axis1Count: Int
    ): FloatArray?
    private external fun nativeParseContainerHeader(
        header: ByteArray,
        totalFileSize: Long
    ): LongArray?
}
