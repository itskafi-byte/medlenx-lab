package com.medlenx.lab.ui.screens.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
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
        const val MAX_SCALE = 10f
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
 * Pinch, rotate and pan with two fingers; double-tap zooms or returns to fit.
 * Manual controls remain in the tools menu for accessibility.
 *
 */
@Composable
fun PrescriptionImageViewer(
    imageUri: String,
    transform: ViewerTransform,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // Clipped to its own frame. A zoomed scan magnifies *inside* the review
            // box instead of painting over the doctor form and the medicine cards
            // around it; the fullscreen control is what gives the zoom the whole
            // display when the officer wants it bigger than the panel.
            .clip(MlxShape.Medium)
            .background(Mlx.Brand100)
            .pointerInput(imageUri, transform) {
                detectTapGestures(onDoubleTap = { position ->
                    if (transform.scale > 1.05f) {
                        transform.scale = 1f
                        transform.offsetX = 0f
                        transform.offsetY = 0f
                    } else {
                        val oldScale = transform.scale
                        val newScale = 2.5f.coerceIn(ViewerTransform.MIN_SCALE, ViewerTransform.MAX_SCALE)
                        val factor = newScale / oldScale
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val anchor = position - center
                        transform.offsetX = anchor.x - (anchor.x - transform.offsetX) * factor
                        transform.offsetY = anchor.y - (anchor.y - transform.offsetY) * factor
                        transform.scale = newScale
                    }
                })
            }
            .pointerInput(imageUri, transform) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val oldScale = transform.scale
                            val newScale = (oldScale * event.calculateZoom())
                                .coerceIn(ViewerTransform.MIN_SCALE, ViewerTransform.MAX_SCALE)
                            val factor = newScale / oldScale
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val anchor = event.calculateCentroid(useCurrent = false) - center
                            if (anchor.x.isFinite() && anchor.y.isFinite()) {
                                val pan = event.calculatePan()
                                transform.offsetX = anchor.x - (anchor.x - transform.offsetX) * factor + pan.x
                                transform.offsetY = anchor.y - (anchor.y - transform.offsetY) * factor + pan.y
                                transform.scale = newScale
                                transform.rotation += event.calculateRotation()
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offsetX
                    translationY = transform.offsetY
                    rotationZ = transform.rotation
                },
        ) {
            AsyncImage(
                model = imageUri,
                contentDescription = "Prescription scan",
                contentScale = ContentScale.Fit,
                colorFilter = if (transform.contrast > 1.01f) contrastFilter(transform.contrast) else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
            text = "Pinch or double-tap to zoom · Two fingers to pan · $zoomPercent%",
            style = MlxType.Meta,
            color = Mlx.Text500,
        )
    }
}

/** Compact toolbar; manual alternatives remain available for accessibility. */
@Composable
fun ViewerTools(transform: ViewerTransform, onFullscreen: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onFullscreen != null) MlxIconButton(Icons.Filled.Fullscreen, "Fullscreen image", onFullscreen)
        Box {
            MlxIconButton(Icons.Filled.MoreVert, "Image tools", { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf(
                    "Zoom in" to transform::zoomIn,
                    "Zoom out" to transform::zoomOut,
                    "Rotate left" to transform::rotateLeft,
                    "Rotate right" to transform::rotateRight,
                    "Change contrast" to transform::cycleContrast,
                    "Reset image" to transform::fit,
                ).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; action() })
                }
            }
        }
    }
}
