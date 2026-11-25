package com.kmj.vea.engine

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidCarrierFusionTensorIndexTest {
    private class FakeDecoder(
        private val values: Map<String, FloatArray>
    ) : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray =
            values[encoded]?.copyOf() ?: throw IllegalArgumentException("Unexpected fixture tensor.")

        override fun decodeInt32Array(encoded: String): IntArray =
            throw IllegalArgumentException("No integer carrier tensors are used.")
    }

    private data class Fixture(
        val tree: S5ModelObjectTree,
        val decoder: S5TypedTensorDecoder
    )

    private fun fixture(
        weightShape: List<Int> = listOf(2),
        weightValues: FloatArray = floatArrayOf(0.25f, -0.5f),
        biasShape: List<Int> = listOf(2, 32, 1, 1),
        biasValues: FloatArray = FloatArray(64) { it / 64f }
    ): Fixture {
        fun encode(shape: List<Int>, values: FloatArray, marker: String): String {
            val bytes = when (marker) {
                "*F32" -> ByteArray(values.size * 4).also { raw ->
                    values.forEachIndexed { index, value ->
                        val bits = value.toRawBits()
                        val offset = index * 4
                        raw[offset] = bits.toByte()
                        raw[offset + 1] = (bits ushr 8).toByte()
                        raw[offset + 2] = (bits ushr 16).toByte()
                        raw[offset + 3] = (bits ushr 24).toByte()
                    }
                }
                else -> error("Unsupported fixture marker.")
            }
            return marker + Base64.getEncoder().encodeToString(bytes)
        }

        val weightEncoded = encode(weightShape, weightValues, "*F32")
        val biasEncoded = encode(biasShape, biasValues, "*F32")
        fun objectJson(shape: List<Int>, encoded: String) =
            "{\"" + S5MegpoidCarrierFusionModelPath.SHAPE_METADATA_KEY + "\":[" +
                shape.joinToString(",") + "],\"" +
                S5MegpoidCarrierFusionModelPath.TYPED_PAYLOAD_KEY + "\":\"" +
                encoded + "\"}"

        val json = "{\"" + S5MegpoidCarrierFusionModelPath.CONFIG_KEY + "\":{" +
            "\"" + S5MegpoidCarrierFusionModelPath.WEIGHTS_KEY + "\":" +
            objectJson(weightShape, weightEncoded) + "," +
            "\"" + S5MegpoidCarrierFusionModelPath.BIAS_KEY + "\":" +
            objectJson(biasShape, biasEncoded) + "}}"

        return Fixture(
            tree = S5ModelObjectTree.parseUtf8(json),
            decoder = FakeDecoder(
                mapOf(weightEncoded to weightValues, biasEncoded to biasValues)
            )
        )
    }

    @Test
    fun readsTheExactInstalledMegpoidCarrierTensorMetadataAndPayloads() {
        val fixture = fixture()
        val index = S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)

        assertEquals(listOf(2), index.weights.descriptorMetadata)
        assertEquals(2, index.weights.elementCount)
        assertEquals("*F32", index.weights.marker)
        assertEquals(listOf(2, 32, 1, 1), index.bias.descriptorMetadata)
        assertEquals(64, index.bias.elementCount)
        assertEquals("*F32", index.bias.marker)

        assertEquals(
            listOf(0.25f, -0.5f),
            index.weights.decodeFloatArray(fixture.decoder).toList()
        )
        assertEquals(64, index.bias.decodeFloatArray(fixture.decoder).size)
    }

    @Test
    fun rejectsTensorMetadataThatDoesNotMatchTheEncodedPayloadCount() {
        val fixture = fixture(
            weightShape = listOf(3),
            weightValues = floatArrayOf(1f, 2f)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)
        }
    }

    @Test
    fun rejectsUnexpectedTensorObjectKeysAndMissingPayloads() {
        val missing = S5ModelObjectTree.parseUtf8(
            "{\"" + S5MegpoidCarrierFusionModelPath.CONFIG_KEY + "\":{" +
                "\"" + S5MegpoidCarrierFusionModelPath.WEIGHTS_KEY + "\":{" +
                "\"" + S5MegpoidCarrierFusionModelPath.SHAPE_METADATA_KEY + "\":[2]}}}"
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionTensorIndex.build(missing)
        }
    }

    @Test
    fun rejectsAmbiguousCarrierTensorKeysInsteadOfChoosingOne() {
        val fixture = fixture()
        val duplicate = S5ModelObjectTree.parseUtf8(
            "{\"a\":{\"" + S5MegpoidCarrierFusionModelPath.WEIGHTS_KEY +
                "\":1},\"b\":{\"" + S5MegpoidCarrierFusionModelPath.WEIGHTS_KEY +
                "\":2}}"
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionTensorIndex.build(duplicate)
        }
        // The ordinary exact fixture remains independently readable.
        assertEquals(2, S5MegpoidCarrierFusionTensorIndex.build(fixture.tree).weights.elementCount)
    }
}
