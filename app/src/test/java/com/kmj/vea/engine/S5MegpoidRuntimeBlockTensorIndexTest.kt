package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidRuntimeBlockTensorIndexTest {
    @Test
    fun indexesTypedTensorForTheExactFactoryRowAndColumn() {
        val lookupKey = "11111111111111111111111111111111"
        val tree = S5ModelObjectTree.parseUtf8(
            """{"$lookupKey":{"payload":"*F32AAAAAA=="}}"""
        )
        val key = S5MegpoidRuntimeBlockFactoryKey(
            factory = S5MegpoidRuntimeBlockFactory.TRANSPOSED_TENSOR,
            row = 2,
            column = 5,
            lookupKey = lookupKey
        )

        val index = S5MegpoidRuntimeBlockTensorIndex.build(tree, listOf(key))
        val tensor = index.uniqueTensorOrNull(key.factory, 2, 5)

        assertEquals(1, index.totalTensorReferences)
        assertEquals(1, index.summary().getValue(key.factory))
        assertEquals(2, tensor?.factoryKey?.row)
        assertEquals(5, tensor?.factoryKey?.column)
        assertEquals("*F32", tensor?.marker)
        assertEquals(1, tensor?.elementCount)
        assertEquals("$/$lookupKey/payload", tensor?.objectPath)
        assertEquals(0.0f, tensor?.decodeFloatArray()?.single() ?: Float.NaN)
    }

    @Test
    fun keepsTheEightFactoryPathsSeparateAtTheSameGridPosition() {
        val factories = S5MegpoidRuntimeBlockFactory.entries
        val keys = factories.mapIndexed { index, factory ->
            S5MegpoidRuntimeBlockFactoryKey(
                factory = factory,
                row = 1,
                column = 3,
                lookupKey = "%032x".format(index + 1)
            )
        }
        val json = keys.joinToString(prefix = "{", postfix = "}") { key ->
            "\"" + key.lookupKey + "\":\"*F32AAAAAA==\""
        }
        val tree = S5ModelObjectTree.parseUtf8(json)

        val index = S5MegpoidRuntimeBlockTensorIndex.build(tree, keys)

        assertEquals(8, index.totalTensorReferences)
        factories.forEach { factory ->
            assertEquals(1, index.tensors(factory, 1, 3).size)
            assertTrue(index.tensors(factory, 1, 2).isEmpty())
        }
    }

    @Test
    fun doesNotGuessWhenOneFactorySlotContainsMultipleTypedTensors() {
        val lookupKey = "22222222222222222222222222222222"
        val tree = S5ModelObjectTree.parseUtf8(
            """{"$lookupKey":{"weight":"*F32AAAAAA==","bias":"*F16AAA="}}"""
        )
        val key = S5MegpoidRuntimeBlockFactoryKey(
            factory = S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS,
            row = 0,
            column = 4,
            lookupKey = lookupKey
        )
        val index = S5MegpoidRuntimeBlockTensorIndex.build(tree, listOf(key))

        assertEquals(2, index.tensors(key.factory, 0, 4).size)
        assertThrows(IllegalArgumentException::class.java) {
            index.uniqueTensorOrNull(key.factory, 0, 4)
        }
    }

    @Test
    fun missingKeysRemainMissingInsteadOfMatchingByShape() {
        val tree = S5ModelObjectTree.parseUtf8("""{"other":"*F32AAAAAA=="}""")
        val key = S5MegpoidRuntimeBlockFactoryKey(
            factory = S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE,
            row = 7,
            column = 9,
            lookupKey = "33333333333333333333333333333333"
        )
        val index = S5MegpoidRuntimeBlockTensorIndex.build(tree, listOf(key))

        assertNull(index.uniqueTensorOrNull(key.factory, 7, 9))
        assertEquals(0, index.totalTensorReferences)
        assertTrue(index.matchedFactorySlots.isEmpty())
    }

    @Test
    fun rejectsDuplicateFactorySlotsAndInvalidCoordinates() {
        val key = S5MegpoidRuntimeBlockFactoryKey(
            factory = S5MegpoidRuntimeBlockFactory.DEPTHWISE_WEIGHTS,
            row = 3,
            column = 6,
            lookupKey = "44444444444444444444444444444444"
        )
        val tree = S5ModelObjectTree.parseUtf8(
            """{" """ + key.lookupKey + """ ":"*F32AAAAAA=="}"""
        )

        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeBlockTensorIndex.build(tree, listOf(key, key))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeBlockFactoryKey(
                factory = key.factory,
                row = 16,
                column = 0,
                lookupKey = key.lookupKey
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeBlockFactoryKey(
                factory = key.factory,
                row = 0,
                column = 0,
                lookupKey = "not-a-hash"
            )
        }
    }
}
