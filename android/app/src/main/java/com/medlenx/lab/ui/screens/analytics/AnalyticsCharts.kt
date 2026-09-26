package com.medlenx.lab.ui.screens.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.repo.MedicineMatcher
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
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

// Colour by position, for the charts whose categories are printed beside the colour
// (Chart A's brands, Chart C's generics). Deliberately not the donut's rule: emerald is
// reserved there because a slice can be a competing company, while here it is simply
// the second colour.
private fun seriesColor(index: Int): Color = Mlx.ChartSeries[index % Mlx.ChartSeries.size]

/**
 * Colour per share-of-voice slice.
 *
 * Two slices do not take a palette colour at all:
 *
 *  * the own company's, which is pinned to emerald and skipped in the palette, so a
 *    competitor can never wear the same colour (the web's `COMPANY_COLOR_PALETTE`
 *    gives the named companies fixed hexes for the same reason);
 *  * the "Others" bucket, which is the web's slate (`others: '#94A3B8'`).
 */
private fun donutColors(data: List<DonutDatum>, ownCompany: String): List<Color> {
    val reserved = Mlx.Ok500
    val palette = Mlx.ChartSeries.filter { it != reserved }
    var next = 0
    return data.map { datum ->
        when {
            datum.isOthers -> Mlx.Brand400
            ownCompany.isNotBlank() &&
                MedicineMatcher.sameCompany(datum.name, ownCompany) -> reserved
            else -> palette[next++ % palette.size]
        }
    }
}

/**
 * Chart A — most prescribed medicines.
 *
 * Horizontal bars, which is what the web draws: `indexAxis:'y'` (`index.html:2507`).
 * Vertical bars were this port's own idea, and on a phone ten of them in one card
 * squeezed every name into "Bilast…" and "Cosec" - the labels had a tenth of the
 * width each.
 *
 * The web's recharts tooltip prints the manufacturer and the share of captured items;
 * a touch screen has no hover, so both are printed on the row instead. Bars are
 * scaled against the largest count and sit on the web's `#F1F5F9` grid line, which is
 * [Mlx.Brand100] - so a chart where every count is 1 reads as ten full-width bars
 * over a visible track rather than as a broken chart.
 */
@Composable
fun MostPrescribedBarChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    onBarClick: (String) -> Unit = {},
) {
    val maxValue = (data.maxOfOrNull { it.value } ?: 1).coerceAtLeast(1).toFloat()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        data.forEachIndexed { index, datum ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBarClick(datum.name) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.width(104.dp)) {
                    Text(
                        text = datum.name,
                        style = MlxType.Meta.copy(fontWeight = FontWeight.SemiBold),
                        color = Mlx.Text900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (datum.manufacturer.isNotBlank()) {
                        Text(
                            text = datum.manufacturer,
                            style = MlxType.MicroPill,
                            color = Mlx.Text400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .background(Mlx.Brand100, RoundedCornerShape(6.dp)),
                ) {
                    // `radius={[4,4,0,0]}` on the web's vertical bars; the horizontal
                    // equivalent rounds the right end only.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(datum.value / maxValue)
                            .height(14.dp)
                            .background(seriesColor(index), RoundedCornerShape(6.dp)),
                    )
                }
                Column(
                    modifier = Modifier.width(58.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = datum.value.toString(),
                        style = MlxType.Meta.copy(fontWeight = FontWeight.Bold),
                        color = Mlx.Text900,
                    )
                    Text(
                        text = "${"%.1f".format(datum.sharePercent)}%",
                        style = MlxType.MicroPill,
                        color = Mlx.Text400,
                    )
                }
            }
        }
    }
}

/**
 * Chart B — company share of voice. Donut with a 2-degree gap between segments,
 * matching recharts' `innerRadius 60 / outerRadius 90 / paddingAngle 2`.
 *
 * [centreCaption] / [centreValue] are two lines rather than the one label this used to
 * take: a single "SoV 14%" cannot say whose share it is, and the caption is only true
 * for the own company when the own company leads the breakdown.
 */
@Composable
fun ShareOfVoiceDonut(
    data: List<DonutDatum>,
    centreCaption: String,
    centreValue: String,
    modifier: Modifier = Modifier,
    ownCompany: String = "",
    onSliceClick: (DonutDatum) -> Unit = {},
) {
    // Arcs are proportional to the item counts, not to a rounded percentage; `total`
    // is the same denominator the web's payload divides by.
    val total = data.sumOf { it.count }.toFloat().coerceAtLeast(1f)
    val colors = donutColors(data, ownCompany)

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
                    val sweep = (datum.count / total) * 360f
                    drawArc(
                        color = colors[index],
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centreCaption,
                    style = MlxType.MicroPill,
                    color = Mlx.Text400,
                )
                Text(
                    text = centreValue,
                    style = MlxType.CardTitle,
                    color = Mlx.Text900,
                )
            }
        }

        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            data.forEachIndexed { index, datum ->
                // The legend row is the tap target rather than the arc. Hit-testing
                // a 30dp stroked arc needs polar maths and still leaves the thin
                // slivers of a long tail nearly untappable, whereas the row is the
                // full width and is labelled with the name being drilled into.
                //
                // The whole datum is passed rather than its name, because the
                // "Others" slice is not a company: the caller has to know that to
                // open its member list instead of querying company_name = 'Others'.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSliceClick(datum) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(colors[index], RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = datum.name,
                        style = MlxType.Meta,
                        color = Mlx.Text600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // `fill = true` (the default): the name takes the whole slack so
                        // the "Own" pill and the counts line up down the column instead
                        // of drifting left under short company names.
                        modifier = Modifier.weight(1f),
                    )
                    // Which row wears the reserved emerald, since no other row can. The
                    // web's tooltip marks no company as the own one; a reserved colour
                    // with nothing labelling it is a colour nobody can decode.
                    if (ownCompany.isNotBlank() &&
                        MedicineMatcher.sameCompany(datum.name, ownCompany)
                    ) {
                        StatusPill(text = "Own", tone = PillTone.Emerald)
                    }
                    Text(
                        // The web's tooltip reads "company: 14.3% (2 items)"; with no
                        // hover, the legend row is where that belongs.
                        text = "${datum.count} (${"%.1f".format(datum.sharePercent)}%)",
                        style = MlxType.Meta,
                        color = Mlx.Text900,
                        modifier = Modifier.padding(start = 4.dp),
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
