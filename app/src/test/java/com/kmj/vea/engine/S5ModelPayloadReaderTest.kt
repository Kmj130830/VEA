package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ModelPayloadReaderTest {
    @Test
    fun extractsTypedFloatAndIntegerFieldsWithoutFlatteningRepeatedKeys() {
        val json = """
            {
              "root": {
                "layerA": {
                  "data": "*F32alpha",
                  "shape": "*S32shapeA"
                },
                "layerB": {
                  "data": "*F16beta"
                }
              },
              "items": [
                { "layerA": { "data": "*F32gamma" } }
              ]
            }
        """.trimIndent()

        val tree = S5ModelPayloadReader.parseDecodedPayload(
            decodedPayload = json.toByteArray(Charsets.UTF_8),
            decodeFloatArray = { encoded ->
                when (encoded) {
                    "*F32alpha" -> floatArrayOf(1.0f, 2.0f)
                    "*F16beta" -> floatArrayOf(3.0f)
                    "*F32gamma" -> floatArrayOf(4.0f, 5.0f)
                    else -> error("Unexpected fixture: $encoded")
                }
            },
            decodeInt32Array = { encoded ->
                assertEquals("*S32shapeA", encoded)
                intArrayOf(2, 1)
            }
        )

        assertEquals(3, tree.objects.size)
        val first = tree.requireUniqueObject("$.root.layerA")
        assertArrayEquals(floatArrayOf(1.0f, 2.0f), first.fields.getValue("data").floatValues, 0.0f)
        assertArrayEquals(intArrayOf(2, 1), first.fields.getValue("shape").intValues)
        assertEquals(S5TensorEncoding.FLOAT32, first.fields.getValue("data").encoding)
        assertEquals(S5TensorEncoding.INT32, first.fields.getValue("shape").encoding)

        val second = tree.requireUniqueObject("$.root.layerB")
        assertArrayEquals(floatArrayOf(3.0f), second.fields.getValue("data").floatValues, 0.0f)
        assertEquals(S5TensorEncoding.FLOAT16, second.fields.getValue("data").encoding)

        val repeated = tree.objectsContaining("data")
        assertEquals(3, repeated.size)
        assertNull(second.fields.getValue("data").intValues)
    }

    @Test
    fun stripsUtf8BomAndTrailingWhitespace() {
        val json = "\uFEFF  { \"weights\": { \"values\": \"*F32fixture\" } }  \n"
        val tree = S5ModelPayloadReader.parseDecodedPayload(
            json.toByteArray(Charsets.UTF_8),
            decodeFloatArray = { floatArrayOf(9.0f) },
            decodeInt32Array = { error("No integer tensor expected.") }
        )

        assertArrayEquals(
            floatArrayOf(9.0f),
            tree.requireUniqueObject("$.weights").fields.getValue("values").floatValues,
            0.0f
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonJsonPayload() {
        S5ModelPayloadReader.parseDecodedPayload(
            "not-json".toByteArray(),
            decodeFloatArray = { floatArrayOf() },
            decodeInt32Array = { intArrayOf() }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteDecodedTensor() {
        val json = """{"weights":{"values":"*F32fixture"}}"""
        S5ModelPayloadReader.parseDecodedPayload(
            json.toByteArray(),
            decodeFloatArray = { floatArrayOf(Float.NaN) },
            decodeInt32Array = { intArrayOf() }
        )
    }
}
