package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import ch.nutrisnap.app.ui.theme.MacroColors

@Composable
fun BarChart(
    values:         List<Float>,
    labels:         List<String>,
    modifier:       Modifier = Modifier,
    chartHeight:    Dp = 120.dp,
    barColor:       Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    highlightIndex: Int? = null,
    valueFormatter: (Float) -> String = { if (it > 0f) it.toInt().toString() else "" }
) {
    val maxVal = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.xs)
        ) {
            values.forEachIndexed { i, v ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (v > 0f) {
                        Text(
                            valueFormatter(v),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(NutriSpacing.xs))
                    }
                    val barHeight = if (v > 0f) ((v / maxVal) * chartHeight.value).coerceAtLeast(6f).dp else 0.dp
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(chartHeight)
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(barHeight)
                                .clip(RoundedCornerShape(topStart = NutriRadius.sm, topEnd = NutriRadius.sm))
                                .background(
                                    color = if (i == highlightIndex) highlightColor else barColor
                                )
                        )
                    }
                    Spacer(Modifier.height(NutriSpacing.xs))
                    Text(
                        labels[i],
                        fontSize = 10.sp,
                        fontWeight = if (i == highlightIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == highlightIndex) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun LineChart(
    values:      List<Float>,
    modifier:    Modifier = Modifier,
    chartHeight: Dp = 100.dp,
    lineColor:   Color = MaterialTheme.colorScheme.primary,
    xLabels:     List<String> = emptyList(),
    valueFormatter: (Float) -> String = { "%.1f".format(it) }
) {
    if (values.size < 2) {
        Box(
            modifier.fillMaxWidth().height(chartHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Nicht genug Daten",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val min   = (values.minOrNull() ?: 0f) - 0.5f
    val max   = (values.maxOrNull() ?: 1f) + 0.5f
    val range = (max - min).coerceAtLeast(0.1f)

    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor       = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val yAxisWidth       = 38.dp

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(chartHeight)) {
            // Y-axis labels (max / min)
            Column(
                Modifier.width(yAxisWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.End
            ) {
                Text(valueFormatter(max), fontSize = 10.sp, color = axisLabelColor)
                Spacer(Modifier.weight(1f))
                Text(valueFormatter(min), fontSize = 10.sp, color = axisLabelColor)
            }
            Spacer(Modifier.width(NutriSpacing.xs))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height
                val stepX = if (values.size > 1) w / (values.size - 1) else 0f

                // Horizontal gridlines (top, middle, bottom)
                for (frac in listOf(0f, 0.5f, 1f)) {
                    val y = h * frac
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end   = Offset(w, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val points = values.mapIndexed { i, v ->
                    Offset(x = i * stepX, y = h - ((v - min) / range) * h)
                }

                // Draw line segments
                for (i in 0 until points.size - 1) {
                    drawLine(
                        color = lineColor,
                        start = points[i],
                        end   = points[i + 1],
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                // Draw data points - all visible, last one highlighted
                points.forEachIndexed { i, p ->
                    val isLast = i == points.size - 1
                    drawCircle(
                        color  = lineColor,
                        radius = if (isLast) 6.dp.toPx() else 3.dp.toPx(),
                        center = p,
                        style  = if (isLast) Fill else Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
        if (xLabels.isNotEmpty()) {
            Spacer(Modifier.height(NutriSpacing.xs))
            Row(Modifier.fillMaxWidth().padding(start = yAxisWidth + NutriSpacing.xs)) {
                xLabels.forEachIndexed { i, label ->
                    Text(
                        label,
                        fontSize = 10.sp,
                        color = axisLabelColor,
                        modifier = Modifier.weight(1f),
                        textAlign = when (i) {
                            0 -> androidx.compose.ui.text.style.TextAlign.Start
                            xLabels.size - 1 -> androidx.compose.ui.text.style.TextAlign.End
                            else -> androidx.compose.ui.text.style.TextAlign.Center
                        }
                    )
                }
            }
        }
    }
}
