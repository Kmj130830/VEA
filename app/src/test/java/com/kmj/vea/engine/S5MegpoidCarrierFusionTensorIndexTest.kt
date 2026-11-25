package com.kmj.vea.engine

import java.util.Base64
import org.junit.Assert.assertArrayEquals
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
        weightShape: List<Int> = listOf(2, 32, 1, 1),
        weightValues: FloatArray = FloatArray(64) { it / 64f },
        biasShape: List<Int> = listOf(2),
        biasValues: FloatArray = floatArrayOf(0.25f, -0.5f)
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

        assertEquals(listOf(2, 32, 1, 1), index.weights.descriptorMetadata)
        assertEquals(64, index.weights.elementCount)
        assertEquals("*F32", index.weights.marker)
        assertEquals(listOf(2), index.bias.descriptorMetadata)
        assertEquals(2, index.bias.elementCount)
        assertEquals("*F32", index.bias.marker)

        assertEquals(64, index.weights.decodeFloatArray(fixture.decoder).size)
        assertEquals(
            listOf(0.25f, -0.5f),
            index.bias.decodeFloatArray(fixture.decoder).toList()
        )

        val dimensions = index.resolveDenseDimensions()
        assertEquals(32, dimensions.inputFeatures)
        assertEquals(2, dimensions.outputFeatures)
        val dense = index.buildFallbackDenseProjection(fixture.decoder)
        assertEquals(32, dense.inputFeatures)
        assertEquals(2, dense.outputFeatures)
    }

    @Test
    fun buildsActiveCarrierSourceMatrixAndAffineControlsFromModelTensors() {
        val weights = FloatArray(64) { index ->
            val withinRow = index % 32
            if (index < 32) (withinRow + 1) * 0.125f else -(withinRow + 1) * 0.0625f
        }
        val bias = floatArrayOf(0.75f, -1.25f)
        val fixture = fixture(weightValues = weights, biasValues = bias)
        val index = S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)
        val normalizationScale = FloatArray(32) { 0.5f + it * 0.03125f }
        val normalizationBias = FloatArray(32) { -0.25f + it * 0.015625f }

        val active = S5MegpoidCarrierFusionActiveParameters.build(
            carrierTensors = index,
            normalizationScale = normalizationScale,
            normalizationBias = normalizationBias,
            tensorDecoder = fixture.decoder
        )

        val expectedMatrix = FloatArray(64)
        val expectedControl162 = bias.copyOf()
        val expectedControl166 = FloatArray(2)
        for (row in 0 until 2) {
            var biasAdjustment = 0f
            var scaleSum = 0f
            for (feature in 0 until 32) {
                val weight = weights[row * 32 + feature]
                val scaled = weight * normalizationScale[feature]
                expectedMatrix[row * 32 + feature] = scaled
                biasAdjustment += weight * normalizationBias[feature]
                scaleSum += scaled
            }
            expectedControl162[row] += biasAdjustment
            expectedControl166[row] = scaleSum
        }

        assertEquals(32, active.inputFeatures)
        assertArrayEquals(expectedMatrix, active.copySourceMatrixTwoByInput(), 0f)
        assertArrayEquals(expectedControl162, active.copyControl162(), 0.0001f)
        assertArrayEquals(expectedControl166, active.copyControl166(), 0.0001f)
    }

    @Test
    fun activeModelParametersDriveTheCarrierWorkspaceWithoutRawBufferInjection() {
        val weights = FloatArray(64)
        weights[0] = 1f
        weights[32 + 1] = 1f
        val fixture = fixture(
            weightValues = weights,
            biasValues = floatArrayOf(0f, 0f)
        )
        val index = S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)
        val active = S5MegpoidCarrierFusionActiveParameters.build(
            carrierTensors = index,
            normalizationScale = FloatArray(32) { 1f },
            normalizationBias = FloatArray(32),
            tensorDecoder = fixture.decoder
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(
            S5MegpoidCarrierFusionBufferGeometry(
                inputFeatures = 32,
                carrierPositions = 2,
                spectrumBins = 2,
                historySlotsFromRoot181 = 0,
                historySlotsFromRoot182 = 0
            )
        )
        val featureMajor = FloatArray(32 * 2)
        featureMajor[0] = 1f
        featureMajor[1] = 2f
        featureMajor[2] = 3f
        featureMajor[3] = 4f

        val output = workspace.projectCarrierConditionedFeatures(
            inputFeatureMajor = featureMajor,
            activeParameters = active,
            scale = 2f,
            amplitude = 0.5f,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        assertArrayEquals(floatArrayOf(1f, 3f, 5f, 7f), output, 0f)
    }

    @Test
    fun rejectsActiveCarrierNormalizationVectorsWithTheWrongWidthOrNonFiniteValues() {
        val fixture = fixture()
        val index = S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)

        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionActiveParameters.build(
                carrierTensors = index,
                normalizationScale = FloatArray(31),
                normalizationBias = FloatArray(32),
                tensorDecoder = fixture.decoder
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionActiveParameters.build(
                carrierTensors = index,
                normalizationScale = FloatArray(32) { if (it == 4) Float.NaN else 1f },
                normalizationBias = FloatArray(32),
                tensorDecoder = fixture.decoder
            )
        }
    }

    @Test
    fun rejectsTensorMetadataThatDoesNotMatchTheEncodedPayloadCount() {
        val fixture = fixture(
            weightShape = listOf(2, 32, 1, 1),
            weightValues = FloatArray(63) { it.toFloat() }
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)
        }
    }

    @Test
    fun rejectsEqualElementCountWithTheWrongNativeMatrixAxes() {
        val fixture = fixture(
            weightShape = listOf(2, 16, 2, 1),
            weightValues = FloatArray(64)
        )
        val index = S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)

        assertEquals(64, index.weights.elementCount)
        assertThrows(IllegalArgumentException::class.java) {
            index.resolveDenseDimensions()
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.buildFallbackDenseProjection(fixture.decoder)
        }
    }

    @Test
    fun rejectsBiasThatDoesNotMatchTheTwoOutputDenseDescriptor() {
        val fixture = fixture(
            biasShape = listOf(1, 2),
            biasValues = floatArrayOf(0.25f, -0.5f)
        )
        val index = S5MegpoidCarrierFusionTensorIndex.build(fixture.tree)
        assertThrows(IllegalArgumentException::class.java) {
            index.resolveDenseDimensions()
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
        assertEquals(64, S5MegpoidCarrierFusionTensorIndex.build(fixture.tree).weights.elementCount)
    }
}
