package com.kmj.vea.engine

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ModelJsonScannerTest {
    private val tensorKey = "0123456789abcdef0123456789abcdef"

    @Test
    fun streamsNestedObjectsArraysAndTypedTensorValues() {
        val json = """
            {
              "root": {
                "$tensorKey": "*F32AQID",
                "meta": {
                  "tag": "*F16AA==",
                  "description": "line\nfeed"
                },
                "values": [1, -2.5e+2, true, false, null, {"escape": "quote: \"A\""}]
              }
            }
        """.trimIndent()

        val result = S5ModelJsonScanner.scanForTensorKeys(
            StringReader(json),
            setOf(tensorKey)
        )

        assertEquals(4L, result.objectCount)
        assertEquals(1L, result.arrayCount)
        assertEquals(7L, result.objectKeyCount)
        assertEquals(1L, result.hexadecimalKeyCount)
        assertEquals(4L, result.stringValueCount)
        assertEquals(2L, result.numberCount)
        assertEquals(2L, result.booleanCount)
        assertEquals(1L, result.nullCount)
        assertEquals(2L, result.typedTensorStringCount)
        assertEquals("*F32", result.matchedTensorStrings[tensorKey]?.typeTag)
        assertEquals("*F32AQID", result.matchedTensorStrings[tensorKey]?.encodedValue)
    }

    @Test
    fun visitorReceivesKeysAndScalarValuesWithoutBuildingTree() {
        val keys = mutableListOf<String>()
        val values = mutableListOf<Pair<String?, String?>>()
        val visitor = object : S5ModelJsonVisitor {
            override fun onObjectKey(key: String, depth: Int) {
                keys += key
            }

            override fun onScalar(
                propertyName: String?,
                kind: S5JsonScalarKind,
                value: String?,
                depth: Int
            ) {
                values += propertyName to value
            }
        }

        val result = S5ModelJsonScanner.scan(
            StringReader("""{"a":"one","b":[3,{"c":false}]}"""),
            visitor
        )

        assertEquals(listOf("a", "b", "c"), keys)
        assertTrue(values.contains("a" to "one"))
        assertTrue(values.contains("c" to "false"))
        assertEquals(2L, result.objectCount)
        assertEquals(1L, result.arrayCount)
    }

    @Test
    fun acceptsUtf8BomAndUnicodeEscapes() {
        val result = S5ModelJsonScanner.scan(
            StringReader("\uFEFF{\"name\":\"\\uAC00\\uB098\"}")
        )
        assertEquals(1L, result.objectCount)
        assertEquals(1L, result.stringValueCount)
    }

    @Test
    fun acceptsRootArrayAndEmptyContainers() {
        val result = S5ModelJsonScanner.scan(StringReader("""[{}, [], null]"""))
        assertEquals(1L, result.objectCount)
        assertEquals(2L, result.arrayCount)
        assertEquals(1L, result.nullCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTrailingContent() {
        S5ModelJsonScanner.scan(StringReader("""{"a":1} garbage"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedNumber() {
        S5ModelJsonScanner.scan(StringReader("""{"a":01}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidEscape() {
        S5ModelJsonScanner.scan(StringReader("""{"a":"\q"}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun enforcesNestingDepthLimit() {
        S5ModelJsonScanner.scan(
            StringReader("""{"a":{"b":{"c":1}}}"""),
            maxDepth = 2
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHexTensorLookupKey() {
        S5ModelJsonScanner.scanForTensorKeys(
            StringReader("""{}"""),
            setOf("not-a-hex-key")
        )
    }
}
