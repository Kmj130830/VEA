package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5ModelObjectTreeTest {
    private class RecordingDecoder : S5TypedTensorDecoder {
        var floatInput: String? = null
        var intInput: String? = null
        override fun decodeFloatArray(encoded: String): FloatArray {
            floatInput = encoded
            return floatArrayOf(1.25f, -2.5f)
        }
        override fun decodeInt32Array(encoded: String): IntArray {
            intInput = encoded
            return intArrayOf(-7, 42)
        }
    }

    @Test
    fun parsesNestedObjectsArraysAndPrimitiveValues() {
        val tree = S5ModelObjectTree.parseUtf8(
            """{"branch":{"weights":[1,2.5,-3e1],"enabled":true,"empty":null}}"""
        )
        val branch = tree.findByLookupKey("branch")!!.asObject()
        assertArrayEquals(floatArrayOf(1f, 2.5f, -30f),
            branch.fields.getValue("weights").asFloatArray(), 0.0001f)
        assertTrue((branch.fields.getValue("enabled") as S5ModelValue.BooleanValue).value)
        assertTrue(branch.fields.getValue("empty") is S5ModelValue.NullValue)
    }

    @Test
    fun hashesPropertyNameThroughInjectedLookupFunction() {
        val tree = S5ModelObjectTree.parseUtf8("""{"deadbeef":17}""")
        var input: String? = null
        val found = tree.findParameter("rnn.weight_ih_l0") {
            input = it
            "deadbeef"
        }
        assertEquals("rnn.weight_ih_l0", input)
        assertEquals(17.0, (found as S5ModelValue.NumberValue).value, 0.0)
        assertNull(tree.findParameter("not-present") { "missing" })
    }

    @Test
    fun findsUniqueLookupKeyInsideNestedObjectAndArrayContainers() {
        val tree = S5ModelObjectTree.parseUtf8(
            """{"container":{"layers":[{"weightKey":{"value":17}}]}}"""
        )

        val match = tree.findUniqueByLookupKeyRecursive("weightKey")

        assertEquals("$/container/layers[0]/weightKey", match?.path)
        assertEquals(17.0, (match?.value as S5ModelValue.ObjectValue)
            .fields.getValue("value").let { (it as S5ModelValue.NumberValue).value }, 0.0)
        assertNull(tree.findUniqueByLookupKeyRecursive("missing"))
    }

    @Test
    fun rejectsAmbiguousRecursiveLookupKeysInsteadOfSelectingOne() {
        val tree = S5ModelObjectTree.parseUtf8(
            """{"left":{"weightKey":"one"},"right":{"weightKey":"two"}}"""
        )

        assertEquals(2, tree.findAllByLookupKeyRecursive("weightKey").size)
        assertThrows(IllegalArgumentException::class.java) {
            tree.findUniqueByLookupKeyRecursive("weightKey")
        }
    }

    @Test
    fun acceptsUtf8AndValidEscapedSurrogatePairs() {
        val tree = S5ModelObjectTree.parseUtf8("""{"text":"한글 😀 \uD83D\uDE00"}""")
        assertEquals("한글 😀 😀", tree.findByLookupKey("text")!!.asString())
    }

    @Test
    fun decodesTypedMarkersThroughInjectedTensorDecoder() {
        val decoder = RecordingDecoder()
        val floatValue = S5ModelValue.StringValue("*F32AAAA")
        val intValue = S5ModelValue.StringValue("*S32AAAA")
        assertArrayEquals(floatArrayOf(1.25f, -2.5f), floatValue.asFloatArray(decoder), 0.0001f)
        assertArrayEquals(intArrayOf(-7, 42), intValue.asIntArray(decoder))
        assertEquals("*F32AAAA", decoder.floatInput)
        assertEquals("*S32AAAA", decoder.intInput)
    }

    @Test
    fun rejectsTypedMarkerTypeConfusion() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelValue.StringValue("*S32AAAA").asFloatArray(RecordingDecoder())
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelValue.StringValue("*F16AAAA").asIntArray(RecordingDecoder())
        }
    }

    @Test
    fun convertsNumericArraysWithRangeChecks() {
        assertArrayEquals(intArrayOf(-2, 0, Int.MAX_VALUE),
            S5ModelValue.ArrayValue(listOf(
                S5ModelValue.NumberValue(-2.0),
                S5ModelValue.NumberValue(0.0),
                S5ModelValue.NumberValue(Int.MAX_VALUE.toDouble())
            )).asIntArray())
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelValue.ArrayValue(listOf(S5ModelValue.NumberValue(1.5))).asIntArray()
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelValue.ArrayValue(listOf(S5ModelValue.NumberValue(Double.MAX_VALUE))).asFloatArray()
        }
    }

    @Test
    fun rejectsDuplicateKeysMalformedNumbersAndTrailingContent() {
        val invalid = listOf(
            """{"a":1,"a":2}""",
            """{"a":01}""",
            """{"a":1.}""",
            """{"a":1e}""",
            """{"a":1} trailing"""
        )
        invalid.forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                S5ModelObjectTree.parseUtf8(input)
            }
        }
    }

    @Test
    fun rejectsUnpairedUnicodeSurrogates() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelObjectTree.parseUtf8("""{"x":"\uD800"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelObjectTree.parseUtf8("""{"x":"\uDC00"}""")
        }
    }

    @Test
    fun rejectsMalformedUtf8AndNonObjectRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelObjectTree.parseUtf8(
                byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte(), 0x28, 0x7d)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelObjectTree.parseUtf8("[1,2]")
        }
        assertTrue(S5ModelObjectTree.parseUtf8("""{"ok":true}""").root.fields.isNotEmpty())
    }

    @Test
    fun enforcesInputDepthAndNodeLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelObjectTree.parseUtf8(
                """{"x":1}""",
                S5ModelObjectTree.Limits(maxInputBytes = 3)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelObjectTree.parseUtf8(
                """{"a":{"b":1}}""",
                S5ModelObjectTree.Limits(maxDepth = 1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ModelObjectTree.parseUtf8(
                """{"a":1,"b":2}""",
                S5ModelObjectTree.Limits(maxNodes = 2)
            )
        }
    }
}
