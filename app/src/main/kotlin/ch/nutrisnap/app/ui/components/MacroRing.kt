package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Circular progress ring showing [eaten] vs [goal], with arbitrary
 * center content (e.g. "X kcal übrig").
 */
@Composable
fun MacroRing(
    eaten:         Float,
    goal:          Float,
    modifier:      Modifier = Modifier,
    size:          Dp = 120.dp,
    strokeWidth:   Dp = 10.dp,
    trackColor:    Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    overflowColor: Color = MaterialTheme.colorScheme.error,
    centerContent: @Composable () -> Unit = {}
) {
    val pct        = (eaten / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val isOver     = eaten > goal
    val ringColor  = if (isOver) overflowColor else progressColor

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = strokeWidth.toPx()
            val diameter = this.size.minDimension - strokePx
            val topLeft  = Offset((this.size.width - diameter) / 2f, (this.size.height - diameter) / 2f)
            val arcSize  = Size(diameter, diameter)

            // Track
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter  = false,
                topLeft    = topLeft,
                size       = arcSize,
                style      = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            // Progress
            if (pct > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * pct,
                    useCenter  = false,
                    topLeft    = topLeft,
                    size       = arcSize,
                    style      = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        centerContent()
    }
}
