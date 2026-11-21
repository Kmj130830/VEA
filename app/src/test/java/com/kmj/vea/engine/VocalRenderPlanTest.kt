package com.kmj.vea.engine

import com.kmj.vea.data.PitchPoint
import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaNote
import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocalRenderPlanTest {
    @Test
    fun convertsClipBarAndNoteTicksToSeconds() {
        val project = VeaProject(bpm = 120, numerator = 4, denominator = 4)
        val note = VeaNote(
            startTick = 240,
            lengthTick = 480,
            pitch = 69,
            lyric = "あ",
            phoneme = "a",
            pitchPoints = listOf(PitchPoint(0, 0), PitchPoint(480, 100))
        )
        val track = VeaTrack(
            name = "Vocal 1",
            singerId = "ai_megpoid_v7",
            singerName = "AI Megpoid",
            singerStyleId = "181b8942-ba8d-41a2-bbbb-4a30bd64e470",
            clips = listOf(VeaClip(name = "Verse", startBar = 2, lengthBars = 4, notes = listOf(note)))
        )

        val plan = VocalRenderPlanBuilder.build(project, track)
        val rendered = plan.notes.single()

        assertEquals(2_160, rendered.startTick)
        assertEquals(2.25, rendered.startSeconds, 0.000001)
        assertEquals(0.5, rendered.durationSeconds, 0.000001)
        assertEquals(440.0, rendered.baseFrequencyHz, 0.000001)
        assertEquals(2.75, plan.totalDurationSeconds, 0.000001)
        assertEquals(listOf(0.0, 0.5), rendered.pitchCurve.map { it.offsetSeconds })
        assertEquals("ai_megpoid_v7", plan.singerId)
    }

    @Test
    fun sortsNotesAcrossClipsAndPreservesLanguageAndPhonemes() {
        val project = VeaProject(bpm = 100)
        val late = VeaNote(startTick = 0, lengthTick = 240, pitch = 60, lyric = "ka", phoneme = "k a")
        val early = VeaNote(startTick = 0, lengthTick = 240, pitch = 62, lyric = "き", phoneme = "k i")
        val track = VeaTrack(
            name = "Vocal",
            language = "jpn",
            singerId = "ai_megpoid_v7",
            clips = listOf(
                VeaClip(name = "late", startBar = 3, lengthBars = 1, notes = listOf(late)),
                VeaClip(name = "early", startBar = 1, lengthBars = 1, notes = listOf(early))
            )
        )

        val plan = VocalRenderPlanBuilder.build(project, track)
        assertEquals(listOf(early.id, late.id), plan.notes.map { it.noteId })
        assertEquals("jpn", plan.notes.first().language)
        assertEquals("k i", plan.notes.first().phonemes)
        assertTrue(plan.totalDurationSeconds > plan.notes.last().startSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAudioTrack() {
        VocalRenderPlanBuilder.build(
            VeaProject(),
            VeaTrack(name = "Audio", kind = "AUDIO", singerId = "ai_megpoid_v7")
        )
    }
}
