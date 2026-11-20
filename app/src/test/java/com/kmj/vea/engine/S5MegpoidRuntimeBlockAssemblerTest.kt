package com.kmj.vea.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidRuntimeBlockAssemblerTest {
    @Test
    fun transposesDepthwiseFilterPerChannelExactlyLikeTheNativeBuilder() {
        val source = FloatArray(2 * 2 * 3) { it.toFloat() }

        val actual = S5MegpoidRuntimeBlockAssembler.transposeDepthwiseFilter(
            source = source,
            channels = 2,
            height = 2,
            width = 3
        )

        assertEquals(listOf(0f, 3f, 1f, 4f, 2f, 5f), actual.take(6))
        assertEquals(listOf(6f, 9f, 7f, 10f, 8f, 11f), actual.drop(6))
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeBlockAssembler.transposeDepthwiseFilter(
                source = FloatArray(11), channels = 2, height = 2, width = 3
            )
        }
    }

    @Test
    fun compilesConditionedMlpParametersForOneRealBlockGridSlot() {
        val row = 0
        val column = 0
        val keys = S5MegpoidRuntimeBlockFactoryKeys.ALL.filter {
            it.row == row && it.column == column
        }
        val depthwise = FloatArray(32 * 7 * 7) { it.toFloat() }
        val descriptorA = FloatArray(32) { 2.0f }
        val descriptorB = FloatArray(32) { 3.0f }
        val expansionWeights = FloatArray(64 * 32) { 1.0f }
        val expansionBias = FloatArray(64) { 4.0f }
        val projectionWeights = FloatArray(32 * 64) { 5.0f }
        val projectionBias = FloatArray(32) { 7.0f }
        val residualScale = FloatArray(32) { 0.5f }

        val values = mapOf(
            S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER to depthwise,
            S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A to descriptorA,
            S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B to descriptorB,
            S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS to expansionWeights,
            S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS to expansionBias,
            S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS to projectionWeights,
            S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS to projectionBias,
            S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE to residualScale
        )
        val metadata = mapOf(
            S5MegpoidRuntimeBlockFactory.DEPTHWISE_FILTER to listOf(7, 7, 1, 32),
            S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_A to listOf(32),
            S5MegpoidRuntimeBlockFactory.SHARED_DESCRIPTOR_B to listOf(32),
            S5MegpoidRuntimeBlockFactory.EXPANSION_WEIGHTS to listOf(1, 1, 32, 64),
            S5MegpoidRuntimeBlockFactory.EXPANSION_OPTIONAL_BIAS to listOf(64),
            S5MegpoidRuntimeBlockFactory.PROJECTION_WEIGHTS to listOf(1, 1, 64, 32),
            S5MegpoidRuntimeBlockFactory.PROJECTION_OPTIONAL_BIAS to listOf(32),
            S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE to listOf(1, 1, 32, 1)
        )
        val json = keys.joinToString(prefix = "{", postfix = "}") { key ->
            val factory = key.factory
            "\"" + key.lookupKey + "\":{" +
                "\"" + TENSOR_CHILD_KEY + "\":\"" + encodeF32(values.getValue(factory)) + "\"," +
                "\"" + METADATA_CHILD_KEY + "\":[" + metadata.getValue(factory).joinToString(",") + "]}"
        }
        val modelTree = S5ModelObjectTree.parseUtf8(json)
        val index = S5MegpoidRuntimeBlockTensorIndex.build(modelTree, keys)

        val compiled = S5MegpoidRuntimeBlockAssembler.compile(
            index = index,
            row = row,
            column = column,
            tensorDecoder = LittleEndianFloatDecoder
        )

        assertEquals(32, compiled.channels)
        assertEquals(2, compiled.expansionMultiplier)
        assertEquals(64, compiled.expandedFeatures)
        assertEquals(1568, compiled.depthwiseWeightsChannelMajor.size)
        assertEquals(1568, compiled.nativeRecordDepthwiseFilter.size)
        assertEquals(2048, compiled.expansionWeights.size)
        assertEquals(64, compiled.expansionGamma.size)
        assertEquals(64, compiled.expansionBeta.size)
        assertEquals(2048, compiled.projectionWeights.size)
        assertEquals(32, compiled.projectionBias.size)
        assertEquals(32, compiled.residualScale.size)

        // W'[feature,channel] = W[feature,channel] * descriptorA[channel]
        assertEquals(2.0f, compiled.expansionWeights[0])
        assertEquals(2.0f, compiled.expansionWeights[31])
        assertEquals(2.0f, compiled.expansionWeights[32])

        // gamma = source bias + dot(W, descriptorB); beta = dot(W, descriptorA)
        assertEquals(100.0f, compiled.expansionGamma[0])
        assertEquals(100.0f, compiled.expansionGamma[63])
        assertEquals(64.0f, compiled.expansionBeta[0])
        assertEquals(64.0f, compiled.expansionBeta[63])

        // The reference kernel retains [channel][height][width] source order.
        assertEquals(depthwise.toList(), compiled.depthwiseWeightsChannelMajor.toList())

        // S5API's record builder permutes [C][H][W] into [W][C][H].
        assertEquals(depthwise[0], compiled.nativeRecordDepthwiseFilter[0])
        assertEquals(depthwise[7], compiled.nativeRecordDepthwiseFilter[1])
        assertEquals(depthwise[1], compiled.nativeRecordDepthwiseFilter[7])
        assertEquals(5.0f, compiled.projectionWeights[0])
        assertEquals(7.0f, compiled.projectionBias[0])
        assertEquals(0.5f, compiled.residualScale[0])
    }

    @Test
    fun rejectsBlockWhenOneOfItsFactoryPropertiesIsMissing() {
        val keys = S5MegpoidRuntimeBlockFactoryKeys.ALL.filter {
            it.row == 0 && it.column == 0 &&
                it.factory != S5MegpoidRuntimeBlockFactory.RESIDUAL_SCALE
        }
        val json = keys.joinToString(prefix = "{", postfix = "}") { key ->
            "\"" + key.lookupKey + "\":{}"
        }
        val index = S5MegpoidRuntimeBlockTensorIndex.build(
            S5ModelObjectTree.parseUtf8(json), keys
        )

        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeBlockAssembler.compile(
                index = index,
                row = 0,
                column = 0,
                tensorDecoder = LittleEndianFloatDecoder
            )
        }
    }

    private fun encodeF32(values: FloatArray): String {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(bytes::putFloat)
        return "*F32" + Base64.getEncoder().encodeToString(bytes.array())
    }

    private object LittleEndianFloatDecoder : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray {
            require(encoded.startsWith("*F32"))
            val bytes = Base64.getDecoder().decode(encoded.substring(4))
            require(bytes.size % 4 == 0)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.float }
        }

        override fun decodeInt32Array(encoded: String): IntArray =
            throw UnsupportedOperationException("Int32 decoding is not used in this test.")
    }

    companion object {
        private const val TENSOR_CHILD_KEY = "0ed3f4c15ca6d021d59b2b1038226a7f"
        private const val METADATA_CHILD_KEY = "4ba18a32e4fa1a2f3b293713692a3a2e"
    }
}
