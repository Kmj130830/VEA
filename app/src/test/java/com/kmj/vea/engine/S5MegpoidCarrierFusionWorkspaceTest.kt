package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class S5MegpoidCarrierFusionWorkspaceTest {
    private fun projection(): S5MegpoidDenseProjection {
        val weights = FloatArray(2 * 32)
        weights[0] = 1f
        weights[32 + 1] = 1f
        return S5MegpoidDenseProjection(
            inputFeatures = 32,
            outputFeatures = 2,
            weights = weights,
            bias = floatArrayOf(0.5f, -0.5f)
        )
    }

    @Test
    fun derivesNativeCarrierDenseAndHistoryBufferLengths() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 7,
            spectrumBins = 513,
            historySlotsFromRoot181 = 2,
            historySlotsFromRoot182 = 1
        )

        assertEquals(4, geometry.historySlotCount)
        assertEquals(224, geometry.denseInputElementCount)
        assertEquals(14, geometry.denseOutputElementCount)
        assertEquals(64, geometry.carrierSourceVectorElementCount)
        assertEquals(1026, geometry.currentCarrierFrameElementCount)
        assertEquals(1026, geometry.carrierHistoryFrameStride)
        assertEquals(4104, geometry.carrierRingElementCount)
        assertEquals(2, geometry.carrierAccumulationVectorElementCount)
    }

    @Test
    fun projectsNormalizedFeatureMajorCarrierInputToTwoRows() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 2,
            spectrumBins = 4,
            historySlotsFromRoot181 = 1,
            historySlotsFromRoot182 = 1
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(geometry, projection())
        val input = FloatArray(32 * 2)
        input[0] = 1f
        input[1] = 2f
        input[2] = 3f
        input[3] = 4f

        val output = workspace.projectNormalizedCarrierFeatures(
            inputFeatureMajor = input,
            gemm = ReferenceS5MegpoidGemmKernel
        )

        assertArrayEquals(floatArrayOf(1.5f, 2.5f, 2.5f, 3.5f), output, 0.00001f)
        assertArrayEquals(input, workspace.denseInputFeatureMajor, 0f)
        assertArrayEquals(output, workspace.denseOutputFeatureMajor, 0f)
    }

    @Test
    fun storesCarrierSpectrumFramesInNativeCircularRingOrder() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 1,
            spectrumBins = 2,
            historySlotsFromRoot181 = 1,
            historySlotsFromRoot182 = 1
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(geometry, projection())

        assertEquals(0, workspace.appendCarrierFramePlanes(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)))
        assertEquals(1, workspace.appendCarrierFramePlanes(floatArrayOf(5f, 6f), floatArrayOf(7f, 8f)))
        assertEquals(2, workspace.appendCarrierFramePlanes(floatArrayOf(9f, 10f), floatArrayOf(11f, 12f)))
        assertEquals(0, workspace.appendCarrierFramePlanes(floatArrayOf(13f, 14f), floatArrayOf(15f, 16f)))

        assertEquals(1, workspace.nextHistoryWriteIndex)
        assertEquals(3L, workspace.historyFramesWritten)
        assertArrayEquals(
            floatArrayOf(
                5f, 6f, 7f, 8f,
                9f, 10f, 11f, 12f,
                13f, 14f, 15f, 16f
            ),
            workspace.copyHistoryOldestToNewest(),
            0f
        )
        assertArrayEquals(
            floatArrayOf(13f, 14f, 15f, 16f),
            workspace.copyMostRecentCarrierFrame(),
            0f
        )
    }

    @Test
    fun writesAndReadsCarrierFramesAsTwoPlanarArraysNotInterleavedPairs() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 2,
            spectrumBins = 2,
            historySlotsFromRoot181 = 0,
            historySlotsFromRoot182 = 0
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(geometry, projection())

        workspace.appendCarrierFramePlanes(
            firstPlane = floatArrayOf(10f, 20f),
            secondPlane = floatArrayOf(30f, 40f)
        )

        // H=1 means the write cursor wraps to the same/oldest ring slot.
        assertEquals(0, workspace.nextHistoryWriteIndex)
        assertArrayEquals(
            floatArrayOf(10f, 20f, 30f, 40f),
            workspace.copyMostRecentCarrierFrame(),
            0f
        )
        assertArrayEquals(
            floatArrayOf(10f, 20f, 30f, 40f),
            workspace.copyHistoryOldestToNewest(),
            0f
        )
    }

    @Test
    fun mixesDenseTwoPlaneOutputWithTheDelayedCarrierSlotLikeNativeForwardPath() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 2,
            spectrumBins = 2,
            historySlotsFromRoot181 = 0,
            historySlotsFromRoot182 = 0
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(geometry, projection())
        workspace.denseOutputFeatureMajor[0] = 11f
        workspace.denseOutputFeatureMajor[1] = 13f
        workspace.denseOutputFeatureMajor[2] = 17f
        workspace.denseOutputFeatureMajor[3] = 19f
        workspace.appendCarrierFramePlanes(
            firstPlane = floatArrayOf(2f, 3f),
            secondPlane = floatArrayOf(5f, 7f)
        )

        val output = FloatArray(4)
        val returned = workspace.accumulateDelayedCarrierFrameInto(output)

        assertSame(output, returned)
        assertArrayEquals(floatArrayOf(-21f, 107f, -34f, 172f), output, 0.00001f)
    }

    @Test
    fun nativeDelayedSlotSelectsTheOldestHistoryEntryAtTheWriteCursor() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 1,
            spectrumBins = 1,
            historySlotsFromRoot181 = 1,
            historySlotsFromRoot182 = 1
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(geometry, projection())
        workspace.denseOutputFeatureMajor[0] = 1f
        workspace.denseOutputFeatureMajor[1] = 0f

        workspace.appendCarrierFramePlanes(floatArrayOf(1f), floatArrayOf(10f))
        workspace.appendCarrierFramePlanes(floatArrayOf(2f), floatArrayOf(20f))
        workspace.appendCarrierFramePlanes(floatArrayOf(3f), floatArrayOf(30f))

        // After three writes H=3 and cursor wraps to slot zero. Native
        // index (cursor - lag182 - lag181 - 1) modulo H is also slot zero.
        assertEquals(0, workspace.nextHistoryWriteIndex)
        val output = FloatArray(2)
        workspace.accumulateDelayedCarrierFrameInto(output)
        assertArrayEquals(floatArrayOf(-10f, 1f), output, 0.00001f)
    }

    @Test
    fun unwrittenHistorySlotsRemainZeroAndResetClearsTheRing() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 1,
            spectrumBins = 2,
            historySlotsFromRoot181 = 1,
            historySlotsFromRoot182 = 1
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(geometry, projection())

        workspace.appendCarrierFramePlanes(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))
        assertArrayEquals(
            floatArrayOf(
                0f, 0f, 0f, 0f,
                1f, 2f, 3f, 4f,
                0f, 0f, 0f, 0f
            ),
            workspace.copyHistoryOldestToNewest(),
            0f
        )

        workspace.resetHistory()
        assertEquals(0, workspace.nextHistoryWriteIndex)
        assertEquals(0L, workspace.historyFramesWritten)
        assertArrayEquals(FloatArray(12), workspace.copyHistoryOldestToNewest(), 0f)
        assertArrayEquals(FloatArray(4), workspace.copyMostRecentCarrierFrame(), 0f)
    }

    @Test
    fun rejectsWrongDenseAndSpectrumShapes() {
        val geometry = S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 32,
            carrierPositions = 2,
            spectrumBins = 2,
            historySlotsFromRoot181 = 1,
            historySlotsFromRoot182 = 0
        )
        val workspace = S5MegpoidCarrierFusionWorkspace(geometry, projection())

        assertThrows(IllegalArgumentException::class.java) {
            workspace.projectNormalizedCarrierFeatures(FloatArray(31 * 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.appendCarrierFramePlanes(FloatArray(1), FloatArray(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.appendCarrierFramePlanes(floatArrayOf(0f, Float.NaN), floatArrayOf(0f, 0f))
        }
    }

    @Test
    fun rejectsInvalidHistoryDimensionsAndDenseProjectionMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionBufferGeometry(
                inputFeatures = 32,
                carrierPositions = 1,
                spectrumBins = 0,
                historySlotsFromRoot181 = 0,
                historySlotsFromRoot182 = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionBufferGeometry(
                inputFeatures = 32,
                carrierPositions = 1,
                spectrumBins = 1,
                historySlotsFromRoot181 = -1,
                historySlotsFromRoot182 = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionWorkspace(
                geometry = S5MegpoidCarrierFusionBufferGeometry(32, 1, 1, 0, 0),
                carrierDenseProjection = S5MegpoidDenseProjection(
                    inputFeatures = 16,
                    outputFeatures = 2,
                    weights = FloatArray(32),
                    bias = FloatArray(2)
                )
            )
        }
    }
}
