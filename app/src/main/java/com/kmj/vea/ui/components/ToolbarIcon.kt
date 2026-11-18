package com.kmj.vea.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class VeaIcon {
    HISTORY, UNDO, REDO, CONFIG, PROPERTY, ARRANGE, MIXER, METRONOME, KEYBOARD,
    COPY, PASTE, ERASER, LOOP, AUTO_SCROLL, EDIT, ACTION, CP_WINDOW, GRID,
    HOME, PREVIOUS, NEXT, STOP, PLAY, RECORD, SUB_TOOL, TONE, CLOSE, LOCK, UNLOCK, ADD, SELECT, PITCH
}

@Composable
fun ToolbarIcon(
    icon: VeaIcon,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .height(48.dp)
            .background(if (selected) Color(0xFF3A452E) else Color.Transparent, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Canvas(Modifier.size(22.dp)) {
            drawVeaIcon(icon, size.width, size.height, if (enabled) Color(0xFFE1E2E3) else Color(0xFF77797E))
        }
        Text(
            label,
            color = if (selected) Color(0xFFB5E853) else if (enabled) Color(0xFFE1E2E3) else Color(0xFF77797E),
            fontSize = 7.sp,
            maxLines = 1
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVeaIcon(
    icon: VeaIcon,
    width: Float,
    height: Float,
    color: Color
) {
    val sx = width / 24f
    val sy = height / 24f
    fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, stroke: Float = 1.8f) {
        drawLine(color, p(x1, y1), p(x2, y2), stroke * sx, cap = StrokeCap.Round)
    }
    fun rect(l: Float, t: Float, r: Float, b: Float, stroke: Float = 1.7f) {
        drawRect(color, p(l, t), Size((r-l)*sx, (b-t)*sy), style = Stroke(stroke * sx))
    }
    fun circle(x: Float, y: Float, radius: Float, stroke: Float = 1.8f) {
        drawCircle(color, radius * sx, p(x, y), style = Stroke(stroke * sx))
    }
    fun path(points: List<Pair<Float, Float>>, close: Boolean = false, stroke: Float = 1.8f) {
        val shape = Path().apply {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.first * sx, point.second * sy)
                else lineTo(point.first * sx, point.second * sy)
            }
            if (close) close()
        }
        drawPath(shape, color, style = Stroke(stroke * sx, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }

    when (icon) {
        VeaIcon.HISTORY -> {
            rect(5f, 3f, 19f, 21f); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 14f, 16f)
        }
        VeaIcon.UNDO -> { path(listOf(11f to 5f, 5f to 10f, 11f to 15f)); line(5f, 10f, 14f, 10f); path(listOf(14f to 10f, 19f to 12f, 20f to 17f)) }
        VeaIcon.REDO -> { path(listOf(13f to 5f, 19f to 10f, 13f to 15f)); line(19f, 10f, 10f, 10f); path(listOf(10f to 10f, 5f to 12f, 4f to 17f)) }
        VeaIcon.CONFIG -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f); circle(9f, 6f, 2f); circle(15f, 12f, 2f); circle(7f, 18f, 2f) }
        VeaIcon.PROPERTY -> { circle(10f, 10f, 6f); line(14.5f, 14.5f, 21f, 21f); line(8f, 10f, 12f, 10f) }
        VeaIcon.ARRANGE -> { path(listOf(3f to 7f, 8f to 7f, 10f to 10f, 21f to 10f, 21f to 20f, 3f to 20f), true); path(listOf(3f to 7f, 3f to 4f, 9f to 4f, 11f to 7f), false) }
        VeaIcon.MIXER -> { line(5f, 3f, 5f, 21f); line(12f, 3f, 12f, 21f); line(19f, 3f, 19f, 21f); rect(2.5f, 7f, 7.5f, 10f); rect(9.5f, 14f, 14.5f, 17f); rect(16.5f, 5f, 21.5f, 8f) }
        VeaIcon.METRONOME -> { path(listOf(6f to 21f, 9f to 4f, 16f to 4f, 20f to 21f, 6f to 21f), true); line(13f, 17f, 16f, 7f); circle(13f, 17f, 1f) }
        VeaIcon.KEYBOARD -> { rect(2f, 5f, 22f, 19f); line(7f, 5f, 7f, 19f); line(12f, 5f, 12f, 19f); line(17f, 5f, 17f, 19f); line(4f, 12f, 5f, 12f); line(9f, 12f, 10f, 12f); line(14f, 12f, 15f, 12f); line(19f, 12f, 20f, 12f) }
        VeaIcon.COPY -> { rect(7f, 3f, 20f, 17f); rect(3f, 7f, 16f, 21f) }
        VeaIcon.PASTE -> { path(listOf(8f to 5f, 5f to 5f, 5f to 21f, 20f to 21f, 20f to 5f, 16f to 5f)); rect(9f, 3f, 15f, 8f); line(8f, 12f, 17f, 12f); line(8f, 16f, 17f, 16f) }
        VeaIcon.ERASER -> { path(listOf(4f to 14f, 12f to 4f, 21f to 12f, 13f to 21f, 4f to 14f), true); line(9f, 17f, 18f, 17f) }
        VeaIcon.LOOP -> { path(listOf(6f to 8f, 8f to 5f, 17f to 5f, 20f to 8f)); path(listOf(18f to 16f, 16f to 19f, 7f to 19f, 4f to 16f)); line(20f, 8f, 20f, 13f); line(4f, 11f, 4f, 16f) }
        VeaIcon.AUTO_SCROLL -> { line(7f, 4f, 7f, 20f); path(listOf(12f to 7f, 17f to 12f, 12f to 17f)); line(17f, 12f, 9f, 12f) }
        VeaIcon.EDIT -> { path(listOf(4f to 18f, 5f to 21f, 8f to 20f, 20f to 8f, 16f to 4f, 4f to 18f), true); line(14f, 6f, 18f, 10f) }
        VeaIcon.ACTION -> { rect(3f, 3f, 21f, 21f); line(9f, 3f, 9f, 21f); line(15f, 3f, 15f, 21f); line(3f, 9f, 21f, 9f); line(3f, 15f, 21f, 15f) }
        VeaIcon.CP_WINDOW -> { rect(3f, 5f, 20f, 19f); line(3f, 9f, 20f, 9f); rect(6f, 2f, 17f, 5f) }
        VeaIcon.GRID -> { rect(3f, 3f, 21f, 21f); line(9f, 3f, 9f, 21f); line(15f, 3f, 15f, 21f); line(3f, 9f, 21f, 9f); line(3f, 15f, 21f, 15f) }
        VeaIcon.HOME -> { path(listOf(3f to 11f, 12f to 3f, 21f to 11f)); path(listOf(6f to 10f, 6f to 21f, 18f to 21f, 18f to 10f)); rect(10f, 14f, 14f, 21f) }
        VeaIcon.PREVIOUS -> { path(listOf(15f to 4f, 7f to 12f, 15f to 20f), true); line(4f, 4f, 4f, 20f) }
        VeaIcon.NEXT -> { path(listOf(9f to 4f, 17f to 12f, 9f to 20f), true); line(20f, 4f, 20f, 20f) }
        VeaIcon.STOP -> drawRect(color, p(5f, 5f), Size(14f*sx, 14f*sy))
        VeaIcon.PLAY -> path(listOf(7f to 3f, 20f to 12f, 7f to 21f), true)
        VeaIcon.RECORD -> {
            circle(12f, 12f, 8f)
            drawCircle(color, 4f * sx, p(12f, 12f))
        }
        VeaIcon.SUB_TOOL -> { path(listOf(3f to 18f, 7f to 7f, 12f to 15f, 17f to 5f, 21f to 18f)); line(4f, 21f, 20f, 21f) }
        VeaIcon.TONE -> { path(listOf(4f to 10f, 8f to 10f, 14f to 5f, 14f to 19f, 8f to 14f, 4f to 14f), true); path(listOf(17f to 9f, 20f to 12f, 17f to 15f)) }
        VeaIcon.CLOSE -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
        VeaIcon.LOCK -> {
            rect(4f, 10f, 20f, 21f)
            path(listOf(7f to 10f, 7f to 7f, 9f to 4f, 15f to 4f, 17f to 7f, 17f to 10f))
            circle(12f, 15f, 1.2f)
        }
        VeaIcon.UNLOCK -> {
            rect(4f, 10f, 20f, 21f)
            path(listOf(7f to 10f, 7f to 7f, 9f to 4f, 15f to 4f, 17f to 7f))
            line(17f, 7f, 17f, 10f)
            circle(12f, 15f, 1.2f)
        }
        VeaIcon.ADD -> { circle(12f, 12f, 9f); line(12f, 7f, 12f, 17f); line(7f, 12f, 17f, 12f) }
        VeaIcon.SELECT -> {
            path(listOf(5f to 3f, 5f to 19f, 9f to 15f, 12f to 21f, 15f to 19f, 12f to 13f, 19f to 13f, 5f to 3f), true)
        }
        VeaIcon.PITCH -> {
            path(listOf(3f to 16f, 7f to 13f, 10f to 15f, 14f to 7f, 18f to 10f, 21f to 5f))
            circle(14f, 7f, 1.1f)
            circle(21f, 5f, 1.1f)
        }
    }
}
