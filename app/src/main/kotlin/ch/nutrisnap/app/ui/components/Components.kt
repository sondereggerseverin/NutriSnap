package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.ui.theme.Coral
import ch.nutrisnap.app.ui.theme.Green400
import ch.nutrisnap.app.ui.theme.Green700

@Composable
fun MacroBar(
    calories: Float,
    goal: Float,
    protein: Float,
    carbs: Float,
    fat: Float,
    modifier: Modifier = Modifier
) {
    val progress = (calories / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val remaining = (goal - calories).coerceAtLeast(0f)

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("${calories.toInt()} kcal", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("gegessen", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${goal.toInt()}", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Ziel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${remaining.toInt()} kcal", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        color = if (remaining > 0) Green400 else Coral)
                    Text(if (remaining > 0) "übrig" else "überschritten", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (progress < 1f) Green700 else Coral,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MacroChip("Protein", protein, Color(0xFF3B82F6))
                MacroChip("Kohlenh.", carbs,  Color(0xFFF59E0B))
                MacroChip("Fett",    fat,     Color(0xFFEF4444))
            }
        }
    }
}

@Composable
private fun MacroChip(label: String, grams: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(4.dp))
        Text("${grams.toInt()}g", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground)
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: @Composable () -> Unit, message: String, sub: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(message, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
