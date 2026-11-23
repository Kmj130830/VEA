package com.kmj.vea.engine

data class S5ContainerHeaderInfo(
    val kind: String,
    val declaredSpan: Long,
    val formatWord: Long,
    val transformSeed: Long,
    val decodedPayloadSize: Long
)

/**
 * Native implementation of the recovered S5API tensor and container boundary.
 *
 * This is not a VOCALOID neural renderer. It validates the fixed outer header
 * and packs already-decoded tensor values into the recovered matrix layout.
 */
object NativeS5TensorBridge {
    private const val MATRIX_AXIS0_ALIGNMENT = 8
    private val loaded: Boolean = runCatching {
        System.loadLibrary("veas5tensor")
        true
    }.getOrDefault(false)

    val isLoaded: Boolean
        get() = loaded

    val abiVersion: Int
        get() = if (loaded) nativeAbiVersion() else 0

    /** Parses the 16-byte S5API prefix: magic, declared span, format word, and transform seed. */
    fun parseContainerHeader(header: ByteArray, totalFileSize: Long): S5ContainerHeaderInfo? {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(header.size == 16) { "The S5API container prefix is exactly 16 bytes." }
        require(totalFileSize >= 16L)
        val fields = nativeParseContainerHeader(header, totalFileSize) ?: return null
        if (fields.size != 5) return null
        val kind = when (fields[0].toInt()) {
            1 -> "VTMB"
            2 -> "VTBR"
            3 -> "VTMG"
            4 -> "VPIT"
            5 -> "VNPV"
            6 -> "VHNV"
            else -> return null
        }
        return S5ContainerHeaderInfo(
            kind = kind,
            declaredSpan = fields[1],
            formatWord = fields[2],
            transformSeed = fields[3],
            decodedPayloadSize = fields[4]
        )
    }

    /**
     * Applies the S5API FUN_18002aa90 byte transform to a complete V7 container.
     * The result contains only the decoded body. It is not yet parsed into the
     * JSON-like object tree or converted into model tensors.
     */
    fun decodeContainerPayload(fileBytes: ByteArray): ByteArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(fileBytes.size >= 16) { "The V7 container is shorter than its 16-byte prefix." }
        return nativeDecodeContainerPayload(fileBytes)
            ?: throw IllegalArgumentException("Unsupported or malformed S5 container payload.")
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
     * Builds an S5API recurrent parameter key such as rnn.weight_ih_l0_reverse.
     * The member suffix must include its leading dot.
     */
    fun buildRecurrentParameterName(
        base: String,
        memberSuffix: String,
        layerIndex: Int,
        reverseDirection: Boolean = false
    ): String {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(base.all { it.code in 0x20..0x7e }) { "Base must be printable ASCII." }
        require(memberSuffix.all { it.code in 0x20..0x7e }) { "Member suffix must be printable ASCII." }
        require(memberSuffix.startsWith(".")) { "Member suffix must start with a dot." }
        require(layerIndex >= 0) { "Layer index must not be negative." }
        return nativeBuildRecurrentParameterName(base, memberSuffix, layerIndex, reverseDirection)
            ?: throw IllegalArgumentException("Could not build S5 recurrent parameter name.")
    }


    /**
     * Runs the recovered residual depthwise/MLP block from FUN_1800c5d50.
     * historyRows: [channel][7][position], depthwise weights: [channel][49].
     * expand weights: [expandedFeature][channel], project weights: [channel][expandedFeature].
     * Input and output/residual tensors are [channel][position].
     */
    fun runResidualDepthwiseMlpBlock(
        historyRows: FloatArray,
        depthwiseWeights: FloatArray,
        expandWeights: FloatArray,
        expandedBiasA: FloatArray,
        expandedBiasB: FloatArray,
        projectWeights: FloatArray,
        residualInput: FloatArray,
        residualScale: FloatArray,
        residualBias: FloatArray?,
        channels: Int,
        positions: Int,
        expandedFeatures: Int,
        epsilon: Float
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(channels > 0 && positions >= 7 && expandedFeatures > 0)
        require(historyRows.size == channels * 7 * positions)
        require(depthwiseWeights.size == channels * 49)
        require(expandWeights.size == expandedFeatures * channels)
        require(expandedBiasA.size >= expandedFeatures && expandedBiasB.size >= expandedFeatures)
        require(projectWeights.size == channels * expandedFeatures)
        require(residualInput.size == channels * positions && residualScale.size >= channels)
        require(residualBias == null || residualBias.size >= channels)
        require(epsilon.isFinite() && epsilon >= 0.0f)
        return nativeRunResidualDwMlpBlock(
            historyRows, depthwiseWeights, expandWeights, expandedBiasA, expandedBiasB,
            projectWeights, residualInput, residualScale, residualBias,
            channels, positions, expandedFeatures, epsilon
        ) ?: throw IllegalArgumentException("S5 residual depthwise/MLP block failed.")
    }

    /**
     * Executes one c5d50 block directly from its decoded VTB2 tensors.
     * The native layer derives the two expanded GELU affine vectors using
     * the transform recovered in FUN_1800d4bf0 before running the block.
     */
    fun runVtb2C5d50StageBlock(
        historyRows: FloatArray,
        parameters: S5Vtb2C5d50StageBlockParameters,
        residualInput: FloatArray,
        positions: Int,
        epsilon: Float
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        val channels = S5Vtb2C5d50StageBlockParameters.CHANNEL_COUNT
        val expandedFeatures = S5Vtb2C5d50StageBlockParameters.EXPANDED_CHANNEL_COUNT
        require(positions >= 7)
        require(historyRows.size.toLong() == channels.toLong() * 7L * positions)
        require(residualInput.size.toLong() == channels.toLong() * positions)
        require(parameters.blockVectorA.size == channels && parameters.blockVectorB.size == channels)
        require(parameters.expansionWeights.size == expandedFeatures * channels)
        require(parameters.expansionBias.size == expandedFeatures)
        require(parameters.projectionWeights.size == channels * expandedFeatures)
        require(parameters.projectionBias.size == channels && parameters.blockVectorC.size == channels)
        require(epsilon.isFinite() && epsilon >= 0.0f)
        return nativeRunVtb2C5d50Block(
            historyRows, parameters.depthwiseKernel, parameters.blockVectorA,
            parameters.blockVectorB, parameters.expansionWeights, parameters.expansionBias,
            parameters.projectionWeights, parameters.projectionBias, parameters.blockVectorC,
            residualInput, channels, positions, expandedFeatures, epsilon
        ) ?: throw IllegalArgumentException(
            "VTB2 c5d50 stage " + parameters.stageIndex + " block " + parameters.blockIndex +
                " execution failed."
        )
    }

    /**
     * Converts VTMG [bank][input][paddedOutput] matrices into row-major
     * [bank][output][input] weights for VEA's v0_naive GEMM port.
     *
     * This adapter bridges two confirmed data layouts. It does not assert
     * that Yamaha uses this exact conversion in its internal object builder.
     */
    fun convertVtmgBankWeightsForV0Naive(
        packedWeights: FloatArray,
        outputFeatures: Int,
        inputFeatures: Int,
        paddedOutputFeatures: Int,
        banks: Int
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(outputFeatures > 0 && inputFeatures > 0 && banks > 0)
        require(paddedOutputFeatures >= outputFeatures &&
            paddedOutputFeatures % MATRIX_AXIS0_ALIGNMENT == 0)
        val expectedSource = banks.toLong() * inputFeatures * paddedOutputFeatures
        val expectedDestination = banks.toLong() * outputFeatures * inputFeatures
        require(expectedSource == packedWeights.size.toLong()) {
            "Packed VTMG matrix length does not match its dimensions."
        }
        require(expectedDestination <= Int.MAX_VALUE.toLong()) {
            "Converted GEMM weights exceed the supported array size."
        }
        return nativeVtmgBankToV0NaiveA(
            packedWeights, outputFeatures, inputFeatures, paddedOutputFeatures, banks
        ) ?: throw IllegalArgumentException("Could not convert VTMG weights to v0_naive layout.")
    }

    /**
     * Executes the 2x interpolation + 3-tap projection recovered from FUN_1800c72d0.
     * Input is [inputChannel][position], weights are [outputChannel][inputChannel*3],
     * and output is [outputChannel][position*2].
     */
    fun applyUpsample2Conv3Projection(
        input: FloatArray,
        weights: FloatArray,
        bias: FloatArray?,
        inputChannels: Int,
        outputChannels: Int,
        positions: Int
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(inputChannels > 0 && outputChannels > 0 && positions > 0)
        val expectedInput = inputChannels.toLong() * positions
        val expectedWeights = outputChannels.toLong() * inputChannels * 3L
        val expectedOutput = outputChannels.toLong() * positions * 2L
        require(expectedInput == input.size.toLong()) {
            "Input length does not match [inputChannel][position]."
        }
        require(expectedWeights == weights.size.toLong()) {
            "Weight length does not match [outputChannel][inputChannel*3]."
        }
        require(bias == null || bias.size == outputChannels) {
            "Bias must contain one value per output channel."
        }
        require(expectedOutput <= Int.MAX_VALUE.toLong()) {
            "Upsample projection output exceeds the supported array size."
        }
        return nativeUpsample2Conv3Projection(
            input, weights, bias, inputChannels, outputChannels, positions
        ) ?: throw IllegalArgumentException("S5 2x upsample/3-tap projection failed.")
    }

    /**
     * Executes the stride-2 Conv1D projection recovered from FUN_1800c5440.
     * Input is [inputChannel][position], weights are
     * [outputChannel][inputChannel][kernelTap], and output is
     * [outputChannel][floor((positionCount - (kernelWidth mod 2))/2) + 1].
     */
    fun applyStride2Conv1dProjection(
        input: FloatArray,
        weights: FloatArray,
        bias: FloatArray?,
        inputChannels: Int,
        outputChannels: Int,
        positions: Int,
        kernelWidth: Int
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(inputChannels > 0 && outputChannels > 0 && positions > 0)
        require(kernelWidth > 0) {
            "Kernel width must be positive."
        }
        val expectedInput = inputChannels.toLong() * positions
        val expectedWeights = outputChannels.toLong() * inputChannels * kernelWidth
        val outputPositions = (positions - (kernelWidth % 2)) / 2 + 1
        val expectedOutput = outputChannels.toLong() * outputPositions
        require(expectedInput == input.size.toLong()) {
            "Input length does not match [inputChannel][position]."
        }
        require(expectedWeights == weights.size.toLong()) {
            "Weight length does not match [outputChannel][inputChannel][kernelTap]."
        }
        require(bias == null || bias.size == outputChannels) {
            "Bias must contain one value per output channel."
        }
        require(expectedOutput <= Int.MAX_VALUE.toLong()) {
            "Stride-2 Conv1D output exceeds the supported array size."
        }
        return nativeStride2Conv1dProjection(
            input, weights, bias, inputChannels, outputChannels, positions, kernelWidth
        ) ?: throw IllegalArgumentException("S5 stride-2 Conv1D projection failed.")
    }

    /**
     * Runs the banked linear projection recovered in FUN_1800c6390.
     * Weights are [bank][outputFeature][inputFeature]; input is [inputFeature][position].
     * Output is [outputFeature][position][bank]. Bias may be null.
     */
    fun applyBankedLinearProjection(
        weights: FloatArray,
        weightRowStride: Int,
        weightBankStride: Int,
        input: FloatArray,
        bias: FloatArray?,
        inputFeatures: Int,
        outputFeatures: Int,
        positions: Int,
        banks: Int
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(inputFeatures > 0 && outputFeatures > 0 && positions > 0 && banks > 0)
        require(weightRowStride >= inputFeatures)
        require(weightBankStride.toLong() >= (outputFeatures - 1L) * weightRowStride + inputFeatures)
        val weightsRequired = (banks - 1L) * weightBankStride +
            (outputFeatures - 1L) * weightRowStride + inputFeatures
        require(weightsRequired <= weights.size.toLong())
        require(inputFeatures.toLong() * positions <= input.size.toLong())
        require(bias == null || bias.size >= outputFeatures)
        return nativeApplyBankedLinear(
            weights, weightRowStride, weightBankStride, input, bias,
            inputFeatures, outputFeatures, positions, banks
        ) ?: throw IllegalArgumentException("S5 banked linear projection failed.")
    }

    /**
     * Executes the recovered S5API v0_naive row-major matrix primitive:
     * C = alpha * A * B + beta * C. Strides count float elements.
     */
    fun gemmRowMajor(
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
        m: Int,
        n: Int,
        k: Int,
        alpha: Float = 1.0f,
        lda: Int = k,
        ldb: Int = n,
        beta: Float = 0.0f,
        ldc: Int = n
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(m > 0 && n > 0 && k >= 0)
        require(lda >= k && ldb >= n && ldc >= n)
        val aRequired = if (k == 0) 0L else (m - 1L) * lda + k
        val bRequired = if (k == 0) 0L else (k - 1L) * ldb + n
        val cRequired = (m - 1L) * ldc + n
        require(aRequired <= a.size.toLong() && bRequired <= b.size.toLong() &&
            cRequired <= c.size.toLong()) { "Input or output buffers are too small for the requested GEMM." }
        return nativeGemmRowMajor(a, b, c, m, n, k, alpha, lda, ldb, beta, ldc)
            ?: throw IllegalArgumentException("S5 row-major GEMM failed.")
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

    /**
     * Applies the recovered S5API scalar GELU operator.
     * x = scale * input + bias; output = x * sigmoid(1.5957692 * (x + 0.044715*x^3)).
     */
    /**
     * Rotates the SPP frame buffer as in FUN_180133fd0.
     * Positive shift rotates left; negative shift rotates right.
     */
    fun rotateSppCircularBuffer(values: FloatArray, shift: Int): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        return nativeRotateSppCircularBuffer(values, shift)
            ?: throw IllegalArgumentException("SPP circular buffer rotation failed.")
    }

    /**
     * Interpolates a selected [magnitude,phase] peak from an SPP complex spectrum.
     * Returns [refinedMagnitude, refinedPhase] using the recovered three-bin parabola.
     */
    fun interpolateSppPeak(magnitudePhase: FloatArray, peakIndex: Int): Pair<Float, Float> {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(magnitudePhase.isNotEmpty() && magnitudePhase.size % 2 == 0)
        require(magnitudePhase.all { it.isFinite() })
        val binCount = magnitudePhase.size / 2
        require(peakIndex in 0 until binCount)
        val result = nativeInterpolateSppPeak(magnitudePhase, binCount, peakIndex)
            ?: throw IllegalArgumentException("SPP peak interpolation failed.")
        check(result.size == 2)
        return result[0] to result[1]
    }

    /**
     * Generates deterministic SPP spectral phase values from magnitudes.
     * state is a mutable four-word xorshift state and is updated in place.
     * Output is interleaved [real,imag]; edgeMode bit 0 controls the final
     * real-only boundary bin.
     */
    fun generateSppPhaseNoise(
        magnitudes: FloatArray,
        state: IntArray,
        edgeMode: Int
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(state.size == 4)
        require(edgeMode in 0..255)
        require(magnitudes.all { it.isFinite() })
        return nativeGenerateSppPhaseNoise(magnitudes, state, edgeMode)
            ?: throw IllegalArgumentException("SPP phase-noise generation failed.")
    }
    /**
     * Fills the recovered S5 real-FFT split schedule and coefficient table.
     * The arrays are logical Kotlin copies; native renderer state will own
     * separately aligned storage when a plan is bound to the FFT executor.
     */
    fun buildSppFftPlanTables(
        fftSize: Int,
        splitTable: IntArray,
        coefficients: FloatArray
    ): Boolean {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(fftSize >= 4 && fftSize.countOneBits() == 1)
        require(splitTable.isNotEmpty() && coefficients.size == fftSize / 2)
        val expectedSplitCount = kotlin.math.sqrt(fftSize / 2.0).toInt() + 2
        require(splitTable.size >= expectedSplitCount)
        return nativeBuildSppFftPlan(fftSize, splitTable, coefficients)
    }

    /**
     * Runs the exact 4- or 8-float S5 complex-leaf butterfly used by the
     * real-FFT wrapper. direction<0 selects FUN_18007d700; >=0 selects
     * FUN_18007fca0. Larger FFT sizes use separate stage kernels.
     */
    fun applySppFftSmallLeaf(
        fftSize: Int,
        direction: Int,
        interleavedValues: FloatArray
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(fftSize == 4 || fftSize == 8)
        require(interleavedValues.size == fftSize)
        require(interleavedValues.all { it.isFinite() })
        return nativeSppFftSmallLeaf(fftSize, direction, interleavedValues)
            ?: throw IllegalArgumentException("S5 small FFT butterfly failed.")
    }

    /**
     * Executes the recovered 4-, 8-, 16- or 32-point complex FFT leaf.
     * For sizes 16/32, coefficients are the plan's generated table and
     * provide the exact twiddles consumed by FUN_18007db00/FUN_18007e140.
     */
    fun applySppFftLeaf(
        fftSize: Int,
        direction: Int,
        interleavedValues: FloatArray,
        coefficients: FloatArray
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(fftSize == 4 || fftSize == 8 || fftSize == 16 || fftSize == 32)
        require(interleavedValues.size == fftSize)
        require(interleavedValues.all { it.isFinite() })
        if (fftSize >= 16) {
            require(coefficients.size >= if (fftSize == 16) 2 else 4)
            require(coefficients.all { it.isFinite() })
        }
        return nativeSppFftLeaf(fftSize, direction, interleavedValues, coefficients)
            ?: throw IllegalArgumentException("S5 FFT leaf execution failed.")
    }

    /**
     * Executes the recovered mixed-radix FFT stage for 64- or 128-float
     * interleaved complex buffers. This is one inner stage, not a complete
     * real-FFT transform or PCM rendering operation.
     */
    fun applySppFftRadixStage(
        fftSize: Int,
        interleavedValues: FloatArray,
        coefficients: FloatArray
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(fftSize == 64 || fftSize == 128)
        require(interleavedValues.size == fftSize)
        require(coefficients.size >= fftSize / 2)
        require(interleavedValues.all { it.isFinite() })
        require(coefficients.all { it.isFinite() })
        return nativeSppFftRadixStage(fftSize, interleavedValues, coefficients)
            ?: throw IllegalArgumentException("S5 FFT radix stage execution failed.")
    }

    /**
     * Executes the recovered 256/512-lane large radix stage from
     * FUN_1800800c0. mode selects the source function's final-group branch.
     */
    fun applySppFftLargeRadixStage(
        fftSize: Int,
        mode: Int,
        interleavedValues: FloatArray,
        coefficients: FloatArray
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(fftSize == 256 || fftSize == 512)
        require(mode == 0 || mode == 1)
        require(interleavedValues.size == fftSize)
        require(coefficients.size >= fftSize / 2)
        require(interleavedValues.all { it.isFinite() })
        require(coefficients.all { it.isFinite() })
        return nativeSppFftLargeRadixStage(fftSize, mode, interleavedValues, coefficients)
            ?: throw IllegalArgumentException("S5 large FFT radix stage execution failed.")
    }

    /**
     * Executes the portable SPP real-FFT wrapper.
     * direction < 0: real frame -> packed [DC, Nyquist, Re/Im bins].
     * direction >= 0: packed spectrum -> real frame, with N/2 scaling.
     */
    fun applySppRealFft(
        fftSize: Int,
        direction: Int,
        frame: FloatArray
    ): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(fftSize in 4..65536 && (fftSize and (fftSize - 1)) == 0)
        require(frame.size == fftSize)
        require(frame.all { it.isFinite() })
        return nativeSppRealFft(fftSize, direction, frame)
            ?: throw IllegalArgumentException("SPP real FFT execution failed.")
    }

    /** Returns the floor(log2(N)) plan-table index used by FUN_180040630. */
    fun sppFftLog2PlanIndex(size: Int): Int {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(size >= 0)
        val result = nativeSppFftLog2Floor(size)
        check(result != Int.MIN_VALUE) { "S5 FFT size index calculation failed." }
        return result
    }

    /**
     * Looks up/interpolates the SPP window table as in FUN_180139870.
     * table contains segmentCount+1 double values; fallback is used outside.
     */
    fun lookupSppWindowTable(
        table: DoubleArray,
        segmentCount: Int,
        scale: Double,
        fallbackValue: Double,
        x: Double
    ): Double {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(segmentCount in 0 until Int.MAX_VALUE)
        require(table.size.toLong() >= segmentCount.toLong() + if (segmentCount > 0) 1L else 0L)
        require(scale.isFinite() && scale >= 0.0)
        require(fallbackValue.isFinite() && x.isFinite())
        val result = nativeLookupSppWindowTable(
            table, segmentCount, scale, fallbackValue, x
        ) ?: throw IllegalArgumentException("SPP window-table lookup failed.")
        check(result.size == 1)
        return result[0]
    }
    /** Ports FUN_180136150's periodic frequency-dependent peak penalty. */
    fun sppPeakWeight(config: FloatArray, coordinate: Float): Float {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(config.size == 5)
        require(config.all { it.isFinite() } && coordinate.isFinite())
        val result = nativeSppPeakWeight(config, coordinate)
        require(result.isFinite()) { "SPP peak-weight evaluation failed." }
        return result
    }

    /**
     * Finds the first maximum of magnitude + SPP frequency penalty.
     * valueStride=1 matches FUN_180135b90; valueStride=2 matches
     * FUN_180135670 where magnitude is stored in each pair's first lane.
     */
    fun findSppWeightedPeak(
        values: FloatArray,
        valueStride: Int,
        startIndex: Int,
        endIndex: Int,
        binSpacing: Float,
        denominator: Float,
        weightConfig: FloatArray
    ): Int {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(valueStride == 1 || valueStride == 2)
        require(values.isNotEmpty() && values.size % valueStride == 0)
        val valueCount = values.size / valueStride
        require(startIndex >= 0 && startIndex <= endIndex && endIndex < valueCount)
        require(binSpacing.isFinite() && binSpacing >= 0.0f)
        require(denominator.isFinite() && denominator > 0.0f)
        require(weightConfig.size == 5 && weightConfig.all { it.isFinite() })
        require(values.all { it.isFinite() })
        val result = nativeFindSppWeightedPeak(
            values, valueStride, startIndex, endIndex,
            binSpacing, denominator, weightConfig
        )
        check(result != Int.MIN_VALUE) { "S5 weighted spectral peak search failed." }
        return result
    }

    /** Applies the frame-wide scalar normalization in FUN_1801397d0. */
    fun scaleSppBuffer(values: FloatArray, scale: Float): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(scale.isFinite())
        return nativeScaleSppBuffer(values, scale)
            ?: throw IllegalArgumentException("SPP frame scaling failed.")
    }
    fun applyGelu(values: FloatArray, scale: Float = 1.0f, bias: Float = 0.0f): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(values.isNotEmpty())
        return nativeApplyGelu(values, scale, bias)
            ?: throw IllegalStateException("S5 GELU activation failed.")
    }

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


    /**
     * Completes the scalar normalization statistics stage recovered in FUN_1800c5d50.
     * Returns mean and 1/sqrt(max(sumSquares/count - mean^2, 0) + epsilon).
     */
    fun computeMeanAndInverseStd(
        sum: Float,
        sumSquares: Float,
        count: Int,
        epsilon: Float
    ): Pair<Float, Float> {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(count > 0 && epsilon >= 0.0f)
        val result = nativeComputeMeanInvStd(sum, sumSquares, count, epsilon)
            ?: throw IllegalArgumentException("Invalid normalization statistics.")
        check(result.size == 2)
        return result[0] to result[1]
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

    /** Creates a persistent native S5 block-history ring. */
    internal fun createHistoryRing(channels: Int, positions: Int, historyRows: Int): Long {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(channels > 0 && positions > 0 && historyRows > 0)
        return nativeCreateHistoryRing(channels, positions, historyRows).also {
            check(it != 0L) { "Could not allocate the S5 history ring." }
        }
    }

    /** Appends one [channel][position] block to the native history ring. */
    internal fun appendHistoryRing(handle: Long, block: FloatArray) {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(handle != 0L && block.isNotEmpty())
        check(nativeAppendHistoryRing(handle, block)) {
            "The S5 history block does not match the ring dimensions."
        }
    }

    /** Returns all history rows as [oldest row][channel][position]. */
    internal fun copyHistoryRingChronological(handle: Long): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(handle != 0L)
        return nativeCopyChronologicalHistoryRing(handle)
            ?: throw IllegalStateException("Could not copy S5 history rows.")
    }

    /** Returns [channel][oldest-to-newest history row][position] directly from native ring storage. */
    internal fun copyHistoryRingChannelMajor(handle: Long): FloatArray {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(handle != 0L)
        return nativeCopyChannelMajorHistoryRing(handle)
            ?: throw IllegalStateException("Could not copy channel-major S5 history rows.")
    }

    internal fun resetHistoryRing(handle: Long) {
        check(loaded) { "Native S5 tensor library is not loaded." }
        require(handle != 0L)
        check(nativeResetHistoryRing(handle)) { "Could not reset S5 history ring." }
    }

    internal fun destroyHistoryRing(handle: Long) {
        if (loaded && handle != 0L) nativeDestroyHistoryRing(handle)
    }

    private external fun nativeCreateHistoryRing(channels: Int, positions: Int, historyRows: Int): Long
    private external fun nativeDestroyHistoryRing(handle: Long)
    private external fun nativeResetHistoryRing(handle: Long): Boolean
    private external fun nativeAppendHistoryRing(handle: Long, block: FloatArray): Boolean
    private external fun nativeCopyChronologicalHistoryRing(handle: Long): FloatArray?
    private external fun nativeCopyChannelMajorHistoryRing(handle: Long): FloatArray?

    private external fun nativeUpsample2Conv3Projection(
        input: FloatArray, weights: FloatArray, bias: FloatArray?,
        inputChannels: Int, outputChannels: Int, positions: Int
    ): FloatArray?

    private external fun nativeStride2Conv1dProjection(
        input: FloatArray, weights: FloatArray, bias: FloatArray?,
        inputChannels: Int, outputChannels: Int, positions: Int, kernelWidth: Int
    ): FloatArray?

    private external fun nativeVtmgBankToV0NaiveA(
        packedWeights: FloatArray,
        outputFeatures: Int,
        inputFeatures: Int,
        paddedOutputFeatures: Int,
        banks: Int
    ): FloatArray?

    private external fun nativeRunResidualDwMlpBlock(
        historyRows: FloatArray, depthwiseWeights: FloatArray,
        expandWeights: FloatArray, expandedBiasA: FloatArray, expandedBiasB: FloatArray,
        projectWeights: FloatArray, residualInput: FloatArray,
        residualScale: FloatArray, residualBias: FloatArray?,
        channels: Int, positions: Int, expandedFeatures: Int, epsilon: Float
    ): FloatArray?
    private external fun nativeRunVtb2C5d50Block(
        historyRows: FloatArray, depthwiseWeights: FloatArray,
        blockVectorA: FloatArray, blockVectorB: FloatArray,
        expansionWeights: FloatArray, expansionBias: FloatArray,
        projectionWeights: FloatArray, projectionBias: FloatArray,
        blockVectorC: FloatArray, residualInput: FloatArray,
        channels: Int, positions: Int, expandedFeatures: Int, epsilon: Float
    ): FloatArray?
    private external fun nativeApplyBankedLinear(
        weights: FloatArray, weightRowStride: Int, weightBankStride: Int,
        input: FloatArray, bias: FloatArray?,
        inputFeatures: Int, outputFeatures: Int, positions: Int, banks: Int
    ): FloatArray?
    private external fun nativeGemmRowMajor(
        a: FloatArray, b: FloatArray, c: FloatArray,
        m: Int, n: Int, k: Int, alpha: Float, lda: Int, ldb: Int, beta: Float, ldc: Int
    ): FloatArray?
    private external fun nativeBuildRecurrentParameterName(base: String, memberSuffix: String, layerIndex: Int, reverseDirection: Boolean): String?
    private external fun nativeResidualAffineAdd(dst: FloatArray, residual: FloatArray, scale: Float, bias: Float): FloatArray?
    private external fun nativeDepthwiseConv7x7Reflect(inputRows: FloatArray, weights: FloatArray, width: Int): FloatArray?
    private external fun nativeApplyGelu(values: FloatArray, scale: Float, bias: Float): FloatArray?
    private external fun nativeRotateSppCircularBuffer(values: FloatArray, shift: Int): FloatArray?
    private external fun nativeScaleSppBuffer(values: FloatArray, scale: Float): FloatArray?
    private external fun nativeLookupSppWindowTable(table: DoubleArray, segmentCount: Int, scale: Double, fallbackValue: Double, x: Double): DoubleArray?
    private external fun nativeSppFftLog2Floor(size: Int): Int
    private external fun nativeSppFftSmallLeaf(fftSize: Int, direction: Int, values: FloatArray): FloatArray?
    private external fun nativeSppFftLeaf(fftSize: Int, direction: Int, values: FloatArray, coefficients: FloatArray): FloatArray?
    private external fun nativeSppFftRadixStage(fftSize: Int, values: FloatArray, coefficients: FloatArray): FloatArray?
    private external fun nativeSppFftLargeRadixStage(fftSize: Int, mode: Int, values: FloatArray, coefficients: FloatArray): FloatArray?
    private external fun nativeSppRealFft(fftSize: Int, direction: Int, frame: FloatArray): FloatArray?
    private external fun nativeBuildSppFftPlan(fftSize: Int, splitTable: IntArray, coefficients: FloatArray): Boolean
    private external fun nativeGenerateSppPhaseNoise(magnitudes: FloatArray, state: IntArray, edgeMode: Int): FloatArray?
    private external fun nativeInterpolateSppPeak(magnitudePhase: FloatArray, binCount: Int, peakIndex: Int): FloatArray?
    private external fun nativeSppPeakWeight(config: FloatArray, coordinate: Float): Float
    private external fun nativeFindSppWeightedPeak(values: FloatArray, valueStride: Int, startIndex: Int, endIndex: Int, binSpacing: Float, denominator: Float, weightConfig: FloatArray): Int
    private external fun nativeComputeMeanInvStd(sum: Float, sumSquares: Float, count: Int, epsilon: Float): FloatArray?
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
    private external fun nativeDecodeContainerPayload(fileBytes: ByteArray): ByteArray?
}
