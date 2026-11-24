package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmNotePhoneAlignerTest {
    @Test
    fun dividesNoteSamplesExactlyAndAssignsRemainderToEarlierPhones() {
        val result = VsmNotePhoneAligner.alignExplicitPhonemes(
            schedule(
                20,
                note(10, 10, "k a i")
            )
        )

        assertEquals(listOf("sil", "k", "a", "i"), result.events.map { it.phoneLabel })
        assertEquals(
            listOf(0L to 10L, 10L to 14L, 14L to 17L, 17L to 20L),
            result.events.map { it.startSeconds.toSample() to it.endSeconds.toSample() }
        )
        assertEquals(1, result.alignedNoteCount)
        assertEquals(3, result.explicitPhoneCount)
        assertEquals(1, result.silenceEventCount)
    }

    @Test
    fun preservesLeadingInternalAndTrailingSilenceGaps() {
        val result = VsmNotePhoneAligner.alignExplicitPhonemes(
            schedule(
                400,
                note(100, 100, "a"),
                note(300, 50, "i")
            )
        )

        assertEquals(
            listOf("sil", "a", "sil", "i", "sil"),
            result.events.map { it.phoneLabel }
        )
        assertEquals(
            listOf(
                0L to 100L,
                100L to 200L,
                200L to 300L,
                300L to 350L,
                350L to 400L
            ),
            result.events.map { it.startSeconds.toSample() to it.endSeconds.toSample() }
        )
        assertEquals(3, result.silenceEventCount)
    }

    @Test
    fun ignoresOnlyStandaloneSyllableAndWordSeparators() {
        val result = VsmNotePhoneAligner.alignExplicitPhonemes(
            schedule(12, note(0, 12, "k a . i | o"))
        )

        assertEquals(listOf("k", "a", "i", "o"), result.events.map { it.phoneLabel })
        assertEquals(4, result.explicitPhoneCount)
    }

    @Test
    fun keepsCompositePhoneLabelsContainingVerticalBarsIntact() {
        val result = VsmNotePhoneAligner.alignExplicitPhonemes(
            schedule(4, note(0, 4, "y|CH"))
        )

        assertEquals(listOf("y|CH"), result.events.map { it.phoneLabel })
        assertTrue(result.fallbackPhoneLabels.isEmpty())
    }

    @Test
    fun reportsLabelsThatFallBackToTheRecoveredCategory() {
        val result = VsmNotePhoneAligner.alignExplicitPhonemes(
            schedule(2, note(0, 2, "S a"))
        )

        assertTrue(result.fallbackPhoneLabels.contains("S"))
        assertFalse(result.fallbackPhoneLabels.contains("a"))
    }

    @Test
    fun rejectsNotesWithoutExplicitPhonemesInsteadOfGuessingG2p() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            VsmNotePhoneAligner.alignExplicitPhonemes(
                schedule(10, note(0, 10, ""))
            )
        }
        assertTrue(error.message!!.contains("Automatic G2P is not connected"))
    }

    @Test
    fun rejectsOverlappingScheduledNotes() {
        assertThrows(IllegalArgumentException::class.java) {
            VsmNotePhoneAligner.alignExplicitPhonemes(
                schedule(
                    30,
                    note(0, 20, "a"),
                    note(10, 20, "i")
                )
            )
        }
    }

    @Test
    fun rejectsNoteWithMorePhonesThanAvailableSamples() {
        assertThrows(IllegalArgumentException::class.java) {
            VsmNotePhoneAligner.alignExplicitPhonemes(
                schedule(totalSamples = 2, note(0, 2, "k a i"))
            )
        }
    }

    private fun schedule(
        totalSamples: Long,
        vararg notes: VsmScheduledNote
    ): VsmRenderSchedule {
        if (totalSamples == 0L) {
            return VsmRenderSchedule(
                sampleRate = SAMPLE_RATE,
                chunkSamples = CHUNK_SAMPLES,
                totalSampleCount = 0L,
                chunks = emptyList()
            )
        }
        val chunkCount = ((totalSamples - 1L) / CHUNK_SAMPLES + 1L).toInt()
        val chunks = List(chunkCount) { index ->
            val startSample = index.toLong() * CHUNK_SAMPLES
            val validSamples = minOf(
                CHUNK_SAMPLES.toLong(),
                totalSamples - startSample
            ).toInt()
            VsmInputChunk(
                index = index,
                startSample = startSample,
                renderSampleCount = CHUNK_SAMPLES,
                validSampleCount = validSamples,
                noteStarts = notes.filter {
                    it.absoluteStartSample / CHUNK_SAMPLES == index.toLong()
                }
            )
        }
        return VsmRenderSchedule(
            sampleRate = SAMPLE_RATE,
            chunkSamples = CHUNK_SAMPLES,
            totalSampleCount = totalSamples,
            chunks = chunks
        )
    }

    private fun note(
        startSample: Long,
        durationSamples: Long,
        phoneme: String
    ) = VsmScheduledNote(
        trackId = "track",
        clipId = "clip",
        noteId = "note-$startSample",
        absoluteStartTick = startSample,
        durationTicks = durationSamples,
        absoluteStartSample = startSample,
        frameOffsetInChunk = (startSample % CHUNK_SAMPLES).toInt(),
        pitch = 60,
        durationSamples = durationSamples,
        sampleRate = SAMPLE_RATE,
        lyric = "test",
        phoneme = phoneme
    )

    private fun Double.toSample(): Long =
        kotlin.math.round(this * SAMPLE_RATE).toLong()

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val CHUNK_SAMPLES = 256
    }
}
