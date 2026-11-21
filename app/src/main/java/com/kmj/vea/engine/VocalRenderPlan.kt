package com.kmj.vea.engine

import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaNote
import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack
import kotlin.math.pow

/**
 * Time/pitch-normalized input to the acoustic renderer.
 *
 * All times are seconds from project start. Pitch points are relative to the
 * note onset and expressed in cents relative to the MIDI note's equal-tempered
 * frequency. No audio is synthesized by this planner.
 */
data class VocalRenderNote(
    val noteId: String,
    val startTick: Int,
    val durationTick: Int,
    val startSeconds: Double,
    val durationSeconds: Double,
    val midiPitch: Int,
    val baseFrequencyHz: Double,
    val lyric: String,
    val phonemes: String,
    val language: String,
    val pitchCurve: List<VocalRenderPitchPoint>
)

data class VocalRenderPitchPoint(
    val offsetSeconds: Double,
    val cents: Int
)

data class VocalRenderPlan(
    val projectId: String,
    val trackId: String,
    val singerId: String,
    val singerStyleId: String,
    val sampleRate: Int,
    val bpm: Int,
    val ticksPerQuarter: Int,
    val totalDurationSeconds: Double,
    val notes: List<VocalRenderNote>
) {
    val isEmpty: Boolean get() = notes.isEmpty()
}

object VocalRenderPlanBuilder {
    const val DEFAULT_SAMPLE_RATE = 44_100
    const val TICKS_PER_QUARTER = 480

    fun build(
        project: VeaProject,
        track: VeaTrack,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): VocalRenderPlan {
        require(sampleRate in 8_000..192_000) { "Unsupported synthesis sample rate." }
        require(project.bpm in 20..400) { "Project tempo must be between 20 and 400 BPM." }
        require(project.numerator in 1..32 && project.denominator in 1..32) {
            "Invalid project time signature."
        }
        require(track.kind != "AUDIO") { "Audio tracks cannot be sent to the vocal renderer." }
        require(track.singerId.isNotBlank()) { "Select a singer before rendering." }

        val ticksPerBar = (
            TICKS_PER_QUARTER.toLong() * project.numerator * 4L / project.denominator
        ).coerceAtLeast(1L)
        val secondsPerTick = 60.0 / (project.bpm * TICKS_PER_QUARTER)
        val notes = track.clips.flatMap { clip ->
            val clipStartTick = (clip.startBar.coerceAtLeast(1) - 1L) * ticksPerBar
            clip.notes.map { note -> toRenderNote(note, clipStartTick, track.language, secondsPerTick) }
        }.sortedWith(compareBy<VocalRenderNote> { it.startTick }.thenBy { it.noteId })

        require(notes.all { it.startTick >= 0 && it.durationTick > 0 }) {
            "A note has an invalid start or duration."
        }

        val endTick = notes.maxOfOrNull { it.startTick.toLong() + it.durationTick } ?: 0L
        val totalDuration = endTick * secondsPerTick
        require(totalDuration.isFinite() && totalDuration <= 60.0 * 60.0 * 4.0) {
            "A single render is limited to four hours."
        }

        return VocalRenderPlan(
            projectId = project.id,
            trackId = track.id,
            singerId = track.singerId,
            singerStyleId = track.singerStyleId,
            sampleRate = sampleRate,
            bpm = project.bpm,
            ticksPerQuarter = TICKS_PER_QUARTER,
            totalDurationSeconds = totalDuration,
            notes = notes
        )
    }

    private fun toRenderNote(
        note: VeaNote,
        clipStartTick: Long,
        language: String,
        secondsPerTick: Double
    ): VocalRenderNote {
        require(note.lengthTick > 0) { "Note ${note.id} has no duration." }
        val localStart = note.startTick.coerceAtLeast(0)
        val absoluteStart = clipStartTick + localStart
        val pitch = note.pitch.coerceIn(0, 127)
        val lyric = note.lyric.trim()
        require(lyric.isNotEmpty()) { "Note ${note.id} has an empty lyric." }

        val phonemes = note.phoneme.trim()
        val pitchCurve = note.pitchPoints
            .filter { it.tick >= 0 && it.tick <= note.lengthTick }
            .sortedBy { it.tick }
            .map { point ->
                VocalRenderPitchPoint(
                    offsetSeconds = point.tick * secondsPerTick,
                    cents = point.cents.coerceIn(-2400, 2400)
                )
            }

        return VocalRenderNote(
            noteId = note.id,
            startTick = absoluteStart.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            durationTick = note.lengthTick,
            startSeconds = absoluteStart * secondsPerTick,
            durationSeconds = note.lengthTick * secondsPerTick,
            midiPitch = pitch,
            baseFrequencyHz = 440.0 * 2.0.pow((pitch - 69) / 12.0),
            lyric = lyric,
            phonemes = phonemes,
            language = language,
            pitchCurve = pitchCurve
        )
    }
}
