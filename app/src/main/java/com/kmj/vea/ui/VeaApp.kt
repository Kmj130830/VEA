package com.kmj.vea.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kmj.vea.engine.MegpoidV7PackageInstaller
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kmj.vea.data.*
import com.kmj.vea.audio.PianoPreview
import com.kmj.vea.data.StandardMidiExporter
import com.kmj.vea.ui.arrangement.ArrangementScreen
import com.kmj.vea.ui.pianoroll.PianoRollScreen

enum class EditorMode {
    ARRANGEMENT,
    PIANO_ROLL
}

@Composable
fun VeaApp() {
    val context = LocalContext.current
    val megpoidVoicebankInfo = remember(context) { VoicebankCatalog.loadMegpoid(context) }
    var modeName by rememberSaveable { mutableStateOf(EditorMode.ARRANGEMENT.name) }
    val mode = EditorMode.valueOf(modeName)

    var project by remember {
        mutableStateOf(ProjectStore.load(context) ?: VeaProject())
    }

    val midiExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        if (uri != null) {
            val exported = runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open the selected destination")
                stream.use { StandardMidiExporter.write(project, it) }
            }.isSuccess
            Toast.makeText(
                context,
                if (exported) "MIDI exported. Import it into VOCALOID7." else "MIDI export failed",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    var installedMegpoidPackage by remember {
        mutableStateOf<MegpoidV7PackageInstaller.InstalledPackage?>(null)
    }
    var megpoidInstallCheckComplete by remember { mutableStateOf(false) }
    var megpoidModelLoadError by remember { mutableStateOf("") }

    LaunchedEffect(context) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                MegpoidV7PackageInstaller.findInstalled(context)
                    ?: MegpoidV7PackageInstaller.installBundled(context)
            }
        }
        result.onSuccess { installedMegpoidPackage = it }
            .onFailure { megpoidModelLoadError = it.message.orEmpty() }
        megpoidInstallCheckComplete = true
    }

    val installedMegpoidDescriptor = installedMegpoidPackage?.toVoicebankDescriptor()

    val history = remember(project.id) { ProjectHistory(project, limit = 200) }

    var selectedTrackId by rememberSaveable {
        mutableStateOf(project.tracks.firstOrNull()?.id.orEmpty())
    }
    var selectedClipId by rememberSaveable { mutableStateOf("") }

    val canUndo = history.canUndo
    val canRedo = history.canRedo

    fun commitProject(next: VeaProject) {
        if (next == project) return
        project = history.push(next)
    }

    // Newly created and previously unassigned vocal tracks use the bundled
    // Megpoid voice by default. Existing explicit singer assignments are kept.
    LaunchedEffect(installedMegpoidDescriptor?.sourceUri) {
        val defaultVoice = installedMegpoidDescriptor ?: return@LaunchedEffect
        val defaultStyleId = megpoidVoicebankInfo.defaultStyleId
        val nextTracks = project.tracks.map { track ->
            if (track.kind != "AUDIO" &&
                track.singerId.isBlank() &&
                track.singerSourceUri.isBlank()
            ) {
                track.copy(
                    singerId = defaultVoice.id,
                    singerName = defaultVoice.displayName,
                    singerStyleId = defaultStyleId,
                    singerSourceUri = defaultVoice.sourceUri
                )
            } else {
                track
            }
        }
        if (nextTracks != project.tracks) {
            commitProject(project.copy(tracks = nextTracks))
        }
    }

    fun undoProject() {
        history.undo()?.let { project = it }
    }

    fun redoProject() {
        history.redo()?.let { project = it }
    }

    LaunchedEffect(project) {
        if (project.tracks.none { it.id == selectedTrackId }) {
            selectedTrackId = project.tracks.firstOrNull()?.id.orEmpty()
        }
        val selectedTrack = project.tracks.firstOrNull { it.id == selectedTrackId }
        if (selectedTrack?.clips?.none { it.id == selectedClipId } != false) {
            selectedClipId = selectedTrack?.clips?.firstOrNull()?.id.orEmpty()
        }
    }

    fun updateTrackVoice(
        trackId: String,
        singerId: String,
        singerName: String,
        styleId: String,
        sourceUri: String
    ) {
        val next = project.copy(
            tracks = project.tracks.map { track ->
                if (track.id == trackId) track.copy(
                    singerId = singerId,
                    singerName = singerName,
                    singerStyleId = styleId,
                    singerSourceUri = sourceUri
                ) else track
            }
        )
        commitProject(next)
    }

    fun playSelectedClip() {
        val track = project.tracks.firstOrNull { it.id == selectedTrackId } ?: return
        val clip = track.clips.firstOrNull { it.id == selectedClipId } ?: return
        PianoPreview.playNotes(clip.notes, project.bpm)
    }

    fun updateTrackLanguage(language: String) {
        val trackId = selectedTrackId
        val next = project.copy(
            tracks = project.tracks.map { track ->
                if (track.id != trackId) track
                else track.copy(
                    language = language,
                    clips = track.clips.map { clip ->
                        clip.copy(notes = clip.notes.map { note ->
                            note.copy(phoneme = XsampaMapper.convert(note.lyric, language))
                        })
                    }
                )
            }
        )
        commitProject(next)
    }

    fun saveProject() {
        val success = ProjectStore.save(context, project)
        Toast.makeText(
            context,
            if (success) "Project saved" else "Could not save project",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun updateClipNotes(notes: List<VeaNote>) {
        // Duplicate/overlapping notes are rejected silently. This path intentionally
        // does not write to Logcat or show a toast.
        if (!hasNoOverlappingNotes(notes)) return
        val trackId = selectedTrackId
        val clipId = selectedClipId
        val next = project.copy(
            tracks = project.tracks.map { track ->
                if (track.id != trackId) track
                else track.copy(
                    clips = track.clips.map { clip ->
                        if (clip.id == clipId) clip.copy(notes = notes) else clip
                    }
                )
            }
        )
        commitProject(next)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF84B94C),
            onPrimary = Color(0xFF17200A),
            secondary = Color(0xFF8C74C7),
            background = Color(0xFF17181B),
            surface = Color(0xFF222429),
            surfaceVariant = Color(0xFF303238),
            onBackground = Color(0xFFE8E9E7),
            onSurface = Color(0xFFE8E9E7)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = Color(0xFF17181B)
        ) {
            if (mode == EditorMode.ARRANGEMENT) {
                ArrangementScreen(
                    project = project,
                    selectedTrackId = selectedTrackId,
                    selectedClipId = selectedClipId,
                    onSelectTrack = { selectedTrackId = it },
                    onSelectClip = { selectedClipId = it },
                    onOpenClip = { trackId, clipId ->
                        selectedTrackId = trackId
                        selectedClipId = clipId
                        modeName = EditorMode.PIANO_ROLL.name
                    },
                    onAddTrack = {
                        val defaultVoice = installedMegpoidDescriptor
                        val track = VeaTrack(
                            name = "Vocal " + (project.tracks.size + 1),
                            kind = "VOCAL_AI",
                            language = "jpn",
                            singerId = defaultVoice?.id.orEmpty(),
                            singerName = defaultVoice?.displayName.orEmpty(),
                            singerStyleId = if (defaultVoice != null) {
                                megpoidVoicebankInfo.defaultStyleId
                            } else {
                                ""
                            },
                            color = 0xFF8C74C7,
                            singerSourceUri = defaultVoice?.sourceUri.orEmpty()
                        )
                        commitProject(project.copy(tracks = project.tracks + track))
                        selectedTrackId = track.id
                        selectedClipId = ""
                    },
                    onAddClip = {
                        val track = project.tracks.firstOrNull { it.id == selectedTrackId }
                        if (track != null && track.kind != "AUDIO") {
                            val startBar = track.clips.maxOfOrNull { it.startBar + it.lengthBars } ?: 1
                            val clip = VeaClip(name = "Clip", startBar = startBar, lengthBars = 4)
                            val next = project.copy(
                                tracks = project.tracks.map { current ->
                                    if (current.id == track.id) current.copy(clips = current.clips + clip)
                                    else current
                                }
                            )
                            commitProject(next)
                            selectedClipId = clip.id
                        }
                    },
                    onSave = ::saveProject,
                    onExportMidi = {
                        val safeName = project.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "VEA_Project" }
                        midiExportLauncher.launch("$safeName.mid")
                    },
                    onOpenSelectedClip = {
                        val track = project.tracks.firstOrNull { it.id == selectedTrackId }
                        val clip = track?.clips?.firstOrNull { it.id == selectedClipId }
                        if (track != null && clip != null) modeName = EditorMode.PIANO_ROLL.name
                    },
                    onUndo = ::undoProject,
                    onRedo = ::redoProject,
                    onPlay = ::playSelectedClip,
                    onStop = PianoPreview::stop,
                    onAssignVoice = ::updateTrackVoice,
                    megpoidVoicebankSourceUri = installedMegpoidPackage
                        ?.toVoicebankDescriptor()?.sourceUri.orEmpty(),
                    isMegpoidModelInstalled = installedMegpoidPackage != null,
                    isMegpoidInstallCheckComplete = megpoidInstallCheckComplete,
                    megpoidModelLoadError = megpoidModelLoadError,
                    canUndo = canUndo,
                    canRedo = canRedo
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
                        onNotesChanged = ::updateClipNotes,
                        onLanguageChanged = ::updateTrackLanguage,
                        onUndo = ::undoProject,
                        onRedo = ::redoProject,
                        canUndo = canUndo,
                        canRedo = canRedo
                    )
                }
            }
        }
    }
}
