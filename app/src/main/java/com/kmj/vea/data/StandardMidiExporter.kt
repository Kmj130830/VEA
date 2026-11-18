package com.kmj.vea.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Standard MIDI File exporter for hand-off to the licensed desktop VOCALOID7
 * editor. Note lyrics are emitted as SMF Lyric meta events (0x05).
 *
 * VEA's local pitch preview points are intentionally not exported as MIDI
 * pitch-bend events: VOCALOID:AI must generate its own expression/pitch from
 * the imported notes and lyrics to preserve the official engine's behavior.
 */
object StandardMidiExporter {
    private const val TICKS_PER_BEAT = 480
    private const val TICKS_PER_BAR = TICKS_PER_BEAT * 4

    private data class Event(
        val tick: Int,
        val priority: Int,
        val bytes: ByteArray
    )

    fun write(project: VeaProject, output: OutputStream) {
        output.write(toByteArray(project))
        output.flush()
    }

    fun toByteArray(project: VeaProject): ByteArray {
        // Standard MIDI has 15 melodic channels per port (channel 10 is drums).
        val musicalTracks = project.tracks.filter { it.kind != "AUDIO" }.take(15)
        val outputTracks = mutableListOf<ByteArray>()
        outputTracks += conductorTrack(project)

        musicalTracks.forEachIndexed { index, track ->
            outputTracks += voiceTrack(project, track, index)
        }

        val file = ByteArrayOutputStream()
        file.write("MThd".toByteArray(Charsets.US_ASCII))
        writeInt32(file, 6)
        writeInt16(file, if (outputTracks.size > 1) 1 else 0)
        writeInt16(file, outputTracks.size)
        writeInt16(file, TICKS_PER_BEAT)

        outputTracks.forEach { trackData ->
            file.write("MTrk".toByteArray(Charsets.US_ASCII))
            writeInt32(file, trackData.size)
            file.write(trackData)
        }
        return file.toByteArray()
    }

    private fun conductorTrack(project: VeaProject): ByteArray {
        val events = mutableListOf<Event>()
        events += Event(0, 0, metaText(0x03, project.name.ifBlank { "VEA Project" }))
        val bpm = project.bpm.coerceIn(20, 400)
        val microsPerQuarter = (60_000_000 / bpm).coerceIn(1, 0xFFFFFF)
        events += Event(
            0,
            1,
            byteArrayOf(
                0xFF.toByte(), 0x51, 0x03,
                ((microsPerQuarter shr 16) and 0xFF).toByte(),
                ((microsPerQuarter shr 8) and 0xFF).toByte(),
                (microsPerQuarter and 0xFF).toByte()
            )
        )
        val denominator = project.denominator.coerceIn(1, 32)
        val denominatorPower = Integer.numberOfTrailingZeros(Integer.highestOneBit(denominator))
        events += Event(
            0,
            2,
            byteArrayOf(
                0xFF.toByte(), 0x58, 0x04,
                project.numerator.coerceIn(1, 32).toByte(),
                denominatorPower.toByte(),
                24, 8
            )
        )
        return encodeTrack(events, 1)
    }

    private fun voiceTrack(project: VeaProject, track: VeaTrack, trackIndex: Int): ByteArray {
        val events = mutableListOf<Event>()
        events += Event(0, -2, metaText(0x03, track.name.ifBlank { "Vocal " + (trackIndex + 1) }))

        // Channels 0-8, then 10-15. Channel 9 is reserved for percussion.
        val channel = if (trackIndex < 9) trackIndex else (trackIndex + 1).coerceAtMost(15)
        val ticksPerBar = (
            TICKS_PER_BEAT * project.numerator.coerceIn(1, 32) * 4 /
                project.denominator.coerceIn(1, 32)
        ).coerceAtLeast(1)
        val noteEvents = track.clips.flatMap { clip ->
            val clipOffset = (clip.startBar.coerceAtLeast(1) - 1) * ticksPerBar
            clip.notes.map { note ->
                val start = clipOffset + note.startTick.coerceAtLeast(0)
                val end = start + note.lengthTick.coerceAtLeast(1)
                Triple(start, end, note)
            }
        }.sortedBy { it.first }

        noteEvents.forEach { (start, end, note) ->
            val pitch = note.pitch.coerceIn(0, 127)
            val lyric = lyricForExport(note, track.language)
            events += Event(start, 0, metaText(0x05, lyric))
            events += Event(start, 1, byteArrayOf((0x90 or channel).toByte(), pitch.toByte(), 100))
            events += Event(end, -1, byteArrayOf((0x80 or channel).toByte(), pitch.toByte(), 0))
        }

        val lastTick = noteEvents.maxOfOrNull { it.second } ?: 0
        return encodeTrack(events, lastTick + 1)
    }

    private fun lyricForExport(note: VeaNote, language: String): String {
        val lyric = note.lyric.ifBlank { "あ" }
        return when (language.lowercase()) {
            "jpn", "ja", "jp" ->
                if (lyric.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                    XsampaMapper.romanToHiragana(lyric)
                } else lyric
            "kor", "ko", "kr" -> XsampaMapper.composeKoreanJamo(lyric)
            else -> lyric
        }
    }

    private fun encodeTrack(events: List<Event>, endTick: Int): ByteArray {
        val sorted = events.sortedWith(compareBy<Event> { it.tick }.thenBy { it.priority })
        val output = ByteArrayOutputStream()
        var previousTick = 0
        sorted.forEach { event ->
            val absoluteTick = event.tick.coerceAtLeast(previousTick)
            writeVariableLength(output, absoluteTick - previousTick)
            output.write(event.bytes)
            previousTick = absoluteTick
        }
        val finalTick = maxOf(previousTick, endTick)
        writeVariableLength(output, finalTick - previousTick)
        output.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        return output.toByteArray()
    }

    private fun metaText(type: Int, value: String): ByteArray {
        val text = value.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        output.write(0xFF)
        output.write(type)
        writeVariableLength(output, text.size)
        output.write(text)
        return output.toByteArray()
    }

    private fun writeVariableLength(output: ByteArrayOutputStream, input: Int) {
        var value = input.coerceAtLeast(0)
        val bytes = ArrayList<Int>(4)
        bytes.add(value and 0x7F)
        value = value ushr 7
        while (value > 0) {
            bytes.add(0, (value and 0x7F) or 0x80)
            value = value ushr 7
        }
        bytes.forEach(output::write)
    }

    private fun writeInt16(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun writeInt32(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }
}
