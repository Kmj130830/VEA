package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ApiFrameAlignedPhoneFeatureMatrixBuilderTest {
    private fun alignment(indices: IntArray) = S5ApiTimedPhoneFrameAlignment(
        framePeriodSeconds = 0.01f,
        frameCount = indices.size,
        eventIndexByFrame = indices,
        relativeStartSecondsByFrame = FloatArray(indices.size),
        eventDurationSecondsByFrame = FloatArray(indices.size) { 0.02f },
        eventControlByFrame = FloatArray(indices.size) { -1f }
    )

    @Test
    fun gathersEventRowsInFrameOwnershipOrder() {
        val events = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 2,
            rowWidth = 3,
            values = floatArrayOf(
                1f, 10f, 100f,
                2f, 20f, 200f
            )
        )

        val frames = S5ApiFrameAlignedPhoneFeatureMatrixBuilder.build(
            eventFeatures = events,
            frameAlignment = alignment(intArrayOf(1, 0, 1, 1, 0))
        )

        assertEquals(5, frames.frameCount)
        assertEquals(3, frames.featureWidth)
        assertArrayEquals(
            floatArrayOf(
                2f, 20f, 200f,
                1f, 10f, 100f,
                2f, 20f, 200f,
                2f, 20f, 200f,
                1f, 10f, 100f
            ),
            frames.values,
            0f
        )
    }

    @Test
    fun returnsCorrectFrameAndFeatureMajorViews() {
        val events = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 2,
            rowWidth = 3,
            values = floatArrayOf(1f, 10f, 100f, 2f, 20f, 200f)
        )
        val frames = S5ApiFrameAlignedPhoneFeatureMatrixBuilder.build(
            events,
            alignment(intArrayOf(1, 0, 1))
        )

        assertArrayEquals(floatArrayOf(2f, 20f, 200f), frames.frame(0), 0f)
        assertArrayEquals(
            floatArrayOf(2f, 1f, 2f, 20f, 10f, 20f, 200f, 100f, 200f),
            frames.toFeatureMajor(),
            0f
        )
        assertThrows(IllegalArgumentException::class.java) {
            frames.frame(3)
        }
    }

    @Test
    fun preservesFeatureWidthWhenThereAreNoCompleteFrames() {
        val events = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 1,
            rowWidth = 2,
            values = floatArrayOf(0.25f, 1f)
        )
        val frames = S5ApiFrameAlignedPhoneFeatureMatrixBuilder.build(
            events,
            alignment(intArrayOf())
        )

        assertEquals(0, frames.frameCount)
        assertEquals(2, frames.featureWidth)
        assertArrayEquals(FloatArray(0), frames.values, 0f)
        assertArrayEquals(FloatArray(0), frames.toFeatureMajor(), 0f)
    }

    @Test
    fun rejectsFrameOwnersOutsideTheEventFeatureMatrix() {
        val events = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 2,
            rowWidth = 1,
            values = floatArrayOf(0f, 1f)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameAlignedPhoneFeatureMatrixBuilder.build(
                events,
                alignment(intArrayOf(0, 2))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameAlignedPhoneFeatureMatrixBuilder.build(
                events,
                alignment(intArrayOf(-1))
            )
        }
    }

    @Test
    fun rejectsNonEmptyFramesWithoutAnyEventRows() {
        val emptyEvents = S5ApiDefaultPhoneFeatureMatrix(
            rowCount = 0,
            rowWidth = 2,
            values = FloatArray(0)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameAlignedPhoneFeatureMatrixBuilder.build(
                emptyEvents,
                alignment(intArrayOf(0))
            )
        }
    }
}
