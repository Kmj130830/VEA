package com.kmj.vea.engine

import java.util.Base64

/** Named layer members resolved by FUN_180068190 in VOCALOID7.vtmg. */
enum class S5MegpoidNamedLayer(
    val modelName: String,
    val matrixCount: Int,
    /** Pair of pointers: banked weight descriptors followed by the bias descriptor. */
    val descriptorPairOffsetBytes: Int
) {
    PRE1("pre1", 1, 0x20),
    PRE2("pre2", 1, 0x30),
    CONV1("conv1", 3, 0x40),
    COND1("cond1", 1, 0x50),
    CONV2("conv2", 3, 0x60),
    COND2("cond2", 1, 0x70),
    CONV3("conv3", 3, 0x80),
    COND3("cond3", 1, 0x90),
    LINEAR("linear", 1, 0xa0)
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
 * Exact matrix descriptor dimensions recovered from FUN_180064670.
 *
 * axis0 is the bias-vector element count (param_5); axis1 is
 * weightElementsPerMatrix / axis0 (param_4). Each matrix stores axis1 rows
 * with axis0 values per row, padded to a multiple of eight.
 */
data class S5MegpoidNamedLayerShape(
    val matrixCount: Int,
    val axis0Count: Int,
    val axis1Count: Int,
    val paddedAxis0Count: Int,
    val weightElementsPerMatrix: Int,
    val biasElementCount: Int
)

data class S5MegpoidPackedNamedLayerMatrixBank(
    val shape: S5MegpoidNamedLayerShape,
    /** Matrix-by-matrix concatenation; each matrix is axis1Count * paddedAxis0Count. */
    val packedValues: FloatArray
)

data class S5MegpoidPackedNamedLayer(
    val layer: S5MegpoidNamedLayer,
    val shape: S5MegpoidNamedLayerShape,
    val packedWeightValues: FloatArray,
    /** Unpadded bias values, retained for diagnostics and higher-level operators. */
    val biasValues: FloatArray,
    /** One-row bias BlockMatrix values with the same axis0 padding as the vendor loader. */
    val packedBiasValues: FloatArray
)

data class S5MegpoidNamedLayerParameters(
    val layer: S5MegpoidNamedLayer,
    val weight: S5MegpoidNamedLayerTensorReference,
    val bias: S5MegpoidNamedLayerTensorReference
) {
    fun shapeOrNull(): S5MegpoidNamedLayerShape? {
        val banks = layer.matrixCount
        val weights = weight.elementCount
        val biases = bias.elementCount
        if (banks <= 0 || weights <= 0 || biases <= 0) return null
        val bankCount = banks.toLong()
        val axis0 = biases.toLong()
        val totalDivisor = axis0 * bankCount
        if (totalDivisor <= 0L || weights.toLong() % totalDivisor != 0L) return null

        val axis1 = weights.toLong() / totalDivisor
        if (axis1 <= 0L || axis1 > Int.MAX_VALUE) return null
        val paddedAxis0 = ((axis0 + 7L) / 8L) * 8L
        if (paddedAxis0 > Int.MAX_VALUE) return null
        val expectedElements = axis0 * axis1 * bankCount
        if (expectedElements != weights.toLong()) return null
        val perMatrix = axis0 * axis1
        if (perMatrix > Int.MAX_VALUE) return null

        return S5MegpoidNamedLayerShape(
            matrixCount = banks,
            axis0Count = axis0.toInt(),
            axis1Count = axis1.toInt(),
            paddedAxis0Count = paddedAxis0.toInt(),
            weightElementsPerMatrix = perMatrix.toInt(),
            biasElementCount = biases
        )
    }

    fun decodeBiasArray(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): FloatArray = bias.decodeFloatArray(tensorDecoder)

    /**
     * Packs bias as the vendor's separate one-row matrix descriptor:
     * axis0 = bias length, axis1 = 1, padded axis0 = alignUp(axis0, 8).
     */
    fun packBiasVector(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        biasPacker: (FloatArray, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packRowMajor
    ): FloatArray {
        val shape = shapeOrNull()
            ?: throw IllegalStateException(
                "Named layer '${layer.modelName}' has inconsistent weight/bias element counts."
            )
        val decodedBias = decodeBiasArray(tensorDecoder)
        val packed = biasPacker(decodedBias, shape.axis0Count, 1)
        check(packed.size == shape.paddedAxis0Count) {
            "Native bias packing returned an unexpected element count for '${layer.modelName}'."
        }
        return packed
    }

    /**
     * Decodes source-order weights [axis0][axis1][matrix] and packs them with
     * the same 8-element axis0 padding used by the S5API BlockMatrix objects.
     */
    fun packWeightMatrixBank(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        matrixPacker: (FloatArray, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packMatrixBank
    ): S5MegpoidPackedNamedLayerMatrixBank {
        val shape = shapeOrNull()
            ?: throw IllegalStateException(
                "Named layer '${layer.modelName}' has inconsistent weight/bias element counts."
            )
        val decodedWeights = weight.decodeFloatArray(tensorDecoder)
        val packed = matrixPacker(
            decodedWeights,
            shape.axis0Count,
            shape.axis1Count,
            shape.matrixCount
        )
        val expectedPackedElements = shape.axis1Count.toLong() *
            shape.paddedAxis0Count.toLong() * shape.matrixCount.toLong()
        check(expectedPackedElements == packed.size.toLong()) {
            "Native matrix packing returned an unexpected element count for '${layer.modelName}'."
        }
        return S5MegpoidPackedNamedLayerMatrixBank(shape, packed)
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

    /**
     * Prepares every named layer for a native forward path: typed weight data
     * is converted to the S5API BlockMatrix storage layout and each bias vector
     * is decoded. This is parameter preparation only; it does not infer audio.
     */
    fun packAllLayers(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        matrixPacker: (FloatArray, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packMatrixBank,
        biasPacker: (FloatArray, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packRowMajor
    ): List<S5MegpoidPackedNamedLayer> = layers.map { parameters ->
        val packed = parameters.packWeightMatrixBank(tensorDecoder, matrixPacker)
        val biasValues = parameters.decodeBiasArray(tensorDecoder)
        check(biasValues.size == packed.shape.axis0Count) {
            "Bias length does not match axis0 for '${parameters.layer.modelName}'."
        }
        val packedBias = parameters.packBiasVector(tensorDecoder, biasPacker)
        S5MegpoidPackedNamedLayer(
            layer = parameters.layer,
            shape = packed.shape,
            packedWeightValues = packed.packedValues,
            biasValues = biasValues,
            packedBiasValues = packedBias
        )
    }

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
                val match = modelTree.findUniqueByLookupKeyRecursive(lookupKey)
                    ?: throw IllegalArgumentException(
                        "Named model property '$propertyName' (key $lookupKey) is missing from the model tree."
                    )
                val value = match.value as? S5ModelValue.StringValue
                    ?: throw IllegalArgumentException(
                        "Named model property '$propertyName' at ${match.path} is not a typed string."
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
