package com.kmj.vea.engine

import kotlin.math.exp

/**
 * One vector-to-vector operator called by the S5API frame projection stack.
 *
 * FUN_180077520 dispatches these through a native vtable at +0x28. The
 * operator owns its weight layout and any model-specific arithmetic; the
 * surrounding stack owns the optional bias and activation order.
 *
 * The interface is intentionally voicebank-neutral. It does not claim that
 * any particular VOCALOID7.vtmg named tensor has been mapped to a slot yet.
 */
interface S5ApiFrameVectorOperator {
    val inputFeatures: Int
    val outputFeatures: Int

    fun project(input: FloatArray): FloatArray
}

/**
 * Row-major dense-vector implementation of an S5API frame operator.
 *
 * weights are stored as [outputFeature][inputFeature]. One vector is treated
 * as a single-position GEMM: C[output,0] = W[output,input] * X[input,0].
 */
class S5ApiFrameDenseVectorOperator(
    override val inputFeatures: Int,
    override val outputFeatures: Int,
    weights: FloatArray,
    private val gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
) : S5ApiFrameVectorOperator {
    private val weights = weights.copyOf()

    init {
        require(inputFeatures > 0 && outputFeatures > 0)
        require(this.weights.size.toLong() ==
            inputFeatures.toLong() * outputFeatures.toLong()) {
            "Frame dense weights must contain outputFeatures * inputFeatures values."
        }
        require(this.weights.all { it.isFinite() }) {
            "Frame dense weights contain a non-finite value."
        }
    }

    override fun project(input: FloatArray): FloatArray {
        require(input.size == inputFeatures) {
            "Frame dense input must contain exactly $inputFeatures values."
        }
        require(input.all { it.isFinite() }) {
            "Frame dense input contains a non-finite value."
        }
        val output = gemm.multiply(
            a = weights,
            b = input,
            c = FloatArray(outputFeatures),
            m = outputFeatures,
            n = 1,
            k = inputFeatures
        )
        require(output.size == outputFeatures && output.all { it.isFinite() }) {
            "Frame dense operator returned an invalid output vector."
        }
        return output
    }

    fun copyWeights(): FloatArray = weights.copyOf()
}

/** Projection plus its optional S5API bias vector. */
class S5ApiFrameProjectionLayer(
    val operator: S5ApiFrameVectorOperator,
    bias: FloatArray? = null
) {
    private val biasValues = bias?.copyOf()

    init {
        require(operator.inputFeatures > 0 && operator.outputFeatures > 0)
        require(biasValues == null || biasValues.size == operator.outputFeatures) {
            "Frame layer bias must contain one value per output feature."
        }
        require(biasValues == null || biasValues.all { it.isFinite() }) {
            "Frame layer bias contains a non-finite value."
        }
    }

    val inputFeatures: Int get() = operator.inputFeatures
    val outputFeatures: Int get() = operator.outputFeatures
    val hasBias: Boolean get() = biasValues != null

    internal fun forward(input: FloatArray): FloatArray {
        val projected = operator.project(input)
        require(projected.size == outputFeatures) {
            "Frame projection returned an unexpected output width."
        }
        val bias = biasValues
        if (bias != null) {
            for (index in projected.indices) projected[index] += bias[index]
        }
        require(projected.all { it.isFinite() }) {
            "Frame projection plus bias produced a non-finite value."
        }
        return projected
    }

    fun copyBias(): FloatArray? = biasValues?.copyOf()
}

enum class S5ApiFrameHiddenActivation {
    /** Native mode +0xF4 == 1: 2 / (exp(-2*x) + 1) - 1. */
    TANH,

    /** Native mode +0xF4 != 1: x for x >= 0, otherwise x * 0.01. */
    LEAKY_RELU
}

/**
 * Portable computational port of FUN_180077520.
 *
 * Native order:
 * 1. Run each configured pre-output projection in order.
 * 2. Add that layer's optional bias.
 * 3. Apply the configured hidden activation after every layer:
 *    - tanh when the native mode field at +0xF4 is 1;
 *    - otherwise leaky ReLU with slope 0.01, optionally clamped to 4 when
 *      the native flag at +0xFC is enabled.
 * 4. Run the final projection and its optional bias.
 * 5. Apply tanh only when the native final-activation field at +0xF8 is 1.
 *
 * This class ports the scalar/vector arithmetic contract only. The native
 * object-table loader, vtable operator construction, branch-indexed table
 * selection, and proof that any specific VTMG tensor supplies these operators
 * remain separate tasks.
 */
class S5ApiFrameProjectionStack(
    preOutputLayers: List<S5ApiFrameProjectionLayer>,
    val finalProjection: S5ApiFrameProjectionLayer,
    val hiddenActivation: S5ApiFrameHiddenActivation,
    val clampLeakyReluToFour: Boolean = false,
    val finalTanhEnabled: Boolean = false
) {
    private val preOutputLayers = preOutputLayers.toList()

    val inputFeatures: Int
    val hiddenFeatures: Int
    val outputFeatures: Int
    val preOutputLayerCount: Int get() = preOutputLayers.size

    init {
        require(finalProjection.inputFeatures > 0)
        require(finalProjection.outputFeatures > 0)

        if (this.preOutputLayers.isEmpty()) {
            inputFeatures = finalProjection.inputFeatures
            hiddenFeatures = finalProjection.inputFeatures
        } else {
            inputFeatures = this.preOutputLayers.first().inputFeatures
            hiddenFeatures = this.preOutputLayers.first().outputFeatures

            require(this.preOutputLayers.first().outputFeatures > 0)
            this.preOutputLayers.forEachIndexed { index, layer ->
                if (index > 0) {
                    require(layer.inputFeatures == hiddenFeatures &&
                        layer.outputFeatures == hiddenFeatures) {
                        "Native hidden projection $index must preserve the configured hidden width."
                    }
                }
            }
            require(finalProjection.inputFeatures == hiddenFeatures) {
                "Final frame projection expects ${finalProjection.inputFeatures} features; " +
                    "the hidden stack produces $hiddenFeatures."
            }
        }
    }

    /** Processes one complete feature vector and returns the final output vector. */
    fun forward(input: FloatArray): FloatArray {
        require(input.size == inputFeatures) {
            "Frame stack input must contain exactly $inputFeatures values."
        }
        require(input.all { it.isFinite() }) {
            "Frame stack input contains a non-finite value."
        }

        var features = input.copyOf()
        for (layer in preOutputLayers) {
            features = applyHiddenActivation(layer.forward(features))
        }

        var output = finalProjection.forward(features)
        if (finalTanhEnabled) {
            output = FloatArray(outputFeatures) { index -> nativeTanh(output[index]) }
        }
        require(output.size == outputFeatures && output.all { it.isFinite() }) {
            "Frame stack produced an invalid output vector."
        }
        return output
    }

    private fun applyHiddenActivation(values: FloatArray): FloatArray =
        FloatArray(values.size) { index ->
            val value = values[index]
            when (hiddenActivation) {
                S5ApiFrameHiddenActivation.TANH -> nativeTanh(value)
                S5ApiFrameHiddenActivation.LEAKY_RELU -> {
                    val leaky = if (value < 0f) value * LEAKY_RELU_SLOPE else value
                    if (clampLeakyReluToFour) minOf(leaky, LEAKY_RELU_MAX) else leaky
                }
            }
        }

    private fun nativeTanh(value: Float): Float {
        // Equivalent scalar expression used by FUN_180077520.
        val exponential = exp(-2.0f * value)
        return 2.0f / (exponential + 1.0f) - 1.0f
    }

    companion object {
        const val LEAKY_RELU_SLOPE = 0.01f
        const val LEAKY_RELU_MAX = 4.0f
    }
}
