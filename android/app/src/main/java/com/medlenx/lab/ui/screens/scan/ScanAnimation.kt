package com.medlenx.lab.ui.screens.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.Text
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.ui.theme.MlxType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx

private val ShimmerCyan = Color(0x2E06B6D4)
private val CyanLeftEdge = Color(0xFF06B6D4)

/** Decorative sweep and transient highlights, not model-provided detections. */
@Composable
fun ScanLaserOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "laser",
    )
    Canvas(modifier.fillMaxSize()) {
        val y = progress * size.height
        // Fixed, illustrative marks fade behind the beam; they are not OCR boxes.
        for (i in 0 until 7) {
            val rowY = size.height * (0.14f + i * 0.12f)
            val age = (y - rowY) / size.height
            if (age in 0f..0.27f) {
                val alpha = (1f - age / 0.27f) * 0.3f
                val x = size.width * (if (i % 2 == 0) 0.13f else 0.22f)
                val w = size.width * (if (i % 3 == 0) 0.58f else 0.43f)
                val h = 15.dp.toPx()
                drawRoundRect(Mlx.Cyan400.copy(alpha = alpha * 0.25f),
                    topLeft = Offset(x, rowY), size = Size(w, h), cornerRadius = CornerRadius(3.dp.toPx()))
                drawRoundRect(Mlx.Cyan400.copy(alpha = alpha),
                    topLeft = Offset(x, rowY), size = Size(w, h), cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()))
            }
        }
        val left = Offset(size.width * 0.08f, y)
        val right = Offset(size.width * 0.92f, y)
        drawLine(Mlx.Cyan400.copy(alpha = 0.10f), left, right, 16.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Mlx.Cyan400.copy(alpha = 0.26f), left, right, 7.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.95f), left, right, 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Skeleton transitions to real response data, never simulated clinical results. */
@Composable
fun ScanSkeleton(
    result: VlScanResult?,
    cards: List<MedicineCardData>,
    revealedMedicines: Int,
    indeterminate: Boolean,
    preparing: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep by transition.animateFloat(
        // CSS `.shimmer-block::after` travels translateX(-100%) -> translateX(200%).
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            // index.css: `animation: shimmer 1.5s infinite`, not 1.6s.
            animation = tween(durationMillis = if (indeterminate) 1500 else 550, easing = LinearEasing),
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
        if (result == null) {
            repeat(2) { SkeletonRow(sweep) }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(result.doctor.name.ifBlank { "Doctor name not identified" }, style = MlxType.CardTitle)
                if (result.doctor.bmdcNo.isNotBlank()) Text("BMDC: ${result.doctor.bmdcNo}", style = MlxType.Meta)
            }
        }
        SkeletonBar(widthFraction = 0.25f, height = 16.dp)
        cards.take(revealedMedicines).forEach { card ->
            Column {
                Text(card.brand.ifBlank { "Medicine name not identified" }, style = MlxType.CardTitle)
                if (card.company.isNotBlank()) Text(card.company, style = MlxType.Meta)
            }
        }
        if (result == null || preparing || revealedMedicines < cards.size) {
            repeat(2) { SkeletonRow(sweep) }
        }
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
