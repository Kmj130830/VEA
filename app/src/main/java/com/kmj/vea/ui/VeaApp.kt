package com.kmj.vea.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kmj.vea.data.*
import com.kmj.vea.ui.arrangement.ArrangementScreen
import com.kmj.vea.ui.pianoroll.PianoRollScreen

enum class EditorMode {
    ARRANGEMENT,
    PIANO_ROLL
}

@Composable
fun VeaApp() {
    val context = LocalContext.current
    var modeName by rememberSaveable { mutableStateOf(EditorMode.ARRANGEMENT.name) }
    val mode = EditorMode.valueOf(modeName)

    // New installations open an intentionally empty project.
    var project by remember {
        mutableStateOf(ProjectStore.load(context) ?: VeaProject())
    }
    var selectedTrackId by rememberSaveable {
        mutableStateOf(project.tracks.firstOrNull()?.id.orEmpty())
    }
    var selectedClipId by rememberSaveable { mutableStateOf("") }

    fun saveProject() {
        val success = ProjectStore.save(context, project)
        Toast.makeText(
            context,
            if (success) "Project saved" else "Could not save project",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun updateClipNotes(notes: List<VeaNote>) {
        if (!hasNoOverlappingNotes(notes)) {
            Toast.makeText(context, "Notes cannot overlap in monophonic mode", Toast.LENGTH_SHORT).show()
            return
        }
        val trackId = selectedTrackId
        val clipId = selectedClipId
        project = project.copy(
            tracks = project.tracks.map { track ->
                if (track.id != trackId) track
                else track.copy(
                    clips = track.clips.map { clip ->
                        if (clip.id == clipId) clip.copy(notes = notes) else clip
                    }
                )
            }
        )
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB5E853),
            onPrimary = Color(0xFF17200A),
            secondary = Color(0xFF8C74C7),
            background = Color(0xFF17181B),
            surface = Color(0xFF222429),
            surfaceVariant = Color(0xFF303238),
            onBackground = Color(0xFFE8E9E7),
            onSurface = Color(0xFFE8E9E7)
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF17181B)) {
            if (mode == EditorMode.ARRANGEMENT) {
                ArrangementScreen(
                    project = project,
                    selectedTrackId = selectedTrackId,
                    onSelectTrack = { selectedTrackId = it },
                    onOpenClip = { trackId, clipId ->
                        selectedTrackId = trackId
                        selectedClipId = clipId
                        modeName = EditorMode.PIANO_ROLL.name
                    },
                    onAddTrack = {
                        val track = VeaTrack(
                            name = "Vocal " + (project.tracks.size + 1),
                            kind = "VOCAL_AI",
                            color = 0xFF8C74C7
                        )
                        project = project.copy(tracks = project.tracks + track)
                        selectedTrackId = track.id
                    },
                    onAddClip = {
                        val track = project.tracks.firstOrNull { it.id == selectedTrackId }
                        if (track != null && track.kind != "AUDIO") {
                            val startBar = track.clips.maxOfOrNull { it.startBar + it.lengthBars } ?: 1
                            val clip = VeaClip(name = "Clip", startBar = startBar, lengthBars = 4)
                            project = project.copy(
                                tracks = project.tracks.map { current ->
                                    if (current.id == track.id) current.copy(clips = current.clips + clip)
                                    else current
                                }
                            )
                            selectedClipId = clip.id
                            modeName = EditorMode.PIANO_ROLL.name
                        }
                    },
                    onSave = ::saveProject,
                    onOpenSelectedClip = {
                        val track = project.tracks.firstOrNull { it.id == selectedTrackId }
                        val clip = track?.clips?.firstOrNull { it.id == selectedClipId }
                        if (track != null && clip != null) {
                            selectedClipId = clip.id
                            modeName = EditorMode.PIANO_ROLL.name
                        }
                    }
                )
            } else {
                val track = project.tracks.firstOrNull { it.id == selectedTrackId }
                val clip = track?.clips?.firstOrNull { it.id == selectedClipId }
                if (track == null || clip == null) {
                    modeName = EditorMode.ARRANGEMENT.name
                } else {
                    PianoRollScreen(
                        track = track,
                        clip = clip,
                        bpm = project.bpm,
                        onBack = { modeName = EditorMode.ARRANGEMENT.name },
                        onSave = ::saveProject,
                        onNotesChanged = ::updateClipNotes
                    )
                }
            }
        }
    }
}
