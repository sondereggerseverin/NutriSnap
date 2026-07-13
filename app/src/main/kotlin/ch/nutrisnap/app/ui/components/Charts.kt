package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    lineColor:   Color = MaterialTheme.colorScheme.primary
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

    Canvas(modifier = modifier.fillMaxWidth().height(chartHeight)) {
        val w = size.width
        val h = size.height
        val stepX = if (values.size > 1) w / (values.size - 1) else 0f

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

        // Draw data points
        points.forEachIndexed { i, p ->
            val isLast = i == points.size - 1
            drawCircle(
                color  = if (isLast) lineColor else Color.Transparent,
                radius = if (isLast) 6.dp.toPx() else 4.dp.toPx(),
                center = p,
                style  = if (isLast) Fill else Stroke(width = 2.dp.toPx())
            )
        }
    }
}
