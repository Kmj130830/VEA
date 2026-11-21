package com.kmj.vea.ui.arrangement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack
import com.kmj.vea.data.VoicebankCatalog
import com.kmj.vea.data.MegpoidPortrait
import com.kmj.vea.ui.components.ToolbarIcon
import com.kmj.vea.ui.components.VeaIcon

private const val BAR_WIDTH_DP = 42
private const val TRACK_HEADER_DP = 88

@Composable
fun ArrangementScreen(
    project: VeaProject,
    selectedTrackId: String,
    selectedClipId: String,
    onSelectTrack: (String) -> Unit,
    onSelectClip: (String) -> Unit,
    onOpenClip: (String, String) -> Unit,
    onAddTrack: () -> Unit,
    onAddClip: () -> Unit,
    onSave: () -> Unit,
    onExportMidi: () -> Unit,
    onOpenSelectedClip: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onAssignVoice: (String, String, String, String, String) -> Unit,
    megpoidVoicebankSourceUri: String,
    isMegpoidModelInstalled: Boolean,
    isMegpoidInstallCheckComplete: Boolean,
    onImportMegpoidModel: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean
) {
    val selectedTrack = project.tracks.firstOrNull { it.id == selectedTrackId }
    val context = LocalContext.current
    val voicebank = remember(context) { VoicebankCatalog.loadMegpoid(context) }
    var voicePickerTrack by remember { mutableStateOf<VeaTrack?>(null) }
    var selectedVoiceStyleId by remember { mutableStateOf(voicebank.defaultStyleId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE7E7E7))
            .padding(horizontal = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).background(Color(0xFFD1D1D1)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("VEA", color = Color(0xFF596A3D), fontWeight = FontWeight.Black, fontSize = 19.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF222222))
                Text("${project.bpm} BPM    ${project.numerator}/${project.denominator}",
                    color = Color(0xFF555555), fontSize = 9.sp)
            }
            TextButton(onClick = onExportMidi, contentPadding = PaddingValues(horizontal = 5.dp)) {
                Text("V7 MIDI", fontSize = 10.sp)
            }
            TextButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 5.dp)) {
                Text("SAVE", fontSize = 10.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(25.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(TRACK_HEADER_DP.dp).fillMaxHeight().background(Color(0xFFC9CACC)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("TRACK", modifier = Modifier.padding(start = 8.dp), fontSize = 9.sp, color = Color(0xFF333333))
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
                TimelineRuler()
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (project.tracks.isEmpty()) {
                item { EmptyArrangementGrid() }
            } else {
                items(project.tracks, key = { it.id }) { track ->
                    ArrangementTrackRow(
                        track = track,
                        selected = track.id == selectedTrackId,
                        selectedClipId = selectedClipId,
                        onSelect = { onSelectTrack(track.id) },
                        onSelectClip = onSelectClip,
                        onOpenClip = { clip -> onOpenClip(track.id, clip.id) },
                        onOpenVoice = {
                            voicePickerTrack = track
                            selectedVoiceStyleId = track.singerStyleId.ifBlank { voicebank.defaultStyleId }
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(43.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape)
                    .background(Color(0xFF3C3F45))
                    .clickable(onClick = onAddTrack),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(22.dp)) {
                    drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width/2, 2f),
                        androidx.compose.ui.geometry.Offset(size.width/2, size.height-2f), 2f)
                    drawLine(Color.White, androidx.compose.ui.geometry.Offset(2f, size.height/2),
                        androidx.compose.ui.geometry.Offset(size.width-2f, size.height/2), 2f)
                }
            }
            Text("ADD TRACK", fontSize = 9.sp, color = Color.LightGray,
                modifier = Modifier.clickable(onClick = onAddTrack))
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onAddClip,
                enabled = selectedTrack != null && selectedTrack.kind != "AUDIO",
                contentPadding = PaddingValues(horizontal = 5.dp)
            ) { Text("ADD CLIP", fontSize = 9.sp) }
            TextButton(
                onClick = onOpenSelectedClip,
                enabled = selectedTrack?.clips?.any { it.id == selectedClipId } == true,
                contentPadding = PaddingValues(horizontal = 5.dp)
            ) { Text("PIANO ROLL", fontSize = 9.sp) }
        }

        ToolbarRow(
            items = listOf(
                VeaIcon.HISTORY to "HISTORY",
                VeaIcon.UNDO to "UNDO",
                VeaIcon.REDO to "REDO",
                VeaIcon.CONFIG to "CONFIG",
                VeaIcon.PROPERTY to "PROPERTY",
                VeaIcon.ARRANGE to "ARRANGE",
                VeaIcon.MIXER to "MIXER",
                VeaIcon.METRONOME to "METRONOME",
                VeaIcon.KEYBOARD to "KEYBOARD"
            ),
            selected = emptySet(),
            canUndo = canUndo,
            canRedo = canRedo,
            onAction = { label ->
                when (label) {
                    "UNDO" -> onUndo()
                    "REDO" -> onRedo()
                }
            }
        )
        ToolbarRow(
            items = listOf(
                VeaIcon.COPY to "COPY",
                VeaIcon.PASTE to "PASTE",
                VeaIcon.ERASER to "ERASE",
                VeaIcon.LOOP to "LOOP",
                VeaIcon.AUTO_SCROLL to "AUTO SCROLL",
                VeaIcon.EDIT to "EDIT",
                VeaIcon.ACTION to "ACTION",
                VeaIcon.GRID to "1/1"
            ),
            selected = setOf("AUTO SCROLL"),
            canUndo = canUndo,
            canRedo = canRedo,
            onAction = {}
        )
        ToolbarRow(
            items = listOf(
                VeaIcon.HOME to "HOME",
                VeaIcon.PREVIOUS to "PREVIOUS",
                VeaIcon.NEXT to "NEXT",
                VeaIcon.STOP to "STOP",
                VeaIcon.PLAY to "PLAY",
                VeaIcon.RECORD to "REC",
                VeaIcon.SUB_TOOL to "SUB TOOL",
                VeaIcon.GRID to "SNAP"
            ),
            selected = setOf("SUB TOOL", "SNAP"),
            canUndo = canUndo,
            canRedo = canRedo,
            onAction = { label ->
                when (label) {
                    "PLAY" -> onPlay()
                    "STOP" -> onStop()
                }
            }
        )
    }

    val targetTrack = voicePickerTrack
    if (targetTrack != null) {
        AlertDialog(
            onDismissRequest = { voicePickerTrack = null },
            title = { Text("Select Voicebank") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MegpoidPortrait(68.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(voicebank.displayName, fontWeight = FontWeight.Bold)
                            Text("VOCALOID 7 · " + voicebank.version, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            Text(voicebank.languages.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                    Divider()
                    Text("VOICE STYLE", style = MaterialTheme.typography.labelSmall)
                    voicebank.styles.forEach { style ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable { selectedVoiceStyleId = style.id }
                                .padding(vertical = 3.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedVoiceStyleId == style.id,
                                onClick = { selectedVoiceStyleId = style.id }
                            )
                            Text(style.name, fontSize = 13.sp)
                        }
                    }
                    Text(
                        "Singer identity and styles are loaded from the supplied package. VOCALOID rendering still requires an authorized Android engine.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Divider()
                    Text(
                        when {
                            !isMegpoidInstallCheckComplete -> "MODEL FILES: CHECKING..."
                            isMegpoidModelInstalled -> "MODEL FILES: VERIFIED IN APP STORAGE"
                            else -> "MODEL FILES: NOT INSTALLED"
                        },
                        color = if (isMegpoidModelInstalled) Color(0xFF6A9A42)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(
                        onClick = onImportMegpoidModel,
                        enabled = isMegpoidInstallCheckComplete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isMegpoidModelInstalled) "REIMPORT MODEL FILES" else "IMPORT MODEL FILES")
                    }
                    Text(
                        "Select megpoidV7.zip first, then AI Model.zip. The app installs only files that pass SHA-256 and container-header checks.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = isMegpoidModelInstalled && megpoidVoicebankSourceUri.isNotBlank(),
                    onClick = {
                    onAssignVoice(
                        targetTrack.id,
                        voicebank.id,
                        voicebank.displayName,
                        selectedVoiceStyleId,
                        megpoidVoicebankSourceUri
                    )
                    voicePickerTrack = null
                }) { Text("LOAD VOICE") }
            },
            dismissButton = {
                TextButton(onClick = { voicePickerTrack = null }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
private fun ToolbarRow(
    items: List<Pair<VeaIcon, String>>,
    selected: Set<String>,
    canUndo: Boolean,
    canRedo: Boolean,
    onAction: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(49.dp)
            .background(Color(0xFF111214))
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (icon, label) ->
            val enabled = when (label) {
                "UNDO" -> canUndo
                "REDO" -> canRedo
                else -> true
            }
            ToolbarIcon(
                icon = icon,
                label = label,
                modifier = Modifier.width(43.dp),
                selected = label in selected,
                enabled = enabled,
                onClick = { onAction(label) }
            )
        }
    }
}

@Composable
private fun EmptyArrangementGrid() {
    Row(modifier = Modifier.fillMaxWidth().height(170.dp).background(Color(0xFFE7E7E7))) {
        Spacer(
            modifier = Modifier.width(TRACK_HEADER_DP.dp).fillMaxHeight()
                .background(Color(0xFFE7E7E7))
        )
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val barWidth = BAR_WIDTH_DP.dp.toPx()
            for (bar in 0..24) {
                val x = bar * barWidth
                drawLine(
                    color = if (bar % 4 == 0) Color(0xFFB5B7BA) else Color(0xFFD0D2D4),
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = if (bar % 4 == 0) 1.2f else 0.7f
                )
            }
        }
    }
}

@Composable
private fun TimelineRuler() {
    Box(modifier = Modifier.width(1260.dp).fillMaxHeight().background(Color(0xFF303238))) {
        Canvas(Modifier.fillMaxSize()) {
            val barWidth = BAR_WIDTH_DP.dp.toPx()
            for (bar in 0..30) {
                val x = bar * barWidth
                drawLine(
                    color = if (bar % 8 == 0) Color(0xFF72757C) else Color(0xFF414349),
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = if (bar % 8 == 0) 1.4f else 0.8f
                )
            }
        }
        for (bar in listOf(1, 9, 17, 25)) {
            Text(
                text = bar.toString(),
                modifier = Modifier.offset(x = BAR_WIDTH_DP.dp * (bar - 1).toFloat()).padding(start = 5.dp, top = 2.dp),
                fontSize = 10.sp,
                color = Color(0xFFE7E7E7)
            )
        }
    }
}

@Composable
private fun ArrangementTrackRow(
    track: VeaTrack,
    selected: Boolean,
    selectedClipId: String,
    onSelect: () -> Unit,
    onSelectClip: (String) -> Unit,
    onOpenClip: (VeaClip) -> Unit,
    onOpenVoice: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(76.dp).background(Color(0xFFE0E1E2))) {
        Column(
            modifier = Modifier.width(TRACK_HEADER_DP.dp).fillMaxHeight()
                .background(if (selected) Color(0xFFBFC1C3) else Color(0xFF303238))
                .clickable(onClick = onSelect)
                .padding(start = 8.dp, top = 9.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(track.name, color = Color(0xFF222222), fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (track.kind == "AUDIO") {
                Text("AUDIO", fontSize = 8.sp, color = Color(0xFF555555))
            } else {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF45494F))
                        .clickable(onClick = onOpenVoice)
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (track.singerName.isNotBlank()) MegpoidPortrait(19.dp)
                    Text(
                        track.singerName.ifBlank { "VOCAL AI" },
                        fontSize = 8.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            Box(modifier = Modifier.width(1260.dp).fillMaxHeight().background(Color(0xFFECEDEE))) {
                Canvas(Modifier.fillMaxSize()) {
                    val barWidth = BAR_WIDTH_DP.dp.toPx()
                    for (bar in 0..30) {
                        val x = bar * barWidth
                        drawLine(
                            color = if (bar % 4 == 0) Color(0xFFB7B9BC) else Color(0xFF34363B),
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = if (bar % 4 == 0) 1.2f else 0.7f
                        )
                    }
                }
                track.clips.forEach { clip ->
                    val isSelected = clip.id == selectedClipId
                    Box(
                        modifier = Modifier
                            .offset(x = BAR_WIDTH_DP.dp * (clip.startBar - 1).toFloat())
                            .padding(horizontal = 2.dp, vertical = 6.dp)
                            .width(BAR_WIDTH_DP.dp * clip.lengthBars.toFloat() - 4.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(track.color.toInt()))
                            .then(
                                if (isSelected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(3.dp))
                                else Modifier
                            )
                            .pointerInput(clip.id) {
                                detectTapGestures(
                                    onTap = { onSelectClip(clip.id) },
                                    onDoubleTap = { onOpenClip(clip) }
                                )
                            }
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val totalTicks = (clip.lengthBars * 1920).coerceAtLeast(1).toFloat()
                            clip.notes.forEach { note ->
                                val left = (note.startTick / totalTicks * size.width).coerceIn(0f, size.width)
                                val right = ((note.startTick + note.lengthTick) / totalTicks * size.width)
                                    .coerceAtLeast(left + 2f).coerceAtMost(size.width)
                                val normalizedPitch = ((note.pitch - 48f) / 24f).coerceIn(0f, 1f)
                                val y = (1f - normalizedPitch) * (size.height - 13f) + 8f
                                drawRoundRect(
                                    color = Color(0xFFE7F6B8),
                                    topLeft = androidx.compose.ui.geometry.Offset(left, y),
                                    size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(2f), 3.5f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                                )
                            }
                        }
                        Text(
                            clip.name,
                            modifier = Modifier.align(Alignment.TopStart),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
