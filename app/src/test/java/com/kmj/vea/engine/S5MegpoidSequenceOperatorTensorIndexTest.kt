package com.kmj.vea.engine

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidSequenceOperatorTensorIndexTest {
    private class FakeDecoder(private val values: Map<String, FloatArray>) : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray =
            values[encoded]?.copyOf() ?: throw IllegalArgumentException("Unexpected fixture tensor.")
        override fun decodeInt32Array(encoded: String): IntArray = throw IllegalArgumentException("Unused.")
    }
    private data class Fixture(val tree: S5ModelObjectTree, val decoder: S5TypedTensorDecoder)

    private fun fixture(
        tensors: Map<Pair<S5MegpoidSequenceOperatorFactory, Int>, FloatArray>,
        metadata: Map<Pair<S5MegpoidSequenceOperatorFactory, Int>, List<Int>> = emptyMap()
    ): Fixture {
        val decoded = mutableMapOf<String, FloatArray>()
        val fields = tensors.entries.map { entry ->
            val key = S5MegpoidSequenceOperatorFactoryKeys.lookupKey(entry.key.first, entry.key.second)
            val raw = ByteArray(entry.value.size * 4)
            raw[0] = (entry.key.second + 1).toByte()
            val encoded = "*F32" + Base64.getEncoder().encodeToString(raw)
            decoded[encoded] = entry.value
            val dimensions = metadata[entry.key] ?: listOf(entry.value.size)
            """{"$key":{"${S5MegpoidCarrierFusionModelPath.SHAPE_METADATA_KEY}":[${dimensions.joinToString(",")}],"${S5MegpoidCarrierFusionModelPath.TYPED_PAYLOAD_KEY}":"$encoded"}"}""
        }
        return Fixture(S5ModelObjectTree.parseUtf8("{\"selected\":{" + fields.joinToString(",") + "}}"), FakeDecoder(decoded))
    }

    @Test fun exposesAllSixSixteenEntryFactoryTables() {
        S5MegpoidSequenceOperatorFactory.entries.forEach {
            assertEquals(16, S5MegpoidSequenceOperatorFactoryKeys.keys(it).size)
        }
        assertEquals("c3d133918cd850510369fb60a88ef3f2",
            S5MegpoidSequenceOperatorFactoryKeys.lookupKey(S5MegpoidSequenceOperatorFactory.TEMPORAL_BIAS, 0))
        assertEquals("df046af48cc4ee1a7c4e19bf5ca56393",
            S5MegpoidSequenceOperatorFactoryKeys.lookupKey(S5MegpoidSequenceOperatorFactory.TEMPORAL_WEIGHTS, 0))
        assertEquals("2afb60a53c85da8f2b1430e47c9409cb",
            S5MegpoidSequenceOperatorFactoryKeys.lookupKey(S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_BIAS, 0))
        assertEquals("650c34b31190b6797bb78a3bdf852de4",
            S5MegpoidSequenceOperatorFactoryKeys.lookupKey(S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_WEIGHTS, 0))
    }

    @Test fun buildsTemporalProjectionFromCorrectWeightAndBiasFactories() {
        val f = fixture(mapOf(
            (S5MegpoidSequenceOperatorFactory.TEMPORAL_WEIGHTS to 0) to FloatArray(12) { it + 1f },
            (S5MegpoidSequenceOperatorFactory.TEMPORAL_BIAS to 0) to floatArrayOf(0.5f, -0.5f)
        ))
        val stage = S5MegpoidSequenceOperatorTensorIndex.build(f.tree)
            .buildTemporalProjection(0, 2, 2, 3, true, f.decoder)
        assertEquals(2, stage.inputFeatures)
        assertEquals(2, stage.outputFeatures)
        assertEquals(3, stage.kernelSize)
    }

    @Test fun honorsDisabledTemporalBiasWithoutRequiringItsKey() {
        val f = fixture(mapOf(
            (S5MegpoidSequenceOperatorFactory.TEMPORAL_WEIGHTS to 1) to FloatArray(8)
        ))
        val index = S5MegpoidSequenceOperatorTensorIndex.build(f.tree)
        val stage = index.buildTemporalProjection(1, 2, 2, 2, false, f.decoder)
        assertEquals(2, stage.kernelSize)
        assertNull(index.tensorOrNull(S5MegpoidSequenceOperatorFactory.TEMPORAL_BIAS, 1))
    }

    @Test fun transposesBankedSourceToNativeBankOutputInputLayout() {
        val f = fixture(mapOf(
            (S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_WEIGHTS to 2) to floatArrayOf(1f,2f,3f,4f,5f,6f,7f,8f),
            (S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_BIAS to 2) to floatArrayOf(0.25f, -0.25f)
        ))
        val stage = S5MegpoidSequenceOperatorTensorIndex.build(f.tree)
            .buildBankedLinearProjection(2, 2, 2, 2, true, f.decoder)
        assertEquals(listOf(1f,5f,3f,7f,2f,6f,4f,8f), stage.copyPackedWeights().toList())
        assertEquals(listOf(0.25f,-0.25f), stage.copyBias()?.toList())
    }

    @Test
    fun preservesTheNativeAlternateTransitionShapeMetadata() {
        val weightKey = S5MegpoidSequenceOperatorFactory.ALTERNATE_WEIGHTS to 0
        val biasKey = S5MegpoidSequenceOperatorFactory.ALTERNATE_BIAS to 0
        val f = fixture(
            tensors = mapOf(
                weightKey to FloatArray(32 * 32 * 3),
                biasKey to FloatArray(32)
            ),
            metadata = mapOf(
                weightKey to listOf(32, 32, 3, 1),
                biasKey to listOf(32)
            )
        )
        val pair = S5MegpoidSequenceOperatorTensorIndex.build(f.tree)
            .alternateTransitionPairOrNull(0)

        assertNotNull(pair)
        assertEquals(listOf(32, 32, 3, 1), pair?.weights?.descriptorMetadata)
        assertEquals(listOf(32), pair?.bias?.descriptorMetadata)
        assertEquals(32 * 32 * 3, pair?.weights?.elementCount)
    }

    @Test
    fun rejectsSequenceTensorMetadataThatDoesNotMatchItsPayload() {
        val weightKey = S5MegpoidSequenceOperatorFactory.ALTERNATE_WEIGHTS to 0
        val f = fixture(
            tensors = mapOf(weightKey to FloatArray(32 * 32 * 3)),
            metadata = mapOf(weightKey to listOf(32, 32, 2, 1))
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceOperatorTensorIndex.build(f.tree)
        }
    }

    @Test fun keepsAlternateTransitionSeparateFromStandardBankedProjection() {
        val f = fixture(mapOf(
            (S5MegpoidSequenceOperatorFactory.ALTERNATE_WEIGHTS to 0) to floatArrayOf(1f,2f),
            (S5MegpoidSequenceOperatorFactory.ALTERNATE_BIAS to 0) to floatArrayOf(0.1f)
        ))
        val pair = S5MegpoidSequenceOperatorTensorIndex.build(f.tree).alternateTransitionPairOrNull(0)
        assertNotNull(pair)
        assertEquals(S5MegpoidSequenceOperatorFactory.ALTERNATE_WEIGHTS, pair?.weights?.factoryKey?.factory)
        assertEquals(S5MegpoidSequenceOperatorFactory.ALTERNATE_BIAS, pair?.bias?.factoryKey?.factory)
    }

    @Test fun rejectsIncorrectTemporalAndBankedShapes() {
        val f = fixture(mapOf(
            (S5MegpoidSequenceOperatorFactory.TEMPORAL_WEIGHTS to 0) to FloatArray(10),
            (S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_WEIGHTS to 0) to FloatArray(7)
        ))
        val index = S5MegpoidSequenceOperatorTensorIndex.build(f.tree)
        assertThrows(IllegalArgumentException::class.java) {
            index.buildTemporalProjection(0, 2, 2, 3, false, f.decoder)
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.buildBankedLinearProjection(0, 2, 2, 2, false, f.decoder)
        }
    }

    @Test fun rejectsDuplicateKeysAndMissingRequiredWeights() {
        val key = S5MegpoidSequenceOperatorFactoryKeys.lookupKey(S5MegpoidSequenceOperatorFactory.TEMPORAL_WEIGHTS, 0)
        val encoded = "*F32" + Base64.getEncoder().encodeToString(ByteArray(16))
        val dup = S5ModelObjectTree.parseUtf8(
            "{\"left\":{\"" + key + "\":\"" + encoded + "\"},\"right\":{\"" + key + "\":\"" + encoded + "\"}}"
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceOperatorTensorIndex.build(dup)
        }
        val empty = fixture(emptyMap())
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceOperatorTensorIndex.build(empty.tree).buildTemporalProjection(0,2,2,3,false,empty.decoder)
        }
    }
}
