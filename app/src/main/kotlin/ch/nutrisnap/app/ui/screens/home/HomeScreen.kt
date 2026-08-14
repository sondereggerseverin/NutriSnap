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
    val state by vm.uiState.collectAsState()
    val hcState by hcVm.uiState.collectAsState()
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
            onConfirm = { kcal -> vm.logManualActivity(kcal); showActivityDialog = false },
            onDismiss = { showActivityDialog = false }
        )
    }
}

@Composable
private fun HealthCard(
    data: ch.nutrisnap.app.data.model.HealthConnectCache?,
    hasPermission: Boolean,
    onOpenHealth: () -> Unit,
    onEditWeight: () -> Unit = {}
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs)
            .clickable(onClick = onOpenHealth),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(NutriSpacing.lg)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Favorite, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(NutriSpacing.sm))
                    Text(
                        "Health Connect",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
                Icon(
                    Icons.Default.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.height(NutriSpacing.md))

            if (!hasPermission || data == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(NutriSpacing.sm))
                    Text(
                        "Tippe um Health Connect zu verbinden",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    HealthStatItem(icon = "\uD83D\uDC63", value = "%,d".format(data.steps), label = "Schritte")
                    HealthStatItem(
                        icon = "\uD83D\uDD25",
                        value = data.activeCaloriesKcal?.let { "${it.toInt()} kcal" } ?: "–",
                        label = "Verbrannt"
                    )
                    if (data.sleepMinutes > 0) {
                        val h = data.sleepMinutes / 60
                        val m = data.sleepMinutes % 60
                        HealthStatItem(icon = "\uD83D\uDE34", value = "${h}h ${m}m", label = "Schlaf")
                    }
                    if (data.weightKg != null) {
                        Box {
                            HealthStatItem(
                                icon = "\u2696\uFE0F",
                                value = "%.1f kg".format(data.weightKg),
                                label = "Gewicht"
                            )
                            // Edit icon for manual weight correction
                            Icon(
                                Icons.Default.Edit, "Gewicht bearbeiten",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(12.dp)
                                    .clickable { onEditWeight() }
                            )
                        }
                    }
                }
                // Auto-sync hint
                Spacer(Modifier.height(NutriSpacing.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Sync, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Automatisch synchronisiert via Health Connect",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthStatItem(icon: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 20.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Kompakter Header: gleiche ungefähre Höhe wie vor dem Redesign,
 * damit Ring + alle 4 Mahlzeiten wieder ohne Scrollen auf den Screen passen.
 */
@Composable
private fun HomeHeader(
    state: HomeUiState,
    onShowYesterday: () -> Unit = {},
    onShowToday: () -> Unit = {}
) {
    val appTheme = LocalAppTheme.current
    val overGoal = state.totalCalories > state.adjustedGoal && state.adjustedGoal > 0f

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(appTheme.primary, appTheme.primaryDark)
                ),
                shape = RoundedCornerShape(bottomStart = NutriRadius.xl, bottomEnd = NutriRadius.xl)
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 6.dp)
    ) {
        // Top-Zeile: Begrüßung + Heute/Gestern-Chip + Streak (keine Extra-Höhe)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.greeting,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Medium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.isViewingToday) "Heute" else "Gestern",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = {
                            if (state.isViewingToday) onShowYesterday() else onShowToday()
                        },
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.18f)
                    ) {
                        Text(
                            if (state.isViewingToday) "Gestern" else "Heute",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    if (state.isAdaptiveTarget && state.isViewingToday) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Adaptiv · ${state.tdeeConfidence}%",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            StreakBadge(state.streak)
        }

        Spacer(Modifier.height(5.dp))

        // Ring + dichte Kennzahlen-Karte (füllt den rechten Bereich, kein leerer Gap)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MacroRing(
                eaten = state.totalCalories,
                goal = state.adjustedGoal,
                size = 100.dp,
                strokeWidth = 9.dp,
                trackColor = Color.White.copy(alpha = 0.18f),
                progressColor = Color.White,
                overflowColor = Color(0xFFFFD67A)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (overGoal) {
                            "+${(state.totalCalories - state.adjustedGoal).toInt()}"
                        } else {
                            "${state.remaining.toInt()}"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        if (overGoal) "über" else "übrig",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Glas-Karte: 2-Spalten-Grid, damit die Breite für Inhalt statt Luft genutzt wird
            val headerStats = buildList {
                add(Triple("Gegessen", "${state.totalCalories.toInt()} kcal", Color.White))
                if (state.burnedKcal > 0f) {
                    add(Triple("Aktiv", "+${state.burnedKcal.toInt()} kcal", Color(0xFFFFE08A)))
                }
                add(Triple("Ziel", "${state.adjustedGoal.toInt()} kcal", Color.White))
                state.lastWeightKg?.let { kg ->
                    val delta = state.previousWeightKg?.let { prev -> kg - prev }
                    val deltaText = when {
                        delta == null -> null
                        delta > 0.05f -> " · +${"%.1f".format(delta)} kg"
                        delta < -0.05f -> " · ${"%.1f".format(delta)} kg"
                        else -> " · ±0"
                    }
                    add(Triple("Gewicht", "${"%.1f".format(kg)} kg${deltaText ?: ""}", Color.White))
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(Color.White.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                headerStats.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (label, value, color) ->
                            HeaderStatCell(label, value, color, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        // Makros: eine flache Zeile (4 Spalten)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NutriRadius.md))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MacroColumn("Protein", state.totalProtein, state.proteinGoal, accent = MacroColors.protein, modifier = Modifier.weight(1f))
            MacroColumn("Kohlenh.", state.totalCarbs, state.carbsGoal, accent = MacroColors.carbs, modifier = Modifier.weight(1f))
            MacroColumn("Fett", state.totalFat, state.fatGoal, accent = MacroColors.fat, modifier = Modifier.weight(1f))
            MacroColumn("Ballast.", state.totalFiber, state.fiberGoal, decimals = 1, accent = MacroColors.fiber, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeaderStatCell(
    label: String,
    value: String,
    valueColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.65f),
            maxLines = 1
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1
        )
    }
}

@Composable
private fun MacroColumn(
    label: String,
    value: Float,
    goal: Float,
    decimals: Int = 0,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val pct = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val valueText = if (decimals > 0) "%.${decimals}f".format(value) else value.toInt().toString()
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(
                "${valueText}g",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
        }
        Text(
            label,
            fontSize = 9.sp,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
}

@Composable
private fun StreakBadge(streak: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(NutriRadius.xxl))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.xs)
    ) {
        Text("\uD83D\uDD25", fontSize = 14.sp)
        Spacer(Modifier.width(NutriSpacing.xs))
        Text(
            "$streak",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun MealOverviewGrid(
    meals: List<MealOverview>,
    isViewingToday: Boolean = true,
    onClick: (MealOverview) -> Unit,
    onQuickAdd: (MealOverview) -> Unit
) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsState(initial = null)
    val freshHome = (prefs?.get(KEY_FRESH_HOME) == true) || (prefs?.get(KEY_FRESH_UI) == true)

    if (freshHome) {
        // 2×2-Raster: Ring + alle 4 Mahlzeiten auf einer Bildschirmhöhe
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (isViewingToday) "Heute auf dem Plan" else "Gestern auf dem Plan",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            meals.chunked(2).forEach { rowMeals ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowMeals.forEach { meal ->
                        MealTile(
                            meal = meal,
                            onClick = { onClick(meal) },
                            onQuickAdd = { onQuickAdd(meal) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowMeals.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs),
            shape = RoundedCornerShape(NutriRadius.lg),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column {
                meals.forEachIndexed { index, meal ->
                    MealRowItem(
                        meal = meal,
                        onClick = { onClick(meal) },
                        onQuickAdd = { onQuickAdd(meal) }
                    )
                    if (index < meals.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )
                    }
                }
            }
        }
    }
}

/** Zeile innerhalb der gemeinsamen Mahlzeiten-Karte: farbiger Akzentstreifen links
 *  sorgt für schnelle visuelle Unterscheidung, ohne 4 separate Kartenboxen mit
 *  eigenem Rand zu brauchen (wirkte zuvor unruhig/zusammenhangslos). */
@Composable
private fun MealRowItem(meal: MealOverview, onClick: () -> Unit, onQuickAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(meal.color)
        )
        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(meal.color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(meal.icon, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            meal.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            Icons.Default.ChevronRight, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "${meal.kcal.toInt()} kcal · ${meal.count} ${if (meal.count == 1) "Eintrag" else "Einträge"}",
                        fontSize = 11.sp,
                        color = if (meal.count > 0) meal.color else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalIconButton(
                onClick = onQuickAdd,
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = meal.color.copy(alpha = 0.16f),
                    contentColor = meal.color
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Zu ${meal.label} hinzufügen",
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/** Kompakte Kachel für 2×2-Home-Raster (alle 4 Mahlzeiten sichtbar). */
@Composable
private fun MealTile(
    meal: MealOverview,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(meal.color.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(meal.icon, fontSize = 16.sp)
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        meal.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        "${meal.kcal.toInt()} kcal",
                        fontSize = 10.sp,
                        color = if (meal.count > 0) meal.color
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalIconButton(
                onClick = onQuickAdd,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = meal.color.copy(alpha = 0.16f),
                    contentColor = meal.color
                )
            ) {
                Icon(Icons.Default.Add, "Zu ${meal.label} hinzufügen", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StreakCard(streak: Int) {
    if (streak <= 0) return
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MacroColors.fat.copy(alpha = 0.1f))
    ) {
        Row(
            Modifier.padding(NutriSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(MacroColors.fat.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("\uD83D\uDD25", fontSize = 24.sp)
            }
            Spacer(Modifier.width(NutriSpacing.lg))
            Column {
                Text(
                    "$streak-Tage-Streak!",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Du bist auf einem guten Weg. Weiter so!",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WeightEntryDialog(
    currentWeight: Float?,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentWeight?.let { "%.1f".format(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gewicht eintragen") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Gewicht (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.replace(',', '.').toFloatOrNull()
                if (v != null && v > 0f) onConfirm(v)
            }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun ManualActivityCard(
    todayKcal: Float?,
    totalActive: Float,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(NutriSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🏃", fontSize = 22.sp)
            Spacer(Modifier.width(NutriSpacing.md))
            Column(Modifier.weight(1f)) {
                Text("Manuelle Aktivität", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    if (todayKcal != null && todayKcal > 0f)
                        "+${todayKcal.toInt()} kcal ins Ziel · gesamt aktiv ${totalActive.toInt()} kcal"
                    else
                        "Tippen, um Aktivitätskalorien einzutragen (zählen 1:1)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (todayKcal != null && todayKcal > 0f) "${todayKcal.toInt()}" else "+",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ManualActivityDialog(
    currentKcal: Float?,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember {
        mutableStateOf(currentKcal?.takeIf { it > 0f }?.let { it.toInt().toString() } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aktivität heute") },
        text = {
            Column {
                Text(
                    "Kalorien aus Sport/Bewegung, die nicht (vollständig) über Health Connect kommen. Werden zur HC-Aktivität addiert.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } },
                    label = { Text("Aktivitätskalorien (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.replace(',', '.').toFloatOrNull() ?: 0f
                onConfirm(v.coerceAtLeast(0f))
            }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}


@Composable
private fun CalorieBreakdownCard(state: HomeUiState) {
    val b = state.calorieBreakdown
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.md)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "So rechnet sich dein Ziel",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        if (b != null)
                            "${b.targetKcal} kcal · ${if (b.isTrendBased) "aus deinem Verlauf" else "Formel + Aktivität"} · ${b.confidencePercent}%"
                        else
                            "Statisches Ziel ${state.calorieGoal.toInt()} kcal + Sport",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!expanded) return@Column

            Spacer(Modifier.height(NutriSpacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(NutriSpacing.md))

            if (b == null) {
                BreakdownLine("Tagesziel (Profil)", "${state.calorieGoal.toInt()} kcal")
                BreakdownLine(
                    if (state.manualActivityEnabled) "Heute aktiv (HC + manuell)" else "Heute aktiv (Health Connect)",
                    "+${state.burnedKcal.toInt()} kcal"
                )
                BreakdownLine("Budget heute", "${state.adjustedGoal.toInt()} kcal", emphasize = true)
                BreakdownLine("Gegessen", "−${state.totalCalories.toInt()} kcal")
                BreakdownLine("Noch übrig", "${state.remaining.toInt()} kcal", emphasize = true)
                Spacer(Modifier.height(NutriSpacing.sm))
                Text(
                    "Tipp: Gewicht + Mahlzeiten über ≥5 Tage tracken → adaptives Ziel aus deinem echten Verbrauch.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
                return@Column
            }

            Text("1. Erhaltungsbedarf (TDEE)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            b.formulaBmrKcal?.let {
                BreakdownLine("Grundumsatz BMR (Ruhe)", "$it kcal")
            }
            b.formulaTdeeKcal?.let {
                BreakdownLine("Formel-TDEE (BMR × Aktivität)", "$it kcal")
            }
            if (b.trendTdeeKcal != null) {
                BreakdownLine(
                    "Trend-TDEE (Gewicht + Essen)",
                    "${b.trendTdeeKcal} kcal" + if (b.isTrendBased) " ✓" else ""
                )
            } else {
                Text(
                    "Trend noch nicht nutzbar – mind. 5 Tage mit Gewicht (HC oder manuell) und getrackten Mahlzeiten.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            }
            BreakdownLine(
                "Verwendet",
                "${b.maintenanceKcal} kcal (${when { b.formulaBmrKcal != null && !b.isTrendBased && b.maintenanceKcal == b.formulaBmrKcal -> "BMR"; b.isTrendBased -> "Trend"; else -> "Formel" }})",
                emphasize = true
            )

            if (b.weightChangeKg != null || b.avgIntakeKcal != null) {
                Spacer(Modifier.height(NutriSpacing.md))
                Text("Verlauf (${b.trendSpanDays} Tage, ${b.trendOverlapDays} Überlappungen)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                b.weightChangeKg?.let { w ->
                    val label = when {
                        w < -0.05f -> "Abgenommen"
                        w > 0.05f -> "Zugenommen"
                        else -> "Gewicht stabil"
                    }
                    val sign = if (w > 0) "+" else ""
                    BreakdownLine(label, "$sign${"%.2f".format(w)} kg")
                }
                b.avgIntakeKcal?.let {
                    BreakdownLine("Ø gegessen / Tag", "$it kcal")
                }
            }

            Spacer(Modifier.height(NutriSpacing.md))
            Text("2. Tagesziel", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            val deficitNote = b.weeklyTargetLossKg?.let {
                "−${b.deficitKcal} kcal (Ziel ${"%.1f".format(it)} kg/Woche)"
            } ?: "−${b.deficitKcal} kcal (Standard ~0,5 kg/Woche)"
            BreakdownLine("Defizit", deficitNote)
            val act = b.activityBonusKcal
            val pct = if (state.aggressiveSportDay) "100%" else "50%"
            val actLabel = when {
                act > 0 -> "+$act kcal (über Ø, $pct)"
                act < 0 -> "$act kcal (unter Ø, $pct)"
                else -> "±0 kcal (wie Ø)"
            }
            BreakdownLine("Aktivitäts-Anpassung", actLabel)
            b.todayActiveKcal?.let { BreakdownLine("Heute aktiv (HC + manuell)", "$it kcal") }
            b.manualActivityKcal?.let {
                BreakdownLine("davon manuell", "$it kcal")
            }
            BreakdownLine("Ziel heute", "${b.targetKcal} kcal", emphasize = true)

            Spacer(Modifier.height(NutriSpacing.md))
            Text("3. Stand heute", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            BreakdownLine("Gegessen", "−${state.totalCalories.toInt()} kcal")
            BreakdownLine("Noch übrig", "${state.remaining.toInt()} kcal", emphasize = true)

            Spacer(Modifier.height(NutriSpacing.sm))
            Text(
                "Erhaltung aus Verlauf (Zufuhr vs. Gewicht) oder Formel-TDEE. " +
                    "Sport: Abweichung vom Ø-Aktiv × ${if (state.aggressiveSportDay) "100" else "50"}%. " +
                    "Normaler Tag ≈ 2500, großer Sporttag deutlich mehr. " +
                    "Konfidenz ${b.confidencePercent}%.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun BreakdownLine(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium,
            color = if (emphasize) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
