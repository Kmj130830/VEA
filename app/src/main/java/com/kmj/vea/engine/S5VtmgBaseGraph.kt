package com.kmj.vea.engine

/**
 * Tensor descriptor recovered from the FUN_180068190 -> FUN_180064670 call sites.
 *
 * S5API passes axis0Count as param_5, axis1Count as param_4, and matrixCount
 * as param_6. The native loader stores each bank as [axis1][alignUp(axis0, 8)].
 * Bias vectors contain axis0Count values.
 */
data class S5VtmgGraphTensorSpec(
    val layerName: String,
    val axis0Count: Int,
    val axis1Count: Int,
    val matrixCount: Int
) {
    init {
        require(layerName.isNotBlank())
        require(axis0Count > 0 && axis1Count > 0 && matrixCount > 0)
    }

    val paddedAxis0Count: Int
        get() = (axis0Count + 7) and 7.inv()

    val weightElementCount: Int
        get() = (axis0Count.toLong() * axis1Count * matrixCount).toInt()

    val biasElementCount: Int
        get() = axis0Count

    val packedWeightElementCount: Int
        get() = (paddedAxis0Count.toLong() * axis1Count * matrixCount).toInt()

    val weightKey: String
        get() = S5ModelTensorKey.deriveDigestHex(layerName, S5ModelTensorKey.Member.WEIGHT)

    val biasKey: String
        get() = S5ModelTensorKey.deriveDigestHex(layerName, S5ModelTensorKey.Member.BIAS)
}

data class S5VtmgGraphLayerWeights(
    val layerName: String,
    val weight: FloatArray,
    val bias: FloatArray
)

data class S5VtmgBaseGraphWeights(
    val layers: List<S5VtmgGraphLayerWeights>
) {
    val totalFloatCount: Long
        get() = layers.sumOf { it.weight.size.toLong() + it.bias.size }

    fun requireLayer(name: String): S5VtmgGraphLayerWeights =
        layers.singleOrNull { it.layerName == name }
            ?: throw IllegalArgumentException("VTMG base graph has no layer '$name'.")
}

/**
 * One matrix bank after the exact S5API descriptor packing operation.
 * Each matrix occupies axis1Count * paddedAxis0Count floats.
 */
data class S5VtmgPackedGraphLayerWeights(
    val layerName: String,
    val matrices: FloatArray,
    val bias: FloatArray,
    val axis0Count: Int,
    val axis1Count: Int,
    val matrixCount: Int,
    val paddedAxis0Count: Int
) {
    val matrixStride: Int
        get() = axis1Count * paddedAxis0Count

    /**
     * Converts this layer's VTMG-packed banks to row-major v0_naive GEMM A
     * matrices: [bank][outputFeature][inputFeature].
     */
    fun toV0NaiveGemmWeights(
        convert: (FloatArray, Int, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::convertVtmgBankWeightsForV0Naive
    ): FloatArray {
        val converted = convert(
            matrices, axis0Count, axis1Count, paddedAxis0Count, matrixCount
        )
        val expected = axis0Count.toLong() * axis1Count * matrixCount
        require(expected == converted.size.toLong()) {
            "VTMG layer '$layerName' conversion returned an invalid GEMM weight length."
        }
        require(converted.all { it.isFinite() }) {
            "VTMG layer '$layerName' converted weights contain a non-finite value."
        }
        return converted
    }

    /**
     * Creates an explicit banked-projection plan for FUN_1800c6390.
     * Weight conversion is [bank][output][input]; stage assignment is left
     * to the recovered renderer binding layer.
     */
    fun toBankedProjectionPlan(
        convert: (FloatArray, Int, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::convertVtmgBankWeightsForV0Naive
    ): S5VtmgBankedProjectionPlan =
        S5VtmgBankedProjectionPlan(
            weights = toV0NaiveGemmWeights(convert),
            bias = bias.copyOf(),
            inputFeatures = axis1Count,
            outputFeatures = axis0Count,
            banks = matrixCount
        )

    /**
     * Converts packed matrix banks to the row-major temporal-kernel layout
     * consumed by S5API's c5440/c72d0 projections:
     * [outputFeature][inputFeature][kernelTap].
     *
     * This is valid only when the model's matrix-bank axis represents kernel
     * taps; callers must establish that from the render-state descriptor.
     */
    fun toTemporalConv1dWeights(kernelWidth: Int = matrixCount): FloatArray {
        require(kernelWidth > 0 && kernelWidth == matrixCount) {
            "VTMG matrix-bank count must equal the temporal kernel width."
        }
        require(axis0Count > 0 && axis1Count > 0 && paddedAxis0Count >= axis0Count)
        val expectedPacked = axis1Count.toLong() * paddedAxis0Count * matrixCount
        require(expectedPacked == matrices.size.toLong()) {
            "VTMG layer '$layerName' has an invalid packed matrix-bank length."
        }
        val expected = axis0Count.toLong() * axis1Count * kernelWidth
        require(expected <= Int.MAX_VALUE.toLong()) {
            "VTMG layer '$layerName' temporal weights exceed the supported array size."
        }

        val converted = FloatArray(expected.toInt())
        for (output in 0 until axis0Count) {
            for (input in 0 until axis1Count) {
                for (tap in 0 until kernelWidth) {
                    val sourceIndex =
                        (tap * axis1Count + input) * paddedAxis0Count + output
                    val targetIndex =
                        (output * axis1Count + input) * kernelWidth + tap
                    converted[targetIndex] = matrices[sourceIndex]
                }
            }
        }
        require(converted.all { it.isFinite() }) {
            "VTMG layer '$layerName' temporal weights contain a non-finite value."
        }
        return converted
    }
}


/**
 * Ready-to-call parameters for S5API FUN_1800c6390 / the VEA banked linear
 * projection bridge. This is a tensor-layout plan only; it does not assign a
 * VTMG layer to a particular renderer stage.
 *
 * weights: [bank][outputFeature][inputFeature]
 * bias: [outputFeature]
 * result: [outputFeature][position][bank]
 */
data class S5VtmgBankedProjectionPlan(
    val weights: FloatArray,
    val bias: FloatArray,
    val inputFeatures: Int,
    val outputFeatures: Int,
    val banks: Int
) {
    init {
        require(inputFeatures > 0 && outputFeatures > 0 && banks > 0)
        val bankStride = outputFeatures.toLong() * inputFeatures
        require(bankStride <= Int.MAX_VALUE.toLong()) {
            "Banked projection matrix stride exceeds the supported array size."
        }
        val expectedWeights = bankStride * banks
        require(expectedWeights == weights.size.toLong()) {
            "Banked projection weights have an invalid element count."
        }
        require(bias.size == outputFeatures) {
            "Banked projection bias must contain one value per output feature."
        }
        require(weights.all { it.isFinite() } && bias.all { it.isFinite() }) {
            "Banked projection parameters contain a non-finite value."
        }
    }

    val weightRowStride: Int
        get() = inputFeatures

    val weightBankStride: Int
        get() = outputFeatures * inputFeatures

    fun project(input: FloatArray, positions: Int): FloatArray {
        require(positions > 0)
        val expectedInput = inputFeatures.toLong() * positions
        require(expectedInput == input.size.toLong()) {
            "Input length must match [inputFeature][position]."
        }
        val outputPerBank = outputFeatures.toLong() * positions
        require(outputPerBank <= Int.MAX_VALUE.toLong() / banks.toLong()) {
            "Banked projection output exceeds the supported array size."
        }
        return NativeS5TensorBridge.applyBankedLinearProjection(
            weights = weights,
            weightRowStride = weightRowStride,
            weightBankStride = weightBankStride,
            input = input,
            bias = bias,
            inputFeatures = inputFeatures,
            outputFeatures = outputFeatures,
            positions = positions,
            banks = banks
        )
    }
}

data class S5VtmgPackedBaseGraphWeights(
    val layers: List<S5VtmgPackedGraphLayerWeights>
) {
    val totalFloatCount: Long
        get() = layers.sumOf { it.matrices.size.toLong() + it.bias.size }

    fun requireLayer(name: String): S5VtmgPackedGraphLayerWeights =
        layers.singleOrNull { it.layerName == name }
            ?: throw IllegalArgumentException("Packed VTMG base graph has no layer '$name'.")
}

/**
 * Catalog and validator for the shared V7 base graph in VOCALOID7.vtmg.
 *
 * The axes and bank counts come from the arguments prepared at each
 * FUN_180068190 call to FUN_180064670. Matching those dimensions against the
 * 18 actual VTMG tensor lengths resolves C=32, pre1 input width=82,
 * conditioning width=26, and a three-matrix bank for each conv layer.
 *
 * Axis names are kept neutral: the descriptor's packed layout is known, while
 * every caller's higher-level feature semantics are not yet fully recovered.
 */
object S5VtmgGraphTensorCatalog {
    const val BASE_GRAPH_OBJECT_KEY = "6957915c8dfb4d554a8df92ee40e523d"

    val layers: List<S5VtmgGraphTensorSpec> = listOf(
        S5VtmgGraphTensorSpec("pre1", axis0Count = 32, axis1Count = 82, matrixCount = 1),
        S5VtmgGraphTensorSpec("pre2", axis0Count = 32, axis1Count = 32, matrixCount = 1),
        S5VtmgGraphTensorSpec("conv1", axis0Count = 64, axis1Count = 32, matrixCount = 3),
        S5VtmgGraphTensorSpec("cond1", axis0Count = 64, axis1Count = 26, matrixCount = 1),
        S5VtmgGraphTensorSpec("conv2", axis0Count = 64, axis1Count = 32, matrixCount = 3),
        S5VtmgGraphTensorSpec("cond2", axis0Count = 64, axis1Count = 26, matrixCount = 1),
        S5VtmgGraphTensorSpec("conv3", axis0Count = 64, axis1Count = 32, matrixCount = 3),
        S5VtmgGraphTensorSpec("cond3", axis0Count = 64, axis1Count = 26, matrixCount = 1),
        S5VtmgGraphTensorSpec("linear", axis0Count = 3, axis1Count = 32, matrixCount = 1)
    )

    val expectedTensorKeys: Set<String>
        get() = layers.flatMap { listOf(it.weightKey, it.biasKey) }.toSet()

    fun decode(
        scan: S5ModelJsonScanResult,
        decodeFloatArray: (String) -> FloatArray,
        decodeInt32Array: (String) -> IntArray = {
            throw IllegalArgumentException("The VTMG base graph contains no integer tensor fields.")
        }
    ): S5VtmgBaseGraphWeights {
        require(BASE_GRAPH_OBJECT_KEY in scan.matchedObjectKeys) {
            "VOCALOID7.vtmg does not contain the expected base-graph object."
        }
        val matches = scan.matchedTensorStrings[BASE_GRAPH_OBJECT_KEY]
            ?: throw IllegalArgumentException("The VTMG base-graph object has no typed tensor fields.")
        val actualKeys = matches.map { it.fieldKey }.toSet()
        val missing = expectedTensorKeys - actualKeys
        val unexpected = actualKeys - expectedTensorKeys
        require(missing.isEmpty()) {
            "VTMG base graph is missing expected tensor keys: ${missing.sorted().joinToString()}."
        }
        require(unexpected.isEmpty() && matches.size == expectedTensorKeys.size) {
            "VTMG base graph contains unexpected or duplicate tensor fields."
        }
        require(matches.all { it.typeTag == "*F32" }) {
            "VTMG base graph contains a tensor encoding other than *F32."
        }

        val decoded = S5ModelTensorDecoder.decodeMatches(
            matches = matches,
            decodeFloatArray = decodeFloatArray,
            decodeInt32Array = decodeInt32Array
        ).associateBy { it.fieldKey }

        val result = layers.map { spec ->
            val weight = requireNotNull(decoded[spec.weightKey]) {
                "VTMG layer '${spec.layerName}' is missing its weight tensor."
            }
            val bias = requireNotNull(decoded[spec.biasKey]) {
                "VTMG layer '${spec.layerName}' is missing its bias tensor."
            }
            require(weight.kind == S5DecodedTensorKind.FLOAT32 && bias.kind == S5DecodedTensorKind.FLOAT32) {
                "VTMG layer '${spec.layerName}' must use float32 weights and bias."
            }
            val weightValues = requireNotNull(weight.floatValues)
            val biasValues = requireNotNull(bias.floatValues)
            require(weightValues.size == spec.weightElementCount) {
                "VTMG layer '${spec.layerName}' weight has ${weightValues.size} values; " +
                    "expected ${spec.weightElementCount}."
            }
            require(biasValues.size == spec.biasElementCount) {
                "VTMG layer '${spec.layerName}' bias has ${biasValues.size} values; " +
                    "expected ${spec.biasElementCount}."
            }
            S5VtmgGraphLayerWeights(
                layerName = spec.layerName,
                weight = weightValues,
                bias = biasValues
            )
        }
        return S5VtmgBaseGraphWeights(result)
    }
}

/**
 * Converts the decoded tensors into the matrix-bank layout allocated by
 * S5API FUN_180064670: [bank][axis1][alignUp(axis0, 8)].
 */
object S5VtmgBaseGraphPacker {
    fun pack(
        graph: S5VtmgBaseGraphWeights,
        packMatrixBank: (FloatArray, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packMatrixBank
    ): S5VtmgPackedBaseGraphWeights {
        require(graph.layers.size == S5VtmgGraphTensorCatalog.layers.size) {
            "VTMG base graph must contain exactly nine layers."
        }

        val packed = S5VtmgGraphTensorCatalog.layers.map { spec ->
            val source = graph.requireLayer(spec.layerName)
            require(source.weight.size == spec.weightElementCount) {
                "VTMG layer '${spec.layerName}' has an invalid raw weight length."
            }
            require(source.bias.size == spec.biasElementCount) {
                "VTMG layer '${spec.layerName}' has an invalid bias length."
            }
            require(source.weight.all { it.isFinite() } && source.bias.all { it.isFinite() }) {
                "VTMG layer '${spec.layerName}' contains a non-finite parameter."
            }

            val matrices = packMatrixBank(
                source.weight,
                spec.axis0Count,
                spec.axis1Count,
                spec.matrixCount
            )
            require(matrices.size == spec.packedWeightElementCount) {
                "S5API packing returned an invalid matrix-bank length for '${spec.layerName}'."
            }
            require(matrices.all { it.isFinite() }) {
                "Packed VTMG layer '${spec.layerName}' contains a non-finite parameter."
            }

            // FUN_180006a80 allocations are 32-byte aligned and every descriptor
            // pads axis0 to eight floats. The unused columns must remain zero.
            if (spec.paddedAxis0Count > spec.axis0Count) {
                for (bank in 0 until spec.matrixCount) {
                    for (row in 0 until spec.axis1Count) {
                        val rowStart = (bank * spec.axis1Count + row) * spec.paddedAxis0Count
                        for (column in spec.axis0Count until spec.paddedAxis0Count) {
                            require(matrices[rowStart + column] == 0.0f) {
                                "S5API padding is nonzero in VTMG layer '${spec.layerName}'."
                            }
                        }
                    }
                }
            }

            S5VtmgPackedGraphLayerWeights(
                layerName = spec.layerName,
                matrices = matrices,
                bias = source.bias.copyOf(),
                axis0Count = spec.axis0Count,
                axis1Count = spec.axis1Count,
                matrixCount = spec.matrixCount,
                paddedAxis0Count = spec.paddedAxis0Count
            )
        }
        return S5VtmgPackedBaseGraphWeights(packed)
    }
}

/**
 * Loads the nine shared base-network weight/bias pairs from the installed VTMG.
 * The file's outer container and JSON body are decoded by the existing S5API
 * bridges; this function does not execute the graph.
 */
object S5VtmgBaseGraphLoader {
    fun load(
        vtmgFile: java.io.File,
        decodeFloatArray: (String) -> FloatArray = NativeS5TensorBridge::decodeTypedFloatArray,
        decodeInt32Array: (String) -> IntArray = NativeS5TensorBridge::decodeTypedInt32Array
    ): S5VtmgBaseGraphWeights {
        val scan = S5ModelContainerScanner.scanContainerFileForObjectKeys(
            containerFile = vtmgFile,
            expectedKind = "VTMG",
            targetObjectKeys = setOf(S5VtmgGraphTensorCatalog.BASE_GRAPH_OBJECT_KEY)
        )
        return S5VtmgGraphTensorCatalog.decode(scan, decodeFloatArray, decodeInt32Array)
    }
}
