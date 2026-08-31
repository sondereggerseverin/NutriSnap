package ch.nutrisnap.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.ACTIVITY_PRESETS
import ch.nutrisnap.app.domain.ActivityPreset
import ch.nutrisnap.app.domain.estimateKcal
import ch.nutrisnap.app.ui.components.MacroRing
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_FRESH_HOME
import ch.nutrisnap.app.ui.theme.KEY_FRESH_UI
import ch.nutrisnap.app.ui.theme.*
import ch.nutrisnap.app.ui.viewmodel.HealthConnectViewModel

@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    hcVm: HealthConnectViewModel = viewModel(),
    onNavigateToDiary: (meal: MealType?, autoOpenAdd: Boolean) -> Unit = { _, _ -> },
    onNavigateToHealth: () -> Unit = {},
    onNavigateToFoodScan: () -> Unit = {},
    onNavigateToBarcode: () -> Unit = {},
    onNavigateToLabelScan: () -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val hcState by hcVm.uiState.collectAsStateWithLifecycle()
    val macroSuggestions by vm.macroSuggestions.collectAsStateWithLifecycle()
    var showWeightDialog by remember { mutableStateOf(false) }
    var showActivityDialog by remember { mutableStateOf(false) }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    val window = ch.nutrisnap.app.ui.rememberWindowInfo()
    val homeContext = LocalContext.current
    val homePrefs by homeContext.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val reorderHome = homePrefs?.get(KEY_TOGGLE_HOME_REORDER) ?: false
    val mergeActivityCards = (homePrefs?.get(KEY_TOGGLE_HOME_ACTIVITY_MERGE) ?: false) && state.manualActivityEnabled

    ch.nutrisnap.app.ui.AdaptiveContent(window = window) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (window.isTablet) 16.dp else 0.dp,
                end = if (window.isTablet) 16.dp else 0.dp,
                bottom = 100.dp
            )
        ) {
            item {
                HomeHeader(
                    state = state,
                    onShowYesterday = { vm.showYesterday() },
                    onShowToday = { vm.showToday() },
                    onEditActivity = { showActivityDialog = true }
                )
            }
            item {
                MealOverviewGrid(
                    state.meals,
                    isViewingToday = state.isViewingToday,
                    onClick = { meal -> onNavigateToDiary(meal.type, meal.count == 0) },
                    onQuickAdd = { meal -> onNavigateToDiary(meal.type, true) }
                )
            }
            if (reorderHome) {
                // Design-Toggle #16 "Home-Reihenfolge neu": Ring → Meals → Makros → Activity.
                // Scan-Zugriff und Makro-Vorschläge sind Zusatz-Widgets, keine der vier
                // Kern-Sektionen – rücken hinter Activity, vor dem Streak.
                item { CalorieBreakdownCard(state) }
                if (mergeActivityCards) {
                    item {
                        ActivityCombinedCard(
                            data = hcState.todayData,
                            hasPermission = hcState.hasPermission,
                            onOpenHealth = onNavigateToHealth,
                            onEditWeight = { showWeightDialog = true },
                            manualTodayKcal = state.manualActivityKcal,
                            manualTotalActive = state.burnedKcal,
                            onOpenManualActivity = { showActivityDialog = true }
                        )
                    }
                } else {
                    item { HealthCard(hcState.todayData, hcState.hasPermission, onNavigateToHealth) { showWeightDialog = true } }
                    if (state.manualActivityEnabled) {
                        item {
                            ManualActivityCard(
                                todayKcal = state.manualActivityKcal,
                                totalActive = state.burnedKcal,
                                onClick = { showActivityDialog = true }
                            )
                        }
                    }
                }
                item {
                    HomeScanQuickAccess(
                        onFoodScan = onNavigateToFoodScan,
                        onBarcode = onNavigateToBarcode,
                        onLabelScan = onNavigateToLabelScan
                    )
                }
                if (macroSuggestions.isNotEmpty() && state.isViewingToday) {
                    item {
                        RemainingMacroSuggestionsCard(
                            remainingKcal = state.remaining,
                            remainingProtein = state.remainingProtein,
                            suggestions = macroSuggestions,
                            onAdd = { suggestion ->
                                vm.applyMacroSuggestion(suggestion) { ok ->
                                    snackMessage = if (ok) "„${suggestion.title}“ hinzugefügt"
                                    else "Konnte nicht hinzufügen"
                                }
                            }
                        )
                    }
                }
            } else {
                item {
                    HomeScanQuickAccess(
                        onFoodScan = onNavigateToFoodScan,
                        onBarcode = onNavigateToBarcode,
                        onLabelScan = onNavigateToLabelScan
                    )
                }
                if (macroSuggestions.isNotEmpty() && state.isViewingToday) {
                    item {
                        RemainingMacroSuggestionsCard(
                            remainingKcal = state.remaining,
                            remainingProtein = state.remainingProtein,
                            suggestions = macroSuggestions,
                            onAdd = { suggestion ->
                                vm.applyMacroSuggestion(suggestion) { ok ->
                                    snackMessage = if (ok) "„${suggestion.title}“ hinzugefügt"
                                    else "Konnte nicht hinzufügen"
                                }
                            }
                        )
                    }
                }
                // Breakdown unter den Mahlzeiten, damit Ring + 4 Kacheln ohne Scrollen passen
                item { CalorieBreakdownCard(state) }
                if (mergeActivityCards) {
                    item {
                        ActivityCombinedCard(
                            data = hcState.todayData,
                            hasPermission = hcState.hasPermission,
                            onOpenHealth = onNavigateToHealth,
                            onEditWeight = { showWeightDialog = true },
                            manualTodayKcal = state.manualActivityKcal,
                            manualTotalActive = state.burnedKcal,
                            onOpenManualActivity = { showActivityDialog = true }
                        )
                    }
                } else {
                    item { HealthCard(hcState.todayData, hcState.hasPermission, onNavigateToHealth) { showWeightDialog = true } }
                    if (state.manualActivityEnabled) {
                        item {
                            ManualActivityCard(
                                todayKcal = state.manualActivityKcal,
                                totalActive = state.burnedKcal,
                                onClick = { showActivityDialog = true }
                            )
                        }
                    }
                }
            }
            item { StreakCard(state.streak) }
        }
    }

    if (showWeightDialog) {
        WeightEntryDialog(
            currentWeight = state.lastWeightKg,
            onConfirm = { kg -> vm.logWeight(kg); showWeightDialog = false },
            onDismiss = { showWeightDialog = false }
        )
    }
    if (showActivityDialog) {
        val hcBase = (state.burnedKcal - (state.manualActivityKcal ?: 0f))
            .toInt().coerceAtLeast(0)
            .takeIf { state.burnedKcal > 0f || (state.manualActivityKcal ?: 0f) > 0f }
        ManualActivityDialog(
            currentKcal = state.manualActivityKcal,
            weightKg = state.lastWeightKg ?: 75f,
            dayLabel = if (state.isViewingToday) "heute" else "gestern",
            healthConnectKcal = hcBase,
            onConfirm = { kcal ->
                vm.logManualActivity(kcal, state.selectedDate)
                showActivityDialog = false
            },
            onDismiss = { showActivityDialog = false }
        )
    }
    snackMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2200)
            snackMessage = null
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
            ) {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    fontSize = 14.sp
                )
            }
        }
    }
}

