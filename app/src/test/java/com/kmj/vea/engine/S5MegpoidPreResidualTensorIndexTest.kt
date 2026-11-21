package com.kmj.vea.engine

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidPreResidualTensorIndexTest {
    private class FakeTensorDecoder(
        private val values: Map<String, FloatArray>
    ) : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray =
            values[encoded]?.copyOf() ?: throw IllegalArgumentException("Unexpected fake tensor.")
        override fun decodeInt32Array(encoded: String): IntArray =
            throw IllegalArgumentException("No integer tensors are used in this test.")
    }

    private data class Fixture(val tree: S5ModelObjectTree, val decoder: S5TypedTensorDecoder)

    private fun fixture(
        dense: FloatArray = floatArrayOf(1f, 0f, 0f, 1f),
        scale: FloatArray = floatArrayOf(2f, 1f),
        bias: FloatArray = floatArrayOf(0f, 5f),
        includeAllKeys: Boolean = true,
        duplicateDenseKey: Boolean = false
    ): Fixture {
        fun encoded(values: FloatArray, salt: Int): String {
            val bytes = ByteArray(values.size * 4)
            bytes[0] = salt.toByte()
            return "*F32" + Base64.getEncoder().encodeToString(bytes)
        }
        val denseEncoded = encoded(dense, 1)
        val scaleEncoded = encoded(scale, 2)
        val biasEncoded = encoded(bias, 3)
        val fields = mutableListOf<String>()
        fields += "\"\${S5MegpoidPreResidualTensorKeys.DENSE_PROJECTION_WEIGHTS}\":\"\${denseEncoded}\""
        if (includeAllKeys) {
            fields += "\"\${S5MegpoidPreResidualTensorKeys.NORMALIZATION_SCALE}\":\"\${scaleEncoded}\""
            fields += "\"\${S5MegpoidPreResidualTensorKeys.NORMALIZATION_BIAS}\":\"\${biasEncoded}\""
        }
        val nested = fields.joinToString(",")
        val duplicate = if (duplicateDenseKey) {
            ",\"sibling\":{\"\${S5MegpoidPreResidualTensorKeys.DENSE_PROJECTION_WEIGHTS}\":\"\${denseEncoded}\"}"
        } else ""
        return Fixture(
            S5ModelObjectTree.parseUtf8("{\"selected\":{\${nested}}\${duplicate}}"),
            FakeTensorDecoder(mapOf(
                denseEncoded to dense,
                scaleEncoded to scale,
                biasEncoded to bias
            ))
        )
    }

    @Test
    fun resolvesExactDenseAndNormalizationKeysRecursively() {
        val fixture = fixture()
        val index = S5MegpoidPreResidualTensorIndex.build(fixture.tree)
        assertEquals(
            "$/selected/\${S5MegpoidPreResidualTensorKeys.DENSE_PROJECTION_WEIGHTS}",
            index.denseProjectionWeights.objectPath
        )
        assertEquals(4, index.denseProjectionWeights.elementCount)
        assertEquals(2, index.normalizationScale.elementCount)
        assertEquals(2, index.normalizationBias.elementCount)
        assertEquals("*F32", index.denseProjectionWeights.marker)
    }

    @Test
    fun buildsDenseProjectionWithoutBiasAndUsesScaleThenBiasVectors() {
        val fixture = fixture()
        val stage = S5MegpoidPreResidualTensorIndex.build(fixture.tree)
            .buildDenseNormalizationStage(2, 2, fixture.decoder)
        val output = stage.forward(
            input = floatArrayOf(1f, 2f, 3f, 4f),
            positions = 2,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )
        val inverseStd = 1f / kotlin.math.sqrt(1.250001f)
        val expected = floatArrayOf(
            2f * inverseStd * (1f - 2.5f),
            2f * inverseStd * (2f - 2.5f),
            inverseStd * (3f - 2.5f) + 5f,
            inverseStd * (4f - 2.5f) + 5f
        )
        assertEquals(expected.size, output.size)
        expected.indices.forEach { assertEquals(expected[it], output[it], 0.00001f) }
    }

    @Test
    fun rejectsMissingOrAmbiguousModelKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidPreResidualTensorIndex.build(fixture(includeAllKeys = false).tree)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidPreResidualTensorIndex.build(fixture(duplicateDenseKey = true).tree)
        }
    }

    @Test
    fun validatesResolvedDenseAndNormalizationShapes() {
        val fixture = fixture()
        val index = S5MegpoidPreResidualTensorIndex.build(fixture.tree)
        assertThrows(IllegalArgumentException::class.java) {
            index.buildDenseNormalizationStage(3, 2, fixture.decoder)
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.buildDenseNormalizationStage(2, 3, fixture.decoder)
        }
    }
}
