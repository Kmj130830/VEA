package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidAlternateSequenceTransitionPipelineTest {
    private data class BlockCall(val row: Int, val column: Int)

    private class IdentityResidualNode(
        override val row: Int,
        override val column: Int,
        override val channels: Int,
        private val calls: MutableList<BlockCall>
    ) : S5MegpoidResidualBlockNode {
        override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray {
            calls += BlockCall(row, column)
            return residualInput.copyOf()
        }
        override fun resetHistory() = Unit
    }

    private data class Fixture(
        val pipeline: S5MegpoidAlternateSequenceTransitionPipeline,
        val calls: MutableList<BlockCall>
    )

    private fun fixture(): Fixture {
        val channels = 32
        val dense = S5MegpoidDenseNormalizationStage(
            projection = S5MegpoidDenseProjection(
                inputFeatures = channels,
                outputFeatures = channels,
                weights = FloatArray(channels * channels),
                bias = null
            ),
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = channels,
                scale = FloatArray(channels) { 1f },
                bias = FloatArray(channels),
                epsilon = 1.0e-6f
            )
        )
        val temporal = List(3) {
            S5MegpoidTemporalStridedConvolution(
                inputFeatures = channels,
                outputFeatures = channels,
                kernelSize = 3,
                weights = FloatArray(channels * channels * 3),
                bias = FloatArray(channels)
            )
        }
        val alternate = List(3) {
            S5MegpoidAlternateTemporalProjection(
                inputFeatures = channels,
                outputFeatures = channels,
                weights = FloatArray(channels * channels * 3),
                bias = FloatArray(channels)
            )
        }
        val calls = mutableListOf<BlockCall>()
        val nodes = buildList {
            for (row in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS) {
                for (column in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS) {
                    add(IdentityResidualNode(row, column, channels, calls))
                }
            }
        }
        val residual = S5MegpoidResidualBlockExecutor(nodes)

        return Fixture(
            S5MegpoidAlternateSequenceTransitionPipeline.fromModelStages(
                denseStage = dense,
                temporalStages = temporal,
                alternateStages = alternate,
                residualExecutor = residual
            ),
            calls
        )
    }

    @Test
    fun computesMinimumInputLengthForThreeTemporalStagesAndSevenSlotResidualHistory() {
        val fixture = fixture()
        assertEquals(49, fixture.pipeline.minimumInputPositionsForResidualHistory())
    }

    @Test
    fun executesResidualRowsInNativeReverseOrderAndDoublesAtEveryAlternateStage() {
        val fixture = fixture()
        val inputPositions = fixture.pipeline.minimumInputPositionsForResidualHistory()
        val output = fixture.pipeline.run(
            input = FloatArray(fixture.pipeline.inputFeatures * inputPositions),
            inputPositions = inputPositions,
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        assertEquals(
            listOf(
                BlockCall(3, 0), BlockCall(3, 1),
                BlockCall(2, 0), BlockCall(2, 1),
                BlockCall(1, 0), BlockCall(1, 1),
                BlockCall(0, 0), BlockCall(0, 1)
            ),
            fixture.calls
        )
        assertEquals(32, output.channels)
        assertEquals(56, output.positions)
        assertEquals(32 * 56, output.features.size)
    }

    @Test
    fun rejectsASequenceThatCannotReachTheResidualHistoryWindow() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.pipeline.run(
                input = FloatArray(fixture.pipeline.inputFeatures * 48),
                inputPositions = 48,
                gemm = ReferenceS5MegpoidGemmKernel,
                statistics = ReferenceS5MegpoidStatisticsKernel
            )
        }
    }
}
