package com.medlenx.lab.ui.screens.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType

/** Viewer transform state, hoisted so the control bar and the canvas stay in sync. */
class ViewerTransform {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var rotation by mutableFloatStateOf(0f)

    /** 1.0 = untouched; the web contrast toggle steps through these. */
    var contrast by mutableFloatStateOf(1.0f)

    val zoomPercent: Int get() = (scale * 100).toInt()

    fun zoomIn() { scale = (scale + 0.25f).coerceIn(MIN_SCALE, MAX_SCALE) }
    fun zoomOut() { scale = (scale - 0.25f).coerceIn(MIN_SCALE, MAX_SCALE) }
    fun rotateLeft() { rotation -= 90f }
    fun rotateRight() { rotation += 90f }

    fun cycleContrast() {
        contrast = when {
            contrast < 1.25f -> 1.35f
            contrast < 1.5f -> 1.6f
            else -> 1.0f
        }
    }

    fun fit() {
        scale = 1f; offsetX = 0f; offsetY = 0f; rotation = 0f; contrast = 1.0f
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 5f
    }
}

/**
 * Classic contrast matrix: scale RGB about the 128 midpoint so faint handwriting
 * separates from the paper without clipping to pure black/white.
 */
private fun contrastFilter(c: Float): ColorFilter {
    val shift = (1f - c) * 128f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, shift,
                0f, c, 0f, 0f, shift,
                0f, 0f, c, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/**
 * Prescription image canvas.
 *
 * Web equivalents: scroll-to-zoom becomes pinch-to-zoom, drag-to-pan is preserved,
 * and the rotate / contrast / fit controls map 1:1. The zoom readout is kept because
 * the officer needs to know the magnification before judging a confidence badge.
 */
@Composable
fun PrescriptionImageViewer(
    imageUri: String,
    transform: ViewerTransform,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Mlx.Brand100)
            .pointerInput(imageUri) {
                detectTransformGestures { _, pan, zoom, rotationDelta ->
                    transform.scale = (transform.scale * zoom)
                        .coerceIn(ViewerTransform.MIN_SCALE, ViewerTransform.MAX_SCALE)
                    transform.offsetX += pan.x
                    transform.offsetY += pan.y
                    transform.rotation += rotationDelta
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageUri,
            contentDescription = "Prescription scan",
            contentScale = ContentScale.Fit,
            colorFilter = if (transform.contrast > 1.01f) contrastFilter(transform.contrast) else null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offsetX
                    translationY = transform.offsetY
                    rotationZ = transform.rotation
                },
        )
        overlay()
    }
}

/** Zoom / gesture hint strip — the web's "Scroll to zoom · Drag to pan · 100%". */
@Composable
fun ViewerHintStrip(zoomPercent: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Mlx.Brand50)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Pinch to zoom · Drag to pan · $zoomPercent%",
            style = MlxType.Meta,
            color = Mlx.Text500,
        )
    }
}

/**
 * Bounding-box highlight for the medicine line the officer is inspecting.
 *
 * The VL model returns line-based detections without pixel coordinates, so — exactly
 * as on the web — the line index is mapped onto a vertical band of the scan. Drawn in
 * the draw scope rather than laid out, so it tracks the canvas size with no extra
 * measurement pass.
 */
@Composable
fun MedicineBoundingBox(
    lineIndex: Int,
    totalLines: Int,
    modifier: Modifier = Modifier,
) {
    if (totalLines <= 0) return
    val index = lineIndex.coerceIn(0, totalLines - 1)
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val bandHeight = size.height / totalLines
                val top = index * bandHeight
                drawRect(
                    color = BboxFill,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, bandHeight),
                )
                drawRect(
                    color = BboxStroke,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, bandHeight),
                    style = Stroke(width = 2.dp.toPx()),
                )
            },
    )
}

/** Bounding box — Figma `.bbox`: `#FB923C` stroke, same hue at 20% fill. */
private val BboxFill = Mlx.GuessLight.copy(alpha = 0.2f)
private val BboxStroke = Mlx.GuessLight
