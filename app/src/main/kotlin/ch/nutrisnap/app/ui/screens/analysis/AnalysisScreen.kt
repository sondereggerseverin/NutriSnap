package ch.nutrisnap.app.ui.screens.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.ui.components.BarChart
import ch.nutrisnap.app.ui.components.LineChart
import java.time.LocalDate

@Composable
fun AnalysisScreen(vm: AnalysisViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
    ) {
        item {
            Text("Analyse", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text("Letzte 7 Tage", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }

        // ── Calorie chart ───────────────────────────────────────────────────
        item {
            AnalysisCard(title = "Kalorien pro Tag") {
                val todayIndex = state.days.indexOfFirst { it.date == LocalDate.now() }
                BarChart(
                    values = state.days.map { it.calories },
                    labels = state.days.map { it.label },
                    highlightIndex = if (todayIndex >= 0) todayIndex else null
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ø ${state.avgCalories} kcal/Tag", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Ziel: ${state.goals.computedTdee()?.toInt() ?: state.goals.dailyCalorieGoal} kcal",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── Macro distribution ──────────────────────────────────────────────
        item {
            AnalysisCard(title = "Durchschnittliche Makros") {
                MacroAverageRow("Protein",     state.avgProtein, state.goals.proteinGoalG, "#3B82F6")
                Spacer(Modifier.height(10.dp))
                MacroAverageRow("Kohlenhydrate", state.avgCarbs, state.goals.carbsGoalG, "#F59E0B")
                Spacer(Modifier.height(10.dp))
                MacroAverageRow("Fett",        state.avgFat, state.goals.fatGoalG, "#EF4444")
            }
        }

        // ── Weight chart ─────────────────────────────────────────────────────
        item {
            AnalysisCard(title = "Gewichtsverlauf") {
                if (state.weights.isEmpty()) {
                    Text(
                        "Noch keine Gewichtsdaten – trag dein Gewicht auf der Startseite ein.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LineChart(values = state.weights.map { it.weightKg })
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${state.weights.first().weightKg} kg", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val diff = state.weights.last().weightKg - state.weights.first().weightKg
                        val sign = if (diff > 0) "+" else ""
                        Text("$sign%.1f kg".format(diff), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (diff > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("${state.weights.last().weightKg} kg", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ── Streak summary ──────────────────────────────────────────────────
        item {
            AnalysisCard(title = "Konsistenz") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🔥", fontSize = 28.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("${state.streak} Tage Streak", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Aktuelle Serie an Tagen mit Einträgen", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun MacroAverageRow(label: String, value: Float, goal: Float, colorHex: String) {
    val pct = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorHex))
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text("${value.toInt()}g / ${goal.toInt()}g", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}
