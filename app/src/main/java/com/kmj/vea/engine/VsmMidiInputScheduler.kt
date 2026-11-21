package com.kmj.vea.engine

import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaNote
import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack
import kotlin.math.floor

/**
 * Portable, app-owned render timing format.
 *
 * The 44.1 kHz VSM path recovered from the desktop editor renders 256-sample
 * blocks. This class models that scheduling boundary without reproducing the
 * desktop process's pointer-bearing 24-byte event records or its private ABI.
 */
data class VsmRenderFormat(
    val sampleRate: Int = 44_100,
    val chunkSamples: Int = 256,
    val ticksPerQuarter: Int = 480
) {
    init {
        require(sampleRate in 8_000..192_000) { "Unsupported render sample rate." }
        require(chunkSamples > 0) { "Chunk size must be positive." }
        require(ticksPerQuarter > 0) { "Ticks per quarter note must be positive." }
    }
}

/**
 * A note-start event in VEA's portable event model.
 *
 * This is not the VSM native staging-record layout. Lyric and phoneme strings
 * are preserved as editor data; vendor phoneme encoding is a later stage.
 */
data class VsmScheduledNote(
    val trackId: String,
    val clipId: String,
    val noteId: String,
    val absoluteStartSample: Long,
    val frameOffsetInChunk: Int,
    val pitch: Int,
    val durationSamples: Long,
    val lyric: String,
    val phoneme: String
) {
    init {
        require(absoluteStartSample >= 0L)
        require(frameOffsetInChunk >= 0)
        require(pitch in 0..127)
        require(durationSamples > 0L)
    }
}

/**
 * One sequential render block. renderSampleCount is fixed to the backend
 * chunk size; validSampleCount indicates how much of the final block belongs
 * to the project before zero-padding.
 */
data class VsmInputChunk(
    val index: Int,
    val startSample: Long,
    val renderSampleCount: Int,
    val validSampleCount: Int,
    val noteStarts: List<VsmScheduledNote>
) {
    init {
        require(index >= 0)
        require(startSample >= 0L)
        require(renderSampleCount > 0)
        require(validSampleCount in 1..renderSampleCount)
        require(noteStarts.all { it.frameOffsetInChunk in 0 until renderSampleCount })
    }
}

data class VsmRenderSchedule(
    val sampleRate: Int,
    val chunkSamples: Int,
    val totalSampleCount: Long,
    val chunks: List<VsmInputChunk>
) {
    init {
        require(sampleRate > 0)
        require(chunkSamples > 0)
        require(totalSampleCount >= 0L)
        require(chunks.all { it.renderSampleCount == chunkSamples })
    }
}

/**
 * Converts one VEA vocal track into a deterministic sequence of 256-sample
 * input chunks. The result is an app-side timeline only: it does not encode
 * Yamaha's private event strings, call a native voice-engine vtable, or
 * perform note-to-phoneme conversion.
 *
 * VEA currently has a constant project BPM and 480 ticks per quarter note.
 * Tempo automation, vibrato objects, MIDI controllers and pitch-expression
 * serialization are intentionally not guessed here.
 */
object VsmMidiInputScheduler {
    private const val MAX_CHUNKS = 1_000_000

    private data class PendingNote(
        val chunkIndex: Int,
        val event: VsmScheduledNote,
        val sourceOrder: Int
    )

    fun schedule(
        project: VeaProject,
        track: VeaTrack,
        format: VsmRenderFormat = VsmRenderFormat()
    ): VsmRenderSchedule {
        require(project.bpm in 1..1_000) { "Project BPM must be positive." }
        require(project.numerator in 1..32) { "Invalid time-signature numerator." }
        require(project.denominator in 1..32) { "Invalid time-signature denominator." }

        val ticksPerBar = (
            format.ticksPerQuarter.toLong() *
                project.numerator.toLong() * 4L /
                project.denominator.toLong()
            )
        require(ticksPerBar > 0L) { "Time signature produced an empty bar." }

        val pending = ArrayList<PendingNote>()
        var totalSampleCount = 0L
        var sourceOrder = 0

        for (clip in track.clips) {
            require(clip.startBar >= 1) {
                "Clip '${clip.id}' has a start bar before bar 1."
            }
            val clipStartTick = (clip.startBar.toLong() - 1L) * ticksPerBar

            for (note in clip.notes) {
                require(note.startTick >= 0) {
                    "Note '${note.id}' has a negative start tick."
                }
                require(note.lengthTick > 0) {
                    "Note '${note.id}' must have a positive duration."
                }
                require(note.pitch in 0..127) {
                    "Note '${note.id}' pitch must be in the MIDI range 0..127."
                }

                val absoluteStartTick = clipStartTick + note.startTick.toLong()
                val absoluteEndTick = absoluteStartTick + note.lengthTick.toLong()
                val startSample = ticksToSamples(absoluteStartTick, project.bpm, format)
                val endSample = ticksToSamples(absoluteEndTick, project.bpm, format)
                val durationSamples = endSample - startSample
                require(durationSamples > 0L) {
                    "Note '${note.id}' is shorter than one sample at the selected tempo."
                }

                val chunkIndexLong = startSample / format.chunkSamples.toLong()
                require(chunkIndexLong <= Int.MAX_VALUE.toLong()) {
                    "Note '${note.id}' starts beyond the supported chunk index range."
                }
                val chunkIndex = chunkIndexLong.toInt()
                val frameOffset = (startSample % format.chunkSamples.toLong()).toInt()

                pending += PendingNote(
                    chunkIndex = chunkIndex,
                    event = VsmScheduledNote(
                        trackId = track.id,
                        clipId = clip.id,
                        noteId = note.id,
                        absoluteStartSample = startSample,
                        frameOffsetInChunk = frameOffset,
                        pitch = note.pitch,
                        durationSamples = durationSamples,
                        lyric = note.lyric,
                        phoneme = note.phoneme
                    ),
                    sourceOrder = sourceOrder++
                )
                totalSampleCount = maxOf(totalSampleCount, endSample)
            }
        }

        if (totalSampleCount == 0L) {
            return VsmRenderSchedule(
                sampleRate = format.sampleRate,
                chunkSamples = format.chunkSamples,
                totalSampleCount = 0L,
                chunks = emptyList()
            )
        }

        val chunkCountLong = (totalSampleCount + format.chunkSamples - 1L) /
            format.chunkSamples.toLong()
        require(chunkCountLong <= MAX_CHUNKS.toLong()) {
            "Render schedule exceeds the $MAX_CHUNKS-chunk safety limit."
        }
        val chunkCount = chunkCountLong.toInt()

        // Keep event ordering deterministic for events that land on the same
        // sample. This is VEA's ordering rule, not a claim about the private
        // desktop VSM record comparator.
        val eventsByChunk = pending
            .sortedWith(
                compareBy<PendingNote> { it.chunkIndex }
                    .thenBy { it.event.frameOffsetInChunk }
                    .thenBy { it.sourceOrder }
            )
            .groupBy { it.chunkIndex }

        val chunks = List(chunkCount) { index ->
            val startSample = index.toLong() * format.chunkSamples.toLong()
            val validSamples = minOf(
                format.chunkSamples.toLong(),
                totalSampleCount - startSample
            ).toInt()
            VsmInputChunk(
                index = index,
                startSample = startSample,
                renderSampleCount = format.chunkSamples,
                validSampleCount = validSamples,
                noteStarts = eventsByChunk[index].orEmpty().map { it.event }
            )
        }

        return VsmRenderSchedule(
            sampleRate = format.sampleRate,
            chunkSamples = format.chunkSamples,
            totalSampleCount = totalSampleCount,
            chunks = chunks
        )
    }

    private fun ticksToSamples(
        ticks: Long,
        bpm: Int,
        format: VsmRenderFormat
    ): Long {
        require(ticks >= 0L)
        val samples = ticks.toDouble() * format.sampleRate.toDouble() * 60.0 /
            (bpm.toDouble() * format.ticksPerQuarter.toDouble())
        require(samples.isFinite() && samples <= Long.MAX_VALUE.toDouble()) {
            "Tick position exceeds the supported audio timeline."
        }
        // Matches the desktop VSM path's observed nearest-frame rounding:
        // floor(framePosition + 0.5).
        return floor(samples + 0.5).toLong()
    }
}
