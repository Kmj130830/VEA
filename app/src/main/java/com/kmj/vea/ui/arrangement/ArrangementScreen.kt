package com.kmj.vea.ui.arrangement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    onOpenSelectedClip: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean
) {
    val selectedTrack = project.tracks.firstOrNull { it.id == selectedTrackId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF17181B))
            .padding(horizontal = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("VEA", color = Color(0xFFB5E853), fontWeight = FontWeight.Black, fontSize = 19.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("${project.bpm} BPM    ${project.numerator}/${project.denominator}",
                    color = Color.LightGray, fontSize = 9.sp)
            }
            TextButton(onClick = onSave, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("SAVE", fontSize = 10.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(25.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(TRACK_HEADER_DP.dp).fillMaxHeight().background(Color(0xFF303238)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("TRACK", modifier = Modifier.padding(start = 8.dp), fontSize = 9.sp, color = Color.LightGray)
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
                        onOpenClip = { clip -> onOpenClip(track.id, clip.id) }
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
            onAction = {}
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
    Row(modifier = Modifier.fillMaxWidth().height(170.dp).background(Color(0xFF202226))) {
        Box(
            modifier = Modifier.width(TRACK_HEADER_DP.dp).fillMaxHeight().background(Color(0xFF303238)),
            contentAlignment = Alignment.Center
        ) {
            Text("—", color = Color(0xFF77797E), fontSize = 18.sp)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val barWidth = BAR_WIDTH_DP.dp.toPx()
            for (bar in 0..24) {
                val x = bar * barWidth
                drawLine(
                    color = if (bar % 4 == 0) Color(0xFF565960) else Color(0xFF34363B),
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
    onOpenClip: (VeaClip) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().height(76.dp).background(Color(0xFF1F2024))) {
        Column(
            modifier = Modifier.width(TRACK_HEADER_DP.dp).fillMaxHeight()
                .background(if (selected) Color(0xFF41444A) else Color(0xFF303238))
                .clickable(onClick = onSelect)
                .padding(start = 8.dp, top = 9.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(track.name, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (track.kind == "AUDIO") "AUDIO" else "VOCAL AI",
                fontSize = 8.sp, color = Color.LightGray)
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
            Box(modifier = Modifier.width(1260.dp).fillMaxHeight().background(Color(0xFF222429))) {
                Canvas(Modifier.fillMaxSize()) {
                    val barWidth = BAR_WIDTH_DP.dp.toPx()
                    for (bar in 0..30) {
                        val x = bar * barWidth
                        drawLine(
                            color = if (bar % 4 == 0) Color(0xFF4D5056) else Color(0xFF34363B),
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
                        Text(clip.name, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
