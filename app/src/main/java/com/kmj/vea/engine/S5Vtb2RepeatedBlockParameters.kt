package com.kmj.vea.engine

import java.io.File

/**
 * One repeated VTB2 parameter record from the nine-element model array.
 *
 * The source tensor lengths are exact. The dimensional factorisation
 * [bankedWeightValues = 3 * 128 * 64] and
 * [singleWeightValues = 64 * 128] is recorded as a shape hypothesis based on
 * the VTB2 config scalars and renderer block dimensions. The serialization
 * order and the exact c5d50 descriptor field consuming each tensor are not
 * asserted here.
 */
data class S5Vtb2RepeatedBlockParameterPack(
    val index: Int,
    val bankedWeightValues: FloatArray,
    val bankedBiasValues: FloatArray,
    val singleWeightValues: FloatArray
) {
    init {
        require(index >= 0)
        require(bankedWeightValues.size == BANKED_WEIGHT_COUNT) {
            "VTB2 repeated parameter[$index] has ${bankedWeightValues.size} banked weights; " +
                "expected $BANKED_WEIGHT_COUNT."
        }
        require(bankedBiasValues.size == BANKED_BIAS_COUNT) {
            "VTB2 repeated parameter[$index] has ${bankedBiasValues.size} bias values; " +
                "expected $BANKED_BIAS_COUNT."
        }
        require(singleWeightValues.size == SINGLE_WEIGHT_COUNT) {
            "VTB2 repeated parameter[$index] has ${singleWeightValues.size} single-matrix weights; " +
                "expected $SINGLE_WEIGHT_COUNT."
        }
        require(bankedWeightValues.all { it.isFinite() } &&
            bankedBiasValues.all { it.isFinite() } &&
            singleWeightValues.all { it.isFinite() }) {
            "VTB2 repeated parameter[$index] contains a non-finite value."
        }
    }

    companion object {
        const val BANK_COUNT = 3
        const val BANKED_OUTPUT_WIDTH = 128
        const val BANKED_INPUT_WIDTH = 64
        const val BANKED_WEIGHT_COUNT = BANK_COUNT * BANKED_OUTPUT_WIDTH * BANKED_INPUT_WIDTH
        const val BANKED_BIAS_COUNT = BANKED_OUTPUT_WIDTH
        const val SINGLE_OUTPUT_WIDTH = 64
        const val SINGLE_INPUT_WIDTH = 128
        const val SINGLE_WEIGHT_COUNT = SINGLE_OUTPUT_WIDTH * SINGLE_INPUT_WIDTH
    }
}

data class S5Vtb2RepeatedBlockParameterSet(
    val declaredBlockCount: Int,
    val blocks: List<S5Vtb2RepeatedBlockParameterPack>
) {
    init {
        require(declaredBlockCount > 0)
        require(blocks.size == declaredBlockCount) {
            "VTB2 declares $declaredBlockCount repeated parameter records but " +
                "${blocks.size} tensor records were decoded."
        }
        require(blocks.map { it.index } == (0 until declaredBlockCount).toList()) {
            "VTB2 repeated parameter indices are missing or out of order."
        }
    }

    val totalFloatCount: Long
        get() = blocks.sumOf {
            it.bankedWeightValues.size.toLong() +
                it.bankedBiasValues.size +
                it.singleWeightValues.size
        }
}

/**
 * Reads the repeated VTB2 tensor array by full JSON value paths.
 *
 * Two independent pieces of source evidence are checked:
 * - the parent model configuration scalar d31a declares 9 repeated entries;
 * - the model object c688... contains a 9-item 60934... array.
 *
 * Hashed leaf field names are reused throughout the container, so each tensor
 * is selected by its complete path rather than a bare field hash.
 */
object S5Vtb2RepeatedBlockParameterLoader {
    const val ROOT_MODEL_CONFIG_OBJECT_KEY = "a4ba655a061e6151f80574e6088492a0"
    const val MODEL_CONFIG_ENTRY_KEY = "b4a5a1d9f51f573d714597f1e5e64f17"
    const val DECLARED_BLOCK_COUNT_KEY = "d31a34cf5e8888d62df2d9b4e32b80f1"

    const val MODEL_OBJECT_KEY = "6dbe9f75292d82b3cc6c57bd65089b20"
    const val MODEL_PARAMETER_OBJECT_KEY = "c688ac0ef614fc2004874245946c9580"
    const val BLOCK_ARRAY_KEY = "60934ffa2a466aa4dea617defa78f36b"
    const val BANKED_PARAMETER_OBJECT_KEY = "3d8a9ac90f7ac150415ce60a7b3aea38"
    const val SINGLE_PARAMETER_OBJECT_KEY = "88f599b6848cb38e980ea3467fbec4b6"
    const val WEIGHT_FIELD_KEY = "30c16498ff30815c43d58f610a91f938"
    const val BIAS_FIELD_KEY = "c169d8ef47cc23f097ff312d08a6f829"

    fun load(
        vtb2File: File,
        decodeFloatArray: (String) -> FloatArray = NativeS5TensorBridge::decodeTypedFloatArray,
        decodeInt32Array: (String) -> IntArray = NativeS5TensorBridge::decodeTypedInt32Array
    ): S5Vtb2RepeatedBlockParameterSet {
        require(vtb2File.isFile) { "The Megpoid VTB2 resource does not exist." }
        check(NativeS5TensorBridge.isLoaded) {
            "The native S5API container and typed-tensor decoders are not loaded."
        }
        val scan = S5ModelContainerScanner.scanVtb2ForObjectKeys(
            vtb2File,
            setOf(ROOT_MODEL_CONFIG_OBJECT_KEY, MODEL_OBJECT_KEY)
        )
        return decode(scan, decodeFloatArray, decodeInt32Array)
    }

    fun decode(
        scan: S5ModelJsonScanResult,
        decodeFloatArray: (String) -> FloatArray,
        decodeInt32Array: (String) -> IntArray = {
            throw IllegalArgumentException("The repeated VTB2 block tensors are expected to be float32.")
        }
    ): S5Vtb2RepeatedBlockParameterSet {
        require(ROOT_MODEL_CONFIG_OBJECT_KEY in scan.matchedObjectKeys) {
            "The Megpoid VTB2 root model configuration object was not found."
        }
        require(MODEL_OBJECT_KEY in scan.matchedObjectKeys) {
            "The Megpoid VTB2 model parameter object was not found."
        }

        val blockCountPath =
            "$.$ROOT_MODEL_CONFIG_OBJECT_KEY.$MODEL_CONFIG_ENTRY_KEY.$DECLARED_BLOCK_COUNT_KEY"
        val countMatch = scan.matchedValues[ROOT_MODEL_CONFIG_OBJECT_KEY]
            .orEmpty()
            .singleOrNull { it.valuePath == blockCountPath }
            ?: throw IllegalArgumentException(
                "The VTB2 repeated-block count field is missing or duplicated."
            )
        require(countMatch.kind == S5JsonScalarKind.NUMBER) {
            "The VTB2 repeated-block count is not numeric."
        }
        val declaredCount = countMatch.value?.toIntOrNull()
            ?: throw IllegalArgumentException("The VTB2 repeated-block count is invalid.")
        require(declaredCount == EXPECTED_BLOCK_COUNT) {
            "This Megpoid VTB2 model declares $declaredCount repeated parameters; " +
                "expected $EXPECTED_BLOCK_COUNT."
        }

        val arrayPrefix =
            "$.$MODEL_OBJECT_KEY.$MODEL_PARAMETER_OBJECT_KEY.$BLOCK_ARRAY_KEY"
        val tensorMatches = scan.matchedTensorStrings[MODEL_OBJECT_KEY].orEmpty()
            .associateBy { it.valuePath }

        fun decodeTensor(path: String, expectedCount: Int): FloatArray {
            val match = tensorMatches[path]
                ?: throw IllegalArgumentException("Missing VTB2 tensor at '$path'.")
            require(match.typeTag == "*F32") {
                "VTB2 tensor at '$path' uses ${match.typeTag}; expected *F32."
            }
            val decoded = S5ModelTensorDecoder.decodeMatches(
                matches = listOf(match),
                decodeFloatArray = decodeFloatArray,
                decodeInt32Array = decodeInt32Array
            ).single()
            require(decoded.kind == S5DecodedTensorKind.FLOAT32)
            val values = requireNotNull(decoded.floatValues)
            require(values.size == expectedCount) {
                "VTB2 tensor at '$path' has ${values.size} values; expected $expectedCount."
            }
            return values
        }

        val blocks = (0 until declaredCount).map { index ->
            val itemPath = "$arrayPrefix[$index]"
            val bankedPath = "$itemPath.$BANKED_PARAMETER_OBJECT_KEY"
            val singlePath = "$itemPath.$SINGLE_PARAMETER_OBJECT_KEY"
            S5Vtb2RepeatedBlockParameterPack(
                index = index,
                bankedWeightValues = decodeTensor(
                    "$bankedPath.$WEIGHT_FIELD_KEY",
                    S5Vtb2RepeatedBlockParameterPack.BANKED_WEIGHT_COUNT
                ),
                bankedBiasValues = decodeTensor(
                    "$bankedPath.$BIAS_FIELD_KEY",
                    S5Vtb2RepeatedBlockParameterPack.BANKED_BIAS_COUNT
                ),
                singleWeightValues = decodeTensor(
                    "$singlePath.$WEIGHT_FIELD_KEY",
                    S5Vtb2RepeatedBlockParameterPack.SINGLE_WEIGHT_COUNT
                )
            )
        }
        return S5Vtb2RepeatedBlockParameterSet(declaredCount, blocks)
    }

    const val EXPECTED_BLOCK_COUNT = 9
}
