package com.medlenx.lab.ui.screens.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx

private val LaserGradientColors = listOf(
    Color.Transparent, Mlx.Cyan700, Mlx.Cyan400, Mlx.Cyan700, Color.Transparent,
)
private val WashCyan = Color(0x1406B6D4)
private val WashCyanPeak = Color(0x2E06B6D4)
private val ShimmerCyan = Color(0x2E06B6D4)
private val CyanLeftEdge = Color(0xFF06B6D4)

/**
 * Dual-glow radar scan overlay.
 *
 * Port of the web's `.animate-scan-laser` (a 3px bar, gradient
 * transparent -> #0284C7 -> #38BDF8 -> #0284C7 -> transparent, 12px cyan glow,
 * sweeping over 2.2s) combined with `.scan-overlay-glow` (a full-panel cyan wash on
 * the same cycle). `RepeatMode.Reverse` reproduces the CSS `alternate` direction.
 */
@Composable
fun ScanLaserOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        // CSS `.scan-laser` keyframes travel `top: 8% -> 88% -> 8%`, not 0 -> 98%.
        initialValue = 0.08f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "laser",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Full-panel cyan wash (.scan-overlay-glow).
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        (progress - 0.05f).coerceIn(0f, 1f) to WashCyan,
                        progress.coerceIn(0f, 1f) to WashCyanPeak,
                        (progress + 0.05f).coerceIn(0f, 1f) to WashCyan,
                        1.00f to Color.Transparent,
                    ),
                ),
        )
        // The laser line, translated to `progress` of the parent height.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(3.dp)
                .graphicsLayer { translationY = progress * size.height }
                .background(Brush.horizontalGradient(LaserGradientColors)),
        )
    }
}

/**
 * Skeleton rows shown while MedLenX VL works.
 *
 * Port of the web's `.skeleton` shimmer plus `.shimmer-row` — #F1F5F9 blocks with a
 * 4dp cyan-500 left edge and a cyan sweep travelling left to right over 1.6s.
 */
@Composable
fun ScanSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep by transition.animateFloat(
        // CSS `.shimmer-block::after` travels translateX(-100%) -> translateX(200%).
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            // index.css: `animation: shimmer 1.5s infinite`, not 1.6s.
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBar(widthFraction = 0.35f, height = 16.dp)
        repeat(2) { SkeletonRow(sweep) }
        SkeletonBar(widthFraction = 0.25f, height = 16.dp)
        repeat(4) { SkeletonRow(sweep) }
    }
}

@Composable
private fun SkeletonRow(sweep: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Mlx.Brand100)
            .cyanLeftEdgeAndSweep(sweep)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(0.33f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Mlx.Brand200),
        )
        Spacer(Modifier.height(1.dp))
        Box(
            Modifier
                .fillMaxWidth(0.25f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Mlx.Brand200),
        )
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(Mlx.Brand200),
    )
}

/** 4dp cyan left edge plus the travelling highlight of `.shimmer-row::after`. */
private fun Modifier.cyanLeftEdgeAndSweep(sweep: Float): Modifier =
    this.drawWithContent {
        drawContent()
        // Left edge.
        drawRect(color = CyanLeftEdge, topLeft = Offset.Zero, size = Size(4.dp.toPx(), size.height))
        // Travelling highlight.
        val bandWidth = size.width * 0.5f
        val left = sweep * size.width
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, ShimmerCyan, Color.Transparent),
                startX = left,
                endX = left + bandWidth,
            ),
            topLeft = Offset(left, 0f),
            size = Size(bandWidth, size.height),
        )
    }
