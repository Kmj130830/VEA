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
    val paramD05: Double
) {
    init {
        require(param5 > 0) { "Native param[5] sequence-slot count must be positive." }
        require(param6 > 0) { "Native param[6] residual-column count must be positive." }
        require(paramD02.isFinite() && paramD03.isFinite() && paramD05.isFinite()) {
            "Runtime option floating-point fields must be finite."
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
                paramD05 = double(S5MegpoidRuntimeOptionKeys.PARAM_D05)
            )
        }
    }
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
}
