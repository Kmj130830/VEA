package com.kmj.vea.engine

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class S5TensorEncoding {
    FLOAT32,
    FLOAT16,
    INT32
}

data class S5DecodedTensorField(
    val key: String,
    val encoding: S5TensorEncoding,
    val floatValues: FloatArray?,
    val intValues: IntArray?
)

data class S5DecodedTensorObject(
    /** Full JSON path; retained because the same field hashes occur in many objects. */
    val path: String,
    val fields: Map<String, S5DecodedTensorField>
)

data class S5DecodedModelObjectTree(
    val objects: List<S5DecodedTensorObject>
) {
    val floatTensorCount: Int
        get() = objects.sumOf { objectNode ->
            objectNode.fields.values.count { it.floatValues != null }
        }

    val intTensorCount: Int
        get() = objects.sumOf { objectNode ->
            objectNode.fields.values.count { it.intValues != null }
        }

    fun objectsContaining(fieldKey: String): List<S5DecodedTensorObject> =
        objects.filter { fieldKey in it.fields }

    fun requireUniqueObject(path: String): S5DecodedTensorObject {
        val matches = objects.filter { it.path == path }
        require(matches.size == 1) {
            "Expected one decoded tensor object at '$path', found ${matches.size}."
        }
        return matches.single()
    }
}

/**
 * Reads the decoded VTB2 JSON-like object tree and extracts typed tensor strings.
 *
 * The supplied AI Megpoid VTB2 body was confirmed to be UTF-8 JSON. Tensor
 * leaves are tagged strings (*F32, *F16, *S32) stored inside nested objects.
 * We preserve the full object path and each immediate field key: several
 * distinct objects reuse the same inner keys, so flattening them would silently
 * associate the wrong tensor with a parameter.
 *
 * This is a data reader, not a neural graph mapper. It does not guess which
 * opaque object path corresponds to an RNN parameter.
 */
object S5ModelPayloadReader {
    private const val MAX_PAYLOAD_BYTES = 128 * 1024 * 1024
    private const val MAX_JSON_DEPTH = 96
    private const val MAX_OBJECTS = 100_000
    private const val MAX_FIELDS = 1_000_000

    fun decodeContainerFile(file: File): S5DecodedModelObjectTree {
        require(file.isFile) { "S5 model file does not exist: ${file.name}." }
        require(file.length() in 16L..MAX_PAYLOAD_BYTES.toLong()) {
            "S5 model file size is outside the supported range."
        }
        val decoded = NativeS5TensorBridge.decodeContainerPayload(file.readBytes())
        return parseDecodedPayload(
            decodedPayload = decoded,
            decodeFloatArray = NativeS5TensorBridge::decodeTypedFloatArray,
            decodeInt32Array = NativeS5TensorBridge::decodeTypedInt32Array
        )
    }

    /**
     * Injectable decoders keep the JSON traversal testable on the JVM without
     * loading the Android ARM64 JNI library.
     */
    fun parseDecodedPayload(
        decodedPayload: ByteArray,
        decodeFloatArray: (String) -> FloatArray,
        decodeInt32Array: (String) -> IntArray
    ): S5DecodedModelObjectTree {
        require(decodedPayload.isNotEmpty() && decodedPayload.size <= MAX_PAYLOAD_BYTES) {
            "Decoded S5 model payload is empty or exceeds the supported size."
        }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decodedPayload))
            .toString()
            .trimStart('\uFEFF', '\u0000', ' ', '\t', '\r', '\n')
            .trimEnd('\u0000', ' ', '\t', '\r', '\n')
        require(text.startsWith("{") && text.endsWith("}")) {
            "Decoded S5 VTB2 payload is not a JSON object."
        }

        val output = ArrayList<S5DecodedTensorObject>()
        var visitedObjects = 0
        var visitedFields = 0

        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            readValue(
                reader = reader,
                path = "$",
                depth = 0,
                output = output,
                decodeFloatArray = decodeFloatArray,
                decodeInt32Array = decodeInt32Array,
                counters = intArrayOf(0, 0)
            )
            require(reader.peek() == JsonToken.END_DOCUMENT) {
                "Unexpected trailing data after the S5 model JSON object."
            }
            visitedObjects = output.size
            visitedFields = output.sumOf { it.fields.size }
        }

        require(visitedObjects <= MAX_OBJECTS && visitedFields <= MAX_FIELDS) {
            "Decoded S5 model tree exceeds supported object/field limits."
        }
        return S5DecodedModelObjectTree(output.toList())
    }

    private fun readValue(
        reader: JsonReader,
        path: String,
        depth: Int,
        output: MutableList<S5DecodedTensorObject>,
        decodeFloatArray: (String) -> FloatArray,
        decodeInt32Array: (String) -> IntArray,
        counters: IntArray
    ) {
        require(depth <= MAX_JSON_DEPTH) { "S5 model JSON nesting is too deep." }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val tensorFields = LinkedHashMap<String, S5DecodedTensorField>()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    counters[1]++
                    require(counters[1] <= MAX_FIELDS) {
                        "S5 model JSON contains too many fields."
                    }
                    val childPath = appendPath(path, key)
                    if (reader.peek() == JsonToken.STRING) {
                        val encoded = reader.nextString()
                        when {
                            encoded.startsWith("*F32") || encoded.startsWith("*F16") -> {
                                val values = decodeFloatArray(encoded)
                                require(values.all { it.isFinite() }) {
                                    "Tensor '$childPath' contains a non-finite float."
                                }
                                tensorFields[key] = S5DecodedTensorField(
                                    key = key,
                                    encoding = if (encoded.startsWith("*F32")) {
                                        S5TensorEncoding.FLOAT32
                                    } else {
                                        S5TensorEncoding.FLOAT16
                                    },
                                    floatValues = values,
                                    intValues = null
                                )
                            }
                            encoded.startsWith("*S32") -> {
                                tensorFields[key] = S5DecodedTensorField(
                                    key = key,
                                    encoding = S5TensorEncoding.INT32,
                                    floatValues = null,
                                    intValues = decodeInt32Array(encoded)
                                )
                            }
                        }
                    } else {
                        readValue(
                            reader, childPath, depth + 1, output,
                            decodeFloatArray, decodeInt32Array, counters
                        )
                    }
                }
                reader.endObject()
                counters[0]++
                require(counters[0] <= MAX_OBJECTS) {
                    "S5 model JSON contains too many objects."
                }
                if (tensorFields.isNotEmpty()) {
                    output += S5DecodedTensorObject(path, tensorFields.toMap())
                }
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    readValue(
                        reader, "$path[$index]", depth + 1, output,
                        decodeFloatArray, decodeInt32Array, counters
                    )
                    index++
                }
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IllegalArgumentException("Unexpected token in S5 model JSON at $path.")
        }
    }

    private fun appendPath(parent: String, key: String): String {
        return if (key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "$parent.$key"
        } else {
            "$parent[${key.toJsonPathString()}]"
        }
    }

    private fun String.toJsonPathString(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
