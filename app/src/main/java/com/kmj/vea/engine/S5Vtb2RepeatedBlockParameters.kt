package com.kmj.vea.engine

import java.io.File

/**
 * One VTB2 parameter record from the separate nine-element model array.
 *
 * The source tensor lengths are exact. The dimensional factorisation
 * [tensorAValues = 3 * 128 * 64] and
 * [tensorCValues = 64 * 128] is recorded as a shape hypothesis based on
 * the VTB2 config scalars and renderer block dimensions. The serialization
 * order and the exact c5d50 descriptor field consuming each tensor are not
 * asserted here.
 */
data class S5Vtb2RepeatedParameterPack(
    val index: Int,
    val tensorAValues: FloatArray,
    val biasValues: FloatArray,
    val tensorCValues: FloatArray
) {
    init {
        require(index >= 0)
        require(tensorAValues.size == TENSOR_A_VALUE_COUNT) {
            "VTB2 record[$index] has ${tensorAValues.size} tensor A values; " +
                "expected $TENSOR_A_VALUE_COUNT."
        }
        require(biasValues.size == BIAS_VALUE_COUNT) {
            "VTB2 record[$index] has ${biasValues.size} bias values; " +
                "expected $BIAS_VALUE_COUNT."
        }
        require(tensorCValues.size == TENSOR_C_VALUE_COUNT) {
            "VTB2 record[$index] has ${tensorCValues.size} tensor C values; " +
                "expected $TENSOR_C_VALUE_COUNT."
        }
        require(tensorAValues.all { it.isFinite() } &&
            biasValues.all { it.isFinite() } &&
            tensorCValues.all { it.isFinite() }) {
            "VTB2 record[$index] contains a non-finite value."
        }
    }

    companion object {
        const val TENSOR_A_AXIS_FACTOR = 3
        const val TENSOR_A_OUT_FACTOR = 128
        const val TENSOR_A_IN_FACTOR = 64
        const val TENSOR_A_VALUE_COUNT = TENSOR_A_AXIS_FACTOR * TENSOR_A_OUT_FACTOR * TENSOR_A_IN_FACTOR
        const val BIAS_VALUE_COUNT = TENSOR_A_OUT_FACTOR
        const val TENSOR_C_OUT_FACTOR = 64
        const val TENSOR_C_IN_FACTOR = 128
        const val TENSOR_C_VALUE_COUNT = TENSOR_C_OUT_FACTOR * TENSOR_C_IN_FACTOR
        const val TENSORS_PER_RECORD = 3
    }
}

data class S5Vtb2RepeatedParameterSet(
    val declaredRecordCount: Int,
    val records: List<S5Vtb2RepeatedParameterPack>
) {
    init {
        require(declaredRecordCount > 0)
        require(records.size == declaredRecordCount) {
            "VTB2 declares $declaredRecordCount record records but " +
                "${records.size} tensor records were decoded."
        }
        require(records.map { it.index } == (0 until declaredRecordCount).toList()) {
            "VTB2 record indices are missing or out of order."
        }
    }

    val totalFloatCount: Long
        get() = records.sumOf {
            it.tensorAValues.size.toLong() +
                it.biasValues.size +
                it.tensorCValues.size
        }
}

/**
 * Reads the separate VTB2 tensor array by full JSON value paths.
 *
 * Two independent pieces of source evidence are checked:
 * - the parent model configuration scalar d31a declares 9 record entries;
 * - the model object c688... contains a 9-item 60934... array.
 *
 * Hashed leaf field names are reused throughout the container, so each tensor
 * is selected by its complete path rather than a bare field hash.
 */
object S5Vtb2RepeatedParameterLoader {
    const val ROOT_MODEL_CONFIG_OBJECT_KEY = "a4ba655a061e6151f80574e6088492a0"
    const val MODEL_CONFIG_ENTRY_KEY = "b4a5a1d9f51f573d714597f1e5e64f17"
    const val DECLARED_RECORD_COUNT_KEY = "d31a34cf5e8888d62df2d9b4e32b80f1"

    const val MODEL_OBJECT_KEY = "6dbe9f75292d82b3cc6c57bd65089b20"
    const val MODEL_PARAMETER_OBJECT_KEY = "c688ac0ef614fc2004874245946c9580"
    const val RECORD_ARRAY_KEY = "60934ffa2a466aa4dea617defa78f36b"
    const val TENSOR_A_OBJECT_KEY = "3d8a9ac90f7ac150415ce60a7b3aea38"
    const val TENSOR_C_OBJECT_KEY = "88f599b6848cb38e980ea3467fbec4b6"
    const val WEIGHT_FIELD_KEY = "30c16498ff30815c43d58f610a91f938"
    const val BIAS_FIELD_KEY = "c169d8ef47cc23f097ff312d08a6f829"

    fun load(
        vtb2File: File,
        decodeFloatArray: (String) -> FloatArray = NativeS5TensorBridge::decodeTypedFloatArray,
        decodeInt32Array: (String) -> IntArray = NativeS5TensorBridge::decodeTypedInt32Array
    ): S5Vtb2RepeatedParameterSet {
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
            throw IllegalArgumentException("The separate VTB2 tensor records are expected to be float32.")
        }
    ): S5Vtb2RepeatedParameterSet {
        require(ROOT_MODEL_CONFIG_OBJECT_KEY in scan.matchedObjectKeys) {
            "The Megpoid VTB2 root model configuration object was not found."
        }
        require(MODEL_OBJECT_KEY in scan.matchedObjectKeys) {
            "The Megpoid VTB2 model parameter object was not found."
        }

        val recordCountPath =
            "$.$ROOT_MODEL_CONFIG_OBJECT_KEY.$MODEL_CONFIG_ENTRY_KEY.$DECLARED_RECORD_COUNT_KEY"
        val countMatch = scan.matchedValues[ROOT_MODEL_CONFIG_OBJECT_KEY]
            .orEmpty()
            .singleOrNull { it.valuePath == recordCountPath }
            ?: throw IllegalArgumentException(
                "The VTB2 record-count field is missing or duplicated."
            )
        require(countMatch.kind == S5JsonScalarKind.NUMBER) {
            "The VTB2 record count is not numeric."
        }
        val declaredCount = countMatch.value?.toIntOrNull()
            ?: throw IllegalArgumentException("The VTB2 record count is invalid.")
        require(declaredCount == EXPECTED_RECORD_COUNT) {
            "This Megpoid VTB2 model declares $declaredCount records; " +
                "expected $EXPECTED_RECORD_COUNT."
        }

        val arrayPrefix =
            "$.$MODEL_OBJECT_KEY.$MODEL_PARAMETER_OBJECT_KEY.$RECORD_ARRAY_KEY"
        val allTensorMatches = scan.matchedTensorStrings[MODEL_OBJECT_KEY].orEmpty()
        val recordArrayPrefix = "$arrayPrefix["
        val recordTensorMatches = allTensorMatches.filter {
            it.valuePath.startsWith(recordArrayPrefix)
        }
        val expectedRecordTensorCount =
            declaredCount * S5Vtb2RepeatedParameterPack.TENSORS_PER_RECORD
        require(recordTensorMatches.size == expectedRecordTensorCount) {
            "VTB2 separate parameter array contains ${recordTensorMatches.size} typed tensors; " +
                "expected $expectedRecordTensorCount."
        }
        val duplicateTensorPaths = recordTensorMatches.groupBy { it.valuePath }
            .filterValues { it.size != 1 }
            .keys
        require(duplicateTensorPaths.isEmpty()) {
            "VTB2 record array contains duplicate tensor paths: ${duplicateTensorPaths.sorted().joinToString()}."
        }
        val tensorMatches = recordTensorMatches.associateBy { it.valuePath }

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

        val records = (0 until declaredCount).map { index ->
            val itemPath = "$arrayPrefix[$index]"
            val tensorAPath = "$itemPath.$TENSOR_A_OBJECT_KEY"
            val singlePath = "$itemPath.$TENSOR_C_OBJECT_KEY"
            S5Vtb2RepeatedParameterPack(
                index = index,
                tensorAValues = decodeTensor(
                    "$tensorAPath.$WEIGHT_FIELD_KEY",
                    S5Vtb2RepeatedParameterPack.TENSOR_A_VALUE_COUNT
                ),
                biasValues = decodeTensor(
                    "$tensorAPath.$BIAS_FIELD_KEY",
                    S5Vtb2RepeatedParameterPack.BIAS_VALUE_COUNT
                ),
                tensorCValues = decodeTensor(
                    "$singlePath.$WEIGHT_FIELD_KEY",
                    S5Vtb2RepeatedParameterPack.TENSOR_C_VALUE_COUNT
                )
            )
        }
        return S5Vtb2RepeatedParameterSet(declaredCount, records)
    }

    const val EXPECTED_RECORD_COUNT = 9
}
