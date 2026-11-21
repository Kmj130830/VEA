package com.kmj.vea.engine

/**
 * Shape contract recovered from VOCALOID7 S5API FUN_18005ebe0.
 *
 * The reader allocates three gate rows per hidden feature. Each recurrent
 * direction has weight_ih [3 * hiddenFeatures][inputFeatures] and
 * weight_hh [3 * hiddenFeatures][hiddenFeatures]. This class describes and
 * validates those tensors; it deliberately does not claim the RNN forward
 * equations or gate ordering have been recovered.
 */
data class S5RecurrentLayerLayout(
    val inputFeatures: Int,
    val hiddenFeatures: Int,
    val layerCount: Int,
    val bidirectional: Boolean,
    val hasBias: Boolean
) {
    init {
        require(inputFeatures > 0) { "RNN input feature count must be positive." }
        require(hiddenFeatures > 0) { "RNN hidden feature count must be positive." }
        require(layerCount in 1..128) { "RNN layer count must be between 1 and 128." }
        checkedElementCount(hiddenFeatures, GATE_COUNT)
        checkedElementCount(hiddenFeatures, directionCount)
    }

    val directionCount: Int
        get() = if (bidirectional) 2 else 1

    val gateRows: Int
        get() = checkedElementCount(hiddenFeatures, GATE_COUNT)

    fun inputFeaturesForLayer(layerIndex: Int): Int {
        require(layerIndex in 0 until layerCount) { "RNN layer index is out of range." }
        return if (layerIndex == 0) {
            inputFeatures
        } else {
            checkedElementCount(hiddenFeatures, directionCount)
        }
    }

    companion object {
        /** S5API's recurrent parameter reader multiplies hidden size by three. */
        const val GATE_COUNT = 3

        private fun checkedElementCount(a: Int, b: Int): Int {
            val result = a.toLong() * b.toLong()
            require(result in 1..Int.MAX_VALUE.toLong()) { "RNN tensor dimensions overflow." }
            return result.toInt()
        }
    }
}

data class S5RecurrentTensorSpec(
    val name: String,
    val rows: Int,
    val columns: Int,
    val member: S5ModelTensorKey.Member
) {
    /** Hashed key used by the decoded S5API JSON-like model object. */
    val lookupKey: String
        get() = S5ModelTensorKey.derive(name, member)

    val elementCount: Int
        get() {
            val count = rows.toLong() * columns.toLong()
            require(count in 1..Int.MAX_VALUE.toLong()) { "RNN tensor size overflows." }
            return count.toInt()
        }
}

data class S5RecurrentDirectionParameters(
    val layerIndex: Int,
    val reverseDirection: Boolean,
    val inputFeatures: Int,
    val hiddenFeatures: Int,
    val inputWeights: FloatArray,
    val recurrentWeights: FloatArray,
    val inputBias: FloatArray?,
    val recurrentBias: FloatArray?
)

data class S5PackedRecurrentMatrix(
    val rows: Int,
    val logicalColumns: Int,
    val paddedColumns: Int,
    val values: FloatArray
)

data class S5PackedRecurrentDirection(
    val layerIndex: Int,
    val reverseDirection: Boolean,
    val inputWeights: S5PackedRecurrentMatrix,
    val recurrentWeights: S5PackedRecurrentMatrix,
    val inputBias: FloatArray?,
    val recurrentBias: FloatArray?
)

/**
 * Converts decoded S5 recurrent tensors into the padded row-major matrix
 * representation used by the existing native S5 tensor bridge.
 *
 * tensors is the already-decoded name -> float-array map. The outer VTB2
 * object-tree parser and the RNN forward pass are separate, unfinished parts.
 */
object S5RecurrentParameterLoader {
    private const val DEFAULT_BASE = "rnn"

    fun specifications(
        layout: S5RecurrentLayerLayout,
        base: String = DEFAULT_BASE
    ): List<S5RecurrentTensorSpec> {
        require(base.isNotBlank() && base.all { it.code in 0x20..0x7e }) {
            "RNN parameter base must be printable ASCII."
        }
        val specs = ArrayList<S5RecurrentTensorSpec>()
        for (layer in 0 until layout.layerCount) {
            val inputFeatures = layout.inputFeaturesForLayer(layer)
            for (direction in 0 until layout.directionCount) {
                val reverse = direction == 1
                specs += S5RecurrentTensorSpec(
                    parameterName(base, ".weight_ih_l", layer, reverse),
                    layout.gateRows,
                    inputFeatures,
                    S5ModelTensorKey.Member.WEIGHT
                )
                specs += S5RecurrentTensorSpec(
                    parameterName(base, ".weight_hh_l", layer, reverse),
                    layout.gateRows,
                    layout.hiddenFeatures,
                    S5ModelTensorKey.Member.WEIGHT
                )
                if (layout.hasBias) {
                    specs += S5RecurrentTensorSpec(
                        parameterName(base, ".bias_ih_l", layer, reverse),
                        layout.gateRows,
                        1,
                        S5ModelTensorKey.Member.BIAS
                    )
                    specs += S5RecurrentTensorSpec(
                        parameterName(base, ".bias_hh_l", layer, reverse),
                        layout.gateRows,
                        1,
                        S5ModelTensorKey.Member.BIAS
                    )
                }
            }
        }
        return specs
    }

    /**
     * Validates all tensors required for one recurrent parameter set and
     * returns stable copies. Extra map entries are ignored because a full
     * model contains parameters for operators outside this recurrent layer.
     */
    fun validate(
        tensors: Map<String, FloatArray>,
        layout: S5RecurrentLayerLayout,
        base: String = DEFAULT_BASE
    ): List<S5RecurrentDirectionParameters> {
        val result = ArrayList<S5RecurrentDirectionParameters>()
        for (layer in 0 until layout.layerCount) {
            val inputFeatures = layout.inputFeaturesForLayer(layer)
            for (direction in 0 until layout.directionCount) {
                val reverse = direction == 1
                val inputName = parameterName(base, ".weight_ih_l", layer, reverse)
                val recurrentName = parameterName(base, ".weight_hh_l", layer, reverse)
                val inputWeights = requiredTensor(
                    tensors, inputName, checkedProduct(layout.gateRows, inputFeatures),
                    S5ModelTensorKey.Member.WEIGHT
                )
                val recurrentWeights = requiredTensor(
                    tensors, recurrentName,
                    checkedProduct(layout.gateRows, layout.hiddenFeatures),
                    S5ModelTensorKey.Member.WEIGHT
                )
                val inputBias = if (layout.hasBias) {
                    requiredTensor(
                        tensors,
                        parameterName(base, ".bias_ih_l", layer, reverse),
                        layout.gateRows,
                        S5ModelTensorKey.Member.BIAS
                    )
                } else {
                    null
                }
                val recurrentBias = if (layout.hasBias) {
                    requiredTensor(
                        tensors,
                        parameterName(base, ".bias_hh_l", layer, reverse),
                        layout.gateRows,
                        S5ModelTensorKey.Member.BIAS
                    )
                } else {
                    null
                }

                result += S5RecurrentDirectionParameters(
                    layerIndex = layer,
                    reverseDirection = reverse,
                    inputFeatures = inputFeatures,
                    hiddenFeatures = layout.hiddenFeatures,
                    inputWeights = inputWeights,
                    recurrentWeights = recurrentWeights,
                    inputBias = inputBias,
                    recurrentBias = recurrentBias
                )
            }
        }
        return result
    }

    /**
     * Packs validated weight matrices through NativeS5TensorBridge.packRowMajor.
     * The returned matrix rows remain 3 * hiddenFeatures; only the column
     * dimension is padded to an eight-float boundary.
     */
    fun pack(
        tensors: Map<String, FloatArray>,
        layout: S5RecurrentLayerLayout,
        base: String = DEFAULT_BASE
    ): List<S5PackedRecurrentDirection> {
        check(NativeS5TensorBridge.isLoaded) {
            "Native S5 tensor library is required to pack recurrent matrices."
        }
        return validate(tensors, layout, base).map { parameters ->
            val inputPadded = alignColumns(parameters.inputFeatures)
            val hiddenPadded = alignColumns(parameters.hiddenFeatures)
            S5PackedRecurrentDirection(
                layerIndex = parameters.layerIndex,
                reverseDirection = parameters.reverseDirection,
                inputWeights = S5PackedRecurrentMatrix(
                    rows = layout.gateRows,
                    logicalColumns = parameters.inputFeatures,
                    paddedColumns = inputPadded,
                    values = NativeS5TensorBridge.packRowMajor(
                        parameters.inputWeights,
                        parameters.inputFeatures,
                        layout.gateRows
                    )
                ),
                recurrentWeights = S5PackedRecurrentMatrix(
                    rows = layout.gateRows,
                    logicalColumns = parameters.hiddenFeatures,
                    paddedColumns = hiddenPadded,
                    values = NativeS5TensorBridge.packRowMajor(
                        parameters.recurrentWeights,
                        parameters.hiddenFeatures,
                        layout.gateRows
                    )
                ),
                inputBias = parameters.inputBias,
                recurrentBias = parameters.recurrentBias
            )
        }
    }

    private fun parameterName(
        base: String,
        suffix: String,
        layerIndex: Int,
        reverseDirection: Boolean
    ): String {
        require(layerIndex >= 0)
        val fallback = buildString {
            append(base)
            append(suffix)
            append(layerIndex)
            if (reverseDirection) append("_reverse")
        }
        if (NativeS5TensorBridge.isLoaded) {
            val native = NativeS5TensorBridge.buildRecurrentParameterName(
                base, suffix, layerIndex, reverseDirection
            )
            check(native == fallback) { "Native and Kotlin S5 parameter names disagree." }
            return native
        }
        return fallback
    }

    private fun requiredTensor(
        tensors: Map<String, FloatArray>,
        name: String,
        expectedCount: Int,
        member: S5ModelTensorKey.Member
    ): FloatArray {
        val lookupKey = S5ModelTensorKey.derive(name, member)
        val values = tensors[lookupKey] ?: throw IllegalArgumentException(
            "Required S5 recurrent tensor '$name' (model key '$lookupKey') is missing."
        )
        require(values.size == expectedCount) {
            "S5 recurrent tensor '$name' has ${values.size} values; expected $expectedCount."
        }
        require(values.all { it.isFinite() }) {
            "S5 recurrent tensor '$name' contains a non-finite value."
        }
        return values.copyOf()
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val result = a.toLong() * b.toLong()
        require(result in 1..Int.MAX_VALUE.toLong()) { "RNN tensor size overflows." }
        return result.toInt()
    }

    private fun alignColumns(columns: Int): Int {
        val aligned = (columns.toLong() + 7L) and -8L
        require(aligned in 1..Int.MAX_VALUE.toLong()) { "Padded RNN matrix width overflows." }
        return aligned.toInt()
    }
}
