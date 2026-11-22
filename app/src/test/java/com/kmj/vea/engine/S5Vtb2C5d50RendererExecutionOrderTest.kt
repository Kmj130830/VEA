package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5Vtb2C5d50RendererExecutionOrderTest {
    @Test
    fun `megpoid c7ad0 invokes stage two then one then zero with two blocks each`() {
        val actual = S5Vtb2C5d50RendererExecutionOrder.activeBlocks(
            stageCount = 4,
            blocksPerStage = 2
        )

        assertEquals(
            listOf(
                S5Vtb2C5d50BlockCoordinate(2, 0),
                S5Vtb2C5d50BlockCoordinate(2, 1),
                S5Vtb2C5d50BlockCoordinate(1, 0),
                S5Vtb2C5d50BlockCoordinate(1, 1),
                S5Vtb2C5d50BlockCoordinate(0, 0),
                S5Vtb2C5d50BlockCoordinate(0, 1)
            ),
            actual
        )
    }

    @Test
    fun `stage three descriptors are retained but not invoked by c7ad0`() {
        assertEquals(
            listOf(
                S5Vtb2C5d50BlockCoordinate(3, 0),
                S5Vtb2C5d50BlockCoordinate(3, 1)
            ),
            S5Vtb2C5d50RendererExecutionOrder.notCalledByC7ad0(4, 2)
        )
    }

    @Test
    fun `single stage configuration does not call residual blocks`() {
        assertEquals(
            emptyList<S5Vtb2C5d50BlockCoordinate>(),
            S5Vtb2C5d50RendererExecutionOrder.activeBlocks(1, 2)
        )
    }

    @Test
    fun `invalid renderer dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2C5d50RendererExecutionOrder.activeBlocks(0, 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2C5d50RendererExecutionOrder.activeBlocks(4, 0)
        }
    }
}
