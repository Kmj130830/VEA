package com.kmj.vea.engine

/**
 * Optional accelerated kernel used by FUN_18007a0a0 when output width is
 * divisible by 64. The native state object stores this function pointer at
 * +0x398. Its inputs are the input-major weights, input vector, zeroed output
 * vector, input width and output width.
 */
fun interface S5ApiFrameAlignedDenseKernel {
    fun project(
        weightsInputMajor: FloatArray,
        input: FloatArray,
        output: FloatArray,
        inputFeatures: Int,
        outputFeatures: Int
    )
}

/**
 * Native-storage-order dense vector operator from FUN_18007a0a0.
 *
 * Unlike conventional row-major [output][input] Dense matrices, the native
 * descriptor stores [inputFeature][outputFeature] values with output width as
 * the row stride:
 *
 *   output[o] += input[i] * weights[i * outputFeatures + o]
 *
 * The scalar path below mirrors the non-64-aligned native loop. If output
 * width is a multiple of 64 and an accelerated kernel is supplied, the
 * operator dispatches through that kernel, matching the +0x398 branch.
 *
 * This is an operator primitive only. It does not establish which named
 * VOCALOID7.vtmg tensor, if any, is loaded into this descriptor.
 */
class S5ApiFrameInputMajorDenseVectorOperator(
    override val inputFeatures: Int,
    override val outputFeatures: Int,
    weightsInputMajor: FloatArray,
    private val alignedKernel: S5ApiFrameAlignedDenseKernel? = null
) : S5ApiFrameVectorOperator {
    private val weights = weightsInputMajor.copyOf()

    init {
        require(inputFeatures > 0 && outputFeatures > 0)
        require(this.weights.size.toLong() ==
            inputFeatures.toLong() * outputFeatures.toLong()) {
            "Input-major frame weights must contain inputFeatures * outputFeatures values."
        }
        require(this.weights.all { it.isFinite() }) {
            "Input-major frame weights contain a non-finite value."
        }
    }

    override fun project(input: FloatArray): FloatArray {
        require(input.size == inputFeatures) {
            "Native frame operator input must contain exactly $inputFeatures values."
        }
        require(input.all { it.isFinite() }) {
            "Native frame operator input contains a non-finite value."
        }

        val output = FloatArray(outputFeatures)
        val accelerated = alignedKernel
        if ((outputFeatures and 0x3f) == 0 && accelerated != null) {
            accelerated.project(
                weightsInputMajor = weights,
                input = input,
                output = output,
                inputFeatures = inputFeatures,
                outputFeatures = outputFeatures
            )
        } else {
            for (inputFeature in 0 until inputFeatures) {
                val inputValue = input[inputFeature]
                val weightOffset = inputFeature * outputFeatures
                for (outputFeature in 0 until outputFeatures) {
                    output[outputFeature] +=
                        inputValue * weights[weightOffset + outputFeature]
                }
            }
        }

        require(output.all { it.isFinite() }) {
            "Native frame operator produced a non-finite output."
        }
        return output
    }

    fun copyWeightsInputMajor(): FloatArray = weights.copyOf()
}
