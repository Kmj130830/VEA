package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidResidualBlockScheduleTest {
    @Test
    fun buildsSequenceMajorEightBlockOrderWithAlternatingWorkspaces() {
        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7(sequenceCount = 3)

        assertEquals(2, schedule.sequenceIterations)
        assertEquals(16, schedule.steps.size)
        assertEquals(
            (0..3).flatMap { row -> (0..1).map { column -> row to column } },
            schedule.blockPositions.map { it.row to it.column }
        )

        val firstSequence = schedule.steps.take(8)
        assertEquals(List(8) { 0 }, firstSequence.map { it.sequenceIndex })
        assertEquals((0..7).toList(), firstSequence.map { it.blockOrdinal })
        assertEquals(
            listOf(
                S5MegpoidWorkspaceSlot.FIRST to S5MegpoidWorkspaceSlot.SECOND,
                S5MegpoidWorkspaceSlot.SECOND to S5MegpoidWorkspaceSlot.FIRST
            ).let { pair -> List(8) { pair[it % 2] } },
            firstSequence.map { it.inputWorkspace to it.outputWorkspace }
        )

        val secondSequence = schedule.steps.drop(8)
        assertEquals(List(8) { 1 }, secondSequence.map { it.sequenceIndex })
        assertEquals(firstSequence.last().outputWorkspace, secondSequence.first().inputWorkspace)
    }

    @Test
    fun sequenceCountOneProducesNoResidualInvocations() {
        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7(sequenceCount = 1)

        assertEquals(0, schedule.sequenceIterations)
        assertEquals(0, schedule.steps.size)
    }

    @Test
    fun supportsTheOtherInitialWorkspaceWithoutChangingBlockOrder() {
        val schedule = S5MegpoidResidualBlockSchedule.megpoidV7(
            sequenceCount = 2,
            firstInputWorkspace = S5MegpoidWorkspaceSlot.SECOND
        )

        assertEquals(S5MegpoidWorkspaceSlot.SECOND, schedule.steps.first().inputWorkspace)
        assertEquals(S5MegpoidWorkspaceSlot.FIRST, schedule.steps.first().outputWorkspace)
        assertEquals((0..7).toList(), schedule.steps.map { it.blockOrdinal })
    }

    @Test
    fun rejectsInvalidSequenceCountsAndDuplicateBlockPositions() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockSchedule.megpoidV7(sequenceCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidResidualBlockSchedule.build(
                sequenceCount = 2,
                blockPositions = listOf(
                    S5MegpoidBlockPosition(0, 0),
                    S5MegpoidBlockPosition(0, 0)
                )
            )
        }
    }

    @Test
    fun eachStepUsesDifferentWorkspaceSlots() {
        val steps = S5MegpoidResidualBlockSchedule.megpoidV7(sequenceCount = 4).steps
        steps.forEach { step ->
            assertNotEquals(step.inputWorkspace, step.outputWorkspace)
        }
    }
}
