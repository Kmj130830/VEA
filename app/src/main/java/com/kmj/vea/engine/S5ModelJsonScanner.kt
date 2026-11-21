package com.kmj.vea.engine

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.PushbackReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

enum class S5JsonScalarKind { STRING, NUMBER, BOOLEAN, NULL }

interface S5ModelJsonVisitor {
    fun onObjectKey(key: String, depth: Int) = Unit
    fun onScalar(propertyName: String?, kind: S5JsonScalarKind, value: String?, depth: Int) = Unit
}

data class S5TypedTensorString(
    /** The matched 32-character hashed JSON object key. */
    val objectKey: String,
    /** Full path to the typed string value, including its inner field key. */
    val valuePath: String,
    val fieldKey: String,
    val typeTag: String,
    val encodedValue: String
)

data class S5ModelJsonScanResult(
    val objectCount: Long,
    val arrayCount: Long,
    val objectKeyCount: Long,
    val hexadecimalKeyCount: Long,
    val stringValueCount: Long,
    val numberCount: Long,
    val booleanCount: Long,
    val nullCount: Long,
    val typedTensorStringCount: Long,
    val matchedTensorStrings: Map<String, List<S5TypedTensorString>>
)

/**
 * Streaming JSON scanner for the decoded S5API model body.
 *
 * It does not build a JSONObject tree and avoids retaining the complete VTB2
 * document plus a second in-memory object graph. Callbacks receive object keys
 * and scalar values as the parser consumes the stream.
 *
 * F32, F16, and S32 typed payload strings are recognized as ordinary JSON values;
 * tensor data is decoded by NativeS5TensorBridge in a separate stage.
 */
object S5ModelJsonScanner {
    private const val DEFAULT_MAX_DEPTH = 128
    private const val DEFAULT_MAX_STRING_CHARS = 64 * 1024 * 1024
    private const val DEFAULT_MAX_DOCUMENT_CHARS = 128L * 1024L * 1024L

    fun scan(
        reader: Reader,
        visitor: S5ModelJsonVisitor? = null,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxStringChars: Int = DEFAULT_MAX_STRING_CHARS,
        maxDocumentChars: Long = DEFAULT_MAX_DOCUMENT_CHARS
    ): S5ModelJsonScanResult {
        require(maxDepth in 1..512)
        require(maxStringChars > 0)
        require(maxDocumentChars > 0)
        val parser = Parser(
            reader = if (reader is PushbackReader) reader else PushbackReader(BufferedReader(reader), 1),
            visitor = visitor,
            maxDepth = maxDepth,
            maxStringChars = maxStringChars,
            maxDocumentChars = maxDocumentChars,
            targetKeys = emptySet()
        )
        parser.parseDocument()
        return parser.result()
    }

    fun scanForTensorKeys(
        reader: Reader,
        targetKeys: Set<String>,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxStringChars: Int = DEFAULT_MAX_STRING_CHARS,
        maxDocumentChars: Long = DEFAULT_MAX_DOCUMENT_CHARS
    ): S5ModelJsonScanResult {
        require(targetKeys.all { it.length == 32 && it.all(::isHexDigit) }) {
            "Tensor lookup keys must be 32-character hexadecimal strings."
        }
        require(maxDepth in 1..512)
        require(maxStringChars > 0)
        require(maxDocumentChars > 0)
        val parser = Parser(
            reader = if (reader is PushbackReader) reader else PushbackReader(BufferedReader(reader), 1),
            visitor = null,
            maxDepth = maxDepth,
            maxStringChars = maxStringChars,
            maxDocumentChars = maxDocumentChars,
            targetKeys = targetKeys
        )
        parser.parseDocument()
        return parser.result()
    }

    private fun isHexDigit(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

    private class Parser(
        private val reader: PushbackReader,
        private val visitor: S5ModelJsonVisitor?,
        private val maxDepth: Int,
        private val maxStringChars: Int,
        private val maxDocumentChars: Long,
        private val targetKeys: Set<String>
    ) {
        private var charsRead = 0L
        private var lastChar = -1
        private var objects = 0L
        private var arrays = 0L
        private var objectKeys = 0L
        private var hexKeys = 0L
        private var strings = 0L
        private var numbers = 0L
        private var booleans = 0L
        private var nulls = 0L
        private var typedStrings = 0L
        private val matched = LinkedHashMap<String, MutableList<S5TypedTensorString>>()

        fun parseDocument() {
            skipWhitespace()
            val first = readChar()
            if (first == 0xFEFF) {
                skipWhitespace()
            } else {
                unreadChar(first)
            }
            parseValue(propertyName = null, path = "$", depth = 0, matchedObjectKey = null)
            skipWhitespace()
            require(readChar() == -1) { "Trailing content after the S5 model JSON value." }
        }

        fun result() = S5ModelJsonScanResult(
            objectCount = objects,
            arrayCount = arrays,
            objectKeyCount = objectKeys,
            hexadecimalKeyCount = hexKeys,
            stringValueCount = strings,
            numberCount = numbers,
            booleanCount = booleans,
            nullCount = nulls,
            typedTensorStringCount = typedStrings,
            matchedTensorStrings = matched.mapValues { it.value.toList() }
        )

        private fun parseValue(
            propertyName: String?,
            path: String,
            depth: Int,
            matchedObjectKey: String?
        ) {
            require(depth <= maxDepth) { "S5 model JSON nesting exceeds the configured limit." }
            skipWhitespace()
            when (val ch = readChar()) {
                '{'.code -> parseObject(path, depth + 1, matchedObjectKey)
                '['.code -> parseArray(path, depth + 1, matchedObjectKey)
                '"'.code -> {
                    val value = parseString()
                    strings++
                    visitor?.onScalar(propertyName, S5JsonScalarKind.STRING, value, depth)
                    if (value.startsWith("*F32") || value.startsWith("*F16") || value.startsWith("*S32")) {
                        typedStrings++
                        if (matchedObjectKey != null && propertyName != null) {
                            matched.getOrPut(matchedObjectKey) { ArrayList() } += S5TypedTensorString(
                                objectKey = matchedObjectKey,
                                valuePath = path,
                                fieldKey = propertyName,
                                typeTag = value.substring(0, 4),
                                encodedValue = value
                            )
                        }
                    }
                }
                't'.code -> {
                    expectLiteral("rue")
                    booleans++
                    visitor?.onScalar(propertyName, S5JsonScalarKind.BOOLEAN, "true", depth)
                }
                'f'.code -> {
                    expectLiteral("alse")
                    booleans++
                    visitor?.onScalar(propertyName, S5JsonScalarKind.BOOLEAN, "false", depth)
                }
                'n'.code -> {
                    expectLiteral("ull")
                    nulls++
                    visitor?.onScalar(propertyName, S5JsonScalarKind.NULL, null, depth)
                }
                '-'.code, in '0'.code..'9'.code -> {
                    val token = parseNumber(ch)
                    numbers++
                    visitor?.onScalar(propertyName, S5JsonScalarKind.NUMBER, token, depth)
                }
                -1 -> throw IllegalArgumentException("Unexpected end of S5 model JSON.")
                else -> throw IllegalArgumentException("Unexpected JSON token '" + ch.toChar() + "'.")
            }
        }

        private fun parseObject(path: String, depth: Int, matchedObjectKey: String?) {
            objects++
            skipWhitespace()
            val first = readChar()
            if (first == '}'.code) return
            unreadChar(first)
            while (true) {
                skipWhitespace()
                require(readChar() == '"'.code) { "Expected a quoted key in S5 model JSON object." }
                val key = parseString()
                objectKeys++
                if (key.length == 32 && key.all(::isHexDigit)) hexKeys++
                visitor?.onObjectKey(key, depth)
                skipWhitespace()
                require(readChar() == ':'.code) { "Expected ':' after S5 model JSON object key." }
                val childPath = appendJsonPath(path, key)
                val childTarget = if (key in targetKeys) key else matchedObjectKey
                parseValue(key, childPath, depth, childTarget)
                skipWhitespace()
                when (readChar()) {
                    ','.code -> continue
                    '}'.code -> return
                    else -> throw IllegalArgumentException("Expected ',' or '}' in S5 model JSON object.")
                }
            }
        }

        private fun parseArray(path: String, depth: Int, matchedObjectKey: String?) {
            arrays++
            skipWhitespace()
            val first = readChar()
            if (first == ']'.code) return
            unreadChar(first)
            var index = 0
            while (true) {
                parseValue(
                    propertyName = null,
                    path = "$path[$index]",
                    depth = depth,
                    matchedObjectKey = matchedObjectKey
                )
                index++
                skipWhitespace()
                when (readChar()) {
                    ','.code -> continue
                    ']'.code -> return
                    else -> throw IllegalArgumentException("Expected ',' or ']' in S5 model JSON array.")
                }
            }
        }

        private fun appendJsonPath(parent: String, key: String): String =
            if (key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                "$parent.$key"
            } else {
                "$parent[\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]"
            }

        private fun parseString(): String {
            val result = StringBuilder()
            while (true) {
                val ch = readChar()
                when {
                    ch == -1 -> throw IllegalArgumentException("Unterminated JSON string in S5 model.")
                    ch == '"'.code -> return result.toString()
                    ch == '\\'.code -> {
                        val escaped = readChar()
                        when (escaped) {
                            '"'.code -> result.append('"')
                            '\\'.code -> result.append('\\')
                            '/'.code -> result.append('/')
                            'b'.code -> result.append('\b')
                            'f'.code -> result.append('\u000c')
                            'n'.code -> result.append('\n')
                            'r'.code -> result.append('\r')
                            't'.code -> result.append('\t')
                            'u'.code -> result.append(readUnicodeEscape())
                            else -> throw IllegalArgumentException("Invalid JSON escape: " + escaped.toChar())
                        }
                    }
                    ch < 0x20 -> throw IllegalArgumentException("Unescaped control character in S5 model JSON string.")
                    else -> result.append(ch.toChar())
                }
                require(result.length <= maxStringChars) {
                    "S5 model JSON string exceeds the configured character limit."
                }
            }
        }

        private fun readUnicodeEscape(): Char {
            var value = 0
            repeat(4) {
                val ch = readChar()
                val digit = when (ch) {
                    in '0'.code..'9'.code -> ch - '0'.code
                    in 'a'.code..'f'.code -> ch - 'a'.code + 10
                    in 'A'.code..'F'.code -> ch - 'A'.code + 10
                    else -> throw IllegalArgumentException("Invalid hexadecimal digit in JSON Unicode escape.")
                }
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun parseNumber(first: Int): String {
            val result = StringBuilder().append(first.toChar())
            while (true) {
                val ch = readChar()
                if (ch == -1) break
                if (ch == ','.code || ch == '}'.code || ch == ']'.code ||
                    ch == ' '.code || ch == '\n'.code || ch == '\r'.code || ch == '\t'.code
                ) {
                    unreadChar(ch)
                    break
                }
                result.append(ch.toChar())
                require(result.length <= 128) { "S5 model JSON number token is too long." }
            }
            val token = result.toString()
            require(NUMBER_PATTERN.matches(token)) { "Invalid JSON number in S5 model." }
            return token
        }

        private fun expectLiteral(rest: String) {
            for (expected in rest) {
                require(readChar() == expected.code) { "Invalid JSON literal in S5 model." }
            }
        }

        private fun skipWhitespace() {
            while (true) {
                val ch = readChar()
                when (ch) {
                    ' '.code, '\n'.code, '\r'.code, '\t'.code -> Unit
                    else -> {
                        unreadChar(ch)
                        return
                    }
                }
            }
        }

        private fun readChar(): Int {
            val ch = reader.read()
            if (ch >= 0) {
                charsRead++
                require(charsRead <= maxDocumentChars) {
                    "S5 model JSON exceeds the configured document character limit."
                }
            }
            lastChar = ch
            return ch
        }

        private fun unreadChar(ch: Int) {
            if (ch < 0) return
            reader.unread(ch)
            charsRead = (charsRead - 1).coerceAtLeast(0)
        }

        companion object {
            private val NUMBER_PATTERN =
                Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
        }
    }
}

data class S5ModelResourceScan(
    val fileName: String,
    val containerKind: String,
    val result: S5ModelJsonScanResult
)

/**
 * File-level streaming entry points for the five bundled Megpoid/shared
 * VOCALOID7 resources. The JSON bodies are scanned one at a time; no full
 * JSONObject tree is retained.
 */
object S5ModelContainerScanner {
    private val bundledResources = listOf(
        "AI_Megpoid.vtb2" to "VTBR",
        "AI_Megpoid.vpit" to "VPIT",
        "COMMON7.vpit" to "VPIT",
        "VOCALOID7.vhnv" to "VHNV",
        "VOCALOID7.vtmg" to "VTMG"
    )
    private val packageScanCache = ConcurrentHashMap<String, List<S5ModelResourceScan>>()

    fun scanVtb2(
        vtb2File: File,
        targetTensorKeys: Set<String>,
        maxDocumentChars: Long = 128L * 1024L * 1024L
    ): S5ModelJsonScanResult = scanContainerFile(
        containerFile = vtb2File,
        expectedKind = "VTBR",
        targetTensorKeys = targetTensorKeys,
        maxDocumentChars = maxDocumentChars
    )

    fun scanMegpoidPackage(
        packageDirectory: File,
        targetTensorKeys: Set<String> = emptySet(),
        maxDocumentChars: Long = 128L * 1024L * 1024L
    ): List<S5ModelResourceScan> {
        require(packageDirectory.isDirectory) {
            "The installed Megpoid model directory does not exist."
        }
        val directory = packageDirectory.canonicalFile
        val files = bundledResources.map { (fileName, _) ->
            File(directory, fileName).also { file ->
                require(file.isFile) { "Required model resource is missing: $fileName." }
            }
        }
        val cacheKey = buildString {
            append(directory.absolutePath)
            append('|')
            append(targetTensorKeys.sorted().joinToString(","))
            for (file in files) {
                append('|').append(file.name)
                append(':').append(file.length())
                append(':').append(file.lastModified())
            }
            append('|').append(maxDocumentChars)
        }
        return packageScanCache.computeIfAbsent(cacheKey) {
            bundledResources.zip(files).map { (resource, file) ->
                val (fileName, kind) = resource
                S5ModelResourceScan(
                    fileName = fileName,
                    containerKind = kind,
                    result = scanContainerFile(
                        containerFile = file,
                        expectedKind = kind,
                        targetTensorKeys = targetTensorKeys,
                        maxDocumentChars = maxDocumentChars
                    )
                )
            }
        }
    }

    fun scanContainerFile(
        containerFile: File,
        expectedKind: String,
        targetTensorKeys: Set<String>,
        maxDocumentChars: Long = 128L * 1024L * 1024L
    ): S5ModelJsonScanResult {
        require(containerFile.isFile) {
            "The model container file does not exist: ${containerFile.name}."
        }
        require(containerFile.length() in 16L..512L * 1024L * 1024L) {
            "The model container size is outside the supported range: ${containerFile.name}."
        }
        check(NativeS5TensorBridge.isLoaded) {
            "The native S5API container decoder is not loaded."
        }

        val encoded = FileInputStream(containerFile).use { it.readBytes() }
        val prefix = encoded.copyOfRange(0, 16)
        val header = NativeS5TensorBridge.parseContainerHeader(prefix, encoded.size.toLong())
            ?: throw IllegalArgumentException("Invalid S5API container prefix: ${containerFile.name}.")
        require(header.kind == expectedKind) {
            "Expected $expectedKind in ${containerFile.name}, found ${header.kind}."
        }

        val decoded = NativeS5TensorBridge.decodeContainerPayload(encoded)
        require(decoded.size.toLong() == header.decodedPayloadSize) {
            "Decoded body size does not match the container prefix: ${containerFile.name}."
        }

        return ByteArrayInputStream(decoded).use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                S5ModelJsonScanner.scanForTensorKeys(
                    reader,
                    targetTensorKeys,
                    maxDocumentChars = maxDocumentChars
                )
            }
        }
    }
}
