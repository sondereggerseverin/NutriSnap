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
    onNavigateToHealth: () -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val hcState by hcVm.uiState.collectAsStateWithLifecycle()
    var showWeightDialog by remember { mutableStateOf(false) }
    var showActivityDialog by remember { mutableStateOf(false) }
    val window = ch.nutrisnap.app.ui.rememberWindowInfo()

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
                    onShowToday = { vm.showToday() }
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
            // Breakdown unter den Mahlzeiten, damit Ring + 4 Kacheln ohne Scrollen passen
            item { CalorieBreakdownCard(state) }
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
        ManualActivityDialog(
            currentKcal = state.manualActivityKcal,
            weightKg = state.lastWeightKg ?: 75f,
            onConfirm = { kcal ->
                vm.logManualActivity(kcal)
                showActivityDialog = false
            },
            onDismiss = { showActivityDialog = false }
        )
    }
}

