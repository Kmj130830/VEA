package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5ApiTimedPhoneFrameAlignerTest {
    private fun event(start: Double, end: Double, label: String) = VsmPhoneEvent(
        startSeconds = start,
        endSeconds = end,
        phoneLabel = label,
        opaqueSourceU32 = 0
    )

    @Test
    fun mapsPhoneEventsToNativeCeilEndFrameBoundariesAndRelativeTimes() {
        val result = S5ApiTimedPhoneFrameAligner.align(
            events = listOf(
                event(0.003, 0.021, "sil"),
                event(0.025, 0.045, "a")
            ),
            framePeriodSeconds = 0.01f,
            eventControlForCategories2To13 = 1f
        )

        assertEquals(4, result.frameCount)
        assertArrayEquals(intArrayOf(0, 0, 0, 1), result.copyEventIndices(), 0)
        assertEquals(-0.003f, result.relativeStartSecondsByFrame[0], 1.0e-7f)
        assertEquals(0.007f, result.relativeStartSecondsByFrame[1], 1.0e-6f)
        assertEquals(0.017f, result.relativeStartSecondsByFrame[2], 1.0e-6f)
        assertEquals(0.005f, result.relativeStartSecondsByFrame[3], 1.0e-6f)
        assertArrayEquals(
            floatArrayOf(0.018f, 0.018f, 0.018f, 0.020f),
            result.copyEventDurationSeconds(),
            1.0e-6f
        )
        // Native phone categories 0 and 1 are forced to -1; category 2 uses
        // the caller's param_8 value.
        assertArrayEquals(floatArrayOf(-1f, -1f, -1f, 1f), result.copyEventControls(), 0f)
    }

    @Test
    fun mapsCategoryOneToNegativeControlAndKeepsCallerControlForConsonants() {
        val result = S5ApiTimedPhoneFrameAligner.align(
            events = listOf(
                event(0.0, 0.01, "br"),
                event(0.01, 0.03, "k")
            ),
            framePeriodSeconds = 0.01f,
            eventControlForCategories2To13 = 0.75f
        )

        assertEquals(3, result.frameCount)
        assertArrayEquals(intArrayOf(0, 1, 1), result.copyEventIndices(), 0)
        assertArrayEquals(floatArrayOf(-1f, 0.75f, 0.75f), result.copyEventControls(), 0f)
        assertArrayEquals(
            floatArrayOf(0.01f, 0.02f, 0.02f),
            result.copyEventDurationSeconds(),
            1.0e-6f
        )
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0.01f),
            result.copyRelativeStartSeconds(),
            1.0e-6f
        )
    }

    @Test
    fun obtainsFramePeriodFromDescriptorAndDoesNotGuessMissingValue() {
        val descriptor = S5ApiDefaultPhoneFeatureDescriptor(
            categoryWidthAt10 = 14,
            minimumPhoneSecondsAt14 = 0.01f,
            maximumPhoneSecondsAt18 = 1.0f,
            followingContextCountAt1C = 0,
            precedingContextCountAt20 = 0,
            includeLeadingFlagPairAt24 = false,
            layoutModeAt25 = 0,
            framePeriodSecondsAt30 = 0.02f
        )

        val result = S5ApiTimedPhoneFrameAligner.align(
            events = listOf(event(0.0, 0.05, "a")),
            descriptor = descriptor,
            eventControlForCategories2To13 = -1f
        )
        assertEquals(2, result.frameCount)
        assertEquals(0.02f, result.framePeriodSeconds, 0f)

        val missingPeriod = descriptor.copy(framePeriodSecondsAt30 = null)
        assertThrows(IllegalStateException::class.java) {
            S5ApiTimedPhoneFrameAligner.align(
                events = listOf(event(0.0, 0.05, "a")),
                descriptor = missingPeriod,
                eventControlForCategories2To13 = 1f
            )
        }
    }

    @Test
    fun truncatesTotalEndTimeToFrameCountButRoundsEachEventEndUpward() {
        val result = S5ApiTimedPhoneFrameAligner.align(
            events = listOf(
                event(0.0, 0.015, "sil"),
                event(0.015, 0.025, "a")
            ),
            framePeriodSeconds = 0.01f,
            eventControlForCategories2To13 = 1f
        )

        // Total = int(0.025 / 0.01) = 2 frames. Event 0's end is ceil(1.5)=2,
        // so the first event owns both frames and the second has no frame row.
        assertEquals(2, result.frameCount)
        assertArrayEquals(intArrayOf(0, 0), result.copyEventIndices(), 0)
    }

    @Test
    fun returnsZeroFramesWhenLastEventEndsBeforeOneCompleteFramePeriod() {
        val result = S5ApiTimedPhoneFrameAligner.align(
            events = listOf(event(0.001, 0.009, "a")),
            framePeriodSeconds = 0.01f,
            eventControlForCategories2To13 = 1f
        )
        assertEquals(0, result.frameCount)
        assertEquals(0, result.eventIndexByFrame.size)
        assertEquals(0, result.relativeStartSecondsByFrame.size)
    }

    @Test
    fun rejectsEmptyUnorderedInvalidAndOverflowingInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiTimedPhoneFrameAligner.align(emptyList(), 0.01f, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiTimedPhoneFrameAligner.align(
                listOf(event(0.02, 0.03, "a"), event(0.01, 0.04, "i")),
                0.01f,
                1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiTimedPhoneFrameAligner.align(
                listOf(event(-0.01, 0.01, "a")),
                0.01f,
                1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiTimedPhoneFrameAligner.align(
                listOf(event(0.0, 0.01, "a")),
                0f,
                1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiTimedPhoneFrameAligner.align(
                listOf(event(0.0, 1.0e12, "a")),
                1.0e-6f,
                1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiTimedPhoneFrameAligner.align(
                listOf(event(0.0, 0.01, "a")),
                0.01f,
                Float.NaN
            )
        }
    }
}
