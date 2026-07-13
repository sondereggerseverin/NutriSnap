package ch.nutrisnap.app.ui.screens.analysis

import androidx.compose.foundation.background
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
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import java.time.LocalDate

@Composable
fun AnalysisScreen(vm: AnalysisViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NutriSpacing.lg,
            end = NutriSpacing.lg,
            top = NutriSpacing.xxl,
            bottom = 100.dp
        ),
        verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
    ) {
        item {
            Column {
                Text(
                    "Analyse",
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Spacer(Modifier.height(NutriSpacing.xs))
                Text(
                    "Letzte 7 Tage",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                Spacer(Modifier.height(NutriSpacing.md))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "\u00D8 ${state.avgCalories} kcal/Tag",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Ziel: ${state.goals.computedTdee()?.toInt() ?: state.goals.dailyCalorieGoal} kcal",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Macro distribution ──────────────────────────────────────────────
        item {
            AnalysisCard(title = "Durchschnittliche Makros") {
                MacroAverageRow("Protein",       state.avgProtein, state.goals.proteinGoalG, MacroColors.protein)
                Spacer(Modifier.height(NutriSpacing.md))
                MacroAverageRow("Kohlenhydrate", state.avgCarbs, state.goals.carbsGoalG, MacroColors.carbs)
                Spacer(Modifier.height(NutriSpacing.md))
                MacroAverageRow("Fett",          state.avgFat, state.goals.fatGoalG, MacroColors.fat)
            }
        }

        // ── Weight chart ─────────────────────────────────────────────────────
        item {
            AnalysisCard(title = "Gewichtsverlauf") {
                if (state.weights.isEmpty()) {
                    Text(
                        "Noch keine Gewichtsdaten – trag dein Gewicht auf der Startseite ein.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LineChart(values = state.weights.map { it.weightKg })
                    Spacer(Modifier.height(NutriSpacing.md))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${state.weights.first().weightKg} kg",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val diff = state.weights.last().weightKg - state.weights.first().weightKg
                        val sign = if (diff > 0) "+" else ""
                        Text(
                            "$sign%.1f kg".format(diff),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (diff > 0) MaterialTheme.colorScheme.error else MacroColors.calories
                        )
                        Text(
                            "${state.weights.last().weightKg} kg",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Streak summary ──────────────────────────────────────────────────
        item {
            AnalysisCard(title = "Konsistenz") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(NutriRadius.md))
                            .background(MacroColors.fat.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("\uD83D\uDD25", fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(NutriSpacing.lg))
                    Column {
                        Text(
                            "${state.streak} Tage Streak",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            "Aktuelle Serie an Tagen mit Einträgen",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(NutriSpacing.lg)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(NutriSpacing.md))
            content()
        }
    }
}

@Composable
private fun MacroAverageRow(label: String, value: Float, goal: Float, color: androidx.compose.ui.graphics.Color) {
    val pct = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "${value.toInt()}g / ${goal.toInt()}g",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(NutriSpacing.xs))
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.12f),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}
