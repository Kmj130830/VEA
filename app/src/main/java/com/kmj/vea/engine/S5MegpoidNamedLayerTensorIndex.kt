package com.kmj.vea.engine

import java.util.Base64

/** Named layer members resolved by FUN_180068190 in VOCALOID7.vtmg. */
enum class S5MegpoidNamedLayer(
    val modelName: String,
    val matrixCount: Int
) {
    PRE1("pre1", 1),
    PRE2("pre2", 1),
    CONV1("conv1", 3),
    COND1("cond1", 1),
    CONV2("conv2", 3),
    COND2("cond2", 1),
    CONV3("conv3", 3),
    COND3("cond3", 1),
    LINEAR("linear", 1)
}

/**
 * A lazily decoded *F32 or *F16 tensor reached through a named layer property.
 * [elementCount] is metadata only; [decodeFloatArray] materializes float data.
 */
class S5MegpoidNamedLayerTensorReference internal constructor(
    val layer: S5MegpoidNamedLayer,
    val propertyName: String,
    val lookupKey: String,
    val marker: String,
    val elementCount: Int,
    private val encodedValue: S5ModelValue.StringValue
) {
    fun decodeFloatArray(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): FloatArray {
        val decoded = encodedValue.asFloatArray(tensorDecoder)
        check(decoded.size == elementCount) {
            "Decoded tensor '$propertyName' does not match its indexed element count."
        }
        return decoded
    }
}

/**
 * Arithmetic shape evidence for a named banked matrix.
 *
 * These fields deliberately avoid row/column naming: element counts and the
 * constructor's matrix-bank count are known, but the semantic axis orientation
 * has not been established for every named layer call site.
 */
data class S5MegpoidNamedLayerShapeCandidate(
    val matrixCount: Int,
    val weightElementsPerMatrix: Int,
    val biasElementCount: Int,
    val weightToBiasElementRatio: Int
)

data class S5MegpoidNamedLayerParameters(
    val layer: S5MegpoidNamedLayer,
    val weight: S5MegpoidNamedLayerTensorReference,
    val bias: S5MegpoidNamedLayerTensorReference
) {
    fun shapeCandidateOrNull(): S5MegpoidNamedLayerShapeCandidate? {
        val banks = layer.matrixCount
        val weights = weight.elementCount
        val biases = bias.elementCount
        if (banks <= 0 || weights <= 0 || biases <= 0) return null
        val divisor = biases.toLong() * banks.toLong()
        if (divisor <= 0L || weights.toLong() % divisor != 0L) return null
        val perMatrix = weights.toLong() / banks.toLong()
        if (perMatrix > Int.MAX_VALUE) return null
        val ratio = perMatrix / biases.toLong()
        if (perMatrix % biases.toLong() != 0L || ratio <= 0L || ratio > Int.MAX_VALUE) {
            return null
        }
        return S5MegpoidNamedLayerShapeCandidate(
            matrixCount = banks,
            weightElementsPerMatrix = perMatrix.toInt(),
            biasElementCount = biases,
            weightToBiasElementRatio = ratio.toInt()
        )
    }
}

/**
 * Index of the nine named model layers stored in VOCALOID7.vtmg.
 *
 * Parameter names follow the observed FUN_180068190 / FUN_180064670 path:
 * "<layer>.weight" and "<layer>.bias". Lookup keys are derived using the
 * supplied S5API salted-MD5 hasher. Tensor weights stay encoded until decoded.
 */
class S5MegpoidNamedLayerTensorIndex private constructor(
    private val layerMap: Map<S5MegpoidNamedLayer, S5MegpoidNamedLayerParameters>
) {
    val layers: List<S5MegpoidNamedLayerParameters>
        get() = S5MegpoidNamedLayer.entries.mapNotNull(layerMap::get)

    fun layer(id: S5MegpoidNamedLayer): S5MegpoidNamedLayerParameters? = layerMap[id]

    fun requireLayer(id: S5MegpoidNamedLayer): S5MegpoidNamedLayerParameters =
        layerMap[id] ?: throw IllegalArgumentException(
            "Named model layer '${id.modelName}' is missing from VOCALOID7.vtmg."
        )

    companion object {
        fun build(
            modelTree: S5ModelObjectTree,
            keyHasher: (String) -> String = NativeS5TensorBridge::hashParameterLookupKey
        ): S5MegpoidNamedLayerTensorIndex {
            fun readTensor(
                layer: S5MegpoidNamedLayer,
                suffix: String
            ): S5MegpoidNamedLayerTensorReference {
                val propertyName = "${layer.modelName}.$suffix"
                val lookupKey = keyHasher(propertyName)
                val value = modelTree.findByLookupKey(lookupKey) as? S5ModelValue.StringValue
                    ?: throw IllegalArgumentException(
                        "Named model property '$propertyName' is missing or is not a typed string."
                    )
                val marker = value.value.take(4)
                require(marker == "*F32" || marker == "*F16") {
                    "Named model property '$propertyName' is not a floating-point tensor."
                }
                val payload = value.value.substring(4)
                val byteCount = try {
                    Base64.getDecoder().decode(payload).size
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "Named model property '$propertyName' has malformed Base64 data.",
                        error
                    )
                }
                val scalarBytes = if (marker == "*F16") 2 else 4
                require(byteCount > 0 && byteCount % scalarBytes == 0) {
                    "Named model property '$propertyName' is empty or misaligned."
                }
                val elements = byteCount / scalarBytes
                return S5MegpoidNamedLayerTensorReference(
                    layer = layer,
                    propertyName = propertyName,
                    lookupKey = lookupKey,
                    marker = marker,
                    elementCount = elements,
                    encodedValue = value
                )
            }

            val layers = S5MegpoidNamedLayer.entries.associateWith { layer ->
                S5MegpoidNamedLayerParameters(
                    layer = layer,
                    weight = readTensor(layer, "weight"),
                    bias = readTensor(layer, "bias")
                )
            }
            return S5MegpoidNamedLayerTensorIndex(layers)
        }
    }
}
