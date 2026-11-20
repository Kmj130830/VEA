package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

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
    fun pairsPrimaryAndSecondaryPropertiesAndReportsDescriptorElementRatio() {
        val firstKey = S5MegpoidBlockLookupSlot.KEY_1.lookupKey
        val secondKey = S5MegpoidBlockLookupSlot.KEY_2.lookupKey
        val tree = S5ModelObjectTree.parseUtf8(
            """{"layers":[{"$firstKey":"*F32AAAAAAAAAAA=","$secondKey":"*F32AAAAAA=="},{"$firstKey":"*F32AAAAAAAAAAA=","$secondKey":"*F32AAAAAAAAAAAAAAAA"}]}"""
        )

        val pairs = S5MegpoidBlockTensorIndex.build(tree).modelPropertyPairs()

        assertEquals(2, pairs.size)
        assertEquals("$/layers[0]", pairs[0].containingObjectPath)
        assertEquals(2, pairs[0].first.elementCount)
        assertEquals(1, pairs[0].second.elementCount)
        assertEquals(2, pairs[0].primaryToSecondaryElementRatio)
        assertEquals(2, pairs[1].first.elementCount)
        assertEquals(3, pairs[1].second.elementCount)
        assertEquals(null, pairs[1].primaryToSecondaryElementRatio)
        assertEquals(null, pairs[0].singleBankEntryShapeCandidateOrNull())
        assertEquals(null, pairs[1].singleBankEntryShapeCandidateOrNull())
    }

    @Test
    fun keepsSingleBankEntryShapeAsCompatibilityCandidateNotRepeatedBankMatch() {
        val firstKey = S5MegpoidBlockLookupSlot.KEY_1.lookupKey
        val secondKey = S5MegpoidBlockLookupSlot.KEY_2.lookupKey
        val primary = typedF32Zeros(24)
        val secondary = typedF32Zeros(4)
        val tree = S5ModelObjectTree.parseUtf8(
            """{"$firstKey":"$primary","$secondKey":"$secondary"}"""
        )
        val pair = S5MegpoidBlockTensorIndex.build(tree).modelPropertyPairs().single()

        assertEquals(
            S5MegpoidRepeatedBlockMatrixShape(rows = 4, columns = 2, matrixCount = 3),
            pair.repeatedBlockMatrixShapeOrNull()
        )
        assertEquals(
            S5MegpoidRepeatedBlockMatrixShape(rows = 4, columns = 6, matrixCount = 1),
            pair.singleBankEntryShapeCandidateOrNull()
        )
    }

    @Test
    fun infersRepeatedBlockMatrixRowsColumnsAndBankCountOnlyForMatchingPairs() {
        val firstKey = S5MegpoidBlockLookupSlot.KEY_1.lookupKey
        val secondKey = S5MegpoidBlockLookupSlot.KEY_2.lookupKey
        val primaryValid = typedF32Zeros(24)
        val secondaryValid = typedF32Zeros(4)
        val primaryNonMatching = typedF32Zeros(12)
        val secondaryNonMatching = typedF32Zeros(4)
        val tree = S5ModelObjectTree.parseUtf8(
            """{"layers":[{"$firstKey":"$primaryValid","$secondKey":"$secondaryValid"},{"$firstKey":"$primaryNonMatching","$secondKey":"$secondaryNonMatching"}]}"""
        )

        val pairs = S5MegpoidBlockTensorIndex.build(tree).modelPropertyPairs()
        val validShape = pairs[0].repeatedBlockMatrixShapeOrNull()

        assertEquals(2, pairs.size)
        assertEquals(24, pairs[0].first.elementCount)
        assertEquals(4, pairs[0].second.elementCount)
        assertEquals(S5MegpoidRepeatedBlockMatrixShape(rows = 4, columns = 2, matrixCount = 3), validShape)
        assertEquals(null, pairs[1].repeatedBlockMatrixShapeOrNull())
    }

    private fun typedF32Zeros(elements: Int): String {
        val payload = Base64.getEncoder().encodeToString(ByteArray(elements * 4))
        return "*F32" + payload
    }

    @Test
    fun decodesInterleavedMatrixBanksIntoNativeObjectOrder() {
        val firstKey = S5MegpoidBlockLookupSlot.KEY_1.lookupKey
        val secondKey = S5MegpoidBlockLookupSlot.KEY_2.lookupKey
        val primary = FloatArray(24) { it.toFloat() }
        val secondary = floatArrayOf(100f, 101f, 102f, 103f)
        val tree = S5ModelObjectTree.parseUtf8(
            """{"$firstKey":"${typedF32(primary)}","$secondKey":"${typedF32(secondary)}"}"""
        )
        val pair = S5MegpoidBlockTensorIndex.build(tree).modelPropertyPairs().single()
        val decoder = Base64F32TensorDecoder()

        val rowMajor = pair.decodeMatrixBankPayloadOrNull(
            S5MegpoidMatrixInputOrder.ROW_MAJOR,
            decoder
        )!!
        assertEquals(S5MegpoidRepeatedBlockMatrixShape(4, 2, 3), rowMajor.shape)
        assertEquals(
            listOf(2f, 5f, 8f, 11f, 14f, 17f, 20f, 23f),
            rowMajor.matrices[0].toList()
        )
        assertEquals(
            listOf(1f, 4f, 7f, 10f, 13f, 16f, 19f, 22f),
            rowMajor.matrices[1].toList()
        )
        assertEquals(
            listOf(0f, 3f, 6f, 9f, 12f, 15f, 18f, 21f),
            rowMajor.matrices[2].toList()
        )
        assertEquals(secondary.toList(), rowMajor.secondaryProperty.toList())

        val nativeOrder = pair.decodeNativeMatrixBankPayloadOrNull(decoder)!!
        assertEquals(rowMajor.shape, nativeOrder.shape)
        assertEquals(
            rowMajor.matrices.map { it.toList() },
            nativeOrder.matrices.map { it.toList() }
        )
        assertEquals(rowMajor.secondaryProperty.toList(), nativeOrder.secondaryProperty.toList())

        val columnMajor = pair.decodeMatrixBankPayloadOrNull(
            S5MegpoidMatrixInputOrder.COLUMN_MAJOR,
            decoder
        )!!
        assertEquals(
            listOf(2f, 14f, 5f, 17f, 8f, 20f, 11f, 23f),
            columnMajor.matrices[0].toList()
        )
        assertEquals(
            listOf(1f, 13f, 4f, 16f, 7f, 19f, 10f, 22f),
            columnMajor.matrices[1].toList()
        )
        assertEquals(
            listOf(0f, 12f, 3f, 15f, 6f, 18f, 9f, 21f),
            columnMajor.matrices[2].toList()
        )
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

    private fun typedF32(values: FloatArray): String {
        val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach(::putFloat) }
            .array()
        return "*F32" + Base64.getEncoder().encodeToString(bytes)
    }

    private class Base64F32TensorDecoder : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray {
            require(encoded.startsWith("*F32"))
            val bytes = Base64.getDecoder().decode(encoded.substring(4))
            require(bytes.size % Float.SIZE_BYTES == 0)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
        }

        override fun decodeInt32Array(encoded: String): IntArray =
            error("No integer tensor is expected in this test.")
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
