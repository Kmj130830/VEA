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
    fun `renderer layout separates projection and residual index directions`() {
        val layout = S5RendererStageExecutionLayout.create(stageCount = 4, blocksPerStage = 2)

        assertEquals(listOf(0, 1, 2), layout.forwardStride2DescriptorIndices)
        assertEquals(listOf(0, 1, 2), layout.projectionDescriptorIndices)
        assertEquals(8, layout.loadedResidualDescriptorCount)
        assertEquals(8, layout.residualWorkspaceCount)
        assertEquals(6, layout.activeResidualDescriptorCount)
        assertEquals(
            listOf(4, 5, 2, 3, 0, 1),
            layout.invocations.map { it.flattenedResidualDescriptorIndex(2) }
        )
        assertEquals(
            listOf(
                S5RendererStageInvocation(1, 0, 2, 0),
                S5RendererStageInvocation(1, 0, 2, 1),
                S5RendererStageInvocation(2, 1, 1, 0),
                S5RendererStageInvocation(2, 1, 1, 1),
                S5RendererStageInvocation(3, 2, 0, 0),
                S5RendererStageInvocation(3, 2, 0, 1)
            ),
            layout.invocations
        )
        assertEquals(
            listOf(
                S5Vtb2C5d50BlockCoordinate(3, 0),
                S5Vtb2C5d50BlockCoordinate(3, 1)
            ),
            layout.uninvokedResidualCoordinates
        )
    }

    @Test
    fun `renderer layout uses the actual projection mode byte`() {
        val layout = S5RendererStageExecutionLayout.create(stageCount = 4, blocksPerStage = 2)
        assertEquals(
            S5RendererProjectionOperator.BANKED_LINEAR_C6390,
            layout.projectionOperatorForRawFlag(0)
        )
        assertEquals(
            S5RendererProjectionOperator.UPSAMPLE_2X_C72D0,
            layout.projectionOperatorForRawFlag(1)
        )
        assertEquals(
            S5RendererProjectionOperator.UPSAMPLE_2X_C72D0,
            layout.projectionOperatorForRawFlag(255)
        )
        assertThrows(IllegalArgumentException::class.java) {
            layout.projectionOperatorForRawFlag(256)
        }
    }

    @Test
    fun `renderer layout guards descriptor count overflow and excessive sizes`() {
        assertThrows(ArithmeticException::class.java) {
            S5RendererStageExecutionLayout.create(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5RendererStageExecutionLayout.create(1001, 1000)
        }
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
