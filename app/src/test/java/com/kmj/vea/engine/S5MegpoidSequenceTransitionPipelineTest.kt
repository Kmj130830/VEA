package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidSequenceTransitionPipelineTest {
    private class DenseFake : S5MegpoidDenseFeatureOperator {
        override val inputFeatures = 1
        override val outputFeatures = 1
        override fun forward(
            input: FloatArray, positions: Int,
            gemm: S5MegpoidGemmKernel, statistics: S5MegpoidStatisticsKernel
        ): FloatArray = FloatArray(input.size) { input[it] + 1.0f }
    }
    private class TemporalFake(
        private val index: Int, private val calls: MutableList<String>
    ) : S5MegpoidTemporalProjectionOperator {
        override val inputFeatures = 1
        override val outputFeatures = 1
        override fun outputPositions(inputPositions: Int): Int = inputPositions
        override fun project(input: FloatArray, inputPositions: Int, gemm: S5MegpoidGemmKernel): FloatArray {
            calls += "T$index"
            return FloatArray(input.size) { input[it] + 1.0f }
        }
    }
    private class BankedFake(
        private val index: Int, override val banks: Int,
        private val calls: MutableList<String>, private val inputHeads: MutableList<Float>
    ) : S5MegpoidBankedProjectionOperator {
        override val inputFeatures = 1
        override val outputFeatures = 1
        override fun project(input: FloatArray, positions: Int): FloatArray {
            calls += "B$index"
            inputHeads += input.first()
            return FloatArray(positions * banks) { i -> input[i / banks] + 1.0f }
        }
    }
    private class ResidualFake(
        override val row: Int, override val column: Int,
        private val calls: MutableList<String>
    ) : S5MegpoidResidualBlockNode {
        override val channels = 1
        private val increment = (row * 2 + column + 1).toFloat()
        override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray {
            calls += "R$row:$column"
            return FloatArray(residualInput.size) { residualInput[it] + increment }
        }
        override fun resetHistory() = Unit
    }

    private val calls = mutableListOf<String>()
    private val bankedInputs = mutableListOf<Float>()
    private fun makePipeline(banks: IntArray = intArrayOf(1, 1, 1, 1)) =
        S5MegpoidSequenceTransitionPipeline(
            denseStage = DenseFake(),
            temporalStages = (0 until 4).map { TemporalFake(it, calls) },
            bankedStages = (0 until 4).map { BankedFake(it, banks[it], calls, bankedInputs) },
            residualExecutor = S5MegpoidResidualBlockExecutor(buildList {
                for (row in 0 until 4) for (column in 0 until 2) {
                    add(ResidualFake(row, column, calls))
                }
            })
        )

    @Test
    fun runsTemporalForwardAndBankedResidualInterleavedInNativeOrder() {
        calls.clear()
        bankedInputs.clear()
        val result = makePipeline().run(FloatArray(7), inputPositions = 7)
        assertEquals(
            listOf(
                "T0", "T1", "T2", "T3",
                "B0", "R3:0", "R3:1",
                "B1", "R2:0", "R2:1",
                "B2", "R1:0", "R1:1",
                "B3", "R0:0", "R0:1"
            ),
            calls
        )
        assertEquals(listOf(5f, 21f, 33f, 41f), bankedInputs)
        assertEquals(45f, result.features.first(), 0.0001f)
        assertEquals(1, result.channels)
        assertEquals(7, result.positions)
    }

    @Test
    fun foldsBankAxisIntoResidualPositionAxis() {
        calls.clear()
        bankedInputs.clear()
        val result = makePipeline(intArrayOf(2, 2, 2, 2)).run(FloatArray(7), inputPositions = 7)
        assertEquals(112, result.positions)
        assertEquals(112, result.features.size)
    }

    @Test
    fun rejectsWrongTransitionCountAndFeatureConnections() {
        val executor = S5MegpoidResidualBlockExecutor(buildList {
            for (row in 0 until 4) for (column in 0 until 2) {
                add(ResidualFake(row, column, mutableListOf()))
            }
        })
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceTransitionPipeline(
                DenseFake(), listOf(TemporalFake(0, mutableListOf())),
                (0 until 4).map { BankedFake(it, 1, mutableListOf(), mutableListOf()) }, executor
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceTransitionPipeline(
                DenseFake(), (0 until 4).map { TemporalFake(it, mutableListOf()) },
                (0 until 4).map {
                    object : S5MegpoidBankedProjectionOperator {
                        override val inputFeatures = 9
                        override val outputFeatures = 1
                        override val banks = 1
                        override fun project(input: FloatArray, positions: Int) = FloatArray(positions)
                    }
                }, executor
            )
        }
    }
}
