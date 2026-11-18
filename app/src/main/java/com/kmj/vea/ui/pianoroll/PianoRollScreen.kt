package com.kmj.vea.ui.pianoroll

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kmj.vea.audio.PianoPreview
import com.kmj.vea.data.PitchPoint
import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaNote
import com.kmj.vea.data.VeaTrack
import com.kmj.vea.data.XsampaMapper
import com.kmj.vea.data.MegpoidPortrait
import com.kmj.vea.data.canPlaceNote
import com.kmj.vea.data.hasNoOverlappingNotes
import com.kmj.vea.ui.components.ToolbarIcon
import com.kmj.vea.ui.components.VeaIcon
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val MIN_PITCH = 48
private const val MAX_PITCH = 72
private const val TICKS_PER_BEAT = 480
private const val TICKS_PER_BAR = 1920
private val NoteGreen = Color(0xFFA8DA55)
private val PitchCurveColor = Color(0xFFE76B22)
private val EditorBackground = Color(0xFFE8E9EA)

private enum class PianoTool { DRAW, SELECT, PITCH }

private fun defaultPitchPoints(lengthTick: Int): List<PitchPoint> {
    val end = lengthTick.coerceAtLeast(1)
    return listOf(
        PitchPoint(0, -36),
        PitchPoint((end * 0.08f).toInt(), 12),
        PitchPoint((end * 0.48f).toInt(), 34),
        PitchPoint((end * 0.78f).toInt(), 10),
        PitchPoint(end, -28)
    ).distinctBy { it.tick }.sortedBy { it.tick }
}
private val GridValues = listOf(1, 2, 4, 8, 16, 32)

private fun rescalePitchPoints(
    points: List<PitchPoint>,
    oldLength: Int,
    newLength: Int
): List<PitchPoint> {
    if (points.isEmpty()) return defaultPitchPoints(newLength)
    val safeOld = oldLength.coerceAtLeast(1)
    val safeNew = newLength.coerceAtLeast(1)
    return points.map { point ->
        point.copy(tick = (point.tick.toFloat() * safeNew / safeOld).roundToInt().coerceIn(0, safeNew))
    }.distinctBy { it.tick }.sortedBy { it.tick }
}

@Composable
fun PianoRollScreen(
    track: VeaTrack,
    clip: VeaClip,
    bpm: Int,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onNotesChanged: (List<VeaNote>) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.screenWidthDp < configuration.screenHeightDp
    val baseRowHeight = if (isPortrait) 21.dp else 23.dp
    val baseBeatWidth = if (isPortrait) 32.dp else 48.dp
    var zoomScale by remember(clip.id) { mutableFloatStateOf(1.0f) }
    val boundedZoom = zoomScale.coerceIn(0.65f, 2.30f)
    val rowHeight = baseRowHeight * boundedZoom
    val beatWidth = baseBeatWidth * boundedZoom
    val laneHeight = if (isPortrait) 50.dp else 60.dp
    val headerHeight = if (isPortrait) 40.dp else 34.dp
    val toolsHeight = 49.dp
    val editHeight = if (isPortrait) 35.dp else 32.dp
    val transportHeight = if (isPortrait) 43.dp else 35.dp
    val keyWidth = if (isPortrait) 39.dp else 48.dp

    var selectedNoteId by remember(clip.id) {
        mutableStateOf(clip.notes.firstOrNull()?.id.orEmpty())
    }
    val selectedNote = clip.notes.firstOrNull { it.id == selectedNoteId }
    var lyricDialogNoteId by remember(clip.id) { mutableStateOf<String?>(null) }
    var lyricInput by remember { mutableStateOf("") }
    var phonemeInput by remember { mutableStateOf("") }
    var phonemeLocked by remember { mutableStateOf(false) }
    var currentTool by remember(clip.id) { mutableStateOf(PianoTool.DRAW) }

    // Changing this value only changes snap points and grid lines.
    // Existing startTick/lengthTick values remain untouched.
    var gridDenominator by remember(clip.id) { mutableIntStateOf(4) }
    var gridMenuExpanded by remember { mutableStateOf(false) }
    val gridTicks = TICKS_PER_BAR / gridDenominator

    val density = androidx.compose.ui.platform.LocalDensity.current
    val beatWidthPx = with(density) { beatWidth.toPx() }
    val rowHeightPx = with(density) { rowHeight.toPx() }

    // Keep only one measure of look-ahead after the last note. A new note
    // expands the canvas when it reaches the current end, rather than reserving
    // the clip's full empty four-bar placeholder from the beginning.
    val lastNoteEnd = clip.notes.maxOfOrNull { it.startTick + it.lengthTick } ?: 0
    val canvasEndTick = max(TICKS_PER_BAR, lastNoteEnd + TICKS_PER_BEAT)
    val visibleBeats = ceil(canvasEndTick / TICKS_PER_BEAT.toDouble()).toInt()
    val contentHeight = rowHeight * (MAX_PITCH - MIN_PITCH + 1).toFloat()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    fun commitNotes(notes: List<VeaNote>) {
        // The monophonic collision rule is intentionally silent. Rejected edits
        // do not generate a toast or a Logcat entry.
        if (hasNoOverlappingNotes(notes)) onNotesChanged(notes)
    }

    fun updateNote(updated: VeaNote) {
        commitNotes(clip.notes.map { if (it.id == updated.id) updated else it })
    }

    fun updateLyric(noteId: String, lyric: String, phoneme: String) {
        val current = clip.notes.firstOrNull { it.id == noteId } ?: return
        updateNote(current.copy(lyric = lyric, phoneme = phoneme))
    }

    val lyricNote = clip.notes.firstOrNull { it.id == lyricDialogNoteId }
    LaunchedEffect(lyricDialogNoteId, clip.id) {
        if (lyricDialogNoteId != null && lyricNote == null) lyricDialogNoteId = null
    }

    fun openLyricEditor(note: VeaNote) {
        selectedNoteId = note.id
        lyricDialogNoteId = note.id
        lyricInput = note.lyric.ifBlank { "あ" }
        // Recompute the unlocked G2P suggestion when opening the editor so
        // notes created by older builds do not keep obsolete automatic phones.
        phonemeInput = XsampaMapper.convert(lyricInput, track.language)
        phonemeLocked = false
    }

    fun commitLyricEditor(noteId: String) {
        val resolvedPhoneme = if (phonemeLocked) {
            phonemeInput.trim()
        } else {
            XsampaMapper.convert(lyricInput, track.language)
        }
        updateLyric(noteId, lyricInput, resolvedPhoneme)
    }

    fun selectAdjacentLyricNote(direction: Int) {
        val currentId = lyricDialogNoteId ?: return
        val ordered = clip.notes.sortedBy { it.startTick }
        val index = ordered.indexOfFirst { it.id == currentId }
        val target = ordered.getOrNull(index + direction) ?: return
        commitLyricEditor(currentId)
        openLyricEditor(target)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF17181B))) {
        val viewportWidth = (maxWidth - keyWidth).coerceAtLeast(1.dp)
        val contentWidth = maxOf(viewportWidth, beatWidth * visibleBeats)
        Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarIcon(VeaIcon.HOME, "BACK", modifier = Modifier.width(42.dp), onClick = onBack)
            if (track.singerName.isNotBlank()) {
                MegpoidPortrait(26.dp)
                Spacer(Modifier.width(5.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(track.name + " / " + clip.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 12.sp)
                Text(
                    "${bpm} BPM · ${track.language.uppercase()} · " + track.singerName.ifBlank { "NO SINGER" },
                    fontSize = 9.sp,
                    color = Color.LightGray,
                    maxLines = 1
                )
            }
            TextButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text("SAVE", fontSize = 10.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(toolsHeight)
                .background(Color(0xFF303238)).horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarIcon(VeaIcon.EDIT, "DRAW", modifier = Modifier.width(45.dp),
                selected = currentTool == PianoTool.DRAW, onClick = { currentTool = PianoTool.DRAW })
            ToolbarIcon(VeaIcon.SELECT, "SELECT", modifier = Modifier.width(48.dp),
                selected = currentTool == PianoTool.SELECT, onClick = { currentTool = PianoTool.SELECT })
            ToolbarIcon(VeaIcon.PITCH, "PITCH", modifier = Modifier.width(45.dp),
                selected = currentTool == PianoTool.PITCH, onClick = { currentTool = PianoTool.PITCH })
            ToolbarIcon(VeaIcon.GRID, "GRID", modifier = Modifier.width(42.dp), selected = true)
            ToolbarIcon(VeaIcon.CP_WINDOW, "CP", modifier = Modifier.width(40.dp))
            ToolbarIcon(VeaIcon.KEYBOARD, "KEYS", modifier = Modifier.width(42.dp))
            ToolbarIcon(VeaIcon.LOOP, "LOOP", modifier = Modifier.width(40.dp))
            ToolbarIcon(VeaIcon.TONE, "TONE", modifier = Modifier.width(40.dp), selected = true)
            TextButton(
                onClick = { zoomScale = (boundedZoom - 0.10f).coerceAtLeast(0.65f) },
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) { Text("−", fontSize = 16.sp) }
            Slider(
                value = boundedZoom,
                onValueChange = { zoomScale = it.coerceIn(0.65f, 2.30f) },
                valueRange = 0.65f..2.30f,
                modifier = Modifier.width(94.dp).height(32.dp)
            )
            Text(
                (boundedZoom * 100f).roundToInt().toString() + "%",
                color = Color(0xFFE6E6E6),
                fontSize = 9.sp
            )
            TextButton(
                onClick = { zoomScale = (boundedZoom + 0.10f).coerceAtMost(2.30f) },
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) { Text("+", fontSize = 16.sp) }
            Box {
                TextButton(onClick = { gridMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 5.dp)) {
                    Text("1/$gridDenominator", color = NoteGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = gridMenuExpanded, onDismissRequest = { gridMenuExpanded = false }) {
                    GridValues.forEach { denominator ->
                        DropdownMenuItem(
                            text = { Text("1/$denominator") },
                            onClick = { gridDenominator = denominator; gridMenuExpanded = false }
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(keyWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (track.singerName.isNotBlank()) MegpoidPortrait(23.dp)
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalScroll)) {
                PianoRuler(width = contentWidth, beatWidth = beatWidth, gridTicks = gridTicks)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 2.dp).verticalScroll(verticalScroll)
        ) {
            PianoKeyboard(
                modifier = Modifier.width(keyWidth).height(contentHeight),
                rowHeight = rowHeight
            )
            Box(modifier = Modifier.weight(1f).height(contentHeight).horizontalScroll(horizontalScroll)) {
                NoteGrid(
                    notes = clip.notes,
                    selectedNoteId = selectedNoteId,
                    width = contentWidth,
                    height = contentHeight,
                    beatWidthPx = beatWidthPx,
                    rowHeightPx = rowHeightPx,
                    gridTicks = gridTicks,
                    language = track.language,
                    tool = currentTool,
                    onSelectNote = { note ->
                        selectedNoteId = note.id
                        PianoPreview.play(note.pitch)
                    },
                    onEditNote = { note ->
                        selectedNoteId = note.id
                        openLyricEditor(note)
                    },
                    onAddNote = { candidate ->
                        if (canPlaceNote(clip.notes, candidate)) {
                            commitNotes(clip.notes + candidate)
                            selectedNoteId = candidate.id
                            PianoPreview.play(candidate.pitch)
                        }
                    },
                    onMoveNote = { moved ->
                        val withoutMoved = clip.notes.filterNot { it.id == moved.id }
                        if (canPlaceNote(withoutMoved, moved)) {
                            commitNotes(clip.notes.map { if (it.id == moved.id) moved else it })
                            selectedNoteId = moved.id
                            PianoPreview.play(moved.pitch)
                        }
                    },
                    onResizeNote = { noteId, length ->
                        val current = clip.notes.firstOrNull { it.id == noteId }
                        if (current != null) {
                            val resized = current.copy(
                                lengthTick = length,
                                pitchPoints = rescalePitchPoints(current.pitchPoints, current.lengthTick, length)
                            )
                            val others = clip.notes.filterNot { it.id == noteId }
                            if (canPlaceNote(others, resized)) {
                                commitNotes(clip.notes.map { if (it.id == noteId) resized else it })
                            }
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(editHeight).background(Color(0xFF222429)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("NOTE", color = NoteGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(selectedNote?.let { pitchName(it.pitch) } ?: "—", color = Color.White, fontSize = 10.sp)
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(pitch = (it.pitch - 1).coerceIn(MIN_PITCH, MAX_PITCH))) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("−", fontSize = 10.sp) }
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(pitch = (it.pitch + 1).coerceIn(MIN_PITCH, MAX_PITCH))) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("+", fontSize = 10.sp) }
            TextButton(
                onClick = {
                    selectedNote?.let { note ->
                        val shorter = (note.lengthTick - gridTicks).coerceAtLeast(gridTicks)
                        val others = clip.notes.filterNot { it.id == note.id }
                        if (canPlaceNote(others, note.copy(lengthTick = shorter))) {
                            updateNote(note.copy(lengthTick = shorter))
                        }
                    }
                },
                enabled = selectedNote != null && selectedNote.lengthTick > gridTicks,
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) { Text("−LEN", fontSize = 9.sp) }
            TextButton(
                onClick = {
                    selectedNote?.let { note ->
                        val longer = note.lengthTick + gridTicks
                        val others = clip.notes.filterNot { it.id == note.id }
                        if (canPlaceNote(others, note.copy(lengthTick = longer))) {
                            updateNote(note.copy(lengthTick = longer))
                        }
                    }
                },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) { Text("+LEN", fontSize = 9.sp) }
            TextButton(
                onClick = {
                    selectedNote?.let { note ->
                        commitNotes(clip.notes.filterNot { it.id == note.id })
                        selectedNoteId = ""
                    }
                },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) { Text("DEL", color = Color(0xFFFF8A80), fontSize = 9.sp) }
        }

        Text(
            "PITCH CURVE",
            color = NoteGreen,
            fontSize = 8.sp,
            modifier = Modifier.padding(start = 6.dp, top = 1.dp, bottom = 1.dp)
        )
        PitchLane(
            note = selectedNote,
            enabled = currentTool == PianoTool.PITCH,
            width = contentWidth,
            beatWidth = beatWidth,
            laneHeight = laneHeight,
            keyWidth = keyWidth,
            scrollState = horizontalScroll,
            onPointsChanged = { points ->
                selectedNote?.let { updateNote(it.copy(pitchPoints = points)) }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(transportHeight).background(Color(0xFF111214)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ToolbarIcon(VeaIcon.PREVIOUS, "PREVIOUS", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.NEXT, "NEXT", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.STOP, "STOP", modifier = Modifier.weight(1f), onClick = PianoPreview::stop)
            ToolbarIcon(
                VeaIcon.PLAY, "PLAY", modifier = Modifier.weight(1f),
                onClick = { PianoPreview.playNotes(clip.notes, bpm) }
            )
            ToolbarIcon(VeaIcon.RECORD, "REC", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.SUB_TOOL, "SUB TOOL", modifier = Modifier.weight(1f), selected = true)
            ToolbarIcon(VeaIcon.UNDO, "UNDO", modifier = Modifier.weight(1f), enabled = canUndo, onClick = onUndo)
            ToolbarIcon(VeaIcon.REDO, "REDO", modifier = Modifier.weight(1f), enabled = canRedo, onClick = onRedo)
        }
    }
    }

    if (lyricDialogNoteId != null && lyricNote != null) {
        LyricEditorPopup(
            lyric = lyricInput,
            phoneme = phonemeInput,
            language = track.language,
            phonemeLocked = phonemeLocked,
            suggestions = XsampaMapper.pronunciationSuggestions(lyricInput, track.language),
            onLyricChange = {
                lyricInput = it
                if (!phonemeLocked) phonemeInput = XsampaMapper.convert(it, track.language)
            },
            onPhonemeChange = { phonemeInput = it; phonemeLocked = true },
            onSuggestionSelected = { suggestion ->
                phonemeInput = suggestion
                phonemeLocked = true
            },
            onToggleLock = {
                val wasLocked = phonemeLocked
                phonemeLocked = !wasLocked
                if (wasLocked) phonemeInput = XsampaMapper.convert(lyricInput, track.language)
            },
            onLanguageChange = { language ->
                phonemeInput = XsampaMapper.convert(lyricInput, language)
                phonemeLocked = false
                onLanguageChanged(language)
            },
            onClearLyric = {
                lyricInput = ""
                if (!phonemeLocked) phonemeInput = ""
            },
            onPrevious = { selectAdjacentLyricNote(-1) },
            onNext = { selectAdjacentLyricNote(1) },
            onCancel = { lyricDialogNoteId = null },
            onConfirm = {
                commitLyricEditor(lyricNote.id)
                lyricDialogNoteId = null
            }
        )
    }
}

@Composable
private fun LyricEditorPopup(
    lyric: String,
    phoneme: String,
    language: String,
    phonemeLocked: Boolean,
    suggestions: List<String>,
    onLyricChange: (String) -> Unit,
    onPhonemeChange: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit,
    onToggleLock: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onClearLyric: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val lyricFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var lyricFieldValue by remember {
        mutableStateOf(TextFieldValue(text = lyric, selection = TextRange(lyric.length)))
    }

    LaunchedEffect(lyric) {
        if (lyric != lyricFieldValue.text) {
            lyricFieldValue = TextFieldValue(text = lyric, selection = TextRange(lyric.length))
        }
    }

    LaunchedEffect(Unit) {
        lyricFocusRequester.requestFocus()
        keyboardController?.show()
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(13.dp),
            color = Color(0xFFF1F1F1),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    OutlinedTextField(
                        value = phoneme,
                        onValueChange = onPhonemeChange,
                        modifier = Modifier.weight(1f).height(52.dp),
                        singleLine = true,
                        readOnly = false,
                        textStyle = LocalTextStyle.current.copy(
                            color = Color(0xFF222222),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        placeholder = { Text("X-SAMPA", color = Color.Gray, fontSize = 13.sp) }
                    )
                    Box {
                        TextButton(
                            onClick = { languageMenuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(language.uppercase(), color = Color(0xFF222222), fontSize = 13.sp)
                        }
                        DropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false }
                        ) {
                            listOf("jpn", "eng", "zho", "kor").forEach { code ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (code) {
                                                "jpn" -> "JAPANESE"
                                                "eng" -> "ENGLISH"
                                                "zho" -> "CHINESE"
                                                else -> "KOREAN · PREVIEW ONLY"
                                            }
                                        )
                                    },
                                    onClick = {
                                        languageMenuExpanded = false
                                        onLanguageChange(code)
                                    }
                                )
                            }
                        }
                    }
                    ToolbarIcon(
                        icon = if (phonemeLocked) VeaIcon.LOCK else VeaIcon.UNLOCK,
                        label = if (phonemeLocked) "LOCK" else "EDIT",
                        modifier = Modifier.width(44.dp),
                        selected = phonemeLocked,
                        onClick = onToggleLock
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = lyricFieldValue,
                        onValueChange = {
                            lyricFieldValue = it
                            onLyricChange(it.text)
                        },
                        modifier = Modifier.weight(1f).height(56.dp).focusRequester(lyricFocusRequester),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = Color(0xFF222222),
                            fontSize = 23.sp
                        ),
                        placeholder = { Text("あ", color = Color.Gray, fontSize = 20.sp) }
                    )
                    ToolbarIcon(
                        VeaIcon.CLOSE,
                        "CLEAR",
                        modifier = Modifier.width(42.dp),
                        onClick = onClearLyric
                    )
                    TextButton(onClick = onPrevious, contentPadding = PaddingValues(horizontal = 5.dp)) {
                        Text("<<", color = Color(0xFF444444), fontSize = 16.sp)
                    }
                    TextButton(onClick = onNext, contentPadding = PaddingValues(horizontal = 5.dp)) {
                        Text(">>", color = Color(0xFFB00067), fontSize = 16.sp)
                    }
                }

                if (language.lowercase() in setOf("eng", "en") && suggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("G2P", color = Color(0xFF666666), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        suggestions.forEach { suggestion ->
                            OutlinedButton(
                                onClick = { onSuggestionSelected(suggestion) },
                                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 1.dp),
                                modifier = Modifier.height(29.dp)
                            ) {
                                Text(suggestion, fontSize = 11.sp, color = Color(0xFF333333))
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) {
                        Text("CANCEL", color = Color(0xFF555555))
                    }
                    TextButton(onClick = onConfirm) {
                        Text("OK", color = Color(0xFFB00067), fontWeight = FontWeight.Bold)
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(9.dp)) {
                    val tail = Path().apply {
                        moveTo(size.width / 2f - 13.dp.toPx(), 0f)
                        lineTo(size.width / 2f, size.height)
                        lineTo(size.width / 2f + 13.dp.toPx(), 0f)
                        close()
                    }
                    drawPath(tail, Color(0xFFF1F1F1))
                }
            }
        }
    }
}

@Composable
private fun PianoRuler(width: Dp, beatWidth: Dp, gridTicks: Int) {
    Box(modifier = Modifier.width(width).fillMaxHeight().background(Color(0xFFD9DADD))) {
        Canvas(Modifier.fillMaxSize()) {
            val beat = beatWidth.toPx()
            val totalTicks = (size.width / beat * TICKS_PER_BEAT).toInt()
            var tick = 0
            while (tick <= totalTicks) {
                val x = tick.toFloat() / TICKS_PER_BEAT * beat
                val isBar = tick % TICKS_PER_BAR == 0
                drawLine(
                    color = if (isBar) Color(0xFF85888B) else Color(0xFFB8BABC),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = if (isBar) 1.2f else 0.55f
                )
                tick += gridTicks
            }
            drawIntoCanvas { canvas ->
                val paint = AndroidPaint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 9.dp.toPx()
                    isAntiAlias = true
                }
                val bars = (size.width / (beat * 4f)).toInt()
                for (bar in 0..bars) {
                    canvas.nativeCanvas.drawText((bar + 1).toString(), bar * beat * 4f + 4f, size.height * 0.75f, paint)
                }
            }
        }
    }
}

@Composable
private fun PianoKeyboard(modifier: Modifier = Modifier, rowHeight: Dp) {
    Canvas(modifier = modifier.background(Color(0xFF777A7E))) {
        val rowPx = rowHeight.toPx()
        val blackKeys = setOf(1, 3, 6, 8, 10)
        for (row in 0..(MAX_PITCH - MIN_PITCH)) {
            val pitch = MAX_PITCH - row
            val y = row * rowPx
            drawRect(
                color = if (pitch % 12 in blackKeys) Color(0xFF55575A) else Color.White,
                topLeft = Offset(0f, y),
                size = Size(size.width, rowPx - 1f)
            )
            if (pitch % 12 == 0) {
                drawIntoCanvas { canvas ->
                    val paint = AndroidPaint().apply {
                        color = android.graphics.Color.DKGRAY
                        textSize = 9.dp.toPx()
                        isAntiAlias = true
                    }
                    canvas.nativeCanvas.drawText(
                        "C" + (pitch / 12 - 1),
                        size.width - 21.dp.toPx(),
                        y + rowPx * 0.72f,
                        paint
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteGrid(
    notes: List<VeaNote>,
    selectedNoteId: String,
    width: Dp,
    height: Dp,
    beatWidthPx: Float,
    rowHeightPx: Float,
    gridTicks: Int,
    language: String,
    tool: PianoTool,
    onSelectNote: (VeaNote) -> Unit,
    onEditNote: (VeaNote) -> Unit,
    onAddNote: (VeaNote) -> Unit,
    onMoveNote: (VeaNote) -> Unit,
    onResizeNote: (String, Int) -> Unit
) {
    val latestNotes = rememberUpdatedState(notes)
    val latestSelect = rememberUpdatedState(onSelectNote)
    val latestEdit = rememberUpdatedState(onEditNote)
    val latestAdd = rememberUpdatedState(onAddNote)
    val latestMove = rememberUpdatedState(onMoveNote)
    val latestResize = rememberUpdatedState(onResizeNote)

    var activeNoteId by remember { mutableStateOf<String?>(null) }
    var activeGesture by remember { mutableStateOf("NONE") }
    var previewStartTick by remember { mutableIntStateOf(0) }
    var previewPitch by remember { mutableIntStateOf(60) }
    var previewLengthTick by remember { mutableIntStateOf(0) }
    var previewValid by remember { mutableStateOf(true) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val edgeThresholdPx = with(density) { 18.dp.toPx() }

    Canvas(
        modifier = Modifier.width(width).height(height).background(EditorBackground)
            .pointerInput(gridTicks, beatWidthPx, rowHeightPx, language, tool) {
                detectTapGestures(
                    onTap = { position ->
                        if (tool == PianoTool.PITCH) return@detectTapGestures
                        val hit = latestNotes.value.firstOrNull { note ->
                            val left = note.startTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                            val right = left + note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                            val top = (MAX_PITCH - note.pitch) * rowHeightPx
                            position.x >= left && position.x < right &&
                                position.y >= top && position.y <= top + rowHeightPx
                        }
                        if (hit != null) {
                            latestSelect.value(hit)
                        } else if (tool == PianoTool.DRAW) {
                            val rawTick = (position.x / beatWidthPx * TICKS_PER_BEAT).roundToInt().coerceAtLeast(0)
                            val startTick = ((rawTick + gridTicks / 2) / gridTicks) * gridTicks
                            val row = floor(position.y / rowHeightPx).toInt()
                            val pitch = (MAX_PITCH - row).coerceIn(MIN_PITCH, MAX_PITCH)
                            latestAdd.value(
                                VeaNote(
                                    startTick = startTick,
                                    lengthTick = gridTicks,
                                    pitch = pitch,
                                    lyric = "あ",
                                    phoneme = XsampaMapper.convert("あ", language),
                                    pitchPoints = defaultPitchPoints(gridTicks)
                                )
                            )
                        }
                    },
                    onDoubleTap = { position ->
                        if (tool != PianoTool.PITCH) {
                            val hit = latestNotes.value.firstOrNull { note ->
                                val left = note.startTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                                val right = left + note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                                val top = (MAX_PITCH - note.pitch) * rowHeightPx
                                position.x >= left && position.x < right &&
                                    position.y >= top && position.y <= top + rowHeightPx
                            }
                            if (hit != null) latestEdit.value(hit)
                        }
                    }
                )
            }
            .pointerInput(gridTicks, beatWidthPx, rowHeightPx, edgeThresholdPx, tool) {
                var initialStart = 0
                var initialPitch = 60
                var initialLength = 0
                var accumulatedX = 0f
                var accumulatedY = 0f

                detectDragGestures(
                    onDragStart = { position ->
                        val hit = latestNotes.value.firstOrNull { note ->
                            val left = note.startTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                            val right = left + note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                            val top = (MAX_PITCH - note.pitch) * rowHeightPx
                            position.x >= left && position.x <= right &&
                                position.y >= top && position.y <= top + rowHeightPx
                        }
                        activeNoteId = if (tool == PianoTool.PITCH) null else hit?.id
                        initialStart = hit?.startTick ?: 0
                        initialPitch = hit?.pitch ?: 60
                        initialLength = hit?.lengthTick ?: 0
                        accumulatedX = 0f
                        accumulatedY = 0f
                        val hitRight = hit?.let {
                            (it.startTick + it.lengthTick).toFloat() / TICKS_PER_BEAT * beatWidthPx
                        } ?: 0f
                        val resizeThreshold = with(density) { 20.dp.toPx() }
                        activeGesture = when {
                            tool == PianoTool.PITCH || hit == null -> "NONE"
                            position.x >= hitRight - resizeThreshold && position.x <= hitRight + with(density) { 7.dp.toPx() } -> "RESIZE"
                            else -> "MOVE"
                        }
                        previewStartTick = initialStart
                        previewPitch = initialPitch
                        previewLengthTick = initialLength
                        previewValid = true
                    },
                    onDrag = { change, amount ->
                        if (activeNoteId == null) return@detectDragGestures
                        accumulatedX += amount.x
                        accumulatedY += amount.y
                        if (activeGesture == "RESIZE") {
                            val deltaTicks = (accumulatedX / beatWidthPx * TICKS_PER_BEAT).roundToInt()
                            val requested = max(gridTicks, initialLength + deltaTicks)
                            previewLengthTick = max(
                                gridTicks,
                                ((requested + gridTicks / 2) / gridTicks) * gridTicks
                            )
                            val current = latestNotes.value.firstOrNull { it.id == activeNoteId }
                            if (current != null) {
                                val candidate = current.copy(lengthTick = previewLengthTick)
                                previewValid = canPlaceNote(latestNotes.value.filterNot { it.id == current.id }, candidate)
                            }
                        } else {
                            val deltaTicks = (accumulatedX / beatWidthPx * TICKS_PER_BEAT).roundToInt()
                            val rawStart = (initialStart + deltaTicks).coerceAtLeast(0)
                            previewStartTick = ((rawStart + gridTicks / 2) / gridTicks) * gridTicks
                            val pitchDelta = (accumulatedY / rowHeightPx).roundToInt()
                            previewPitch = (initialPitch - pitchDelta).coerceIn(MIN_PITCH, MAX_PITCH)
                            val current = latestNotes.value.firstOrNull { it.id == activeNoteId }
                            if (current != null) {
                                val candidate = current.copy(startTick = previewStartTick, pitch = previewPitch)
                                previewValid = canPlaceNote(latestNotes.value.filterNot { it.id == current.id }, candidate)
                            }
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val current = latestNotes.value.firstOrNull { it.id == activeNoteId }
                        if (current != null && previewValid) {
                            if (activeGesture == "RESIZE") {
                                latestResize.value(current.id, previewLengthTick)
                            } else {
                                latestMove.value(current.copy(startTick = previewStartTick, pitch = previewPitch))
                            }
                        }
                        activeNoteId = null
                        activeGesture = "NONE"
                    },
                    onDragCancel = {
                        activeNoteId = null
                        activeGesture = "NONE"
                    }
                )
            }
    ) {
        val rowHeight = rowHeightPx
        val beatWidth = beatWidthPx
        val blackKeys = setOf(1, 3, 6, 8, 10)

        for (row in 0..(MAX_PITCH - MIN_PITCH)) {
            val pitch = MAX_PITCH - row
            drawRect(
                color = if (pitch % 12 in blackKeys) Color(0xFFE4E5E6) else Color(0xFFF6F6F6),
                topLeft = Offset(0f, row * rowHeight),
                size = Size(size.width, rowHeight - 1f)
            )
            drawLine(
                color = Color(0xFFC9CBCE),
                start = Offset(0f, row * rowHeight),
                end = Offset(size.width, row * rowHeight),
                strokeWidth = 0.65f
            )
        }

        val totalTicks = (size.width / beatWidth * TICKS_PER_BEAT).toInt()
        var gridTick = 0
        while (gridTick <= totalTicks) {
            val x = gridTick.toFloat() / TICKS_PER_BEAT * beatWidth
            val isBar = gridTick % TICKS_PER_BAR == 0
            drawLine(
                color = if (isBar) Color(0xFF85888B) else Color(0xFFD1D3D5),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = if (isBar) 1.25f else 0.55f
            )
            gridTick += gridTicks
        }

        notes.forEach { note ->
            val moving = note.id == activeNoteId && activeGesture == "MOVE"
            val resizing = note.id == activeNoteId && activeGesture == "RESIZE"
            val visualStart = if (moving) previewStartTick else note.startTick
            val visualPitch = if (moving) previewPitch else note.pitch
            val visualLength = if (resizing) previewLengthTick else note.lengthTick
            val left = visualStart.toFloat() / TICKS_PER_BEAT * beatWidth
            val right = (visualStart + visualLength).toFloat() / TICKS_PER_BEAT * beatWidth
            val noteTop = (MAX_PITCH - visualPitch) * rowHeight
            val noteBlockTop = noteTop + rowHeight * 0.38f
            val noteBlockHeight = rowHeight * 0.58f
            val noteWidth = (right - left).coerceAtLeast(8f) - 1f
            val alpha = if ((moving || resizing) && !previewValid) 0.38f else 1f
            val noteColor = if (moving && !previewValid) Color(0xFFD28A8A) else NoteGreen

            if (moving) {
                val originalLeft = note.startTick.toFloat() / TICKS_PER_BEAT * beatWidth
                val originalTop = (MAX_PITCH - note.pitch) * rowHeight + rowHeight * 0.38f
                drawRoundRect(
                    color = NoteGreen.copy(alpha = 0.20f),
                    topLeft = Offset(originalLeft, originalTop),
                    size = Size((note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidth).coerceAtLeast(8f) - 1f, noteBlockHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                )
            }

            drawRoundRect(
                color = noteColor.copy(alpha = if (moving) 0.48f else alpha),
                topLeft = Offset(left + 0.5f, noteBlockTop),
                size = Size(noteWidth, noteBlockHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )

            // Render the actual lyric inside the note. Earlier builds only drew
            // X-SAMPA above the note, which made committed lyrics appear missing.
            if (note.lyric.isNotBlank()) {
                drawIntoCanvas { canvas ->
                    val paint = android.text.TextPaint().apply {
                        color = android.graphics.Color.rgb(35, 40, 28)
                        textSize = 8.dp.toPx()
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    val availableWidth = (noteWidth - 5f).coerceAtLeast(1f)
                    val visibleLyric = android.text.TextUtils.ellipsize(
                        note.lyric,
                        paint,
                        availableWidth,
                        android.text.TextUtils.TruncateAt.END
                    ).toString()
                    canvas.nativeCanvas.drawText(
                        visibleLyric,
                        left + 2.5f,
                        noteBlockTop + noteBlockHeight * 0.76f,
                        paint
                    )
                }
            }

            // Phoneme label is drawn immediately above the note block.
            val phoneme = note.phoneme.ifBlank { XsampaMapper.convert(note.lyric, language) }
            if (phoneme.isNotBlank()) {
                drawIntoCanvas { canvas ->
                    val paint = AndroidPaint().apply {
                        color = android.graphics.Color.rgb(45, 45, 45)
                        textSize = 8.dp.toPx()
                        isAntiAlias = true
                    }
                    canvas.nativeCanvas.drawText(phoneme, left + 2f, noteTop + rowHeight * 0.34f, paint)
                }
            }
            if (note.id == selectedNoteId) {
                drawRoundRect(
                    color = Color(0xFFDA4C94),
                    topLeft = Offset(left, noteBlockTop - 1f),
                    size = Size(noteWidth + 1f, noteBlockHeight + 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                    style = Stroke(width = 1.4f)
                )
            }
            // Right-edge grip for length resizing.
            drawRoundRect(
                color = Color(0xFF60763A),
                topLeft = Offset(right - 5f, noteBlockTop + noteBlockHeight * 0.22f),
                size = Size(4f, noteBlockHeight * 0.56f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
        }
    }
}

@Composable
private fun PitchLane(
    note: VeaNote?,
    enabled: Boolean,
    width: Dp,
    beatWidth: Dp,
    laneHeight: Dp,
    keyWidth: Dp,
    scrollState: androidx.compose.foundation.ScrollState,
    onPointsChanged: (List<PitchPoint>) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val beatWidthPx = with(density) { beatWidth.toPx() }

    Row(modifier = Modifier.fillMaxWidth().height(laneHeight)) {
        Spacer(Modifier.width(keyWidth))
        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(scrollState)) {
            Canvas(
                modifier = Modifier.width(width).fillMaxHeight()
                    .pointerInput(note, enabled) {
                        detectTapGestures { position ->
                            if (!enabled) return@detectTapGestures
                            val current = note ?: return@detectTapGestures
                            val absoluteTick = (position.x / beatWidthPx * TICKS_PER_BEAT).roundToInt()
                            val relativeTick = (absoluteTick - current.startTick).coerceIn(0, current.lengthTick)
                            val cents = ((size.height / 2f - position.y) / (size.height / 700f))
                                .roundToInt().coerceIn(-300, 300)
                            val point = PitchPoint(relativeTick, cents)
                            val basePoints = if (current.pitchPoints.isEmpty()) {
                                defaultPitchPoints(current.lengthTick)
                            } else {
                                current.pitchPoints
                            }
                            val updated = (
                                basePoints.filterNot { abs(it.tick - relativeTick) <= 30 } + point
                            ).sortedBy { it.tick }
                            onPointsChanged(updated)
                        }
                    }
            ) {
                drawRect(Color(0xFFD4DDC8))
                val centerY = size.height / 2f
                drawLine(Color(0xFF929C84), Offset(0f, centerY), Offset(size.width, centerY), 1f)
                for (index in 0..(size.width / beatWidthPx).toInt()) {
                    val x = index * beatWidthPx
                    drawLine(
                        color = Color(0xFFB5BEA8),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = if (index % 4 == 0) 1.1f else 0.6f
                    )
                }
                val points = note?.let {
                    if (it.pitchPoints.isEmpty()) defaultPitchPoints(it.lengthTick)
                    else it.pitchPoints
                }.orEmpty().sortedBy { it.tick }
                if (points.size >= 2 && note != null) {
                    val offsets = points.map { point ->
                        Offset(
                            x = (note.startTick + point.tick).toFloat() / TICKS_PER_BEAT * beatWidthPx,
                            y = centerY - point.cents.coerceIn(-300, 300) * (size.height / 700f)
                        )
                    }
                    val smoothPath = Path().apply {
                        moveTo(offsets.first().x, offsets.first().y)
                        for (index in 0 until offsets.lastIndex) {
                            val start = offsets[index]
                            val end = offsets[index + 1]
                            val dx = end.x - start.x
                            cubicTo(
                                start.x + dx * 0.36f, start.y,
                                end.x - dx * 0.36f, end.y,
                                end.x, end.y
                            )
                        }
                    }
                    drawPath(
                        path = smoothPath,
                        color = PitchCurveColor,
                        style = Stroke(width = 2.6f)
                    )
                    offsets.forEach { drawCircle(PitchCurveColor, radius = 2.4f, center = it) }
                }
            }
        }
    }
}

private fun pitchName(midi: Int): String {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[midi % 12] + (midi / 12 - 1)
}
