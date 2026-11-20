package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidBlockTensorIndexTest {
    @Test
    fun indexesTypedTensorsBelowNestedFixedLookupKeys() {
        val key1 = S5MegpoidBlockLookupSlot.KEY_1.lookupKey
        val key2 = S5MegpoidBlockLookupSlot.KEY_2.lookupKey
        val tree = S5ModelObjectTree.parseUtf8(
            """{"root":{"$key1":[{"weight":"*F32AAAAAA=="},{"weight":"*F32AQAAAA=="}],"$key2":{"scale":"*F16AAE="}}}"""
        )

        val index = S5MegpoidBlockTensorIndex.build(tree)

        assertEquals(2, index.tensors(S5MegpoidBlockLookupSlot.KEY_1).size)
        assertEquals(1, index.tensors(S5MegpoidBlockLookupSlot.KEY_2).size)
        assertEquals(2, index.matchedLookupSlots.size)
        assertTrue(index.tensors(S5MegpoidBlockLookupSlot.KEY_3).isEmpty())

        val first = index.tensors(S5MegpoidBlockLookupSlot.KEY_1).first()
        assertEquals("*F32", first.marker)
        assertEquals(1, first.elementCount)
        assertEquals(4, first.encodedPayloadBytes)
        assertEquals(floatArrayOf(0.25f).toList(), first.decodeFloatArray(TestTensorDecoder()).toList())

        val half = index.tensors(S5MegpoidBlockLookupSlot.KEY_2).single()
        assertEquals("*F16", half.marker)
        assertEquals(1, half.elementCount)
        assertEquals(2, half.encodedPayloadBytes)
        assertEquals(floatArrayOf(-0.5f).toList(), half.decodeFloatArray(TestTensorDecoder()).toList())
    }

    @Test
    fun pairsPrimaryAndSecondaryPropertiesOnlyWithinTheSameLayerObject() {
        val firstKey = S5MegpoidBlockLookupSlot.KEY_1.lookupKey
        val secondKey = S5MegpoidBlockLookupSlot.KEY_2.lookupKey
        val tree = S5ModelObjectTree.parseUtf8(
            """{"layers":[{"$firstKey":"*F32AAAAAA==","$secondKey":"*F32AQAAAA=="},{"$firstKey":"*F32AgAAAA=="}]}"""
        )

        val pairs = S5MegpoidBlockTensorIndex.build(tree).modelPropertyPairs()

        assertEquals(1, pairs.size)
        assertEquals("$/layers[0]", pairs.single().containingObjectPath)
        assertEquals(S5MegpoidBlockLookupSlot.KEY_1, pairs.single().first.slot)
        assertEquals(S5MegpoidBlockLookupSlot.KEY_2, pairs.single().second.slot)
    }

    @Test
    fun rejectsMalformedBase64LengthsAndMisalignedTensorPayloads() {
        val key = S5MegpoidBlockLookupSlot.KEY_1.lookupKey
        val malformedLength = S5ModelObjectTree.parseUtf8(
            """{"$key":"*F32A"}"""
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidBlockTensorIndex.build(malformedLength)
        }

        val misalignedF32 = S5ModelObjectTree.parseUtf8(
            """{"$key":"*F32AAA="}"""
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidBlockTensorIndex.build(misalignedF32)
        }
    }

    private class TestTensorDecoder : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray = when (encoded) {
            "*F32AAAAAA==" -> floatArrayOf(0.25f)
            "*F32AQAAAA==" -> floatArrayOf(0.75f)
            "*F32AgAAAA==" -> floatArrayOf(1.25f)
            "*F16AAE=" -> floatArrayOf(-0.5f)
            else -> error("Unexpected test tensor.")
        }

        override fun decodeInt32Array(encoded: String): IntArray =
            error("No integer tensor is expected in this test.")
    }
}
