package com.kmj.vea.ui.arrangement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kmj.vea.data.VeaClip
import com.kmj.vea.data.VeaProject
import com.kmj.vea.data.VeaTrack

private val Panel = Color(0xFF25272C)
private val GridLine = Color(0xFF414349)
private const val BAR_WIDTH_DP = 48

@Composable
fun ArrangementScreen(
    project: VeaProject,
    selectedTrackId: String,
    onSelectTrack: (String) -> Unit,
    onOpenClip: (String, String) -> Unit,
    onAddTrack: () -> Unit,
    onAddClip: () -> Unit,
    onSave: () -> Unit,
    onOpenSelectedClip: () -> Unit
) {
    val selectedTrack = project.tracks.firstOrNull { it.id == selectedTrackId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF17181B))
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("VEA", color = Color(0xFFB5E853), fontWeight = FontWeight.Black, fontSize = 21.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(project.bpm.toString() + " BPM  •  " + project.numerator + "/" + project.denominator,
                    color = Color.LightGray, fontSize = 11.sp)
            }
            TextButton(onClick = onSave) { Text("SAVE") }
            TextButton(
                onClick = onOpenSelectedClip,
                enabled = selectedTrack?.clips?.isNotEmpty() == true
            ) { Text("PIANO ROLL") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp)
                .clip(RoundedCornerShape(8.dp)).background(Panel).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("◀", color = Color.LightGray)
            Spacer(Modifier.width(18.dp))
            Text("■", color = Color.LightGray)
            Spacer(Modifier.width(18.dp))
            Text("▶", color = Color(0xFFB5E853), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            Text("REC", color = Color.LightGray)
            Spacer(Modifier.weight(1f))
            Text("BAR  1  |  BEAT  1", color = Color(0xFFB5E853), fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(104.dp).fillMaxHeight().background(Color(0xFF303238)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("TRACKS", modifier = Modifier.padding(start = 10.dp), fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, color = Color.LightGray)
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())
            ) {
                TimelineRuler()
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(project.tracks, key = { it.id }) { track ->
                ArrangementTrackRow(
                    track = track,
                    selected = track.id == selectedTrackId,
                    onSelect = { onSelectTrack(track.id) },
                    onOpenClip = { clip -> onOpenClip(track.id, clip.id) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(62.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onAddTrack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34373D))
            ) { Text("+ TRACK") }
            Button(
                onClick = onAddClip,
                modifier = Modifier.weight(1f),
                enabled = selectedTrack?.kind != "AUDIO",
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34373D))
            ) { Text("+ CLIP") }
        }
    }
}

@Composable
private fun TimelineRuler() {
    Box(
        modifier = Modifier.width(1920.dp).fillMaxHeight().background(Color(0xFF303238))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barWidth = BAR_WIDTH_DP.dp.toPx()
            for (bar in 0..40) {
                val x = bar * barWidth
                drawLine(
                    color = if (bar % 8 == 0) Color(0xFF72757C) else GridLine,
                    start = androidx.compose.ui.geometry.Offset(x, 0f),
                    end = androidx.compose.ui.geometry.Offset(x, size.height),
                    strokeWidth = if (bar % 8 == 0) 1.5f else 1f
                )
            }
        }
        for (bar in listOf(1, 9, 17, 25, 33, 41)) {
            Text(
                text = bar.toString(),
                modifier = Modifier.offset(x = BAR_WIDTH_DP.dp * (bar - 1).toFloat()).padding(start = 5.dp, top = 4.dp),
                fontSize = 11.sp,
                color = Color(0xFFE7E7E7)
            )
        }
    }
}

@Composable
private fun ArrangementTrackRow(
    track: VeaTrack,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpenClip: (VeaClip) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(82.dp).background(Color(0xFF1F2024))
    ) {
        Column(
            modifier = Modifier.width(104.dp).fillMaxHeight()
                .background(if (selected) Color(0xFF41444A) else Color(0xFF303238))
                .clickable(onClick = onSelect)
                .padding(start = 10.dp, top = 10.dp, end = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(track.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (track.kind == "AUDIO") "AUDIO" else "VOCAL AI",
                fontSize = 9.sp, color = Color.LightGray)
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier.width(1920.dp).fillMaxHeight().background(Color(0xFF222429))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val barWidth = BAR_WIDTH_DP.dp.toPx()
                    for (bar in 0..40) {
                        val x = bar * barWidth
                        drawLine(
                            color = if (bar % 4 == 0) Color(0xFF4D5056) else Color(0xFF34363B),
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(x, size.height),
                            strokeWidth = if (bar % 4 == 0) 1.3f else 0.7f
                        )
                    }
                    drawLine(
                        color = Color(0xFF37393F),
                        start = androidx.compose.ui.geometry.Offset(0f, size.height - 1f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height - 1f),
                        strokeWidth = 1f
                    )
                }

                track.clips.forEach { clip ->
                    Box(
                        modifier = Modifier
                            .offset(x = BAR_WIDTH_DP.dp * (clip.startBar - 1).toFloat())
                            .padding(horizontal = 3.dp, vertical = 7.dp)
                            .width(BAR_WIDTH_DP.dp * clip.lengthBars.toFloat() - 6.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(track.color.toInt()))
                            .clickable { onOpenClip(clip) }
                            .padding(horizontal = 7.dp, vertical = 5.dp)
                    ) {
                        Column {
                            Text(clip.name, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (track.kind == "AUDIO") "Audio region" else "VOCAL AI",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.82f)
                            )
                        }
                    }
                }
            }
        }
    }
}
