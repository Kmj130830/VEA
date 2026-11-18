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
    val language: String = "jpn",
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

/**
 * Monophonic editing rule: note intervals may not overlap, even at different pitches.
 * Touching end-to-start is valid.
 */
fun hasNoOverlappingNotes(notes: List<VeaNote>): Boolean {
    val ordered = notes.sortedBy { it.startTick }
    return ordered.all { it.startTick >= 0 && it.lengthTick > 0 } &&
        ordered.zipWithNext().all { (left, right) ->
            left.startTick + left.lengthTick <= right.startTick
        }
}

fun canPlaceNote(existing: List<VeaNote>, candidate: VeaNote): Boolean {
    if (candidate.lengthTick <= 0 || candidate.startTick < 0) return false
    val candidateEnd = candidate.startTick + candidate.lengthTick
    return existing.none { note ->
        candidate.startTick < note.startTick + note.lengthTick &&
            note.startTick < candidateEnd
    }
}

fun sanitizeMonophonicNotes(notes: List<VeaNote>): List<VeaNote> {
    val accepted = mutableListOf<VeaNote>()
    var occupiedUntil = 0
    notes.sortedWith(compareBy<VeaNote> { it.startTick }.thenBy { it.id }).forEach { note ->
        if (note.lengthTick > 0 && note.startTick >= occupiedUntil) {
            accepted += note
            occupiedUntil = note.startTick + note.lengthTick
        }
    }
    return accepted
}
