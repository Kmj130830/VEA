package com.kmj.vea.engine

/**
 * One pre-frame projection plus the residual vector added by FUN_18003e920
 * immediately after the operator returns.
 *
 * The projection's model-specific weights/layout stay behind
 * [S5ApiFrameVectorOperator]; the residual is a separate native state vector,
 * not silently folded into a bias.
 */
class S5ApiFrameResidualProjection(
    val operator: S5ApiFrameVectorOperator,
    residual: FloatArray
) {
    private val residualValues = residual.copyOf()

    init {
        require(residualValues.size == operator.outputFeatures) {
            "Frame residual must contain one value per projection output feature."
        }
        require(residualValues.all { it.isFinite() }) {
            "Frame residual contains a non-finite value."
        }
    }

    fun forward(input: FloatArray): FloatArray {
        val output = operator.project(input)
        require(output.size == operator.outputFeatures) {
            "Frame projection returned an unexpected residual width."
        }
        for (index in output.indices) output[index] += residualValues[index]
        require(output.all { it.isFinite() }) {
            "Frame projection plus residual produced a non-finite value."
        }
        return output
    }

    fun copyResidual(): FloatArray = residualValues.copyOf()
}

/**
 * The two projection/residual/tanh stages at the start of FUN_18003e920.
 *
 * Native order:
 *   projection A -> residual A -> tanh
 *   projection B -> residual B -> tanh
 *
 * The final stage then appends a separately supplied feature tail. Only the
 * tail is multiplied by [tailScale]. If [appendScaleSentinel] is true, the
 * native wrapper writes the unscaled tailScale as one final element.
 *
 * This is the generic S5API VSM pre-frame path. It is deliberately independent
 * from the Megpoid VTB2 32-to-8 output projection and from the vector stack in
 * FUN_180077520.
 */
class S5ApiFramePreProjectionChain(
    private val first: S5ApiFrameResidualProjection,
    private val second: S5ApiFrameResidualProjection
) {
    val inputFeatures: Int get() = first.operator.inputFeatures
    val intermediateFeatures: Int get() = first.operator.outputFeatures
    val baseFrameFeatures: Int get() = second.operator.outputFeatures

    init {
        require(second.operator.inputFeatures == intermediateFeatures) {
            "Second frame projection input must match the first projection output."
        }
    }

    fun projectBaseFrame(input: FloatArray): FloatArray {
        require(input.size == inputFeatures) {
            "Frame pre-projection input must contain exactly $inputFeatures values."
        }
        require(input.all { it.isFinite() }) {
            "Frame pre-projection input contains a non-finite value."
        }

        val firstOutput = applyNativeTanh(first.forward(input))
        val secondOutput = applyNativeTanh(second.forward(firstOutput))
        check(secondOutput.size == baseFrameFeatures)
        return secondOutput
    }

    /**
     * Matches FUN_18003e920's tail handling before FUN_180076080.
     *
     * [frameTail] corresponds to the caller-supplied tail at param_4.
     * [tailScale] corresponds to param_5 and is applied to tail values only.
     * The optional final marker corresponds to the one-value write controlled
     * by the flag at param_1 + 0xae.
     */
    fun buildFrameInput(
        input: FloatArray,
        frameTail: FloatArray,
        tailScale: Float,
        appendScaleSentinel: Boolean
    ): FloatArray {
        require(tailScale.isFinite()) { "Frame tail scale must be finite." }
        require(frameTail.all { it.isFinite() }) {
            "Frame tail contains a non-finite value."
        }

        val base = projectBaseFrame(input)
        val total = base.size.toLong() + frameTail.size.toLong() +
            if (appendScaleSentinel) 1L else 0L
        require(total in 1..Int.MAX_VALUE.toLong()) {
            "Frame input exceeds the supported FloatArray size."
        }

        val result = FloatArray(total.toInt())
        base.copyInto(result, destinationOffset = 0)
        for (index in frameTail.indices) {
            result[base.size + index] = tailScale * frameTail[index]
        }
        if (appendScaleSentinel) {
            result[result.lastIndex] = tailScale
        }
        require(result.all { it.isFinite() }) {
            "Prepared frame input contains a non-finite value."
        }
        return result
    }

    private fun applyNativeTanh(values: FloatArray): FloatArray =
        FloatArray(values.size) { index ->
            S5ApiFrameMath.standardTanh(values[index])
        }
}
