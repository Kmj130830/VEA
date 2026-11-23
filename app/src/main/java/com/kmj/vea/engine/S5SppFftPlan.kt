package com.kmj.vea.engine

/**
 * Logical Kotlin copy of one recovered S5 real-FFT plan record's backing data.
 *
 * The native 0x18-byte record stores the FFT size and pointers to two
 * separately 32-byte-aligned buffers. This class stores only their logical
 * contents; it is not itself a native record and does not expose raw pointers.
 *
 * Execution support is currently ported through the 512-lane inner radix
 * stage. This does not mean the complete real-FFT wrapper or final renderer
 * spectral pipeline is available.
 */
class S5SppFftPlan internal constructor(
    val fftSize: Int,
    splitTable: IntArray,
    coefficients: FloatArray
) {
    private val splitData = splitTable.copyOf()
    private val coefficientData = coefficients.copyOf()

    val splitTable: IntArray
        get() = splitData.copyOf()

    val coefficients: FloatArray
        get() = coefficientData.copyOf()

    /**
     * Runs the recovered small-size complex leaf selected by this plan.
     * Larger plans remain table-only until their stage kernels are ported.
     */
    fun transformSmallLeaf(
        direction: Int,
        interleavedValues: FloatArray,
        execute: (Int, Int, FloatArray) -> FloatArray =
            NativeS5TensorBridge::applySppFftSmallLeaf
    ): FloatArray {
        require(fftSize == 4 || fftSize == 8) {
            "Only the recovered 4- and 8-size FFT leaf kernels are available."
        }
        require(interleavedValues.size == fftSize)
        require(interleavedValues.all { it.isFinite() })
        return execute(fftSize, direction, interleavedValues)
    }

    /**
     * Executes the recovered 4-, 8-, 16- or 32-point complex leaf.
     * The 16/32-point butterflies consume this plan's generated twiddle table.
     */
    fun transformLeaf(
        direction: Int,
        interleavedValues: FloatArray,
        execute: (Int, Int, FloatArray, FloatArray) -> FloatArray =
            NativeS5TensorBridge::applySppFftLeaf
    ): FloatArray {
        require(fftSize == 4 || fftSize == 8 || fftSize == 16 || fftSize == 32) {
            "Only the recovered 4-, 8-, 16- and 32-point FFT leaves are available."
        }
        require(interleavedValues.size == fftSize)
        require(interleavedValues.all { it.isFinite() })
        return execute(fftSize, direction, interleavedValues, coefficientData.copyOf())
    }

    /**
     * Runs the recovered internal 64/128-lane stage for a plan of matching
     * size. It does not claim to execute the surrounding FFT preparation
     * and final direction-specific output stages.
     */
    fun transformRadixStage(
        interleavedValues: FloatArray,
        execute: (Int, FloatArray, FloatArray) -> FloatArray =
            NativeS5TensorBridge::applySppFftRadixStage
    ): FloatArray {
        require(fftSize == 64 || fftSize == 128) {
            "Only 64- and 128-lane radix stages are available."
        }
        require(interleavedValues.size == fftSize)
        require(interleavedValues.all { it.isFinite() })
        return execute(fftSize, interleavedValues, coefficientData.copyOf())
    }
    /**
     * Runs the recovered 256/512-lane large radix stage. This is an inner
     * stage of FUN_1800800c0; it does not perform the root reorder or real
     * spectrum packing.
     */
    fun transformLargeRadixStage(
        mode: Int,
        interleavedValues: FloatArray,
        execute: (Int, Int, FloatArray, FloatArray) -> FloatArray =
            NativeS5TensorBridge::applySppFftLargeRadixStage
    ): FloatArray {
        require(fftSize == 256 || fftSize == 512) {
            "Only 256- and 512-lane large radix stages are available."
        }
        require(mode == 0 || mode == 1)
        require(interleavedValues.size == fftSize)
        require(interleavedValues.all { it.isFinite() })
        return execute(fftSize, mode, interleavedValues, coefficientData.copyOf())
    }
    /**
     * Runs the complete portable real-FFT boundary for this plan size.
     * Negative direction returns packed real-spectrum bins; non-negative
     * direction reconstructs the real frame with the unnormalized N/2 scale.
     */
    fun transformReal(
        direction: Int,
        frame: FloatArray,
        execute: (Int, Int, FloatArray) -> FloatArray =
            NativeS5TensorBridge::applySppRealFft
    ): FloatArray {
        require(frame.size == fftSize)
        require(frame.all { it.isFinite() })
        return execute(fftSize, direction, frame)
    }
    init {
        require(fftSize >= 4 && (fftSize and (fftSize - 1)) == 0)
        val expectedSplitCount = kotlin.math.sqrt(fftSize / 2.0).toInt() + 2
        require(splitData.size == expectedSplitCount)
        require(coefficientData.size == fftSize / 2)
        require(splitData[0] == fftSize / 4)
        require(splitData[1] == if (fftSize == 4) 1 else fftSize / 4)
        require(coefficientData.all { it.isFinite() })
    }
}

/** Creates the native S5API-compatible split and twiddle plan arrays. */
object S5SppFftPlanBuilder {
    fun build(
        fftSize: Int,
        buildTables: (Int, IntArray, FloatArray) -> Boolean =
            NativeS5TensorBridge::buildSppFftPlanTables
    ): S5SppFftPlan {
        require(fftSize >= 4 && (fftSize and (fftSize - 1)) == 0) {
            "S5 FFT size must be a power of two of at least four samples."
        }
        val splitCount = kotlin.math.sqrt(fftSize / 2.0).toInt() + 2
        val splitTable = IntArray(splitCount)
        val coefficients = FloatArray(fftSize / 2)
        check(buildTables(fftSize, splitTable, coefficients)) {
            "Could not build the S5 real-FFT plan tables for size $fftSize."
        }
        return S5SppFftPlan(fftSize, splitTable, coefficients)
    }
}
