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
        assertEquals("*F32", result.matchedTensorStrings[tensorKey]?.single()?.typeTag)
        assertEquals("*F32AQID", result.matchedTensorStrings[tensorKey]?.single()?.encodedValue)
        assertEquals("$.root.$tensorKey", result.matchedTensorStrings[tensorKey]?.single()?.valuePath)
    }

    @Test
    fun capturesTypedFieldsNestedUnderMatchedOpaqueObjectKey() {
        val json = """{
            "root": {
                "$tensorKey": {
                    "weights": {
                        "input": "*F32AQID",
                        "recurrent": "*F16AA=="
                    }
                }
            }
        }"""

        val result = S5ModelJsonScanner.scanForTensorKeys(
            StringReader(json),
            setOf(tensorKey)
        )
        val matches = result.matchedTensorStrings.getValue(tensorKey)

        assertEquals(2, matches.size)
        assertEquals(
            listOf(
                "$.root.$tensorKey.weights.input",
                "$.root.$tensorKey.weights.recurrent"
            ),
            matches.map { it.valuePath }
        )
        assertEquals(listOf("input", "recurrent"), matches.map { it.fieldKey })
        assertEquals(listOf("*F32", "*F16"), matches.map { it.typeTag })
    }

    @Test
    fun decodesOnlyTensorStringsSelectedByHashedObjectKey() {
        val matches = listOf(
            S5TypedTensorString(
                objectKey = tensorKey,
                valuePath = "$.root.$tensorKey.weights",
                fieldKey = "weights",
                typeTag = "*F32",
                encodedValue = "*F32weights"
            ),
            S5TypedTensorString(
                objectKey = tensorKey,
                valuePath = "$.root.$tensorKey.bias",
                fieldKey = "bias",
                typeTag = "*S32",
                encodedValue = "*S32bias"
            )
        )

        val decoded = S5ModelTensorDecoder.decodeMatches(
            matches = matches,
            decodeFloatArray = { encoded ->
                assertEquals("*F32weights", encoded)
                floatArrayOf(1.25f, -0.5f)
            },
            decodeInt32Array = { encoded ->
                assertEquals("*S32bias", encoded)
                intArrayOf(2, 3)
            }
        )

        assertEquals(2, decoded.size)
        assertEquals(S5DecodedTensorKind.FLOAT32, decoded[0].kind)
        assertEquals(listOf(1.25f, -0.5f), decoded[0].floatValues?.toList())
        assertEquals(S5DecodedTensorKind.INT32, decoded[1].kind)
        assertEquals(listOf(2, 3), decoded[1].intValues?.toList())
        assertEquals("$.root.$tensorKey.weights", decoded[0].valuePath)
    }

    @Test
    fun scansNumericAndBooleanConfigFieldsUnderSelectedHashedObject() {
        val configKey = "fedcba9876543210fedcba9876543210"
        val json = """
            {
              "layers": {
                "$configKey": {
                  "input_width": 418,
                  "hidden_width": 9,
                  "enabled": true,
                  "optional": null,
                  "nested": { "scale": 0.25 }
                },
                "unrelated": { "input_width": 99 }
              }
            }
        """.trimIndent()

        val result = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json),
            setOf(configKey)
        )
        val values = result.matchedValues.getValue(configKey)

        assertEquals(setOf(configKey), result.matchedObjectKeys)
        assertEquals(5, values.size)
        assertEquals(
            listOf("input_width", "hidden_width", "enabled", "optional", "scale"),
            values.map { it.fieldKey }
        )
        assertEquals(
            listOf("418", "9", "true", null, "0.25"),
            values.map { it.value }
        )
        assertEquals(
            listOf(
                S5JsonScalarKind.NUMBER,
                S5JsonScalarKind.NUMBER,
                S5JsonScalarKind.BOOLEAN,
                S5JsonScalarKind.NULL,
                S5JsonScalarKind.NUMBER
            ),
            values.map { it.kind }
        )
        assertEquals(
            "$.layers.$configKey.nested.scale",
            values.last().valuePath
        )
        assertEquals(0, result.matchedValues.size - 1)
    }

    @Test
    fun recordsPresenceOfDirectFeatureFlagKeyEvenWhenValueIsFalse() {
        val featureKey = "9f174dc2b1ab89f96f4577e17d5e568a"
        val json = """{"model":{"$featureKey":false}}"""

        val result = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json),
            setOf(featureKey)
        )

        assertEquals(setOf(featureKey), result.matchedObjectKeys)
        assertEquals("false", result.matchedValues.getValue(featureKey).single().value)
    }

    @Test
    fun absentFeatureKeyIsNotReportedAsPresent() {
        val featureKey = "9f174dc2b1ab89f96f4577e17d5e568a"
        val result = S5ModelJsonScanner.scanForObjectKeys(
            StringReader("""{"model":{"other":true}}"""),
            setOf(featureKey)
        )

        assertTrue(result.matchedObjectKeys.isEmpty())
        assertTrue(result.matchedValues.isEmpty())
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
