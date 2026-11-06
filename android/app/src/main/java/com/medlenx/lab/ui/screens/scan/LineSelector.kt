package com.medlenx.lab.ui.screens.scan

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/** Shared Fit transform for preview drawing, hit testing and original-pixel crop rectangles. */
private class ImageTransform(width: Float, height: Float, bitmap: Bitmap) {
    val scale = minOf(width / bitmap.width, height / bitmap.height)
    val dx = (width - bitmap.width * scale) / 2f
    val dy = (height - bitmap.height * scale) / 2f
    val maxX = bitmap.width.toFloat()
    val maxY = bitmap.height.toFloat()
    fun inImage(p: Offset): Boolean = p.x >= dx && p.y >= dy && p.x < dx + maxX * scale && p.y < dy + maxY * scale
    fun imagePoint(p: Offset) = Offset(((p.x - dx) / scale).coerceIn(0f, maxX), ((p.y - dy) / scale).coerceIn(0f, maxY))
}

/** Draw mode is explicit so ordinary taps still toggle boxes and scrolling remains possible. */
@Composable
fun LineSelector(bitmap: Bitmap, boxes: List<Rect>, selected: Set<Int>, onToggle: (Int) -> Unit, onAdd: (Rect) -> Unit, enabled: Boolean) {
    var drawMode by remember(bitmap) { mutableStateOf(false) }
    var start by remember(bitmap) { mutableStateOf<Offset?>(null) }
    var end by remember(bitmap) { mutableStateOf<Offset?>(null) }
    Column {
        TextButton(enabled = enabled, onClick = { drawMode = !drawMode; start = null; end = null }) {
            Text(if (drawMode) "Done drawing" else "Draw missing line")
        }
        Text(if (drawMode) "Drag across a medicine line to add it." else "Tap boxes to select or exclude them.")
        Box(Modifier.fillMaxWidth().height(480.dp)) {
            Image(bitmap.asImageBitmap(), contentDescription = "Prescription for line selection",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            val gestures = if (drawMode && enabled) Modifier.pointerInput(bitmap, drawMode, enabled) {
                detectDragGestures(
                    onDragStart = { p ->
                        val transform = ImageTransform(size.width.toFloat(), size.height.toFloat(), bitmap)
                        start = p.takeIf { transform.inImage(it) }
                        end = start
                    },
                    onDrag = { change, _ -> if (start != null) { end = change.position; change.consume() } },
                    onDragCancel = { start = null; end = null },
                    onDragEnd = {
                        val a = start
                        val b = end
                        if (a != null && b != null) {
                            val transform = ImageTransform(size.width.toFloat(), size.height.toFloat(), bitmap)
                            val x = transform.imagePoint(a)
                            val y = transform.imagePoint(b)
                            // Reject accidental tiny gestures in screen space, not source-image pixels.
                            if (kotlin.math.abs(a.x - b.x) >= 8.dp.toPx() && kotlin.math.abs(a.y - b.y) >= 8.dp.toPx()) {
                                val rect = Rect(floor(minOf(x.x, y.x)).toInt(), floor(minOf(x.y, y.y)).toInt(),
                                    ceil(maxOf(x.x, y.x)).toInt(), ceil(maxOf(x.y, y.y)).toInt())
                                if (!rect.isEmpty) onAdd(rect)
                            }
                        }
                        start = null; end = null
                    },
                )
            } else Modifier.pointerInput(bitmap, boxes, selected, enabled) {
                detectTapGestures { point ->
                    if (enabled) {
                        val transform = ImageTransform(size.width.toFloat(), size.height.toFloat(), bitmap)
                        if (transform.inImage(point)) {
                            val p = transform.imagePoint(point)
                            boxes.indices.reversed().firstOrNull { i ->
                                p.x >= boxes[i].left && p.x < boxes[i].right && p.y >= boxes[i].top && p.y < boxes[i].bottom
                            }?.let(onToggle)
                        }
                    }
                }
            }
            Canvas(Modifier.fillMaxSize().then(gestures)) {
                val transform = ImageTransform(size.width, size.height, bitmap)
                boxes.forEachIndexed { i, rect ->
                    val top = Offset(transform.dx + rect.left * transform.scale, transform.dy + rect.top * transform.scale)
                    val boxSize = Size(rect.width() * transform.scale, rect.height() * transform.scale)
                    val color = if (i in selected) Color(0xFF00B46B) else Color.LightGray
                    drawRect(color.copy(alpha = if (i in selected) 0.25f else 0.10f), top, boxSize)
                    drawRect(color, top, boxSize, style = Stroke(width = 2.dp.toPx()))
                }
                val a = start; val b = end
                if (a != null && b != null) {
                    val pa = transform.imagePoint(a); val pb = transform.imagePoint(b)
                    val left = minOf(pa.x, pb.x); val top = minOf(pa.y, pb.y)
                    drawRect(Color(0xFF00B46B).copy(alpha = 0.3f),
                        Offset(transform.dx + left * transform.scale, transform.dy + top * transform.scale),
                        Size(kotlin.math.abs(pa.x - pb.x) * transform.scale, kotlin.math.abs(pa.y - pb.y) * transform.scale))
                }
            }
        }
    }
}
