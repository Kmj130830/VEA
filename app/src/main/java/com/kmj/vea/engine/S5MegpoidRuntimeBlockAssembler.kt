package com.kmj.vea.engine

/**
 * Runtime-ready residual block assembled from the eight source properties in
 * AI_Megpoid.vtb2. The voicebank stores the conditioning matrix/vectors; this
 * class applies the exact preparation observed in FUN_1800d4bf0 and exposes
 * the resulting tensors in the layout consumed by the Android native kernel.
 */
class S5MegpoidCompiledRuntimeBlock internal constructor(
    val row: Int,
    val column: Int,
    val channels: Int,
    val expansionMultiplier: Int,
    val expandedFeatures: Int,
    /** Original [channel][height][width] layout used by the current reference kernel. */
    val depthwiseWeightsChannelMajor: FloatArray,
    /** Exact FUN_1800d4bf0 record layout: [channel][width][height]. */
    val nativeRecordDepthwiseFilter: FloatArray,
    val expansionWeights: FloatArray,
    val expansionGamma: FloatArray,
    val expansionBeta: FloatArray,
    val projectionWeights: FloatArray,
    val projectionBias: FloatArray,
    val residualScale: FloatArray
) {
    /** Every residual block owns an independent temporal history ring. */
    private val depthwiseHistory = S5MegpoidDepthwiseHistoryState(channels)

    /**
     * Append a sequential feature chunk and return [channel][7][position].
     * A chunk-length change resets the ring automatically.
     */
    fun buildHistoryWindow(residualInput: FloatArray): FloatArray {
        require(residualInput.isNotEmpty() && residualInput.size % channels == 0) {
            "Residual input must contain a non-empty, whole number of channel rows."
        }
        val positions = residualInput.size / channels
        require(positions >= S5MegpoidResidualBlockShape.KERNEL_WIDTH) {
            "The 7x7 reference kernel requires at least " +
                "${S5MegpoidResidualBlockShape.KERNEL_WIDTH} positions per chunk."
        }
        return depthwiseHistory.appendAndBuildWindow(residualInput)
    }

    /** Reset temporal state at the beginning of a new utterance. */
    fun resetHistory() = depthwiseHistory.reset()

    /**
     * Executes the isolated reference-kernel path on an already materialized
     * [channel][7][position] window. For sequential chunks, obtain that
     * window from [buildHistoryWindow] so this block's own temporal state is
     * preserved. The native depthwise arithmetic remains a reference kernel;
     * exact S5API equivalence still requires validation against runtime traces.
     */
    fun runReferenceWindow(
        historyRows: FloatArray,
        residualInput: FloatArray,
        epsilon: Float = S5MegpoidResidualBlockShape.DEFAULT_EPSILON
    ): FloatArray {
        require(residualInput.size % channels == 0) {
            "Residual input length must be divisible by the block channel count."
        }
        val positions = residualInput.size / channels
        val shape = S5MegpoidResidualBlockShape(
            channels = channels,
            expansionMultiplier = expansionMultiplier,
            positions = positions
        )
        shape.validateInputs(
            historyRows = historyRows,
            depthwiseWeights = depthwiseWeightsChannelMajor,
            expandWeights = expansionWeights,
            expandedBiasA = expansionGamma,
            expandedBiasB = expansionBeta,
            projectWeights = projectionWeights,
            residualInput = residualInput,
            residualScale = residualScale,
            residualBias = projectionBias,
            epsilon = epsilon
        )
        return NativeS5TensorBridge.runResidualDepthwiseMlpBlock(
            historyRows = historyRows,
            depthwiseWeights = depthwiseWeightsChannelMajor,
            expandWeights = expansionWeights,
            expandedBiasA = expansionGamma,
            expandedBiasB = expansionBeta,
            projectWeights = projectionWeights,
            residualInput = residualInput,
            residualScale = residualScale,
            residualBias = projectionBias,
            channels = channels,
            positions = positions,
            expandedFeatures = expandedFeatures,
            epsilon = epsilon
        )
    }
    /**
     * Runs one sequential feature chunk through this block's private history
     * ring and the isolated residual-block kernel. This is not a complete
     * voice synthesis path: callers must supply the actual input features,
     * and upstream/downstream model layers are still required.
     */
    fun runChunk(
        residualInput: FloatArray,
        epsilon: Float = S5MegpoidResidualBlockShape.DEFAULT_EPSILON
    ): FloatArray {
        val window = buildHistoryWindow(residualInput)
        return runReferenceWindow(window, residualInput, epsilon)
    }
}

/**
 * Reconstructs the exact per-block parameter layout used by FUN_1800d4bf0.
 *
 * For each expanded feature f and input channel c:
 *   effectiveWeight[f,c] = sourceWeight[f,c] * descriptorA[c]
 *   gamma[f] = sourceBias[f] + sum_c(sourceWeight[f,c] * descriptorB[c])
 *   beta[f] = sum_c(sourceWeight[f,c] * descriptorA[c])
 *
 * The depthwise filter is separately permuted from [C][H][W] to [C][W][H],
 * matching the native record builder. The original channel-major payload is
 * retained separately for the current reference convolution kernel.
 */
object S5MegpoidRuntimeBlockAssembler {
    const val DEPTHWISE_FILTER_HEIGHT = 7
    const val DEPTHWISE_FILTER_WIDTH = 7
    const val EXPECTED_CHANNELS = 32
    const val EXPECTED_EXPANDED_FEATURES = 64

    fun compile(
        index: S5MegpoidRuntimeBlockTensorIndex,
        row: Int,
        column: Int,
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidCompiledRuntimeBlock {
        require(row in 0 until S5MegpoidRuntimeBlockFactoryKey.TABLE_SIZE)
        require(column in 0 until S5MegpoidRuntimeBlockFactoryKey.TABLE_SIZE)

        val depthwiseSource = tensor(
            index, S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER, row, column,
            listOf(7, 7, 1, EXPECTED_CHANNELS), EXPECTED_CHANNELS * 49, tensorDecoder
        )
        val descriptorA = tensor(
            index, S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A, row, column,
            listOf(EXPECTED_CHANNELS), EXPECTED_CHANNELS, tensorDecoder
        )
        val descriptorB = tensor(
            index, S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B, row, column,
            listOf(EXPECTED_CHANNELS), EXPECTED_CHANNELS, tensorDecoder
        )
        val sourceExpansionWeights = tensor(
            index, S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS, row, column,
            listOf(1, 1, EXPECTED_CHANNELS, EXPECTED_EXPANDED_FEATURES),
            EXPECTED_CHANNELS * EXPECTED_EXPANDED_FEATURES, tensorDecoder
        )
        val sourceExpansionBias = tensor(
            index, S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS, row, column,
            listOf(EXPECTED_EXPANDED_FEATURES), EXPECTED_EXPANDED_FEATURES, tensorDecoder
        )
        val projectionWeights = tensor(
            index, S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS, row, column,
            listOf(1, 1, EXPECTED_EXPANDED_FEATURES, EXPECTED_CHANNELS),
            EXPECTED_CHANNELS * EXPECTED_EXPANDED_FEATURES, tensorDecoder
        )
        val projectionBias = tensor(
            index, S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS, row, column,
            listOf(EXPECTED_CHANNELS), EXPECTED_CHANNELS, tensorDecoder
        )
        val residualScale = tensor(
            index, S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE, row, column,
            listOf(1, 1, EXPECTED_CHANNELS, 1), EXPECTED_CHANNELS, tensorDecoder
        )

        val channels = EXPECTED_CHANNELS
        val expandedFeatures = EXPECTED_EXPANDED_FEATURES
        val expansionMultiplier = expandedFeatures / channels
        val expansionWeightCount = channels.toLong() * expandedFeatures.toLong()
        require(expansionWeightCount <= Int.MAX_VALUE) {
            "Expanded matrix exceeds the supported Kotlin array limit."
        }
        val effectiveExpansionWeights = FloatArray(expansionWeightCount.toInt())
        val gamma = FloatArray(expandedFeatures)
        val beta = FloatArray(expandedFeatures)

        for (feature in 0 until expandedFeatures) {
            var gammaValue = sourceExpansionBias[feature]
            var betaValue = 0.0f
            val featureOffset = feature * channels
            for (channel in 0 until channels) {
                val sourceWeight = sourceExpansionWeights[featureOffset + channel]
                val conditionedWeight = sourceWeight * descriptorA[channel]
                effectiveExpansionWeights[featureOffset + channel] = conditionedWeight
                gammaValue += sourceWeight * descriptorB[channel]
                betaValue += conditionedWeight
            }
            gamma[feature] = gammaValue
            beta[feature] = betaValue
        }

        val transposedDepthwiseFilter = transposeDepthwiseFilter(
            source = depthwiseSource,
            channels = channels,
            height = DEPTHWISE_FILTER_HEIGHT,
            width = DEPTHWISE_FILTER_WIDTH
        )

        return S5MegpoidCompiledRuntimeBlock(
            row = row,
            column = column,
            channels = channels,
            expansionMultiplier = expansionMultiplier,
            expandedFeatures = expandedFeatures,
            depthwiseWeightsChannelMajor = depthwiseSource,
            nativeRecordDepthwiseFilter = transposedDepthwiseFilter,
            expansionWeights = effectiveExpansionWeights,
            expansionGamma = gamma,
            expansionBeta = beta,
            projectionWeights = projectionWeights,
            projectionBias = projectionBias,
            residualScale = residualScale
        )
    }

    /**
     * Mirrors FUN_1800d4bf0's three-axis permutation:
     * source [C][H][W] index ((c * H + y) * W + x)
     * becomes native-record [C][W][H] index ((c * W + x) * H + y).
     */
    fun transposeDepthwiseFilter(
        source: FloatArray,
        channels: Int,
        height: Int,
        width: Int
    ): FloatArray {
        require(channels > 0 && height > 0 && width > 0)
        val expectedCount = channels.toLong() * height.toLong() * width.toLong()
        require(expectedCount <= Int.MAX_VALUE && source.size == expectedCount.toInt()) {
            "Depthwise filter tensor must contain channels * height * width values."
        }
        val destination = FloatArray(source.size)
        for (channel in 0 until channels) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sourceIndex = (channel * height + y) * width + x
                    val destinationIndex = (channel * width + x) * height + y
                    destination[destinationIndex] = source[sourceIndex]
                }
            }
        }
        return destination
    }

    private fun tensor(
        index: S5MegpoidRuntimeBlockTensorIndex,
        factory: S5MegpoidRuntimeBlockFactory,
        row: Int,
        column: Int,
        expectedMetadata: List<Int>,
        expectedElements: Int,
        decoder: S5TypedTensorDecoder
    ): FloatArray {
        val reference = index.uniqueTensorOrNull(factory, row, column)
            ?: throw IllegalArgumentException(
                "Missing \${factory.nativeFunction} tensor for block [\$row,\$column]."
            )
        require(reference.marker == "*F32") {
            "\${factory.nativeFunction} must be a float32 tensor for this voicebank."
        }
        require(reference.descriptorMetadata == expectedMetadata) {
            "\${factory.nativeFunction} metadata mismatch at block [\$row,\$column]: " +
                "expected \$expectedMetadata, got \${reference.descriptorMetadata}."
        }
        require(reference.elementCount == expectedElements) {
            "\${factory.nativeFunction} length mismatch at block [\$row,\$column]: " +
                "expected \$expectedElements, got \${reference.elementCount}."
        }
        return reference.decodeFloatArray(decoder)
    }
}
