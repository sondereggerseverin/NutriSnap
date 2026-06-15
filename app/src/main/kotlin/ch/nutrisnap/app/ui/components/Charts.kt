package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Simple vertical bar chart. One bar per entry in [values]; [highlightIndex]
 * (e.g. "today") is drawn in [highlightColor], all others in [barColor].
 */
@Composable
fun BarChart(
    values:         List<Float>,
    labels:         List<String>,
    modifier:       Modifier = Modifier,
    chartHeight:    Dp = 110.dp,
    barColor:       Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    highlightIndex: Int? = null,
    valueFormatter: (Float) -> String = { if (it > 0f) it.toInt().toString() else "" }
) {
    val maxVal = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEachIndexed { i, v ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    valueFormatter(v), fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                val barHeight = if (v > 0f) ((v / maxVal) * chartHeight.value).coerceAtLeast(4f).dp else 0.dp
                Box(Modifier.fillMaxWidth(0.6f).height(chartHeight)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(barHeight)
                            .background(
                                color = if (i == highlightIndex) highlightColor else barColor,
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(labels[i], fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Simple line chart for trend data (e.g. body weight over time).
 */
@Composable
fun LineChart(
    values:    List<Float>,
    modifier:  Modifier = Modifier,
    chartHeight: Dp = 90.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary
) {
    if (values.size < 2) {
        Box(modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
            Text("Nicht genug Daten", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        for (i in 0 until points.size - 1) {
            drawLine(
                color = lineColor,
                start = points[i],
                end   = points[i + 1],
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        points.forEachIndexed { i, p ->
            val isLast = i == points.size - 1
            drawCircle(
                color  = lineColor,
                radius = if (isLast) 5.dp.toPx() else 3.dp.toPx(),
                center = p,
                style  = if (isLast) Fill else Stroke(width = 2.dp.toPx())
            )
        }
    }
}
