package ch.nutrisnap.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.ACTIVITY_PRESETS
import ch.nutrisnap.app.domain.ActivityPreset
import ch.nutrisnap.app.domain.estimateKcal
import ch.nutrisnap.app.ui.components.MacroRing
import ch.nutrisnap.app.ui.components.NutriCard
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.*

@Composable
internal fun HealthCard(
    data: ch.nutrisnap.app.data.model.HealthConnectCache?,
    hasPermission: Boolean,
    onOpenHealth: () -> Unit,
    onEditWeight: () -> Unit = {}
) {
    NutriCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs),
        onClick = onOpenHealth
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
internal fun HealthStatItem(icon: String, value: String, label: String) {
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
internal fun HomeHeader(
    state: HomeUiState,
    onShowYesterday: () -> Unit = {},
    onShowToday: () -> Unit = {},
    onEditActivity: () -> Unit = {}
) {
    val appTheme = LocalAppTheme.current
    val macros = rememberMacroColors()
    val overGoal = state.totalCalories > state.adjustedGoal && state.adjustedGoal > 0f
    val headerContext = LocalContext.current
    val headerPrefs by headerContext.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val highlightRemaining = headerPrefs?.get(KEY_TOGGLE_CALORIES_REMAINING_HIGHLIGHT) ?: false

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
                        color = if (highlightRemaining) {
                            if (overGoal) Color(0xFFFFD67A) else macros.calories
                        } else {
                            Color.White
                        }
                    )
                    Text(
                        if (overGoal) "über" else "übrig",
                        fontSize = 10.sp,
                        color = if (highlightRemaining && !overGoal) {
                            macros.calories.copy(alpha = 0.85f)
                        } else {
                            Color.White.copy(alpha = 0.75f)
                        }
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Glas-Karte: 2-Spalten-Grid, damit die Breite für Inhalt statt Luft genutzt wird
            val headerStats = buildList {
                add(Triple("Gegessen", "${state.totalCalories.toInt()} kcal", Color.White))
                // Immer anzeigen — Tap öffnet Korrektur (HC + manueller Zusatz)
                val aktivLabel = when {
                    state.manualActivityKcal != null && state.manualActivityKcal!! > 0f ->
                        "+${state.burnedKcal.toInt()} kcal · ✎"
                    state.burnedKcal > 0f ->
                        "+${state.burnedKcal.toInt()} kcal"
                    else -> "anpassen"
                }
                add(Triple("Aktiv", aktivLabel, Color(0xFFFFE08A)))
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
                            HeaderStatCell(
                                label = label,
                                value = value,
                                valueColor = color,
                                modifier = Modifier.weight(1f),
                                onClick = if (label == "Aktiv") onEditActivity else null
                            )
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
            MacroColumn("Protein", state.totalProtein, state.proteinGoal, accent = macros.protein, modifier = Modifier.weight(1f))
            MacroColumn("Kohlenh.", state.totalCarbs, state.carbsGoal, accent = macros.carbs, modifier = Modifier.weight(1f))
            MacroColumn("Fett", state.totalFat, state.fatGoal, accent = macros.fat, modifier = Modifier.weight(1f))
            MacroColumn("Ballast.", state.totalFiber, state.fiberGoal, decimals = 1, accent = macros.fiber, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun HeaderStatCell(
    label: String,
    value: String,
    valueColor: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier.then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
    ) {
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
internal fun MacroColumn(
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
internal fun StreakBadge(streak: Int) {
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
internal fun MealOverviewGrid(
    meals: List<MealOverview>,
    isViewingToday: Boolean = true,
    onClick: (MealOverview) -> Unit,
    onQuickAdd: (MealOverview) -> Unit
) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
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
        NutriCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs)
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
internal fun MealRowItem(meal: MealOverview, onClick: () -> Unit, onQuickAdd: () -> Unit) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val largerQuickAdd = prefs?.get(KEY_TOGGLE_TOUCH_MEAL_QUICKADD) ?: true
    val largerIcon = prefs?.get(KEY_TOGGLE_TOUCH_MEAL_ICON) ?: true
    val iconSize = if (largerIcon) 46.dp else 38.dp
    val quickAddSize = if (largerQuickAdd) 46.dp else 34.dp
    val quickAddIconSize = if (largerQuickAdd) 22.dp else 17.dp

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
                        .size(iconSize)
                        .clip(RoundedCornerShape(11.dp))
                        .background(meal.color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(meal.icon, fontSize = if (largerIcon) 20.sp else 17.sp)
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
                modifier = Modifier.size(quickAddSize),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = meal.color.copy(alpha = 0.16f),
                    contentColor = meal.color
                )
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Zu ${meal.label} hinzufügen",
                    modifier = Modifier.size(quickAddIconSize)
                )
            }
        }
    }
}

/** Kompakte Kachel für 2×2-Home-Raster (alle 4 Mahlzeiten sichtbar). */
@Composable
internal fun MealTile(
    meal: MealOverview,
    onClick: () -> Unit,
    onQuickAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val largerQuickAdd = prefs?.get(KEY_TOGGLE_TOUCH_MEAL_QUICKADD) ?: true
    val largerIcon = prefs?.get(KEY_TOGGLE_TOUCH_MEAL_ICON) ?: true
    val iconSize = if (largerIcon) 44.dp else 32.dp
    val quickAddHeight = if (largerQuickAdd) 44.dp else 32.dp

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
                        .size(iconSize)
                        .clip(RoundedCornerShape(10.dp))
                        .background(meal.color.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(meal.icon, fontSize = if (largerIcon) 18.sp else 16.sp)
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
                modifier = Modifier.fillMaxWidth().height(quickAddHeight),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = meal.color.copy(alpha = 0.16f),
                    contentColor = meal.color
                )
            ) {
                Icon(Icons.Default.Add, "Zu ${meal.label} hinzufügen", Modifier.size(if (largerQuickAdd) 22.dp else 20.dp))
            }
        }
    }
}

/** Direkter Scan-Zugriff auf der Startseite (Essen, Barcode, Nährwerttabelle). */
@Composable
internal fun HomeScanQuickAccess(
    onFoodScan: () -> Unit,
    onBarcode: () -> Unit,
    onLabelScan: () -> Unit
) {
    val macros = rememberMacroColors()
    NutriCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs)
    ) {
        Column(Modifier.padding(NutriSpacing.md)) {
            Text(
                "Scannen",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = NutriSpacing.sm)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
            ) {
                HomeScanChip(
                    icon = Icons.Default.PhotoCamera,
                    label = "Essen",
                    color = macros.calories,
                    onClick = onFoodScan,
                    modifier = Modifier.weight(1f)
                )
                HomeScanChip(
                    icon = Icons.Default.QrCodeScanner,
                    label = "Barcode",
                    color = macros.protein,
                    onClick = onBarcode,
                    modifier = Modifier.weight(1f)
                )
                HomeScanChip(
                    icon = Icons.Default.CameraAlt,
                    label = "Nährwert",
                    color = macros.carbs,
                    onClick = onLabelScan,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HomeScanChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(NutriRadius.md),
        color = color.copy(alpha = 0.12f)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, Modifier.size(22.dp), tint = color)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun StreakCard(streak: Int) {
    if (streak <= 0) return
    val macros = rememberMacroColors()
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = macros.fat.copy(alpha = 0.1f))
    ) {
        Row(
            Modifier.padding(NutriSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(macros.fat.copy(alpha = 0.15f)),
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
internal fun WeightEntryDialog(
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
internal fun ManualActivityCard(
    todayKcal: Float?,
    totalActive: Float,
    onClick: () -> Unit
) {
    NutriCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
        onClick = onClick
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
internal fun ManualActivityDialog(
    currentKcal: Float?,
    weightKg: Float,
    onConfirm: (kcal: Float) -> Unit,
    onDismiss: () -> Unit,
    dayLabel: String = "heute",
    healthConnectKcal: Int? = null
) {
    var text by remember {
        mutableStateOf(currentKcal?.takeIf { it > 0f }?.let { it.toInt().toString() } ?: "")
    }
    var selectedPreset by remember { mutableStateOf<ActivityPreset?>(null) }
    var durationText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aktivität $dayLabel") },
        text = {
            Column {
                Text(
                    "Zusatz zu Health Connect (z. B. fehlende Sport-kcal). " +
                        (healthConnectKcal?.let { "HC: $it kcal. " } ?: "") +
                        "Preset: MET × Gewicht × Dauer.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                // Presets
                ACTIVITY_PRESETS.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { preset ->
                            val selected = selectedPreset?.name == preset.name
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedPreset = preset
                                    durationText = preset.defaultDurationMin.toInt().toString()
                                    val dur = preset.defaultDurationMin
                                    val kcal = preset.estimateKcal(weightKg, dur)
                                    text = kcal.toInt().toString()
                                },
                                label = { Text(preset.name, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                if (selectedPreset != null) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { raw ->
                            durationText = raw.filter { it.isDigit() || it == ',' || it == '.' }
                            val dur = durationText.replace(',', '.').toFloatOrNull() ?: selectedPreset!!.defaultDurationMin
                            val kcal = selectedPreset!!.estimateKcal(weightKg, dur)
                            text = kcal.toInt().toString()
                        },
                        label = { Text("Dauer (Minuten)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }
                        selectedPreset = null // manuelle kcal → Preset lösen
                    },
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
                val dur = durationText.replace(',', '.').toFloatOrNull()
                onConfirm(v.coerceAtLeast(0f))
            }) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}


@Composable
internal fun CalorieBreakdownCard(state: HomeUiState) {
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
internal fun BreakdownLine(label: String, value: String, emphasize: Boolean = false) {
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

@Composable
internal fun RemainingMacroSuggestionsCard(
    remainingKcal: Float,
    remainingProtein: Float,
    suggestions: List<ch.nutrisnap.app.domain.MacroSuggestion>,
    onAdd: (ch.nutrisnap.app.domain.MacroSuggestion) -> Unit
) {
    if (suggestions.isEmpty()) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Was passt noch?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${remainingKcal.toInt()} kcal · P ${remainingProtein.toInt()}g offen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            suggestions.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onAdd(s) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val emoji = when (s.kind) {
                        ch.nutrisnap.app.domain.MacroSuggestionKind.RECIPE -> "🍽️"
                        ch.nutrisnap.app.domain.MacroSuggestionKind.CUSTOM_FOOD -> "⭐"
                        ch.nutrisnap.app.domain.MacroSuggestionKind.FREQUENT_FOOD -> "🔁"
                    }
                    Text(emoji, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            s.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    FilledTonalButton(
                        onClick = { onAdd(s) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Hinzufügen", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
