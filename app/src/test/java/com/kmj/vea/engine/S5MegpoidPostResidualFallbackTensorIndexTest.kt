package com.kmj.vea.engine

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidPostResidualFallbackTensorIndexTest {
    private class FakeDecoder(
        private val values: Map<String, FloatArray>
    ) : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray =
            values[encoded]?.copyOf() ?: throw IllegalArgumentException("Unexpected fallback tensor.")

        override fun decodeInt32Array(encoded: String): IntArray =
            throw IllegalArgumentException("No integer fallback tensors are used.")
    }

    private data class Fixture(
        val tree: S5ModelObjectTree,
        val decoder: S5TypedTensorDecoder
    )

    private fun fixture(
        scale: FloatArray = FloatArray(32) { 1f },
        normalizationBias: FloatArray = FloatArray(32),
        denseWeights: FloatArray = FloatArray(8 * 32).also { weights ->
            for (feature in 0 until 8) weights[feature * 32 + feature] = 1f
        },
        denseWeightMetadata: List<Int> = listOf(8, 32, 1, 1),
        denseBias: FloatArray = FloatArray(8)
    ): Fixture {
        fun encoded(values: FloatArray, salt: Int): String {
            val bytes = ByteArray(values.size * 4)
            values.forEachIndexed { index, value ->
                val bits = value.toRawBits()
                val offset = index * 4
                bytes[offset] = bits.toByte()
                bytes[offset + 1] = (bits ushr 8).toByte()
                bytes[offset + 2] = (bits ushr 16).toByte()
                bytes[offset + 3] = (bits ushr 24).toByte()
            }
            if (bytes.isNotEmpty()) bytes[0] = salt.toByte()
            return "*F32" + Base64.getEncoder().encodeToString(bytes)
        }

        val tensors = linkedMapOf(
            S5MegpoidPostResidualFallbackTensorKeys.NORMALIZATION_SCALE to
                (listOf(32) to encoded(scale, 1)),
            S5MegpoidPostResidualFallbackTensorKeys.NORMALIZATION_BIAS to
                (listOf(32) to encoded(normalizationBias, 2)),
            S5MegpoidPostResidualFallbackTensorKeys.DENSE_WEIGHTS to
                (denseWeightMetadata to encoded(denseWeights, 3)),
            S5MegpoidPostResidualFallbackTensorKeys.DENSE_BIAS to
                (listOf(8) to encoded(denseBias, 4))
        )

        fun tensorObject(shape: List<Int>, payload: String): String =
            "{\"" + S5MegpoidPostResidualFallbackTensorKeys.SHAPE_METADATA_KEY +
                "\":[" + shape.joinToString(",") + "],\"" +
                S5MegpoidPostResidualFallbackTensorKeys.TYPED_PAYLOAD_KEY +
                "\":\"" + payload + "\"}"

        val json = "{\"selected\":{" + tensors.entries.joinToString(",") { (key, value) ->
            "\"" + key + "\":" + tensorObject(value.first, value.second)
        } + "}}"
        val decoded = tensors.values.associate { it.second to run {
            val payload = Base64.getDecoder().decode(it.second.substring(4))
            FloatArray(payload.size / 4) { index ->
                val offset = index * 4
                val bits = (payload[offset].toInt() and 0xff) or
                    ((payload[offset + 1].toInt() and 0xff) shl 8) or
                    ((payload[offset + 2].toInt() and 0xff) shl 16) or
                    ((payload[offset + 3].toInt() and 0xff) shl 24)
                Float.fromBits(bits)
            }
        } }
        return Fixture(
            tree = S5ModelObjectTree.parseUtf8(json),
            decoder = FakeDecoder(decoded)
        )
    }

    @Test
    fun resolvesTheExactFourFallbackTensorsAndNativeShapes() {
        val fixture = fixture()
        val index = S5MegpoidPostResidualFallbackTensorIndex.build(fixture.tree)

        assertEquals(listOf(32), index.normalizationScale.descriptorMetadata)
        assertEquals(32, index.normalizationScale.elementCount)
        assertEquals(listOf(32), index.normalizationBias.descriptorMetadata)
        assertEquals(32, index.normalizationBias.elementCount)
        assertEquals(listOf(8, 32, 1, 1), index.denseWeights.descriptorMetadata)
        assertEquals(256, index.denseWeights.elementCount)
        assertEquals(listOf(8), index.denseBias.descriptorMetadata)
        assertEquals(8, index.denseBias.elementCount)

        val dimensions = index.resolveDimensions()
        assertEquals(32, dimensions.inputFeatures)
        assertEquals(8, dimensions.outputFeatures)
    }

    @Test
    fun buildsTheFallbackStageInNativeNormalizeThenDenseOrder() {
        val fixture = fixture()
        val stage = S5MegpoidPostResidualFallbackTensorIndex.build(fixture.tree)
            .buildStage(fixture.decoder)

        assertEquals(32, stage.inputFeatures)
        assertEquals(8, stage.outputFeatures)

        val input = FloatArray(32 * 2) { index -> index.toFloat() }
        val output = stage.forward(
            inputFeatureMajor = input,
            positions = 2,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )
        assertEquals(16, output.size)
        assertTrueAllFinite(output)
    }

    @Test
    fun rejectsAWeightPayloadCountThatDisagreesWithItsMetadata() {
        val fixture = fixture(denseWeights = FloatArray(255))
        // The nested VTB2 parser checks metadata product against payload
        // length before the feature-specific [8,32,1,1] contract is resolved.
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidPostResidualFallbackTensorIndex.build(fixture.tree)
        }
    }

    @Test
    fun rejectsAValidElementCountWithWrongAxisMetadata() {
        val fixture = fixture(
            denseWeightMetadata = listOf(8, 16, 2, 1),
            denseWeights = FloatArray(256)
        )
        val index = S5MegpoidPostResidualFallbackTensorIndex.build(fixture.tree)
        assertEquals(256, index.denseWeights.elementCount)
        assertThrows(IllegalArgumentException::class.java) {
            index.resolveDimensions()
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.buildStage(fixture.decoder)
        }
    }

    @Test
    fun rejectsMissingAndAmbiguousFallbackProperties() {
        val missing = S5ModelObjectTree.parseUtf8("{}")
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidPostResidualFallbackTensorIndex.build(missing)
        }

        val fixture = fixture()
        val duplicate = S5ModelObjectTree.parseUtf8(
            "{\"a\":{\"" + S5MegpoidPostResidualFallbackTensorKeys.DENSE_WEIGHTS +
                "\":1},\"b\":{\"" + S5MegpoidPostResidualFallbackTensorKeys.DENSE_WEIGHTS +
                "\":2}}"
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidPostResidualFallbackTensorIndex.build(duplicate)
        }
        assertEquals(32, S5MegpoidPostResidualFallbackTensorIndex.build(fixture.tree)
            .normalizationScale.elementCount)
    }

    private fun assertTrueAllFinite(values: FloatArray) {
        values.forEach { value ->
            org.junit.Assert.assertTrue(value.isFinite())
        }
    }
}
