package com.kmj.vea.ui.pianoroll

import android.graphics.Paint as AndroidPaint
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmj.vea.data.PitchPoint
import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaNote
import com.kmj.vea.data.VeaTrack
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MIN_PITCH = 48
private const val MAX_PITCH = 83
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
    val compact = LocalConfiguration.current.screenHeightDp < 420
    val rowHeight = if (compact) 14.dp else 20.dp
    val laneHeight = if (compact) 56.dp else 78.dp
    val headerHeight = if (compact) 40.dp else 48.dp
    val toolbarHeight = if (compact) 28.dp else 34.dp
    val rulerHeight = if (compact) 20.dp else 23.dp
    val controlHeight = if (compact) 38.dp else 43.dp
    val transportHeight = if (compact) 30.dp else 36.dp

    var selectedNoteId by remember(clip.id) {
        mutableStateOf(clip.notes.firstOrNull()?.id.orEmpty())
    }
    val selectedNote = clip.notes.firstOrNull { it.id == selectedNoteId }
    var lyricValue by remember(selectedNote?.id) { mutableStateOf(selectedNote?.lyric.orEmpty()) }

    val density = LocalDensity.current
    val beatWidthPx = with(density) { 48.dp.toPx() }
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val contentWidth = 48.dp * (clip.lengthBars * 4).coerceAtLeast(16).toFloat()
    val contentHeight = rowHeight * (MAX_PITCH - MIN_PITCH + 1).toFloat()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    fun updateNote(updated: VeaNote) {
        onNotesChanged(clip.notes.map { if (it.id == updated.id) updated else it })
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF17181B)).padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(headerHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                Text("‹ BACK")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(track.name + " / " + clip.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 13.sp)
                Text(bpm.toString() + " BPM  •  " + track.kind, fontSize = 9.sp, color = Color.LightGray)
            }
            TextButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                Text("SAVE")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(toolbarHeight)
                .clip(RoundedCornerShape(6.dp)).background(Color(0xFF303238))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("SNAP 1/4", color = NoteGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("LYRIC / PHONEME", color = Color.LightGray, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text("VOCALOID AI · ENGINE OFFLINE", color = Color(0xFFFFC36B), fontSize = 8.sp)
        }

        Row(modifier = Modifier.fillMaxWidth().height(rulerHeight), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontalScroll)) {
                PianoRuler(width = contentWidth)
            }
        }

        Row(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(verticalScroll)) {
            PianoKeyboard(modifier = Modifier.width(48.dp).height(contentHeight), rowHeight = rowHeight)
            Box(modifier = Modifier.weight(1f).horizontalScroll(horizontalScroll)) {
                NoteGrid(
                    notes = clip.notes,
                    selectedNoteId = selectedNoteId,
                    width = contentWidth,
                    height = contentHeight,
                    beatWidthPx = beatWidthPx,
                    rowHeightPx = rowHeightPx,
                    onSelectNote = { selectedNoteId = it },
                    onAddNote = { note ->
                        onNotesChanged(clip.notes + note)
                        selectedNoteId = note.id
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(controlHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("NOTE", fontSize = 9.sp, color = NoteGreen, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(pitch = (it.pitch - 1).coerceIn(MIN_PITCH, MAX_PITCH))) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("−") }
            Text(selectedNote?.let { pitchName(it.pitch) } ?: "—", fontWeight = FontWeight.Bold, fontSize = 10.sp)
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(pitch = (it.pitch + 1).coerceIn(MIN_PITCH, MAX_PITCH))) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("+") }
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(lengthTick = (it.lengthTick - 120).coerceAtLeast(120))) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("−LEN") }
            TextButton(
                onClick = { selectedNote?.let { updateNote(it.copy(lengthTick = it.lengthTick + 120)) } },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("+LEN") }
            OutlinedTextField(
                value = lyricValue,
                onValueChange = { value ->
                    lyricValue = value
                    selectedNote?.let { updateNote(it.copy(lyric = value)) }
                },
                modifier = Modifier.weight(1f).height(controlHeight - 2.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                                enabled = selectedNote != null
            )
            TextButton(
                onClick = {
                    selectedNote?.let { note ->
                        onNotesChanged(clip.notes.filterNot { it.id == note.id })
                        selectedNoteId = ""
                    }
                },
                enabled = selectedNote != null,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("DEL", color = Color(0xFFFF8A80), fontSize = 10.sp) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selectedNote?.let { "PHONEME  " + it.phoneme } ?: "Select a note",
                color = Color.LightGray,
                fontSize = 9.sp,
                modifier = Modifier.padding(start = 50.dp).weight(1f)
            )
            Text("PITCH CURVE · TAP TO ADD POINT", color = NoteGreen, fontSize = 8.sp)
        }

        PitchLane(
            note = selectedNote,
            width = contentWidth,
            laneHeight = laneHeight,
            scrollState = horizontalScroll,
            onPointsChanged = { points ->
                selectedNote?.let { updateNote(it.copy(pitchPoints = points)) }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(transportHeight).background(Color(0xFF222429)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Text("◀ PREV", color = Color.LightGray, fontSize = 9.sp)
            Text("■ STOP", color = Color.LightGray, fontSize = 9.sp)
            Text("▶ PLAY", color = NoteGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("1/4 SNAP", color = NoteGreen, fontSize = 9.sp)
            Text("UNDO", color = Color.LightGray, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PianoRuler(width: Dp) {
    Box(modifier = Modifier.width(width).fillMaxHeight().background(Color(0xFF25272C))) {
        Canvas(Modifier.fillMaxSize()) {
            val beatWidth = 48.dp.toPx()
            val barCount = (size.width / (beatWidth * 4f)).toInt()
            for (bar in 0..barCount) {
                val x = bar * beatWidth * 4f
                drawLine(Color(0xFF72757C), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            drawIntoCanvas { canvas ->
                val paint = AndroidPaint().apply {
                    color = android.graphics.Color.LTGRAY
                    textSize = 10.dp.toPx()
                    isAntiAlias = true
                }
                for (bar in 0..barCount) {
                    canvas.nativeCanvas.drawText((bar + 1).toString(), bar * beatWidth * 4f + 5f, size.height * 0.72f, paint)
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
                        textSize = 10.dp.toPx()
                        isAntiAlias = true
                    }
                    canvas.nativeCanvas.drawText(
                        "C" + (pitch / 12 - 1),
                        size.width - 24.dp.toPx(),
                        y + rowPx * 0.68f,
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
                    val hit = notes.lastOrNull { note ->
                        val left = note.startTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                        val right = left + note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidthPx
                        val top = (MAX_PITCH - note.pitch) * rowHeightPx
                        position.x in left..right && position.y in top..(top + rowHeightPx)
                    }
                    if (hit != null) {
                        onSelectNote(hit.id)
                    } else {
                        val beat = floor(position.x / beatWidthPx).toInt().coerceAtLeast(0)
                        val row = floor(position.y / rowHeightPx).toInt()
                        val pitch = (MAX_PITCH - row).coerceIn(MIN_PITCH, MAX_PITCH)
                        val newNote = VeaNote(
                            startTick = beat * TICKS_PER_BEAT,
                            lengthTick = TICKS_PER_BEAT,
                            pitch = pitch,
                            lyric = "あ",
                            phoneme = "a",
                            pitchPoints = listOf(PitchPoint(0, 0), PitchPoint(TICKS_PER_BEAT, 0))
                        )
                        onAddNote(newNote)
                    }
                }
            }
    ) {
        val rowHeight = rowHeightPx
        val beatWidth = beatWidthPx
        val blackKeys = setOf(1, 3, 6, 8, 10)

        for (row in 0..(MAX_PITCH - MIN_PITCH)) {
            val pitch = MAX_PITCH - row
            val isBlack = pitch % 12 in blackKeys
            drawRect(
                color = if (isBlack) Color(0xFF292B2F) else Color(0xFF34363B),
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
            val top = (MAX_PITCH - note.pitch) * rowHeight + 2f
            val noteWidth = (note.lengthTick.toFloat() / TICKS_PER_BEAT * beatWidth).coerceAtLeast(10f) - 2f
            drawRoundRect(
                color = NoteGreen,
                topLeft = Offset(left + 1f, top),
                size = Size(noteWidth, rowHeight - 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
            if (note.id == selectedNoteId) {
                drawRoundRect(
                    color = Color(0xFFFF5CB8),
                    topLeft = Offset(left, top - 1f),
                    size = Size(noteWidth + 2f, rowHeight - 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
                    style = Stroke(width = 2f)
                )
            }
            drawIntoCanvas { canvas ->
                val paint = AndroidPaint().apply {
                    color = android.graphics.Color.rgb(35, 40, 28)
                    textSize = 10.dp.toPx()
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.nativeCanvas.drawText(note.lyric, left + 5f, top + rowHeight * 0.68f, paint)
            }
        }
    }
}

@Composable
private fun PitchLane(
    note: VeaNote?,
    width: Dp,
    laneHeight: Dp,
    scrollState: androidx.compose.foundation.ScrollState,
    onPointsChanged: (List<PitchPoint>) -> Unit
) {
    val density = LocalDensity.current
    val beatWidthPx = with(density) { 48.dp.toPx() }

    Row(modifier = Modifier.fillMaxWidth().height(laneHeight)) {
        Spacer(Modifier.width(48.dp))
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
                for (beat in 0..(size.width / beatWidthPx).toInt()) {
                    val x = beat * beatWidthPx
                    drawLine(
                        color = Color(0xFF7A836D),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = if (beat % 4 == 0) 1.2f else 0.6f
                    )
                }

                val points = note?.pitchPoints.orEmpty().sortedBy { it.tick }
                if (points.size >= 2 && note != null) {
                    val offsets = points.map { point ->
                        Offset(
                            x = (note.startTick + point.tick).toFloat() / TICKS_PER_BEAT * beatWidthPx,
                            y = centerY - point.cents.coerceIn(-300, 300) * (size.height / 700f)
                        )
                    }
                    for (index in 0 until offsets.lastIndex) {
                        drawLine(Color(0xFFDBE98B), offsets[index], offsets[index + 1], 2.5f)
                    }
                    offsets.forEach { drawCircle(Color(0xFFFF5CB8), radius = 4f, center = it) }
                }
            }
        }
    }
}

private fun pitchName(midi: Int): String {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[midi % 12] + (midi / 12 - 1)
}
