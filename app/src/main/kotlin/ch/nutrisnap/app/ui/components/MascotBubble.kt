package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

/**
 * Konversationelle Sprechblase mit Maskottchen-Avatar, angelehnt an FreshBatch.
 * Für Onboarding, leere Zustände und Erfolgsmomente – bewusst erwachsen im Ton,
 * kein Baby-Talk. [emoji] ersetzt eine eigene Illustration, solange keine
 * Asset-Pipeline für ein Maskottchen-Artwork existiert.
 */
@Composable
fun MascotBubble(
    text: String,
    modifier: Modifier = Modifier,
    emoji: String = "🐿️",
    subtext: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(NutriSpacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 26.sp)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(NutriRadius.lg))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.md)
        ) {
            Text(
                text,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtext.isNullOrBlank()) {
                Text(
                    subtext,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * Dünner Fortschrittsbalken für mehrstufige Flows (Onboarding, Plan-Erstellung).
 */
@Composable
fun OnboardingProgressBar(step: Int, totalSteps: Int, modifier: Modifier = Modifier) {
    val progress = (step.toFloat() / totalSteps.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
