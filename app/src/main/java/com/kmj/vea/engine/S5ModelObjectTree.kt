package com.kmj.vea.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed interface S5ModelValue {
    data class ObjectValue(val fields: Map<String, S5ModelValue>) : S5ModelValue
    data class ArrayValue(val values: List<S5ModelValue>) : S5ModelValue
    data class StringValue(val value: String) : S5ModelValue
    data class NumberValue(val value: Double) : S5ModelValue
    data class BooleanValue(val value: Boolean) : S5ModelValue
    data object NullValue : S5ModelValue
}

/** Parses the UTF-8 JSON object representation after S5API's payload preparation stage. */
class S5ModelObjectTree private constructor(val root: S5ModelValue.ObjectValue) {
    data class Limits(
        val maxInputBytes: Int = 128 * 1024 * 1024,
        val maxDepth: Int = 128,
        val maxNodes: Int = 2_000_000
    ) {
        init {
            require(maxInputBytes > 0)
            require(maxDepth >= 0)
            require(maxNodes > 0)
        }
    }

    fun findByLookupKey(key: String): S5ModelValue? = root.fields[key]

    /** Hashes a full property name; the injectable hasher keeps JVM tests independent of JNI. */
    fun findParameter(
        parameterName: String,
        keyHasher: (String) -> String = NativeS5TensorBridge::hashParameterLookupKey
    ): S5ModelValue? {
        require(parameterName.isNotEmpty()) { "Parameter name must not be empty." }
        return findByLookupKey(keyHasher(parameterName))
    }

    companion object {
        fun parseUtf8(utf8: ByteArray, limits: Limits = Limits()): S5ModelObjectTree {
            require(utf8.size <= limits.maxInputBytes) {
                "Model JSON exceeds the " + limits.maxInputBytes + "-byte input limit."
            }
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try {
                decoder.decode(ByteBuffer.wrap(utf8)).toString()
            } catch (e: Exception) {
                throw IllegalArgumentException("Model JSON is not valid UTF-8.", e)
            }
            val parsed = JsonParser(text, limits).parse()
            require(parsed is S5ModelValue.ObjectValue) {
                "The S5API model root must be a JSON object."
            }
            return S5ModelObjectTree(parsed)
        }

        fun parseUtf8(utf8: String, limits: Limits = Limits()): S5ModelObjectTree =
            parseUtf8(utf8.toByteArray(StandardCharsets.UTF_8), limits)

        /**
         * Decodes a supplied S5API model payload, then parses its UTF-8 JSON root object.
         * This still expects the caller to provide the model member bytes, not a ZIP archive.
         */
        fun parseModelPayload(
            encodedPayload: ByteArray,
            limits: Limits = Limits(),
            payloadDecoder: S5ModelPayloadDecoder = NativeS5ModelPayloadDecoder
        ): S5ModelObjectTree =
            parseUtf8(payloadDecoder.decode(encodedPayload), limits)
    }
}

fun interface S5ModelPayloadDecoder {
    fun decode(encodedPayload: ByteArray): ByteArray
}

object NativeS5ModelPayloadDecoder : S5ModelPayloadDecoder {
    override fun decode(encodedPayload: ByteArray): ByteArray =
        NativeS5TensorBridge.decodeModelPayload(encodedPayload)
}

interface S5TypedTensorDecoder {
    fun decodeFloatArray(encoded: String): FloatArray
    fun decodeInt32Array(encoded: String): IntArray
}

object NativeS5TypedTensorDecoder : S5TypedTensorDecoder {
    override fun decodeFloatArray(encoded: String): FloatArray =
        NativeS5TensorBridge.decodeTypedFloatArray(encoded)

    override fun decodeInt32Array(encoded: String): IntArray =
        NativeS5TensorBridge.decodeTypedInt32Array(encoded)
}

fun S5ModelValue.asObject(): S5ModelValue.ObjectValue =
    this as? S5ModelValue.ObjectValue
        ?: throw IllegalArgumentException("Model value is not an object.")

fun S5ModelValue.asArray(): List<S5ModelValue> =
    (this as? S5ModelValue.ArrayValue)?.values
        ?: throw IllegalArgumentException("Model value is not an array.")

fun S5ModelValue.asString(): String =
    (this as? S5ModelValue.StringValue)?.value
        ?: throw IllegalArgumentException("Model value is not a string.")

fun S5ModelValue.asFloatArray(
    tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
): FloatArray = when (this) {
    is S5ModelValue.ArrayValue -> FloatArray(values.size) { index ->
        val number = (values[index] as? S5ModelValue.NumberValue)?.value
            ?: throw IllegalArgumentException("Float sequence contains a non-number at index " + index + ".")
        require(number.isFinite() && number >= -Float.MAX_VALUE && number <= Float.MAX_VALUE) {
            "Float sequence contains a value outside finite float32 range at index " + index + "."
        }
        number.toFloat()
    }
    is S5ModelValue.StringValue -> {
        require(value.startsWith("*F32") || value.startsWith("*F16")) {
            "Typed float value must use the *F32 or *F16 marker."
        }
        tensorDecoder.decodeFloatArray(value)
    }
    else -> throw IllegalArgumentException("Model value cannot be converted to float32.")
}

fun S5ModelValue.asIntArray(
    tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
): IntArray = when (this) {
    is S5ModelValue.ArrayValue -> IntArray(values.size) { index ->
        val number = (values[index] as? S5ModelValue.NumberValue)?.value
            ?: throw IllegalArgumentException("Integer sequence contains a non-number at index " + index + ".")
        require(number.isFinite() && number % 1.0 == 0.0 &&
            number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()) {
            "Integer sequence contains a non-int32 value at index " + index + "."
        }
        number.toInt()
    }
    is S5ModelValue.StringValue -> {
        require(value.startsWith("*S32")) { "Typed integer value must use the *S32 marker." }
        tensorDecoder.decodeInt32Array(value)
    }
    else -> throw IllegalArgumentException("Model value cannot be converted to int32.")
}

private class JsonParser(
    private val source: String,
    private val limits: S5ModelObjectTree.Limits
) {
    private var cursor = 0
    private var nodeCount = 0

    fun parse(): S5ModelValue {
        skipWhitespace()
        val value = parseValue(depth = 0)
        skipWhitespace()
        if (cursor != source.length) fail("Unexpected trailing content.")
        return value
    }

    private fun parseValue(depth: Int): S5ModelValue {
        if (depth > limits.maxDepth) fail("Maximum JSON nesting depth exceeded.")
        countNode()
        skipWhitespace()
        if (cursor >= source.length) fail("Unexpected end of JSON input.")
        return when (source[cursor]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> S5ModelValue.StringValue(parseString())
            't' -> {
                consumeLiteral("true")
                S5ModelValue.BooleanValue(true)
            }
            'f' -> {
                consumeLiteral("false")
                S5ModelValue.BooleanValue(false)
            }
            'n' -> {
                consumeLiteral("null")
                S5ModelValue.NullValue
            }
            '-', in '0'..'9' -> S5ModelValue.NumberValue(parseNumber())
            else -> fail("Unexpected character '" + source[cursor] + "'.")
        }
    }

    private fun parseObject(depth: Int): S5ModelValue.ObjectValue {
        expect('{')
        skipWhitespace()
        val fields = LinkedHashMap<String, S5ModelValue>()
        if (consumeIf('}')) return S5ModelValue.ObjectValue(fields)
        while (true) {
            skipWhitespace()
            if (cursor >= source.length || source[cursor] != '"') {
                fail("Object property name must be a string.")
            }
            val key = parseString()
            if (fields.containsKey(key)) fail("Duplicate object property '" + key + "'.")
            skipWhitespace()
            expect(':')
            fields[key] = parseValue(depth + 1)
            skipWhitespace()
            if (consumeIf('}')) break
            expect(',')
        }
        return S5ModelValue.ObjectValue(fields)
    }

    private fun parseArray(depth: Int): S5ModelValue.ArrayValue {
        expect('[')
        skipWhitespace()
        val values = ArrayList<S5ModelValue>()
        if (consumeIf(']')) return S5ModelValue.ArrayValue(values)
        while (true) {
            values.add(parseValue(depth + 1))
            skipWhitespace()
            if (consumeIf(']')) break
            expect(',')
        }
        return S5ModelValue.ArrayValue(values)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (cursor < source.length) {
            val ch = source[cursor++]
            when {
                ch == '"' -> return result.toString()
                ch == '\\' -> appendEscape(result)
                ch.code < 0x20 -> fail("Unescaped control character in string.")
                ch.isHighSurrogate() -> {
                    if (cursor >= source.length || !source[cursor].isLowSurrogate()) {
                        fail("Unpaired high surrogate in string.")
                    }
                    result.append(ch)
                    result.append(source[cursor++])
                }
                ch.isLowSurrogate() -> fail("Unpaired low surrogate in string.")
                else -> result.append(ch)
            }
        }
        fail("Unterminated JSON string.")
    }

    private fun appendEscape(result: StringBuilder) {
        if (cursor >= source.length) fail("Incomplete string escape.")
        when (val escaped = source[cursor++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val first = readHexCodeUnit()
                when {
                    first.isHighSurrogate() -> {
                        if (cursor + 2 > source.length ||
                            source[cursor] != '\\' || source[cursor + 1] != 'u') {
                            fail("High surrogate escape must be followed by a low surrogate escape.")
                        }
                        cursor += 2
                        val second = readHexCodeUnit()
                        if (!second.isLowSurrogate()) {
                            fail("High surrogate escape is followed by a non-low surrogate.")
                        }
                        result.append(first)
                        result.append(second)
                    }
                    first.isLowSurrogate() -> fail("Unpaired low surrogate escape.")
                    else -> result.append(first)
                }
            }
            else -> fail("Unsupported string escape '\\$escaped'.")
        }
    }

    private fun readHexCodeUnit(): Char {
        if (cursor + 4 > source.length) fail("Incomplete Unicode escape.")
        var value = 0
        repeat(4) {
            val digit = source[cursor++].digitToIntOrNull(16)
                ?: fail("Invalid hexadecimal digit in Unicode escape.")
            value = (value shl 4) or digit
        }
        return value.toChar()
    }

    private fun parseNumber(): Double {
        val start = cursor
        consumeIf('-')
        if (cursor >= source.length) fail("Incomplete number.")
        if (consumeIf('0')) {
            if (cursor < source.length && source[cursor] in '0'..'9') {
                fail("Leading zero is not allowed in a JSON number.")
            }
        } else {
            if (cursor >= source.length || source[cursor] !in '1'..'9') {
                fail("Invalid integer component.")
            }
            while (cursor < source.length && source[cursor] in '0'..'9') cursor++
        }
        if (consumeIf('.')) {
            val fractionStart = cursor
            while (cursor < source.length && source[cursor] in '0'..'9') cursor++
            if (cursor == fractionStart) fail("Fraction requires at least one digit.")
        }
        if (cursor < source.length && (source[cursor] == 'e' || source[cursor] == 'E')) {
            cursor++
            if (cursor < source.length && (source[cursor] == '+' || source[cursor] == '-')) cursor++
            val exponentStart = cursor
            while (cursor < source.length && source[cursor] in '0'..'9') cursor++
            if (cursor == exponentStart) fail("Exponent requires at least one digit.")
        }
        val parsed = source.substring(start, cursor).toDoubleOrNull()
            ?: fail("Invalid JSON number.")
        if (!parsed.isFinite()) fail("JSON number is outside finite float64 range.")
        return parsed
    }

    private fun consumeLiteral(expected: String) {
        if (!source.startsWith(expected, cursor)) fail("Expected '" + expected + "'.")
        cursor += expected.length
    }

    private fun countNode() {
        nodeCount++
        if (nodeCount > limits.maxNodes) fail("Maximum JSON value-node count exceeded.")
    }

    private fun skipWhitespace() {
        while (cursor < source.length &&
            (source[cursor] == ' ' || source[cursor] == '\t' ||
                source[cursor] == '\r' || source[cursor] == '\n')) {
            cursor++
        }
    }

    private fun expect(expected: Char) {
        if (cursor >= source.length || source[cursor] != expected) {
            fail("Expected '" + expected + "'.")
        }
        cursor++
    }

    private fun consumeIf(expected: Char): Boolean {
        if (cursor < source.length && source[cursor] == expected) {
            cursor++
            return true
        }
        return false
    }

    private fun fail(message: String): Nothing =
        throw IllegalArgumentException(message + " (offset " + cursor + ").")

    private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'
    private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'
}
