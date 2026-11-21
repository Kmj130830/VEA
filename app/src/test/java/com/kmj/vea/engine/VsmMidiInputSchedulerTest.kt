package com.kmj.vea.engine

import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaNote
import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmMidiInputSchedulerTest {
    @Test
    fun convertsOneQuarterNoteAt120BpmTo22050Samples() {
        val track = VeaTrack(
            id = "vocal-1",
            name = "Vocal",
            clips = listOf(
                VeaClip(
                    id = "clip-1",
                    name = "Verse",
                    startBar = 1,
                    lengthBars = 1,
                    notes = listOf(
                        VeaNote(
                            id = "note-1",
                            startTick = 0,
                            lengthTick = 480,
                            pitch = 60,
                            lyric = "あ",
                            phoneme = "a"
                        )
                    )
                )
            )
        )

        val schedule = VsmMidiInputScheduler.schedule(VeaProject(bpm = 120), track)

        assertEquals(22_050L, schedule.totalSampleCount)
        assertEquals(87, schedule.chunks.size)
        assertEquals(256, schedule.chunks.first().renderSampleCount)
        assertEquals(256, schedule.chunks.first().validSampleCount)
        assertEquals(34, schedule.chunks.last().validSampleCount)

        val event = schedule.chunks.first().noteStarts.single()
        assertEquals(0L, event.absoluteStartSample)
        assertEquals(0, event.frameOffsetInChunk)
        assertEquals(22_050L, event.durationSamples)
        assertEquals("あ", event.lyric)
        assertEquals("a", event.phoneme)
    }

    @Test
    fun offsetsClipBarsAndSplitsNoteStartAcross256SampleChunks() {
        val track = VeaTrack(
            id = "vocal-2",
            name = "Vocal",
            clips = listOf(
                VeaClip(
                    id = "clip-2",
                    name = "Second bar",
                    startBar = 2,
                    lengthBars = 1,
                    notes = listOf(
                        VeaNote(
                            id = "note-2",
                            startTick = 0,
                            lengthTick = 480,
                            pitch = 64
                        )
                    )
                )
            )
        )

        val schedule = VsmMidiInputScheduler.schedule(VeaProject(bpm = 120), track)
        val noteChunk = schedule.chunks.first { it.noteStarts.isNotEmpty() }
        val event = noteChunk.noteStarts.single()

        assertEquals(88_200L, event.absoluteStartSample)
        assertEquals(344, noteChunk.index)
        assertEquals(136, event.frameOffsetInChunk)
        assertEquals(110_250L, schedule.totalSampleCount)
        assertEquals(431, schedule.chunks.size)
        assertEquals(170, schedule.chunks.last().validSampleCount)
    }

    @Test
    fun keepsSameSampleEventsInStableSourceOrder() {
        val first = VeaNote(id = "first", startTick = 0, lengthTick = 480, pitch = 60)
        val second = VeaNote(id = "second", startTick = 0, lengthTick = 240, pitch = 62)
        val track = VeaTrack(
            id = "vocal-3",
            name = "Vocal",
            clips = listOf(
                VeaClip(
                    id = "clip-3",
                    name = "Same start",
                    startBar = 1,
                    lengthBars = 1,
                    notes = listOf(first, second)
                )
            )
        )

        val schedule = VsmMidiInputScheduler.schedule(VeaProject(bpm = 120), track)
        val events = schedule.chunks.first().noteStarts

        assertEquals(listOf("first", "second"), events.map { it.noteId })
        assertTrue(events.all { it.frameOffsetInChunk == 0 })
    }

    @Test
    fun emptyTrackProducesNoRenderChunks() {
        val schedule = VsmMidiInputScheduler.schedule(
            VeaProject(),
            VeaTrack(name = "Empty")
        )

        assertEquals(0L, schedule.totalSampleCount)
        assertTrue(schedule.chunks.isEmpty())
    }
}
