package ch.nutrisnap.app.ui.screens.analysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.ui.components.BarChart
import ch.nutrisnap.app.ui.components.LineChart
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun AnalysisScreen(vm: AnalysisViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }

    val historyPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { vm.onHistoryPermissionResult() }

    if (showDatePicker) {
        AnalysisDatePickerDialog(
            initialDate = state.anchorDate,
            onDismiss = { showDatePicker = false },
            onConfirm = { date ->
                vm.goToDate(date)
                showDatePicker = false
            }
        )
    }

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
            Text("Analyse", fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }

        item {
            PeriodSelector(selected = state.period, onSelect = vm::selectPeriod)
        }

        item {
            RangeNavigator(
                state = state,
                onPrev = vm::goToPrevious,
                onNext = vm::goToNext,
                onCalendar = { showDatePicker = true },
                onToday = vm::goToToday
            )
        }

        if (state.isSyncingHistory) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(NutriSpacing.sm))
                    Text(
                        "Lade Health-Connect-Daten\u2026",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.showHistoryPermissionPrompt) {
            item {
                HistoryPermissionBanner {
                    historyPermissionLauncher.launch(vm.historyPermissionSet)
                }
            }
        }

        if (state.period == AnalysisPeriod.TAG) {
            item { DayCaloriesCard(state) }
            item { DayActivityCard(state) }
            item { DayWeightCard(state) }
        } else {
            item { CaloriesCard(state) }
            item { ActivityCaloriesCard(state) }
            item { MacroCard(state) }
            item { WeightCard(state) }
        }

        item { StreakCard(streak = state.streak) }
    }
}

// ── Zeitraum-Umschalter ─────────────────────────────────────────────────────

@Composable
private fun PeriodSelector(selected: AnalysisPeriod, onSelect: (AnalysisPeriod) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NutriRadius.lg))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AnalysisPeriod.entries.forEach { period ->
            val isSelected = period == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(period) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    period.label(),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun AnalysisPeriod.label(): String = when (this) {
    AnalysisPeriod.TAG   -> "Tag"
    AnalysisPeriod.WOCHE -> "Woche"
    AnalysisPeriod.MONAT -> "Monat"
}

// ── Zeitraum-Navigation (< Label(Kalender) >) ───────────────────────────────

@Composable
private fun RangeNavigator(
    state: AnalysisUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCalendar: () -> Unit,
    onToday: () -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vorheriger Zeitraum", modifier = Modifier.size(20.dp))
            }
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .clickable(onClick = onCalendar)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = "Datum waehlen",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(state.rangeLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onNext, enabled = !state.isCurrentPeriod) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Naechster Zeitraum",
                    modifier = Modifier.size(20.dp),
                    tint = if (state.isCurrentPeriod)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!state.isCurrentPeriod) {
            TextButton(onClick = onToday, modifier = Modifier.fillMaxWidth()) {
                Text("Zu heute springen", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                return !date.isAfter(LocalDate.now())
            }
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = pickerState.selectedDateMillis
                if (millis != null) {
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                } else {
                    onDismiss()
                }
            }) { Text("\u00DCbernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun HistoryPermissionBanner(onGrant: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            Modifier.padding(NutriSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(NutriSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    "Mehr Verlauf anzeigen",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Ohne zus\u00E4tzliche Berechtigung zeigt Health Connect nur die letzten 30 Tage. Erlaube den Zugriff, um Aktivit\u00E4tskalorien und Gewicht auch f\u00FCr \u00E4ltere Zeitr\u00E4ume zu sehen.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(NutriSpacing.sm))
                TextButton(onClick = onGrant, contentPadding = PaddingValues(0.dp)) {
                    Text("Zugriff erlauben", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Tagesansicht: einzelne Stat-Karten statt Balkendiagramm ────────────────

@Composable
private fun DayCaloriesCard(state: AnalysisUiState) {
    val day = state.days.firstOrNull()
    AnalysisCard(title = "Kalorien gegessen") {
        val eaten = day?.calories?.toInt() ?: 0
        val goal  = state.goals.computedTdee()?.toInt() ?: state.goals.dailyCalorieGoal
        Text("$eaten kcal", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(NutriSpacing.xs))
        Text("Ziel: $goal kcal", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(NutriSpacing.sm))
        val pct = (eaten.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { pct },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = MacroColors.calories,
            trackColor = MacroColors.calories.copy(alpha = 0.12f),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
private fun DayActivityCard(state: AnalysisUiState) {
    val day = state.days.firstOrNull()
    AnalysisCard(title = "Aktivit\u00E4tskalorien") {
        val burned = day?.activityCalories?.toInt() ?: 0
        Text("$burned kcal", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MacroColors.fiber)
        Spacer(Modifier.height(NutriSpacing.xs))
        Text("Aus Health Connect", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayWeightCard(state: AnalysisUiState) {
    val day = state.days.firstOrNull()
    AnalysisCard(title = "Gewicht") {
        val w = day?.weightKg
        if (w != null) {
            Text("%.1f kg".format(w), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(
                "Keine Gewichtsdaten f\u00FCr diesen Tag.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Woche/Monat: Balkendiagramme ────────────────────────────────────────────

@Composable
private fun CaloriesCard(state: AnalysisUiState) {
    AnalysisCard(title = "Kalorien pro Tag") {
        if (state.period == AnalysisPeriod.MONAT) {
            BarChart(
                values = state.weekBuckets.map { it.calories },
                labels = state.weekBuckets.map { it.label }
            )
        } else {
            val todayIndex = state.days.indexOfFirst { it.date == LocalDate.now() }
            BarChart(
                values = state.days.map { it.calories },
                labels = state.days.map { it.label },
                highlightIndex = if (todayIndex >= 0) todayIndex else null
            )
        }
        Spacer(Modifier.height(NutriSpacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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

@Composable
private fun ActivityCaloriesCard(state: AnalysisUiState) {
    AnalysisCard(title = "Aktivit\u00E4tskalorien pro Tag") {
        if (state.period == AnalysisPeriod.MONAT) {
            BarChart(
                values = state.weekBuckets.map { it.activityCalories },
                labels = state.weekBuckets.map { it.label },
                barColor = MacroColors.fiber.copy(alpha = 0.4f),
                highlightColor = MacroColors.fiber
            )
        } else {
            val todayIndex = state.days.indexOfFirst { it.date == LocalDate.now() }
            BarChart(
                values = state.days.map { it.activityCalories },
                labels = state.days.map { it.label },
                barColor = MacroColors.fiber.copy(alpha = 0.4f),
                highlightColor = MacroColors.fiber,
                highlightIndex = if (todayIndex >= 0) todayIndex else null
            )
        }
        Spacer(Modifier.height(NutriSpacing.md))
        Text(
            "\u00D8 ${state.avgActivityCalories} kcal/Tag \u00B7 Health Connect",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MacroCard(state: AnalysisUiState) {
    AnalysisCard(title = "Durchschnittliche Makros") {
        MacroAverageRow("Protein",       state.avgProtein, state.goals.proteinGoalG, MacroColors.protein)
        Spacer(Modifier.height(NutriSpacing.md))
        MacroAverageRow("Kohlenhydrate", state.avgCarbs, state.goals.carbsGoalG, MacroColors.carbs)
        Spacer(Modifier.height(NutriSpacing.md))
        MacroAverageRow("Fett",          state.avgFat, state.goals.fatGoalG, MacroColors.fat)
    }
}

@Composable
private fun WeightCard(state: AnalysisUiState) {
    AnalysisCard(title = "Gewichtsverlauf") {
        val weightPoints = state.days.filter { it.weightKg != null }
        val weightValues = weightPoints.mapNotNull { it.weightKg }
        if (weightValues.isEmpty()) {
            Text(
                "Noch keine Gewichtsdaten f\u00FCr diesen Zeitraum \u2013 trag dein Gewicht auf der Startseite ein oder verbinde Health Connect.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.")
            // Bis zu 4 gleichmäßig verteilte Datumslabels (erste, Mitte(n), letzte)
            val labelCount = minOf(4, weightPoints.size)
            val xLabels = if (labelCount <= 1) {
                listOf(weightPoints.last().date.format(dateFormatter))
            } else {
                (0 until labelCount).map { i ->
                    val idx = i * (weightPoints.size - 1) / (labelCount - 1)
                    weightPoints[idx].date.format(dateFormatter)
                }
            }
            Text(
                "Zeitraum: ${weightPoints.first().date.format(dateFormatter)} \u2013 ${weightPoints.last().date.format(dateFormatter)} \u00B7 ${weightValues.size} Messungen",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(NutriSpacing.xs))
            LineChart(
                values = weightValues,
                xLabels = xLabels,
                valueFormatter = { "%.1f kg".format(it) }
            )
            Spacer(Modifier.height(NutriSpacing.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${state.weightStart} kg",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val diff = (state.weightEnd ?: 0f) - (state.weightStart ?: 0f)
                val sign = if (diff > 0) "+" else ""
                Text(
                    "$sign%.1f kg".format(diff),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (diff > 0) MaterialTheme.colorScheme.error else MacroColors.calories
                )
                Text(
                    "${state.weightEnd} kg",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StreakCard(streak: Int) {
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
                Text("$streak Tage Streak", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Aktuelle Serie an Tagen mit Eintr\u00E4gen",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(NutriSpacing.md))
            content()
        }
    }
}

@Composable
private fun MacroAverageRow(label: String, value: Float, goal: Float, color: Color) {
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
