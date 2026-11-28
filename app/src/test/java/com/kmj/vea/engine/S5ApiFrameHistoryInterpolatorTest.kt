package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ApiFrameHistoryInterpolatorTest {
    private val history = floatArrayOf(
        1f, 2f,
        10f, 20f,
        100f, 200f
    )

    @Test
    fun directSelectionCopiesOnePhysicalHistoryFrame() {
        assertArrayEquals(
            floatArrayOf(10f, 20f),
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 1
            ),
            0f
        )
    }

    @Test
    fun interpolationUsesFirstFramePlusClampedWeightedSecondFrame() {
        assertArrayEquals(
            floatArrayOf(55f, 110f),
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 1,
                secondFrameIndex = 2,
                interpolationWeight = 0.5f
            ),
            0f
        )
        assertArrayEquals(
            floatArrayOf(10f, 20f),
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 1,
                secondFrameIndex = 2,
                interpolationWeight = -5f
            ),
            0f
        )
        assertArrayEquals(
            floatArrayOf(100f, 200f),
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 1,
                secondFrameIndex = 2,
                interpolationWeight = 5f
            ),
            0f
        )
    }

    @Test
    fun nativeNegativeParam8SelectsDirectAndNonnegativeParam8Blends() {
        assertArrayEquals(
            floatArrayOf(10f, 20f),
            S5ApiFrameHistoryInterpolator.selectFromNativeControls(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 1,
                param8 = -1f,
                param9 = Float.NaN
            ),
            0f
        )
        assertArrayEquals(
            floatArrayOf(55f, 110f),
            S5ApiFrameHistoryInterpolator.selectFromNativeControls(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 1,
                param8 = 2f,
                param9 = 0.5f
            ),
            0f
        )
    }

    @Test
    fun rejectsInvalidHistoryGeometryIndicesAndBlendControls() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = floatArrayOf(1f, 2f, 3f),
                frameWidth = 2,
                firstFrameIndex = 0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 3
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 0,
                secondFrameIndex = 3,
                interpolationWeight = 0.5f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 0,
                secondFrameIndex = 1,
                interpolationWeight = Float.NaN
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryInterpolator.selectFromNativeControls(
                historyFrameMajor = history,
                frameWidth = 2,
                firstFrameIndex = 0,
                param8 = Float.POSITIVE_INFINITY,
                param9 = 0.5f
            )
        }
    }

    @Test
    fun rejectsNonFiniteHistorySamples() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameHistoryInterpolator.select(
                historyFrameMajor = floatArrayOf(1f, Float.NaN),
                frameWidth = 2,
                firstFrameIndex = 0
            )
        }
    }
}
