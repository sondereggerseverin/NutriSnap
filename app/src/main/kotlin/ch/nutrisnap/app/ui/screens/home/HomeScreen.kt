package ch.nutrisnap.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.ui.components.MacroRing
import ch.nutrisnap.app.ui.viewmodel.HealthConnectViewModel

@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    hcVm: HealthConnectViewModel = viewModel(),
    onNavigateToDiary: (MealType?) -> Unit = {},
    onNavigateToHealth: () -> Unit = {},
    onNavigateToWater: () -> Unit = {},
    onNavigateToFasting: () -> Unit = {},
    onNavigateToFoodScan: () -> Unit = {},
    onNavigateToRecipeImport: () -> Unit = {}
) {
    val state by vm.uiState.collectAsState()
    val hcState by hcVm.uiState.collectAsState()
    var showWeightDialog by remember { mutableStateOf(false) }
    var showQuickAdd by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumnHome(
            state = state,
            todayHc = hcState.todayData,
            hasHcPermission = hcState.hasPermission,
            onMealClick = { meal -> onNavigateToDiary(meal) },
            onLogWeight = { showWeightDialog = true },
            onOpenHealth = onNavigateToHealth
        )

        QuickAddFab(
            expanded = showQuickAdd,
            onToggle = { showQuickAdd = !showQuickAdd },
            onAddFood = { showQuickAdd = false; onNavigateToDiary(null) },
            onScanFood = { showQuickAdd = false; onNavigateToFoodScan() },
            onLogWater = { showQuickAdd = false; onNavigateToWater() },
            onStartFasting = { showQuickAdd = false; onNavigateToFasting() },
            onImportRecipe = { showQuickAdd = false; onNavigateToRecipeImport() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }

    if (showWeightDialog) {
        WeightEntryDialog(
            currentWeight = state.lastWeightKg,
            onConfirm = { kg -> vm.logWeight(kg); showWeightDialog = false },
            onDismiss = { showWeightDialog = false }
        )
    }
}

// ── Quick-add speed-dial FAB ───────────────────────────────────────────────────
// Bündelt die häufigsten Aktionen (Essen loggen, scannen, Wasser, Fasten,
// Rezept-Import) direkt auf dem Home-Screen, statt sie unter "Mehr" zu verstecken.

@Composable
private fun QuickAddFab(
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddFood: () -> Unit,
    onScanFood: () -> Unit,
    onLogWater: () -> Unit,
    onStartFasting: () -> Unit,
    onImportRecipe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        if (expanded) {
            QuickAddAction("Rezept importieren", Icons.Default.Link, onImportRecipe)
            Spacer(Modifier.height(8.dp))
            QuickAddAction("Fasten starten", Icons.Default.Timer, onStartFasting)
            Spacer(Modifier.height(8.dp))
            QuickAddAction("Wasser eintragen", Icons.Default.WaterDrop, onLogWater)
            Spacer(Modifier.height(8.dp))
            QuickAddAction("Essen scannen", Icons.Default.PhotoCamera, onScanFood)
            Spacer(Modifier.height(8.dp))
            QuickAddAction("Essen hinzufügen", Icons.Default.Restaurant, onAddFood)
            Spacer(Modifier.height(12.dp))
        }
        FloatingActionButton(onClick = onToggle) {
            Icon(if (expanded) Icons.Default.Close else Icons.Default.Add, "Schnellaktionen")
        }
    }
}

@Composable
private fun QuickAddAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.width(8.dp))
        SmallFloatingActionButton(onClick = onClick) { Icon(icon, label) }
    }
}

@Composable
private fun LazyColumnHome(
    state: HomeUiState,
    todayHc: HealthConnectCache?,
    hasHcPermission: Boolean,
    onMealClick: (MealType) -> Unit,
    onLogWeight: () -> Unit,
    onOpenHealth: () -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { HomeHeader(state) }
        item { MealOverviewGrid(state.meals, onClick = onMealClick) }
        item { HealthCard(todayHc, hasHcPermission, onOpenHealth) }
        item { StreakCard(state.streak) }
        item { WeightQuickCard(state.lastWeightKg, state.previousWeightKg, onLogWeight) }
    }
}

// ── Health Connect summary card ───────────────────────────────────────────────

@Composable
private fun HealthCard(
    data: HealthConnectCache?,
    hasPermission: Boolean,
    onOpenHealth: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onOpenHealth),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Health Connect", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Icon(Icons.Default.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.height(12.dp))

            if (!hasPermission || data == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tippe um Health Connect zu verbinden",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    HealthStatItem(
                        icon = "👟",
                        value = "%,d".format(data.steps),
                        label = "Schritte"
                    )
                    HealthStatItem(
                        icon = "🔥",
                        value = data.activeCaloriesKcal?.let { "${it.toInt()} kcal" } ?: "–",
                        label = "Verbrannt"
                    )
                    if (data.sleepMinutes > 0) {
                        val h = data.sleepMinutes / 60
                        val m = data.sleepMinutes % 60
                        HealthStatItem(
                            icon = "😴",
                            value = "${h}h ${m}m",
                            label = "Schlaf"
                        )
                    }
                    if (data.weightKg != null) {
                        HealthStatItem(
                            icon = "⚖️",
                            value = "%.1f kg".format(data.weightKg),
                            label = "Gewicht"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthStatItem(icon: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 22.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Header with calorie ring + macro bars ─────────────────────────────────────

@Composable
private fun HomeHeader(state: HomeUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimaryContainer)
                )
            )
            .padding(20.dp, 24.dp, 20.dp, 28.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text("${state.greeting} 👋", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
                Text("Dein Tag im Ueberblick", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                if (state.isAdaptiveTarget) {
                    Text(
                        "🎯 Adaptives Ziel · ${state.tdeeConfidence}% Konfidenz",
                        fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)
                    )
                }
            }
            StreakBadge(state.streak)
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MacroRing(
                eaten = state.totalCalories, goal = state.adjustedGoal,
                size = 110.dp, strokeWidth = 9.dp,
                trackColor = Color.White.copy(alpha = 0.25f),
                progressColor = Color.White,
                overflowColor = Color(0xFFFFD67A)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${state.remaining.toInt()}",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                    Text("kcal uebrig", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }

            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LabeledValue("${state.totalCalories.toInt()}", "gegessen")
                    if (state.burnedKcal > 0f) {
                        LabeledValue("+${state.burnedKcal.toInt()}", "aktiv")
                    }
                    LabeledValue("${state.adjustedGoal.toInt()}", "Ziel")
                }
                Spacer(Modifier.height(10.dp))
                WhiteMacroBar("Protein",  state.totalProtein, state.proteinGoal)
                Spacer(Modifier.height(6.dp))
                WhiteMacroBar("Kohlenh.", state.totalCarbs,   state.carbsGoal)
                Spacer(Modifier.height(6.dp))
                WhiteMacroBar("Fett",     state.totalFat,     state.fatGoal)
            }
        }
    }
}

@Composable
private fun LabeledValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun WhiteMacroBar(label: String, value: Float, goal: Float) {
    val pct = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
            Text("${value.toInt()}g", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(pct).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Color.White))
        }
    }
}

@Composable
private fun StreakBadge(streak: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text("🔥", fontSize = 16.sp)
        Spacer(Modifier.width(4.dp))
        Text("$streak", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// ── Meal overview grid ─────────────────────────────────────────────────────────

@Composable
private fun MealOverviewGrid(meals: List<MealOverview>, onClick: (MealType) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        for (rowMeals in meals.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowMeals.forEach { meal ->
                    Card(
                        modifier = Modifier.weight(1f).clickable { onClick(meal.type) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                        .background(meal.color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) { Text(meal.icon, fontSize = 14.sp) }
                                Spacer(Modifier.width(8.dp))
                                Text(meal.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${meal.kcal.toInt()} kcal",
                                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                                color = if (meal.count > 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                            )
                            Text(
                                "${meal.count} Eintraege",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (rowMeals.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ── Streak card ───────────────────────────────────────────────────────────────

@Composable
private fun StreakCard(streak: Int) {
    if (streak <= 0) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔥", fontSize = 32.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("$streak-Tage-Streak!", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Du bist auf einem guten Weg. Weiter so!", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
            }
        }
    }
}

// ── Weight quick-entry card ───────────────────────────────────────────────────

@Composable
private fun WeightQuickCard(lastWeight: Float?, previousWeight: Float?, onLogWeight: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Aktuelles Gewicht", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        lastWeight?.let { "%.1f kg".format(it) } ?: "-",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                    )
                    val delta = if (lastWeight != null && previousWeight != null) lastWeight - previousWeight else null
                    if (delta != null && kotlin.math.abs(delta) >= 0.1f) {
                        Spacer(Modifier.width(8.dp))
                        val isUp = delta > 0
                        Text(
                            "${if (isUp) "↑" else "↓"} %.1f kg".format(kotlin.math.abs(delta)),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (isUp) Color(0xFFE05555) else Color(0xFF2D7D46)
                        )
                    }
                }
            }
            FilledTonalButton(onClick = onLogWeight) { Text("Eintragen") }
        }
    }
}

// ── Weight entry dialog ───────────────────────────────────────────────────────

@Composable
private fun WeightEntryDialog(currentWeight: Float?, onConfirm: (Float) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(currentWeight?.let { "%.1f".format(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gewicht eintragen") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Gewicht (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val v = text.replace(',', '.').toFloatOrNull()
                if (v != null && v > 0f) onConfirm(v)
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
