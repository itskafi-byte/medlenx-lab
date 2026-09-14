package com.medlenx.lab.ui.screens.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType

/**
 * The four analytics charts.
 *
 * These are drawn with Compose `Canvas` rather than a charting library. A charting
 * dependency's API changes substantially between minor versions, while `Canvas` is
 * stable Foundation and gives exact control over the strokes, corner radii and grid
 * lines that reproduce the recharts original.
 *
 * Axis labels are real `Text` composables rather than canvas-drawn glyphs — that keeps
 * them selectable, correctly scaled for font settings, and free of the experimental
 * `TextMeasurer` API.
 */

private fun seriesColor(index: Int): Color = Mlx.ChartSeries[index % Mlx.ChartSeries.size]

/**
 * Chart A — most prescribed medicines. Vertical bars coloured from the chart series,
 * matching recharts' `radius={[4,4,0,0]}` top rounding.
 */
@Composable
fun MostPrescribedBarChart(data: List<BarDatum>, modifier: Modifier = Modifier) {
    val maxValue = (data.maxOfOrNull { it.value } ?: 1).toFloat()
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
        ) {
            if (data.isEmpty()) return@Canvas
            val slot = size.width / data.size
            val barWidth = slot * 0.55f
            data.forEachIndexed { index, datum ->
                val barHeight = size.height * (datum.value / maxValue)
                val left = slot * index + (slot - barWidth) / 2f
                drawRoundRect(
                    color = seriesColor(index),
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            data.forEach { datum ->
                Text(
                    text = datum.name,
                    style = MlxType.MicroPill,
                    color = Mlx.Text500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Chart B — company share of voice. Donut with a 2-degree gap between segments and a
 * centred total, matching recharts' `innerRadius 60 / outerRadius 90 / paddingAngle 2`.
 */
@Composable
fun ShareOfVoiceDonut(
    data: List<DonutDatum>,
    centreLabel: String,
    modifier: Modifier = Modifier,
) {
    val total = data.sumOf { it.value }.toFloat().coerceAtLeast(1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(190.dp)) {
                val stroke = 30.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                var start = -90f
                data.forEachIndexed { index, datum ->
                    val sweep = (datum.value / total) * 360f
                    drawArc(
                        color = seriesColor(index),
                        startAngle = start + 1f,
                        sweepAngle = (sweep - 2f).coerceAtLeast(0f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    start += sweep
                }
            }
            Text(
                text = centreLabel,
                style = MlxType.CardTitle,
                color = Mlx.Text900,
            )
        }

        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            data.forEachIndexed { index, datum ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(seriesColor(index), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = datum.name,
                        style = MlxType.Meta,
                        color = Mlx.Text600,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${datum.value}%",
                        style = MlxType.Meta,
                        color = Mlx.Text900,
                    )
                }
            }
        }
    }
}

/**
 * Chart D — generic vs brand matrix. Horizontal stacked bars by specialty with a legend,
 * matching recharts' `layout="vertical"` and `stackId="a"`.
 */
@Composable
fun SpecialtyStackedBarChart(
    data: List<StackedDatum>,
    series: List<String>,
    modifier: Modifier = Modifier,
) {
    val maxValue = (data.maxOfOrNull { row -> row.seriesValues().sum() } ?: 1).toFloat()

    Column(modifier = modifier.fillMaxWidth()) {
        data.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.specialty,
                    style = MlxType.MicroPill,
                    color = Mlx.Text500,
                    maxLines = 1,
                    modifier = Modifier.width(78.dp),
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(18.dp),
                ) {
                    val rowTotal = row.seriesValues().sum().toFloat().coerceAtLeast(1f)
                    val usable = size.width * (rowTotal / maxValue)
                    var left = 0f
                    row.seriesValues().forEachIndexed { index, value ->
                        val width = usable * (value / rowTotal)
                        drawRect(
                            color = seriesColor(index),
                            topLeft = Offset(left, 0f),
                            size = Size(width, size.height),
                        )
                        left += width
                    }
                }
            }
        }

        // Legend, recharts `iconSize 8` at 10sp.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            series.forEachIndexed { index, name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(seriesColor(index), RoundedCornerShape(2.dp)),
                    )
                    Text(text = name, style = MlxType.MicroPill, color = Mlx.Text500)
                }
            }
        }
    }
}
