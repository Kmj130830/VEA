package com.kmj.vea.engine

enum class S5DecodedTensorKind {
    FLOAT32,
    FLOAT16,
    INT32
}

data class S5DecodedTensorMatch(
    val objectKey: String,
    val valuePath: String,
    val fieldKey: String,
    val kind: S5DecodedTensorKind,
    val floatValues: FloatArray?,
    val intValues: IntArray?
)

/**
 * Decodes only tensor strings selected by the JSON scanner. Keeping this
 * separate from scanning avoids allocating every tensor in a large model
 * package when only a small subset is needed by the current graph operator.
 */
object S5ModelTensorDecoder {
    fun decodeObject(
        scan: S5ModelJsonScanResult,
        objectKey: String
    ): List<S5DecodedTensorMatch> {
        check(NativeS5TensorBridge.isLoaded) {
            "The native S5 typed-tensor decoder is not loaded."
        }
        return decodeMatches(
            matches = scan.matchedTensorStrings[objectKey]
                ?: throw IllegalArgumentException("Model object key '$objectKey' was not found."),
            decodeFloatArray = NativeS5TensorBridge::decodeTypedFloatArray,
            decodeInt32Array = NativeS5TensorBridge::decodeTypedInt32Array
        )
    }

    fun decodeMatches(
        matches: List<S5TypedTensorString>,
        decodeFloatArray: (String) -> FloatArray,
        decodeInt32Array: (String) -> IntArray
    ): List<S5DecodedTensorMatch> {
        require(matches.isNotEmpty()) { "No typed tensors were selected for decoding." }
        return matches.map { match ->
            when (match.typeTag) {
                "*F32", "*F16" -> {
                    val values = decodeFloatArray(match.encodedValue)
                    require(values.all { it.isFinite() }) {
                        "Tensor at ${match.valuePath} contains a non-finite float."
                    }
                    S5DecodedTensorMatch(
                        objectKey = match.objectKey,
                        valuePath = match.valuePath,
                        fieldKey = match.fieldKey,
                        kind = if (match.typeTag == "*F32") {
                            S5DecodedTensorKind.FLOAT32
                        } else {
                            S5DecodedTensorKind.FLOAT16
                        },
                        floatValues = values,
                        intValues = null
                    )
                }
                "*S32" -> S5DecodedTensorMatch(
                    objectKey = match.objectKey,
                    valuePath = match.valuePath,
                    fieldKey = match.fieldKey,
                    kind = S5DecodedTensorKind.INT32,
                    floatValues = null,
                    intValues = decodeInt32Array(match.encodedValue)
                )
                else -> throw IllegalArgumentException(
                    "Unsupported tensor encoding ${match.typeTag} at ${match.valuePath}."
                )
            }
        }
    }
}
