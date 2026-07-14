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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.ui.components.MacroRing
import ch.nutrisnap.app.ui.theme.*
import ch.nutrisnap.app.ui.viewmodel.HealthConnectViewModel

@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    hcVm: HealthConnectViewModel = viewModel(),
    onNavigateToDiary: (meal: MealType?, autoOpenAdd: Boolean) -> Unit = { _, _ -> },
    onNavigateToHealth: () -> Unit = {},
    onNavigateToFoodScan: () -> Unit = {},
    onNavigateToRecipeImport: () -> Unit = {}
) {
    val state by vm.uiState.collectAsState()
    val hcState by hcVm.uiState.collectAsState()
    var showWeightDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item { HomeHeader(state) }
            item {
                MealOverviewGrid(
                    state.meals,
                    onClick = { meal -> onNavigateToDiary(meal.type, meal.count == 0) },
                    onQuickAdd = { meal -> onNavigateToDiary(meal.type, true) }
                )
            }
            item {
                QuickActionsRow(
                    onScan = onNavigateToFoodScan,
                    onRecipeImport = onNavigateToRecipeImport
                )
            }
            item { HealthCard(hcState.todayData, hcState.hasPermission, onNavigateToHealth) { showWeightDialog = true } }
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
}

@Composable
private fun QuickActionsRow(onScan: () -> Unit, onRecipeImport: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
    ) {
        QuickActionCard(
            icon = Icons.Default.QrCodeScanner,
            label = "Scannen",
            color = MacroColors.protein,
            onClick = onScan,
            modifier = Modifier.weight(1f)
        )
        QuickActionCard(
            icon = Icons.Default.Link,
            label = "Rezept-Import",
            color = MacroColors.carbs,
            onClick = onRecipeImport,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickActionCard(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.md),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            Modifier.padding(NutriSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
        ) {
            Icon(
                icon, null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
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

@Composable
private fun HomeHeader(state: HomeUiState) {
    val appTheme = LocalAppTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(appTheme.primary, appTheme.primaryDark)
                )
            )
            .padding(horizontal = NutriSpacing.xl, vertical = NutriSpacing.xxl)
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    "${state.greeting} \uD83D\uDC4B",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Dein Tag im Überblick",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (state.isAdaptiveTarget) {
                    Spacer(Modifier.height(NutriSpacing.xs))
                    Text(
                        "\uD83C\uDFAF Adaptives Ziel \u00B7 ${state.tdeeConfidence}% Konfidenz",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
            StreakBadge(state.streak)
        }

        Spacer(Modifier.height(NutriSpacing.xxl))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.xl)
        ) {
            MacroRing(
                eaten = state.totalCalories,
                goal = state.adjustedGoal,
                size = 120.dp,
                strokeWidth = 10.dp,
                trackColor = Color.White.copy(alpha = 0.2f),
                progressColor = Color.White,
                overflowColor = Color(0xFFFFD67A)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${state.remaining.toInt()}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "kcal übrig",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LabeledValue("${state.totalCalories.toInt()}", "gegessen")
                    if (state.burnedKcal > 0f) {
                        LabeledValue("+${state.burnedKcal.toInt()}", "aktiv")
                    }
                    LabeledValue("${state.adjustedGoal.toInt()}", "Ziel")
                }
                Spacer(Modifier.height(NutriSpacing.md))
                WhiteMacroBar("Protein", state.totalProtein, state.proteinGoal)
                Spacer(Modifier.height(NutriSpacing.sm))
                WhiteMacroBar("Kohlenh.", state.totalCarbs, state.carbsGoal)
                Spacer(Modifier.height(NutriSpacing.sm))
                WhiteMacroBar("Fett", state.totalFat, state.fatGoal)
                Spacer(Modifier.height(NutriSpacing.sm))
                WhiteMacroBar("Ballaststoffe", state.totalFiber, state.fiberGoal)
            }
        }
    }
}

@Composable
private fun LabeledValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun WhiteMacroBar(label: String, value: Float, goal: Float) {
    val pct = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Text(
                "${value.toInt()}g",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.2f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White)
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
            .background(Color.White.copy(alpha = 0.2f))
            .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.xs)
    ) {
        Text("\uD83D\uDD25", fontSize = 16.sp)
        Spacer(Modifier.width(NutriSpacing.xs))
        Text(
            "$streak",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun MealOverviewGrid(
    meals: List<MealOverview>,
    onClick: (MealOverview) -> Unit,
    onQuickAdd: (MealOverview) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.md),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column {
            meals.forEachIndexed { index, meal ->
                MealRow(
                    meal = meal,
                    onClick = { onClick(meal) },
                    onQuickAdd = { onQuickAdd(meal) }
                )
                if (index != meals.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

// Kompakte Listenzeile statt Grid-Karte mit überlagertem "+": alle Elemente
// liegen im selben Row-Layout (wie bei Yazio), dadurch kann nichts mehr
// überlappen und der Bildschirm braucht weniger Höhe pro Mahlzeit.
@Composable
private fun MealRow(meal: MealOverview, onClick: () -> Unit, onQuickAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(NutriRadius.sm))
                .background(meal.color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(meal.icon, fontSize = 20.sp)
        }
        Spacer(Modifier.width(NutriSpacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meal.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Default.ChevronRight, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${meal.kcal.toInt()} kcal · ${meal.count} ${if (meal.count == 1) "Eintrag" else "Einträge"}",
                fontSize = 12.sp,
                color = if (meal.count > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(NutriSpacing.sm))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onQuickAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                "Zu ${meal.label} hinzufügen",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
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
