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
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    vm: AnalysisViewModel = viewModel(),
    onNavigateToInsights: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToDeficiencyTrend: () -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showWeekOverview by remember { mutableStateOf(false) }

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

    if (showWeekOverview) {
        WeekOverviewSheet(
            weekRows = state.weekOverview,
            weekLoading = state.weekOverviewLoading,
            dayRows = state.dayOverview,
            dayLoading = state.dayOverviewLoading,
            onDismiss = { showWeekOverview = false }
        )
    }

    val window = ch.nutrisnap.app.ui.rememberWindowInfo()
    ch.nutrisnap.app.ui.AdaptiveContent(window = window) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                Card(
                    modifier = Modifier.weight(1f).clickable(onClick = onNavigateToInsights),
                    shape = RoundedCornerShape(NutriRadius.md)
                ) {
                    Column(Modifier.padding(NutriSpacing.md)) {
                        Icon(Icons.Default.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(NutriSpacing.xs))
                        Text("Insights", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Zusammenhänge in deinen Daten", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f).clickable(onClick = onNavigateToChat),
                    shape = RoundedCornerShape(NutriRadius.md)
                ) {
                    Column(Modifier.padding(NutriSpacing.md)) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(NutriSpacing.xs))
                        Text("Frag deine App", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Chat über deine Daten", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToDeficiencyTrend),
                shape = RoundedCornerShape(NutriRadius.md)
            ) {
                Row(
                    Modifier.padding(NutriSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(NutriSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("Nährstoffmangel-Trend", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Vitamine & Mineralstoffe der letzten 14 Tage",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showWeekOverview = true
                        if (state.weekOverview.isEmpty() && !state.weekOverviewLoading) {
                            vm.loadWeekOverview(12)
                        }
                        if (state.dayOverview.isEmpty() && !state.dayOverviewLoading) {
                            vm.loadDayOverview(30)
                        }
                    },
                shape = RoundedCornerShape(NutriRadius.md)
            ) {
                Row(
                    Modifier.padding(NutriSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(NutriSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("Wochen- & Tagesübersicht", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Gewicht, Kalorien, Aktivität, Zone – Wochen oder Tage",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    } // AdaptiveContent
}

