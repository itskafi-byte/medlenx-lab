package com.medlenx.lab.ui.screens.scan

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** The image and rectangles share the same Fit transform, including letterboxing. */
@Composable
fun LineSelector(bitmap: Bitmap, boxes: List<Rect>, selected: Set<Int>, onToggle: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().height(480.dp)) {
        Image(bitmap.asImageBitmap(), contentDescription = "Prescription for line selection",
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize().pointerInput(bitmap, boxes, selected) {
            detectTapGestures { point ->
                val scale = minOf(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                val x = (point.x - (size.width - bitmap.width * scale) / 2f) / scale
                val y = (point.y - (size.height - bitmap.height * scale) / 2f) / scale
                boxes.indices.reversed().firstOrNull { i ->
                    x >= boxes[i].left && x < boxes[i].right && y >= boxes[i].top && y < boxes[i].bottom
                }?.let(onToggle)
            }
        }) {
            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val dx = (size.width - bitmap.width * scale) / 2f
            val dy = (size.height - bitmap.height * scale) / 2f
            boxes.forEachIndexed { i, rect ->
                val top = Offset(dx + rect.left * scale, dy + rect.top * scale)
                val boxSize = Size(rect.width() * scale, rect.height() * scale)
                val color = if (i in selected) Color(0xFF00B46B) else Color.LightGray
                drawRect(color.copy(alpha = if (i in selected) 0.25f else 0.10f), top, boxSize)
                drawRect(color, top, boxSize, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}
