package com.kmj.vea.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.kmj.vea.data.VeaNote
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Local piano preview / editor transport.
 *
 * This is an audition instrument for checking note pitches and timing. It is
 * intentionally not presented as VOCALOID singing synthesis.
 */
object PianoPreview {
    private const val SAMPLE_RATE = 44100
    private const val TICKS_PER_BEAT = 480
    private const val RELEASE_SECONDS = 0.28
    private const val MAX_PREVIEW_SECONDS = 90.0

    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var activeWorker: Thread? = null
    @Volatile private var generation: Int = 0

    fun play(midiPitch: Int) {
        playNotes(
            listOf(VeaNote(startTick = 0, lengthTick = TICKS_PER_BEAT, pitch = midiPitch)),
            bpm = 120
        )
    }

    /** Play a whole monophonic clip in timeline order using the same piano timbre. */
    fun playNotes(notes: List<VeaNote>, bpm: Int) {
        if (notes.isEmpty()) return
        val playGeneration = beginPlayback()
        val safeBpm = bpm.coerceIn(30, 300)

        val worker = Thread({
            var track: AudioTrack? = null
            try {
                val pcm = renderPianoSequence(notes, safeBpm)
                if (pcm.isEmpty() || !isCurrent(playGeneration)) return@Thread

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(16 * 1024)
                    .build()

                synchronized(this) {
                    if (generation != playGeneration) {
                        runCatching { track.release() }
                        return@Thread
                    }
                    activeTrack = track
                }

                track.play()
                var offset = 0
                while (offset < pcm.size && isCurrent(playGeneration)) {
                    val count = min(4096, pcm.size - offset)
                    val written = track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    offset += written
                }
            } catch (_: Throwable) {
                // Audio output errors are contained; the editor remains usable.
            } finally {
                synchronized(this) {
                    if (generation == playGeneration && activeTrack === track) {
                        runCatching { track?.stop() }
                        runCatching { track?.release() }
                        activeTrack = null
                        activeWorker = null
                    } else {
                        runCatching { track?.release() }
                    }
                }
            }
        }, "VEA-PianoTransport")

        synchronized(this) {
            if (generation == playGeneration) activeWorker = worker
            else return
        }
        worker.start()
    }

    private fun renderPianoSequence(notes: List<VeaNote>, bpm: Int): ShortArray {
        val ordered = notes.filter { it.startTick >= 0 && it.lengthTick > 0 }
            .sortedBy { it.startTick }
        if (ordered.isEmpty()) return ShortArray(0)

        val secondsPerTick = 60.0 / bpm / TICKS_PER_BEAT
        val lastEndSeconds = ordered.maxOf { (it.startTick + it.lengthTick) * secondsPerTick }
        val totalSeconds = (lastEndSeconds + RELEASE_SECONDS).coerceIn(0.2, MAX_PREVIEW_SECONDS)
        val totalFrames = (totalSeconds * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val mix = FloatArray(totalFrames)

        ordered.forEach { note ->
            val startFrame = (note.startTick * secondsPerTick * SAMPLE_RATE).toInt()
            val noteSeconds = (note.lengthTick * secondsPerTick).coerceAtLeast(0.035)
            val endFrame = min(totalFrames, startFrame + ((noteSeconds + RELEASE_SECONDS) * SAMPLE_RATE).toInt())
            val fundamental = 440.0 * 2.0.pow((note.pitch - 69) / 12.0)

            for (frame in startFrame.coerceAtLeast(0) until endFrame) {
                val localTime = (frame - startFrame).toDouble() / SAMPLE_RATE
                val attack = (1.0 - exp(-localTime * 170.0)).coerceIn(0.0, 1.0)
                val pianoDecay = exp(-localTime * 1.05)
                val release = if (localTime <= noteSeconds) {
                    1.0
                } else {
                    exp(-(localTime - noteSeconds) * 15.0)
                }
                var sample = 0.0

                for (partial in 1..8) {
                    val inharmonicity = 1.0 + 0.00012 * partial * partial
                    val frequency = fundamental * partial * inharmonicity
                    val partialAmplitude = 1.0 / partial.toDouble().pow(1.28)
                    val partialDecay = exp(-localTime * (0.55 + partial * 0.30))
                    sample += partialAmplitude * partialDecay *
                        sin(2.0 * PI * frequency * localTime)
                }

                val hammer = exp(-localTime * 95.0) *
                    sin(2.0 * PI * fundamental * 5.2 * localTime) * 0.07
                val value = ((sample + hammer) * attack * pianoDecay * release * 0.31).toFloat()
                mix[frame] += value
            }
        }

        var peak = 0.0f
        for (sample in mix) peak = max(peak, kotlin.math.abs(sample))
        val gain = if (peak > 0.92f) 0.92f / peak else 1.0f
        return ShortArray(mix.size) { index ->
            (mix[index] * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun beginPlayback(): Int {
        synchronized(this) {
            generation += 1
            activeTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
            activeTrack = null
            activeWorker?.interrupt()
            activeWorker = null
            return generation
        }
    }

    private fun isCurrent(expected: Int): Boolean = synchronized(this) {
        generation == expected
    }

    fun stop() {
        synchronized(this) {
            generation += 1
            activeTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.stop() }
                runCatching { track.release() }
            }
            activeTrack = null
            activeWorker?.interrupt()
            activeWorker = null
        }
    }
}
