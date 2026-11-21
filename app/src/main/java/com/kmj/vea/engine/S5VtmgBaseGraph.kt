package com.kmj.vea.engine

/**
 * S5API FUN_180068190 loads these named layer parameters from the object
 * passed to FUN_180064670. The digest keys and element counts below were
 * cross-checked against the supplied VOCALOID7.vtmg JSON payload.
 *
 * Counts are scalar element counts only. The loader has not yet recovered
 * every matrix axis/stride semantic, so this catalog deliberately avoids
 * claiming a complete operator tensor shape.
 */
data class S5VtmgGraphTensorSpec(
    val layerName: String,
    val weightElementCount: Int,
    val biasElementCount: Int
) {
    val weightKey: String
        get() = S5ModelTensorKey.derive(layerName, S5ModelTensorKey.Member.WEIGHT)

    val biasKey: String
        get() = S5ModelTensorKey.derive(layerName, S5ModelTensorKey.Member.BIAS)
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
        get() = layers.sumOf { it.weight.size.toLong() + it.bias.size.toLong() }

    fun requireLayer(name: String): S5VtmgGraphLayerWeights =
        layers.singleOrNull { it.layerName == name }
            ?: throw IllegalArgumentException("VTMG base graph has no layer '$name'.")
}

/**
 * Catalog and validator for the shared V7 base graph in VOCALOID7.vtmg.
 *
 * The 9 layer names match the literal strings passed by FUN_180068190 to
 * FUN_180064670: pre1, pre2, conv1/cond1, conv2/cond2, conv3/cond3, linear.
 */
object S5VtmgGraphTensorCatalog {
    const val BASE_GRAPH_OBJECT_KEY = "6957915c8dfb4d554a8df92ee40e523d"

    val layers: List<S5VtmgGraphTensorSpec> = listOf(
        S5VtmgGraphTensorSpec("pre1", 2624, 32),
        S5VtmgGraphTensorSpec("pre2", 1024, 32),
        S5VtmgGraphTensorSpec("conv1", 6144, 64),
        S5VtmgGraphTensorSpec("cond1", 1664, 64),
        S5VtmgGraphTensorSpec("conv2", 6144, 64),
        S5VtmgGraphTensorSpec("cond2", 1664, 64),
        S5VtmgGraphTensorSpec("conv3", 6144, 64),
        S5VtmgGraphTensorSpec("cond3", 1664, 64),
        S5VtmgGraphTensorSpec("linear", 96, 3)
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
