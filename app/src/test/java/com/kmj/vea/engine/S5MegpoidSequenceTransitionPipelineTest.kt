package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidSequenceTransitionPipelineTest {
    private class DenseFake(
        override val inputFeatures: Int = 1
    ) : S5MegpoidDenseFeatureOperator {
        override val outputFeatures = 1
        override fun forward(
            input: FloatArray,
            positions: Int,
            gemm: S5MegpoidGemmKernel,
            statistics: S5MegpoidStatisticsKernel
        ): FloatArray = FloatArray(positions) { input[it] + 1.0f }
    }

    private class TemporalFake(
        private val index: Int,
        private val calls: MutableList<String>,
        private val kernelSize: Int? = null
    ) : S5MegpoidTemporalProjectionOperator {
        override val inputFeatures = 1
        override val outputFeatures = 1

        override fun outputPositions(inputPositions: Int): Int {
            val kernel = kernelSize ?: return inputPositions
            val padding = kernel / 2
            return (inputPositions + 2 * padding - kernel) / 2 + 1
        }

        override fun project(
            input: FloatArray,
            inputPositions: Int,
            gemm: S5MegpoidGemmKernel
        ): FloatArray {
            calls += "T" + index
            val positions = outputPositions(inputPositions)
            return FloatArray(positions) { outputPosition ->
                val sourcePosition = if (kernelSize == null) outputPosition
                    else (outputPosition * 2).coerceIn(0, inputPositions - 1)
                input[sourcePosition] + 1.0f
            }
        }
    }

    private class BankedFake(
        private val index: Int,
        override val banks: Int,
        private val calls: MutableList<String>,
        private val inputHeads: MutableList<Float>
    ) : S5MegpoidBankedProjectionOperator {
        override val inputFeatures = 1
        override val outputFeatures = 1
        override fun project(input: FloatArray, positions: Int): FloatArray {
            calls += "B" + index
            inputHeads += input.first()
            return FloatArray(positions * banks) { i -> input[i / banks] + 1.0f }
        }
    }

    private class ResidualFake(
        override val row: Int,
        override val column: Int,
        private val calls: MutableList<String>
    ) : S5MegpoidResidualBlockNode {
        override val channels = 1
        private val increment = (row * 2 + column + 1).toFloat()
        override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray {
            calls += "R" + row + ":" + column
            return FloatArray(residualInput.size) { residualInput[it] + increment }
        }
        override fun resetHistory() = Unit
    }

    private val calls = mutableListOf<String>()
    private val bankedInputs = mutableListOf<Float>()

    private fun makePipeline(
        banks: IntArray = intArrayOf(1, 1, 1),
        inputFeatures: Int = 1,
        temporalKernelSize: Int? = null
    ) =
        S5MegpoidSequenceTransitionPipeline(
            denseStage = DenseFake(inputFeatures),
            temporalStages = (0 until 3).map { TemporalFake(it, calls, temporalKernelSize) },
            bankedStages = (0 until 3).map { BankedFake(it, banks[it], calls, bankedInputs) },
            residualExecutor = S5MegpoidResidualBlockExecutor(buildList {
                for (row in 0 until 4) for (column in 0 until 2) {
                    add(ResidualFake(row, column, calls))
                }
            })
        )

    @Test
    fun executesFourSlotsThreeTemporalStagesAndNativeReverseFeedbackOrder() {
        calls.clear()
        bankedInputs.clear()
        val result = makePipeline().run(FloatArray(7), inputPositions = 7)

        assertEquals(
            listOf(
                "T0", "T1", "T2",
                "R3:0", "R3:1",
                "B0", "R2:0", "R2:1",
                "B1", "R1:0", "R1:1",
                "B2", "R0:0", "R0:1"
            ),
            calls
        )
        assertEquals(listOf(19f, 31f, 39f), bankedInputs)
        assertEquals(43f, result.features.first(), 0.0001f)
        assertEquals(1, result.channels)
        assertEquals(7, result.positions)
    }

    @Test
    fun acceptsS5ApiPhoneRowsAndTransposesBeforeDenseProjection() {
        calls.clear()
        bankedInputs.clear()
        val matrix = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 7,
            rowWidth = 2,
            values = floatArrayOf(
                1f, 100f,
                2f, 200f,
                3f, 300f,
                4f, 400f,
                5f, 500f,
                6f, 600f,
                7f, 700f
            )
        )
        val result = makePipeline(inputFeatures = 2).runPhoneFeatureMatrix(matrix)
        assertEquals(44f, result.features.first(), 0.0001f)
        assertEquals(1, result.channels)
        assertEquals(7, result.positions)
    }

    @Test
    fun rejectsPhoneMatricesShorterThanTheResidualHistoryWindow() {
        val matrix = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 6,
            rowWidth = 2,
            values = FloatArray(12)
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            makePipeline(inputFeatures = 2).runPhoneFeatureMatrix(matrix)
        }
    }

    @Test
    fun derivesMinimumInputLengthAfterThreeStrideTwoTemporalStages() {
        val pipeline = makePipeline(temporalKernelSize = 3)
        assertEquals(49, pipeline.minimumInputPositionsForResidualHistory())
    }

    @Test
    fun rejectsInputWhoseTemporalOutputIsShorterThanResidualHistory() {
        val pipeline = makePipeline(temporalKernelSize = 3)
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.run(FloatArray(48), inputPositions = 48)
        }
    }

    @Test
    fun acceptsTheMinimumInputLengthThatLeavesSevenTemporalPositions() {
        val pipeline = makePipeline(temporalKernelSize = 3)
        val output = pipeline.run(FloatArray(49), inputPositions = 49)
        assertEquals(7, output.positions)
    }

    @Test
    fun phoneMatrixValidationUsesTheDerivedTemporalInputLength() {
        val pipeline = makePipeline(inputFeatures = 1, temporalKernelSize = 3)
        val tooShort = S5ApiDefaultPhoneFeatureMatrix(48, 1, FloatArray(48))
        val minimum = S5ApiDefaultPhoneFeatureMatrix(49, 1, FloatArray(49))

        assertThrows(IllegalArgumentException::class.java) {
            pipeline.runPhoneFeatureMatrix(tooShort)
        }
        assertEquals(7, pipeline.runPhoneFeatureMatrix(minimum).positions)
    }

    @Test
    fun foldsBankAxisIntoResidualPositionAxisForEachReverseTransition() {
        calls.clear()
        bankedInputs.clear()
        val result = makePipeline(intArrayOf(2, 2, 2)).run(FloatArray(7), inputPositions = 7)
        assertEquals(56, result.positions)
        assertEquals(56, result.features.size)
    }

    @Test
    fun rejectsIncorrectTemporalCountAndFeatureConnections() {
        val executor = S5MegpoidResidualBlockExecutor(buildList {
            for (row in 0 until 4) for (column in 0 until 2) {
                add(ResidualFake(row, column, mutableListOf()))
            }
        })

        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceTransitionPipeline(
                DenseFake(),
                listOf(TemporalFake(0, mutableListOf())),
                (0 until 3).map { BankedFake(it, 1, mutableListOf(), mutableListOf()) },
                executor
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceTransitionPipeline(
                DenseFake(),
                (0 until 3).map { TemporalFake(it, mutableListOf()) },
                (0 until 3).map {
                    object : S5MegpoidBankedProjectionOperator {
                        override val inputFeatures = 9
                        override val outputFeatures = 1
                        override val banks = 1
                        override fun project(input: FloatArray, positions: Int): FloatArray = FloatArray(positions)
                    }
                },
                executor
            )
        }
    }

    @Test
    fun rejectsTemporalOutputThatDoesNotMatchResidualChannelCount() {
        val executor = S5MegpoidResidualBlockExecutor(buildList {
            for (row in 0 until 4) for (column in 0 until 2) {
                add(ResidualFake(row, column, mutableListOf()))
            }
        })
        val lastTemporal = object : S5MegpoidTemporalProjectionOperator {
            override val inputFeatures = 1
            override val outputFeatures = 2
            override fun outputPositions(inputPositions: Int) = inputPositions
            override fun project(
                input: FloatArray,
                inputPositions: Int,
                gemm: S5MegpoidGemmKernel
            ) = FloatArray(inputPositions * 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSequenceTransitionPipeline(
                DenseFake(),
                listOf(TemporalFake(0, mutableListOf()), TemporalFake(1, mutableListOf()), lastTemporal),
                (0 until 3).map { BankedFake(it, 1, mutableListOf(), mutableListOf()) },
                executor
            )
        }
    }
}
