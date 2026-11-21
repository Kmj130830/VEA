package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidResidualBlockScheduleTest {
    private val rowMajorPositions = (0 until 4).flatMap { row ->
        (0 until 2).map { column -> S5MegpoidBlockPosition(row, column) }
    }

    @Test
    fun schedulesRowsInReverseOrderAndColumnsInAscendingOrder() {
        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7()

        assertEquals(4, schedule.sequenceIterations)
        assertEquals(8, schedule.steps.size)
        assertEquals(rowMajorPositions, schedule.blockPositions)
        assertEquals(
            listOf(3, 3, 2, 2, 1, 1, 0, 0),
            schedule.steps.map { it.sequenceIndex }
        )
        assertEquals(
            listOf(0, 1, 0, 1, 0, 1, 0, 1),
            schedule.steps.map { it.block.column }
        )
    }

    @Test
    fun eachSequenceRowGetsAnIndependentWorkspacePair() {
        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7()
        for (row in 0 until 4) {
            val rowSteps = schedule.steps.filter { it.sequenceIndex == row }
            assertEquals(2, rowSteps.size)
            assertEquals(S5MegpoidWorkspaceSlot.FIRST, rowSteps[0].inputWorkspace)
            assertEquals(S5MegpoidWorkspaceSlot.SECOND, rowSteps[0].outputWorkspace)
            assertEquals(S5MegpoidWorkspaceSlot.SECOND, rowSteps[1].inputWorkspace)
            assertEquals(S5MegpoidWorkspaceSlot.FIRST, rowSteps[1].outputWorkspace)
        }
    }

    @Test
    fun supportsSecondWorkspaceAsTheInitialInputForEveryRow() {
        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7(
            firstInputWorkspace = S5MegpoidWorkspaceSlot.SECOND
        )
        schedule.steps.groupBy { it.sequenceIndex }.values.forEach { rowSteps ->
            assertEquals(S5MegpoidWorkspaceSlot.SECOND, rowSteps.first().inputWorkspace)
            assertEquals(S5MegpoidWorkspaceSlot.FIRST, rowSteps.first().outputWorkspace)
            assertEquals(S5MegpoidWorkspaceSlot.FIRST, rowSteps.last().outputWorkspace)
        }
    }

    @Test
    fun genericBuilderRejectsMissingRowsOrNonContiguousColumns() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockSchedule.build(
                sequenceCount = 4,
                blockPositions = rowMajorPositions.dropLast(1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockSchedule.build(
                sequenceCount = 4,
                blockPositions = rowMajorPositions.map {
                    if (it.column == 1) S5MegpoidBlockPosition(it.row, 2) else it
                }
            )
        }
    }

    @Test
    fun rejectsDuplicatePositionsAndWrongV7SequenceCount() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockSchedule.build(
                sequenceCount = 4,
                blockPositions = rowMajorPositions + rowMajorPositions.first()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockSchedule.megpoidV7(sequenceCount = 3)
        }
    }

    @Test
    fun noStepWritesToItsCurrentInputWorkspace() {
        S5MegpoidResidualBlockSchedule.megpoidV7().steps.forEach { step ->
            assertNotEquals(step.inputWorkspace, step.outputWorkspace)
        }
    }
}
