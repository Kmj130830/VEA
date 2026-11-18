package com.kmj.vea.data

import java.util.UUID

data class VeaProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled Project",
    val bpm: Int = 120,
    val numerator: Int = 4,
    val denominator: Int = 4,
    val tracks: List<VeaTrack> = emptyList()
)

data class VeaTrack(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: String = "VOCAL_AI",
    val color: Long = 0xFF8DBF4A,
    val clips: List<VeaClip> = emptyList()
)

data class VeaClip(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startBar: Int,
    val lengthBars: Int,
    val notes: List<VeaNote> = emptyList()
)

data class VeaNote(
    val id: String = UUID.randomUUID().toString(),
    val startTick: Int,
    val lengthTick: Int = 480,
    val pitch: Int = 60,
    val lyric: String = "あ",
    val phoneme: String = "a",
    val pitchPoints: List<PitchPoint> = emptyList()
)

data class PitchPoint(
    val tick: Int,
    val cents: Int
)

object ProjectFactory {
    fun demoProject(): VeaProject {
        val mainNotes = listOf(
            note(0, 55, "は", "ha"),
            note(480, 60, "じ", "j i"),
            note(960, 62, "ま", "m a"),
            note(1440, 60, "り", "r i"),
            note(1920, 62, "わ", "w a"),
            note(2880, 67, "い", "i"),
            note(3360, 69, "ま", "m a"),
            note(3840, 72, "あ", "a"),
            note(4800, 67, "な", "n a"),
            note(5760, 71, "ひ", "h i")
        )
        val main = VeaTrack(
            name = "Main01",
            kind = "VOCAL_AI",
            color = 0xFF8C74C7,
            clips = listOf(
                VeaClip(name = "Main", startBar = 1, lengthBars = 8, notes = mainNotes),
                VeaClip(name = "Main", startBar = 9, lengthBars = 8, notes = mainNotes),
                VeaClip(name = "Main", startBar = 17, lengthBars = 8, notes = mainNotes)
            )
        )
        val cho1 = VeaTrack(
            name = "Cho01",
            kind = "VOCAL_AI",
            color = 0xFFD27C63,
            clips = listOf(
                VeaClip(name = "Cho1", startBar = 5, lengthBars = 4, notes = listOf(note(0, 64, "あ", "a"))),
                VeaClip(name = "Cho1", startBar = 21, lengthBars = 4, notes = listOf(note(0, 64, "あ", "a")))
            )
        )
        val cho2 = VeaTrack(
            name = "Cho02",
            kind = "VOCAL_AI",
            color = 0xFF69A96B,
            clips = listOf(VeaClip(name = "Cho2", startBar = 13, lengthBars = 2))
        )
        val cho3 = VeaTrack(
            name = "Cho03",
            kind = "VOCAL_AI",
            color = 0xFF42BFC4,
            clips = listOf(VeaClip(name = "Cho3", startBar = 15, lengthBars = 4))
        )
        val mono = VeaTrack(name = "MONO", kind = "AUDIO", color = 0xFF777777)
        val stereo = VeaTrack(
            name = "STEREO",
            kind = "AUDIO",
            color = 0xFF777777,
            clips = listOf(VeaClip(name = "Audio placeholder", startBar = 1, lengthBars = 24))
        )
        return VeaProject(name = "The voice of beginning", tracks = listOf(main, cho1, cho2, cho3, mono, stereo))
    }

    private fun note(tick: Int, pitch: Int, lyric: String, phoneme: String): VeaNote {
        return VeaNote(
            startTick = tick,
            lengthTick = 480,
            pitch = pitch,
            lyric = lyric,
            phoneme = phoneme,
            pitchPoints = listOf(PitchPoint(0, 0), PitchPoint(480, 0))
        )
    }
}
