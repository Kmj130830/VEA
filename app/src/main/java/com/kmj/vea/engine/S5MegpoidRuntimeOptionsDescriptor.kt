package com.kmj.vea.engine

/**
 * Raw scalar options loaded by FUN_1800ced10 from the selected AI_Megpoid.vtb2
 * runtime-options object.
 *
 * The field names intentionally preserve native param offsets. Semantic
 * labels for param[0..4] and the high-offset option fields are not inferred
 * merely from their numeric values.
 */
data class S5MegpoidRuntimeOptionsDescriptor(
    val param0: Int,
    val param1: Int,
    val param2: Int,
    val param3: Int,
    val param4: Int,
    val param5: Int,
    val param6: Int,
    val param13: Boolean,
    val param14: Int,
    val param15: Int,
    val paramD00: Int,
    val paramD01: Boolean,
    val paramD02: Double,
    val paramD03: Double,
    val paramD04: Boolean,
    val paramD05: Double,
    val terminalSpectrumLogMagnitudePhase: Boolean,
    val carrierFusionEnabled: Boolean,
    /** Native render-root byte +0x59: true selects FUN_1800c72d0 Alternate. */
    val alternateTransitionEnabled: Boolean,
    /** Native render-root byte +0x3411, optional and false when absent. */
    val multipartThresholdModeEnabled: Boolean,
    /** Native render-root byte +0x3405, optional and false when absent. */
    val nearestPartMaskEnabled: Boolean,
    /** Required native modulation identifier when +0x3405 is enabled. */
    val partModulationIdentifier: String?
) {
    init {
        require(param5 > 0) { "Native param[5] sequence-slot count must be positive." }
        require(param6 > 0) { "Native param[6] residual-column count must be positive." }
        require(paramD02.isFinite() && paramD03.isFinite() && paramD05.isFinite()) {
            "Runtime option floating-point fields must be finite."
        }
        if (nearestPartMaskEnabled) {
            require(partModulationIdentifier == S5MegpoidRuntimeOptionKeys.EXPECTED_PART_MODULATION_IDENTIFIER) {
                "The selected nearest-part mask mode requires the native AI Megpoid modulation identifier."
            }
        } else {
            require(partModulationIdentifier == null) {
                "A modulation identifier must not be loaded when nearest-part masking is disabled."
            }
        }
    }

    /**
     * Builds the S5API output-transform input from values loaded by
     * FUN_1800ced10 plus constructor defaults recovered from FUN_1800bca40.
     *
     * The native loader stores param[1] at root +0x04, param[3] at +0x0C
     * and param[4] at +0x10. FUN_1800d5a00 forwards those as geometry/FFT
     * size, output sample count, and the descriptor float parameter.
     *
     * The model-population body does not overwrite the output-specific
     * fields at +0x61C, +0x620, +0x1F74, +0x3418 and +0x1F75; they retain
     * the verified blank-root constructor defaults.
     */
    fun buildOutputTransformInputsFromNativeMegpoidDefaults(): S5MegpoidOutputTransformInputs {
        requireMegpoidV7BlockGrid()
        require(param1 > 0 && param3 > 0) {
            "Megpoid runtime options contain invalid output-transform dimensions."
        }
        val floatParameter = param4.toFloat()
        require(floatParameter.isFinite()) {
            "Megpoid output-transform float parameter is not finite."
        }
        return S5MegpoidOutputTransformInputs(
            outputSamplesAtRoot0C = param3,
            geometryAndFftSizeAtRoot04 = param1,
            floatParameterAtRoot10 = floatParameter,
            frameWindowCoreLengthAtRoot61C = 0,
            alternatingBinSignAtRoot620 = false,
            flagAtRoot1F74 = false,
            frameWindowShapeModeAtRoot3418 = 0,
            flagAtRoot1F75 = false
        )
    }
    /**
     * The native model output buffer at root +0x4F8 contains param[2]
     * complex values; the inspected forward path allocates/clears one
     * 8-byte complex element per bin before FUN_18012c740 consumes it.
     */
    fun buildSpectrumOutputShape(): S5MegpoidSpectrumOutputShape =
        S5MegpoidSpectrumOutputShape(
            fftSize = param1,
            complexBinCount = param2
        )
    /**
     * The native post-residual head uses D00 * 2 features when D01 is enabled
     * and D00 is at least two; otherwise it falls back to two terminal planes.
     * FUN_1800ced10 stores this derived width in root word +0xD07.
     */
    val usesMultiPartOutputHead: Boolean
        get() = paramD01 && paramD00 >= 2

    val postResidualHeadFeatureWidth: Int
        get() {
            val width = if (usesMultiPartOutputHead) paramD00.toLong() * 2L else 2L
            require(width in 2L..Int.MAX_VALUE.toLong()) {
                "Post-residual output-head width is outside the supported integer range."
            }
            return width.toInt()
        }

    /**
     * Builds the recovered scalar part-rotation table for one output frame.
     * The two independent mask/threshold flags are loaded and validated from
     * the same selected runtime-options object as D00..D05.
     */
    fun buildMultipartOutputModulation(
        positionCount: Int,
        fundamentalFrequencyHz: Float,
        thetaCenterRadians: Float,
        coordinateKernel: S5MegpoidMultipartCoordinateKernel =
            S5MegpoidMultipartCoordinateKernel.SIMD_SSE_TABLE_TRUNCATION
    ): S5MegpoidMultipartOutputModulation {
        require(usesMultiPartOutputHead) {
            "The active model options do not select the multipart output head."
        }
        require(param1 > 0 && param4 > 0) {
            "Multipart modulation requires positive native FFT and output geometry values."
        }
        val frequencyStep = param4.toFloat() / param1.toFloat()
        require(frequencyStep.isFinite() && frequencyStep > 0f)
        return S5MegpoidMultipartOutputModulationBuilder.build(
            partCount = paramD00,
            positionCount = positionCount,
            frequencyStep = frequencyStep,
            fundamentalFrequencyHz = fundamentalFrequencyHz,
            thetaCenterRadians = thetaCenterRadians,
            maximumDistanceParameter = paramD02.toFloat(),
            distanceExtensionParameter = paramD03.toFloat(),
            extendedThresholdMode = paramD04,
            nearestPartMaskEnabledAtRoot3405 = nearestPartMaskEnabled,
            thresholdModeEnabledAtRoot3411 = multipartThresholdModeEnabled,
            coordinateKernel = coordinateKernel
        )
    }

    /** Native render-root +0x48: true selects log-magnitude/phase terminal planes. */
    val terminalSpectrumEncoding: S5MegpoidSpectrumFrameEncoding
        get() = if (terminalSpectrumLogMagnitudePhase) {
            S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE
        } else {
            S5MegpoidSpectrumFrameEncoding.DIRECT_COMPLEX
        }

    /** Native +0x59 dispatch: false = standard Banked, true = Alternate. */
    val reverseTransitionMode: S5MegpoidReverseTransitionMode
        get() = if (alternateTransitionEnabled) {
            S5MegpoidReverseTransitionMode.ALTERNATE
        } else {
            S5MegpoidReverseTransitionMode.STANDARD_BANKED
        }

    /** Number of feature buffers owned by the native render context. */
    val sequenceSlotCount: Int get() = param5

    /** Native Temporal descriptor count: param[5] - 1. */
    val temporalTransitionCount: Int get() = param5 - 1

    /** Native standard Banked descriptor count: param[5] - 1. */
    val bankedTransitionCount: Int get() = param5 - 1

    /** Number of residual rows in the native grid. */
    val residualBlockRows: Int get() = param5

    /** Number of residual columns in the native grid. */
    val residualBlockColumns: Int get() = param6

    /** Number of residual records constructed by param[5] * param[6]. */
    val residualBlockCount: Int
        get() {
            val count = param5.toLong() * param6.toLong()
            require(count <= Int.MAX_VALUE) {
                "Native residual block count exceeds the supported integer range."
            }
            return count.toInt()
        }

    /**
     * Verify that this options object matches the only block-grid topology
     * currently assembled by this Android port (AI Megpoid V7: 4x2).
     */
    fun requireMegpoidV7BlockGrid(
        expectedRows: Int = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS,
        expectedColumns: Int = S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS
    ) {
        require(param5 == expectedRows && param6 == expectedColumns) {
            "Unsupported S5API residual grid: native param[5]=" + param5 +
                ", param[6]=" + param6 + "; Android implementation expects " +
                expectedRows + "x" + expectedColumns + "."
        }
        require(temporalTransitionCount == expectedRows - 1)
        require(bankedTransitionCount == expectedRows - 1)
        require(residualBlockCount == expectedRows * expectedColumns)
    }

    companion object {
        fun from(optionsTree: S5ModelObjectTree): S5MegpoidRuntimeOptionsDescriptor {
            fun value(key: String): S5ModelValue =
                optionsTree.findByLookupKey(key)
                    ?: throw IllegalArgumentException("Missing S5API runtime option key " + key + ".")

            fun int(key: String): Int {
                val number = (value(key) as? S5ModelValue.NumberValue)?.value
                    ?: throw IllegalArgumentException("S5API runtime option " + key + " must be numeric.")
                require(number.isFinite() && number % 1.0 == 0.0 &&
                    number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()) {
                    "S5API runtime option " + key + " must be a finite int32 value."
                }
                return number.toInt()
            }

            fun double(key: String): Double {
                val number = (value(key) as? S5ModelValue.NumberValue)?.value
                    ?: throw IllegalArgumentException("S5API runtime option " + key + " must be numeric.")
                require(number.isFinite()) {
                    "S5API runtime option " + key + " must be finite."
                }
                return number
            }

            fun boolean(key: String): Boolean =
                (value(key) as? S5ModelValue.BooleanValue)?.value
                    ?: throw IllegalArgumentException("S5API runtime option " + key + " must be boolean.")

            fun optionalBoolean(key: String, defaultValue: Boolean = false): Boolean {
                val candidate = optionsTree.findByLookupKey(key) ?: return defaultValue
                return (candidate as? S5ModelValue.BooleanValue)?.value
                    ?: throw IllegalArgumentException("S5API runtime option " + key + " must be boolean.")
            }

            val nearestPartMaskEnabled =
                optionalBoolean(S5MegpoidRuntimeOptionKeys.NEAREST_PART_MASK_ENABLED)
            val partModulationIdentifier = if (nearestPartMaskEnabled) {
                val candidate = optionsTree.findByLookupKey(
                    S5MegpoidRuntimeOptionKeys.PART_MODULATION_IDENTIFIER
                ) ?: throw IllegalArgumentException(
                    "S5API runtime option " +
                        S5MegpoidRuntimeOptionKeys.PART_MODULATION_IDENTIFIER +
                        " is required when nearest-part masking is enabled."
                )
                (candidate as? S5ModelValue.StringValue)?.value
                    ?: throw IllegalArgumentException(
                        "S5API runtime option " +
                            S5MegpoidRuntimeOptionKeys.PART_MODULATION_IDENTIFIER +
                            " must be a string."
                    )
            } else null

            return S5MegpoidRuntimeOptionsDescriptor(
                param0 = int(S5MegpoidRuntimeOptionKeys.PARAM_0),
                param1 = int(S5MegpoidRuntimeOptionKeys.PARAM_1),
                param2 = int(S5MegpoidRuntimeOptionKeys.PARAM_2),
                param3 = int(S5MegpoidRuntimeOptionKeys.PARAM_3),
                param4 = int(S5MegpoidRuntimeOptionKeys.PARAM_4),
                param5 = int(S5MegpoidRuntimeOptionKeys.PARAM_5),
                param6 = int(S5MegpoidRuntimeOptionKeys.PARAM_6),
                param13 = boolean(S5MegpoidRuntimeOptionKeys.PARAM_13),
                param14 = int(S5MegpoidRuntimeOptionKeys.PARAM_14),
                param15 = int(S5MegpoidRuntimeOptionKeys.PARAM_15),
                paramD00 = int(S5MegpoidRuntimeOptionKeys.PARAM_D00),
                paramD01 = boolean(S5MegpoidRuntimeOptionKeys.PARAM_D01),
                paramD02 = double(S5MegpoidRuntimeOptionKeys.PARAM_D02),
                paramD03 = double(S5MegpoidRuntimeOptionKeys.PARAM_D03),
                paramD04 = boolean(S5MegpoidRuntimeOptionKeys.PARAM_D04),
                paramD05 = double(S5MegpoidRuntimeOptionKeys.PARAM_D05),
                terminalSpectrumLogMagnitudePhase =
                    boolean(S5MegpoidRuntimeOptionKeys.TERMINAL_SPECTRUM_LOG_MAGNITUDE_PHASE),
                carrierFusionEnabled =
                    boolean(S5MegpoidRuntimeOptionKeys.CARRIER_FUSION_ENABLED),
                alternateTransitionEnabled =
                    optionalBoolean(S5MegpoidRuntimeOptionKeys.ALTERNATE_TRANSITION_ENABLED),
                multipartThresholdModeEnabled =
                    optionalBoolean(S5MegpoidRuntimeOptionKeys.MULTIPART_THRESHOLD_MODE_ENABLED),
                nearestPartMaskEnabled = nearestPartMaskEnabled,
                partModulationIdentifier = partModulationIdentifier
            )
        }
    }
}

/** Native reverse-transition branch selected by render-root byte +0x59. */
enum class S5MegpoidReverseTransitionMode {
    STANDARD_BANKED,
    ALTERNATE
}

/** Exact native static property keys used by the runtime-options constructor. */
object S5MegpoidRuntimeOptionKeys {
    const val PARAM_0 = "c1ae30c6c9eb0e4246a26be169bd4729"
    const val PARAM_1 = "ef68f87945291185d8e560e8b34ec81c"
    const val PARAM_2 = "32bb5ec4ed08a982d8ab95a84c8876c5"
    const val PARAM_3 = "591a7b0ec57fd6f02c56b9f281cd5484"
    const val PARAM_4 = "3ddd265ebb678e8eb166c668d192730c"
    const val PARAM_5 = "dc077a67cada0b3a08bcf186cf933f04"
    const val PARAM_6 = "e24517240ee66c01234cb4511909ebe6"
    const val PARAM_13 = "fde4054ae9d99cb5ab3cec9c8464d465"
    const val PARAM_14 = "75301487ab3961391c636424e32a15bb"
    const val PARAM_15 = "f9b7a6e841213ce00a270fa9f706b2e9"
    const val PARAM_D00 = "f4e011d914c8e2b43b6001d3b1b25d61"
    const val PARAM_D01 = "39561207bf2c0e5bb99f01061030745f"
    const val PARAM_D02 = "10f093ece4ee4d12f3ffcc1c7dfeade9"
    const val PARAM_D03 = "0aa79e92bcffa1a3a8b54fed33e6d511"
    const val PARAM_D04 = "c93358e17f2fe6605677e96149ca3b9c"
    const val PARAM_D05 = "d3767e0b7af5dfd6cb065658fd38853a"
    /** FUN_1800e6c20 -> render-root byte +0x48. */
    const val TERMINAL_SPECTRUM_LOG_MAGNITUDE_PHASE = "d22b2750ccebc1459f7a9d9ea7186bbf"
    /** FUN_1800dfb50 -> render-root byte +0x3559. */
    const val CARRIER_FUSION_ENABLED = "66bfcc4a31ba796aca88c5eae36870fc"
    /** FUN_1800e6de0 -> render-root byte +0x59: false=Banked, true=Alternate. */
    const val ALTERNATE_TRANSITION_ENABLED = "119558af50f9c8919539abd8e064b287"
    /** FUN_1800e2770 -> render-root byte +0x3411: optional, absent=false. */
    const val MULTIPART_THRESHOLD_MODE_ENABLED = "ef12c1f702abd89b2e1f07a126cc5411"
    /** FUN_1800e3030 -> render-root byte +0x3405: optional, absent=false. */
    const val NEAREST_PART_MASK_ENABLED = "7a5a0ba0ba2f00ab8a0a0d538add0e6d"
    /** FUN_1800e2e70 -> required string when +0x3405 is true. */
    const val PART_MODULATION_IDENTIFIER = "184ef4ded83d0b35d809a7f1b09b1066"
    const val EXPECTED_PART_MODULATION_IDENTIFIER = "117fd5ea7e76f7852c197a284c4dd3bc"
}

/**
 * Shape of the model-produced one-sided complex spectrum stored by S5API
 * at render-state root +0x4F8.
 *
 * The native runtime stores complexBinCount 8-byte complex values. The
 * Android/JNI boundary uses interleaved float32 values (real, imaginary).
 */
data class S5MegpoidSpectrumOutputShape(
    val fftSize: Int,
    val complexBinCount: Int
) {
    init {
        require(fftSize >= 2 && (fftSize and (fftSize - 1)) == 0) {
            "Megpoid spectral FFT size must be a positive power of two."
        }
        require(complexBinCount == fftSize / 2 + 1) {
            "One-sided real spectrum requires fftSize / 2 + 1 complex bins."
        }
        require(complexBinCount <= Int.MAX_VALUE / 2) {
            "Interleaved spectrum exceeds the JVM array limit."
        }
    }

    val interleavedFloatCount: Int
        get() = complexBinCount * 2

    /** Returns a fresh zeroed [real0, imag0, real1, imag1, ...] frame. */
    fun newInterleavedFrame(): FloatArray = FloatArray(interleavedFloatCount)

    fun requireInterleavedFrame(frame: FloatArray) {
        require(frame.size == interleavedFloatCount) {
            "Model spectrum frame must contain exactly $interleavedFloatCount float32 values."
        }
        require(frame.all { it.isFinite() }) {
            "Model spectrum frame contains a non-finite value."
        }
    }
}
