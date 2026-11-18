package com.kmj.vea.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Small local piano-like note preview.
 * A decaying string/hammer partial model is rendered to PCM, not a beep or square wave.
 */
object PianoPreview {
    private const val SAMPLE_RATE = 44100

    @Volatile
    private var activeTrack: AudioTrack? = null
    @Volatile
    private var generation: Int = 0

    fun play(midiPitch: Int) {
        val playGeneration = synchronized(this) {
            generation += 1
            activeTrack?.let { previous ->
                runCatching { previous.pause() }
                runCatching { previous.flush() }
                runCatching { previous.release() }
            }
            activeTrack = null
            generation
        }

        Thread({
            val track = runCatching { buildPianoTrack(midiPitch) }.getOrNull() ?: return@Thread
            val shouldPlay = synchronized(this) {
                if (generation == playGeneration) {
                    activeTrack = track
                    true
                } else {
                    false
                }
            }
            if (!shouldPlay) {
                runCatching { track.release() }
                return@Thread
            }

            runCatching { track.play() }
            try {
                Thread.sleep(1250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            synchronized(this) {
                if (generation == playGeneration && activeTrack === track) {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    activeTrack = null
                }
            }
        }, "VEA-PianoPreview").start()
    }

    private fun buildPianoTrack(midiPitch: Int): AudioTrack {
        val durationSeconds = 1.15
        val frameCount = (SAMPLE_RATE * durationSeconds).toInt()
        val pcm = ShortArray(frameCount)
        val fundamental = 440.0 * Math.pow(2.0, (midiPitch - 69) / 12.0)

        for (frame in 0 until frameCount) {
            val time = frame.toDouble() / SAMPLE_RATE
            val attack = 1.0 - exp(-time * 180.0)
            var sample = 0.0

            for (partial in 1..8) {
                val inharmonicity = 1.0 + 0.00012 * partial * partial
                val frequency = fundamental * partial * inharmonicity
                val partialDecay = 2.4 + partial * 0.72
                val amplitude = 1.0 / Math.pow(partial.toDouble(), 1.35)
                sample += amplitude * exp(-time * partialDecay) *
                    sin(2.0 * PI * frequency * time)
            }

            val hammer = exp(-time * 95.0) *
                sin(2.0 * PI * fundamental * 5.2 * time) * 0.08
            val normalized = ((sample + hammer) * attack * 0.26).coerceIn(-1.0, 1.0)
            pcm[frame] = (normalized * Short.MAX_VALUE).toInt().toShort()
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()

        val written = track.write(pcm, 0, pcm.size)
        if (written <= 0) {
            track.release()
            throw IllegalStateException("Piano preview buffer could not be written")
        }
        return track
    }

    fun stop() {
        synchronized(this) {
            generation += 1
            activeTrack?.let { track ->
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
            }
            activeTrack = null
        }
    }
}
