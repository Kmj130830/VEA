package com.kmj.vea.ui.pianoroll

import android.graphics.Paint as AndroidPaint
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmj.vea.data.PitchPoint
import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaNote
import com.kmj.vea.data.VeaTrack
import com.kmj.vea.data.canPlaceNote
import com.kmj.vea.data.hasNoOverlappingNotes
import com.kmj.vea.ui.components.ToolbarIcon
import com.kmj.vea.ui.components.VeaIcon
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MIN_PITCH = 48
private const val MAX_PITCH = 72
private const val TICKS_PER_BEAT = 480
private val NoteGreen = Color(0xFFB5E853)
private val EditorBackground = Color(0xFF282A2E)

@Composable
fun PianoRollScreen(
    track: VeaTrack,
    clip: VeaClip,
    bpm: Int,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onNotesChanged: (List<VeaNote>) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.screenWidthDp < configuration.screenHeightDp
    val context = LocalContext.current

    // The same editor remains usable in both orientations; only its density changes.
    val rowHeight = if (isPortrait) 15.dp else 17.dp
    val beatWidth = if (isPortrait) 32.dp else 48.dp
    val laneHeight = if (isPortrait) 54.dp else 66.dp
    val headerHeight = if (isPortrait) 42.dp else 34.dp
    val topToolsHeight = if (isPortrait) 38.dp else 32.dp
    val editHeight = if (isPortrait) 40.dp else 34.dp
    val transportHeight = if (isPortrait) 38.dp else 32.dp

    var selectedNoteId by remember(clip.id) {
        mutableStateOf(clip.notes.firstOrNull()?.id.orEmpty())
    }
    val selectedNote = clip.notes.firstOrNull { it.id == selectedNoteId }
    var lyricValue by remember(selectedNote?.id) { mutableStateOf(selectedNote?.lyric.orEmpty()) }

    val density = LocalDensity.current
    val beatWidthPx = with(density) { beatWidth.toPx() }
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val visibleBeats = (clip.lengthBars.coerceAtLeast(1) * 4)
    val contentWidth = beatWidth * visibleBeats
    val contentHeight = rowHeight * (MAX_PITCH - MIN_PITCH + 1).toFloat()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    fun updateNotesSafely(updatedNotes: List<VeaNote>) {
        if (!hasNoOverlappingNotes(updatedNotes)) {
            Toast.makeText(context, "Overlapping notes are disabled", Toast.LENGTH_SHORT).show()
            return
        }
        onNotesChanged(updatedNotes)
    }

    fun updateNote(updated: VeaNote) {
        updateNotesSafely(clip.notes.map { if (it.id == updated.id) updated else it })
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF17181B))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarIcon(VeaIcon.HOME, "BACK", modifier = Modifier.width(42.dp), onClick = onBack)
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(track.name + " / " + clip.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 12.sp)
                Text("${bpm} BPM", fontSize = 9.sp, color = Color.LightGray)
            }
            TextButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 7.dp)) {
                Text("SAVE", fontSize = 10.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(topToolsHeight)
                .background(Color(0xFF303238)).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarIcon(VeaIcon.GRID, "SNAP", modifier = Modifier.width(43.dp), selected = true)
            ToolbarIcon(VeaIcon.ACTION, "ACTION", modifier = Modifier.width(43.dp))
            ToolbarIcon(VeaIcon.CP_WINDOW, "CP WINDOW", modifier = Modifier.width(49.dp))
            ToolbarIcon(VeaIcon.KEYBOARD, "KEYBOARD", modifier = Modifier.width(49.dp))
            ToolbarIcon(VeaIcon.LOOP, "LOOP", modifier = Modifier.width(42.dp))
            ToolbarIcon(VeaIcon.AUTO_SCROLL, "AUTO SCROLL", modifier = Modifier.width(49.dp), selected = true)
            ToolbarIcon(VeaIcon.TONE, "TONE", modifier = Modifier.width(42.dp))
            Spacer(Modifier.weight(1f))
            Text("1/4", color = NoteGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(if (isPortrait) 39.dp else 48.dp))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalScroll)) {
                PianoRuler(width = contentWidth, beatWidth = beatWidth)
            }
        }

        Row(modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 2.dp).verticalScroll(verticalScroll)) {
            val keyWidth = if (isPortrait) 39.dp else 48.dp
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
                    onSelectNote = { selectedNoteId = it },
                    onAddNote = { candidate ->
                        if (canPlaceNote(clip.notes, candidate)) {
                            onNotesChanged(clip.notes + candidate)
                            selectedNoteId = candidate.id
                        } else {
                            Toast.makeText(context, "This time position is already occupied", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(editHeight).background(Color(0xFF222429)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("NOTE", color = NoteGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(pitch = (it.pitch - 1).coerceIn(MIN_PITCH, MAX_PITCH))) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("− PITCH", fontSize = 9.sp) }
            Text(selectedNote?.let { pitchName(it.pitch) } ?: "—", fontWeight = FontWeight.Bold, fontSize = 10.sp)
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(pitch = (it.pitch + 1).coerceIn(MIN_PITCH, MAX_PITCH))) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("+", fontSize = 10.sp) }
            TextButton(
                onClick = {
                    selectedNote?.let { note ->
                        updateNote(note.copy(lengthTick = (note.lengthTick - 120).coerceAtLeast(120)))
                    }
                },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) { Text("−LEN", fontSize = 9.sp) }
            TextButton(
                onClick = {
                    selectedNote?.let { note -> updateNote(note.copy(lengthTick = note.lengthTick + 120)) }
                },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) { Text("+LEN", fontSize = 9.sp) }
            TextButton(
                onClick = {
                    selectedNote?.let { note ->
                        onNotesChanged(clip.notes.filterNot { it.id == note.id })
                        selectedNoteId = ""
                    }
                },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 3.dp)
            ) { Text("DEL", color = Color(0xFFFF8A80), fontSize = 9.sp) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LYRIC", color = Color.LightGray, fontSize = 9.sp)
            Spacer(Modifier.width(5.dp))
            OutlinedTextField(
                value = lyricValue,
                onValueChange = { value ->
                    lyricValue = value
                    selectedNote?.let { updateNote(it.copy(lyric = value)) }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                enabled = selectedNote != null
            )
            Spacer(Modifier.width(6.dp))
            Text(selectedNote?.phoneme.orEmpty(), color = Color.LightGray, fontSize = 9.sp)
        }

        Text(
            "PITCH CURVE",
            color = NoteGreen,
            fontSize = 8.sp,
            modifier = Modifier.padding(start = 6.dp, top = 1.dp, bottom = 1.dp)
        )
        PitchLane(
            note = selectedNote,
            width = contentWidth,
            beatWidth = beatWidth,
            laneHeight = laneHeight,
            keyWidth = if (isPortrait) 39.dp else 48.dp,
            scrollState = horizontalScroll,
            onPointsChanged = { points ->
                selectedNote?.let { updateNote(it.copy(pitchPoints = points)) }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(45.dp).background(Color(0xFF111214)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ToolbarIcon(VeaIcon.PREVIOUS, "PREVIOUS", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.NEXT, "NEXT", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.STOP, "STOP", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.PLAY, "PLAY", modifier = Modifier.weight(1f), selected = true)
            ToolbarIcon(VeaIcon.RECORD, "REC", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.SUB_TOOL, "SUB TOOL", modifier = Modifier.weight(1f), selected = true)
            ToolbarIcon(VeaIcon.UNDO, "UNDO", modifier = Modifier.weight(1f))
            ToolbarIcon(VeaIcon.REDO, "REDO", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PianoRuler(width: Dp, beatWidth: Dp) {
    Box(modifier = Modifier.width(width).fillMaxHeight().background(Color(0xFF25272C))) {
        Canvas(Modifier.fillMaxSize()) {
            val beat = beatWidth.toPx()
            val barCount = (size.width / (beat * 4f)).toInt()
            for (bar in 0..barCount) {
                val x = bar * beat * 4f
                drawLine(Color(0xFF72757C), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            drawIntoCanvas { canvas ->
                val paint = AndroidPaint().apply {
                    color = android.graphics.Color.LTGRAY
                    textSize = 9.dp.toPx()
                    isAntiAlias = true
                }
                for (bar in 0..barCount) {
                    canvas.nativeCanvas.drawText((bar + 1).toString(), bar * beat * 4f + 5f, size.height * 0.75f, paint)
                }
            }
        }
    }
}

@Composable
private fun PianoKeyboard(modifier: Modifier = Modifier, rowHeight: Dp) {
    Canvas(modifier = modifier.background(Color(0xFF4A4C51))) {
        val rowPx = rowHeight.toPx()
        val blackKeys = setOf(1, 3, 6, 8, 10)
        for (row in 0..(MAX_PITCH - MIN_PITCH)) {
            val pitch = MAX_PITCH - row
            val y = row * rowPx
            drawRect(
                color = if (pitch % 12 in blackKeys) Color(0xFF4A4C51) else Color(0xFFF1F1F1),
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
    onSelectNote: (String) -> Unit,
    onAddNote: (VeaNote) -> Unit
) {
    Canvas(
        modifier = Modifier.width(width).height(height).background(EditorBackground)
            .pointerInput(notes, selectedNoteId) {
                detectTapGestures { position ->
                    val hit = notes.firstOrNull { note ->
                        val left = note.startTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                        val right = left + note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                        val top = (MAX_PITCH - note.pitch) * rowHeightPx
                        position.x >= left && position.x < right &&
                            position.y >= top && position.y < top + rowHeightPx
                    }
                    if (hit != null) {
                        onSelectNote(hit.id)
                    } else {
                        val beat = floor(position.x / beatWidthPx).toInt().coerceAtLeast(0)
                        val row = floor(position.y / rowHeightPx).toInt()
                        val pitch = (MAX_PITCH - row).coerceIn(MIN_PITCH, MAX_PITCH)
                        onAddNote(
                            VeaNote(
                                startTick = beat * TICKS_PER_BEAT,
                                lengthTick = TICKS_PER_BEAT,
                                pitch = pitch,
                                lyric = "",
                                phoneme = "",
                                pitchPoints = emptyList()
                            )
                        )
                    }
                }
            }
    ) {
        val rowHeight = rowHeightPx
        val beatWidth = beatWidthPx
        val blackKeys = setOf(1, 3, 6, 8, 10)

        for (row in 0..(MAX_PITCH - MIN_PITCH)) {
            val pitch = MAX_PITCH - row
            drawRect(
                color = if (pitch % 12 in blackKeys) Color(0xFF292B2F) else Color(0xFF34363B),
                topLeft = Offset(0f, row * rowHeight),
                size = Size(size.width, rowHeight - 1f)
            )
            drawLine(
                color = Color(0xFF45474D),
                start = Offset(0f, row * rowHeight),
                end = Offset(size.width, row * rowHeight),
                strokeWidth = 0.8f
            )
        }

        val totalBeats = (size.width / beatWidth).toInt()
        for (beat in 0..totalBeats) {
            val x = beat * beatWidth
            drawLine(
                color = if (beat % 4 == 0) Color(0xFF777A81) else Color(0xFF414349),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = if (beat % 4 == 0) 1.4f else 0.7f
            )
        }

        notes.forEach { note ->
            val left = note.startTick.toFloat() / TICKS_PER_BEAT * beatWidth
            val top = (MAX_PITCH - note.pitch) * rowHeight + 1f
            val noteWidth = (note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidth).coerceAtLeast(7f) - 1f
            drawRoundRect(
                color = NoteGreen,
                topLeft = Offset(left + 0.5f, top),
                size = Size(noteWidth, rowHeight - 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
            )
            if (note.id == selectedNoteId) {
                drawRoundRect(
                    color = Color(0xFFFF5CB8),
                    topLeft = Offset(left, top - 0.5f),
                    size = Size(noteWidth + 1f, rowHeight - 1f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                    style = Stroke(width = 1.5f)
                )
            }
            if (note.lyric.isNotBlank()) {
                drawIntoCanvas { canvas ->
                    val paint = AndroidPaint().apply {
                        color = android.graphics.Color.rgb(35, 40, 28)
                        textSize = 9.dp.toPx()
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    canvas.nativeCanvas.drawText(note.lyric, left + 3f, top + rowHeight * 0.72f, paint)
                }
            }
        }
    }
}

@Composable
private fun PitchLane(
    note: VeaNote?,
    width: Dp,
    beatWidth: Dp,
    laneHeight: Dp,
    keyWidth: Dp,
    scrollState: androidx.compose.foundation.ScrollState,
    onPointsChanged: (List<PitchPoint>) -> Unit
) {
    val density = LocalDensity.current
    val beatWidthPx = with(density) { beatWidth.toPx() }

    Row(modifier = Modifier.fillMaxWidth().height(laneHeight)) {
        Spacer(Modifier.width(keyWidth))
        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(scrollState)) {
            Canvas(
                modifier = Modifier.width(width).fillMaxHeight()
                    .pointerInput(note) {
                        detectTapGestures { position ->
                            val current = note ?: return@detectTapGestures
                            val absoluteTick = (position.x / beatWidthPx * TICKS_PER_BEAT).roundToInt()
                            val relativeTick = (absoluteTick - current.startTick).coerceIn(0, current.lengthTick)
                            val cents = ((size.height / 2f - position.y) / (size.height / 700f))
                                .roundToInt().coerceIn(-300, 300)
                            val point = PitchPoint(relativeTick, cents)
                            val updated = (
                                current.pitchPoints.filterNot { kotlin.math.abs(it.tick - relativeTick) <= 30 } + point
                            ).sortedBy { it.tick }
                            onPointsChanged(updated)
                        }
                    }
            ) {
                drawRect(Color(0xFF59624F))
                val centerY = size.height / 2f
                drawLine(Color(0xFFAAB39B), Offset(0f, centerY), Offset(size.width, centerY), 1f)
                val beat = beatWidthPx
                for (index in 0..(size.width / beat).toInt()) {
                    val x = index * beat
                    drawLine(
                        color = Color(0xFF7A836D),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = if (index % 4 == 0) 1.1f else 0.6f
                    )
                }
                val points = note?.pitchPoints.orEmpty().sortedBy { it.tick }
                if (points.size >= 2 && note != null) {
                    val offsets = points.map { point ->
                        Offset(
                            x = (note.startTick + point.tick).toFloat() / TICKS_PER_BEAT * beat,
                            y = centerY - point.cents.coerceIn(-300, 300) * (size.height / 700f)
                        )
                    }
                    for (index in 0 until offsets.lastIndex) {
                        drawLine(Color(0xFFDBE98B), offsets[index], offsets[index + 1], 2.2f)
                    }
                    offsets.forEach { drawCircle(Color(0xFFFF5CB8), radius = 3.5f, center = it) }
                }
            }
        }
    }
}

private fun pitchName(midi: Int): String {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[midi % 12] + (midi / 12 - 1)
}
