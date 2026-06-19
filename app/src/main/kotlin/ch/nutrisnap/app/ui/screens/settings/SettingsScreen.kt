package ch.nutrisnap.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.repository.UserProfile

enum class FitnessGoal(val label: String, val emoji: String, val desc: String) {
    LOSE_WEIGHT("Abnehmen",        "🔥", "–500 kcal vom TDEE · mehr Protein"),
    MAINTAIN(   "Halten",          "⚖️", "TDEE als Ziel · ausgewogene Makros"),
    BUILD_MUSCLE("Muskeln",        "💪", "+250 kcal über TDEE · viel Protein"),
    GAIN_WEIGHT( "Zunehmen",       "📈", "+500 kcal über TDEE"),
    SPORT(       "Sport & Leistung","🏃", "TDEE+300 · hohe Kohlenhydrate"),
}

private fun computeGoals(
    weightKg: Float, heightCm: Int, ageYears: Int,
    activityFactor: Float, goal: FitnessGoal
): Triple<Int, Float, Float> {   // kcal, protein, fat (carbs = rest)
    if (weightKg <= 0 || heightCm <= 0 || ageYears <= 0) return Triple(2000, 120f, 65f)
    val bmr  = 10f * weightKg + 6.25f * heightCm - 5f * ageYears + 5f
    val tdee = bmr * activityFactor
    val kcal = when (goal) {
        FitnessGoal.LOSE_WEIGHT  -> (tdee - 500).toInt().coerceAtLeast(1200)
        FitnessGoal.MAINTAIN     -> tdee.toInt()
        FitnessGoal.BUILD_MUSCLE -> (tdee + 250).toInt()
        FitnessGoal.GAIN_WEIGHT  -> (tdee + 500).toInt()
        FitnessGoal.SPORT        -> (tdee + 300).toInt()
    }
    val proteinG = when (goal) {
        FitnessGoal.LOSE_WEIGHT  -> weightKg * 2.2f
        FitnessGoal.BUILD_MUSCLE -> weightKg * 2.4f
        FitnessGoal.SPORT        -> weightKg * 2.2f
        else                     -> weightKg * 1.8f
    }
    val fatG = when (goal) {
        FitnessGoal.SPORT -> kcal * 0.20f / 9f
        else              -> kcal * 0.25f / 9f
    }
    return Triple(kcal, proteinG, fatG)
}

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    onNavigateToNotifSettings: () -> Unit = {},
    onNavigateToWater: () -> Unit = {},
    onNavigateToFasting: () -> Unit = {},
    onNavigateToStats: () -> Unit = {}
) {
    val state   by vm.uiState.collectAsState()
    val profile  = state.profile

    var weightText  by remember(profile.weightKg)         { mutableStateOf(if (profile.weightKg > 0f) profile.weightKg.toInt().toString() else "") }
    var heightText  by remember(profile.heightCm)         { mutableStateOf(if (profile.heightCm > 0) profile.heightCm.toString() else "") }
    var ageText     by remember(profile.ageYears)         { mutableStateOf(if (profile.ageYears > 0) profile.ageYears.toString() else "") }
    var calorieText by remember(profile.dailyCalorieGoal) { mutableStateOf(profile.dailyCalorieGoal.toString()) }
    var proteinText by remember(profile.proteinGoalG)     { mutableStateOf(profile.proteinGoalG.toInt().toString()) }
    var carbsText   by remember(profile.carbsGoalG)       { mutableStateOf(profile.carbsGoalG.toInt().toString()) }
    var fatText     by remember(profile.fatGoalG)         { mutableStateOf(profile.fatGoalG.toInt().toString()) }
    var activity    by remember(profile.activityFactor)   { mutableStateOf(profile.activityFactor) }
    var selectedGoal by remember { mutableStateOf(FitnessGoal.MAINTAIN) }
    var showSaved   by remember { mutableStateOf(false) }

    // Auto-recalculate when body data or goal changes
    fun applyGoal() {
        val w = weightText.toFloatOrNull() ?: return
        val h = heightText.toIntOrNull()   ?: return
        val a = ageText.toIntOrNull()      ?: return
        val (kcal, prot, fat) = computeGoals(w, h, a, activity, selectedGoal)
        val carbs = ((kcal - prot * 4 - fat * 9) / 4f).coerceAtLeast(50f)
        calorieText = kcal.toString()
        proteinText = prot.toInt().toString()
        carbsText   = carbs.toInt().toString()
        fatText     = fat.toInt().toString()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Einstellungen", fontWeight = FontWeight.Bold, fontSize = 22.sp)

        // Feature-Shortcuts
        SettingsCard(title = "Features", icon = Icons.Default.Apps) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNavigateToWater, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.WaterDrop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Wasser", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToFasting, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Timer, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Fasten", fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNavigateToStats, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.BarChart, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Statistik", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToNotifSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Reminder", fontSize = 12.sp)
                }
            }
        }

        // ── Ziel-Auswahl ──────────────────────────────────────────────────────
        SettingsCard(title = "Mein Ziel", icon = Icons.Default.Flag) {
            Text("Was möchtest du erreichen?", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // 2x3 grid of goal chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FitnessGoal.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { goal ->
                            val selected = goal == selectedGoal
                            FilterChip(
                                selected = selected,
                                onClick  = { selectedGoal = goal; applyGoal() },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(goal.emoji, fontSize = 14.sp)
                                        Text(goal.label, fontSize = 12.sp)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor     = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text(selectedGoal.desc, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp))
        }

        // ── Körperdaten ───────────────────────────────────────────────────────
        SettingsCard(title = "Körperdaten", icon = Icons.Default.Person) {
            GoalField("Gewicht (kg)",  weightText, KeyboardType.Number) {
                weightText = it; applyGoal()
            }
            GoalField("Grösse (cm)",   heightText, KeyboardType.Number) {
                heightText = it; applyGoal()
            }
            GoalField("Alter (Jahre)", ageText,    KeyboardType.Number) {
                ageText = it; applyGoal()
            }
        }

        // ── Ernährungsziele (auto-filled) ─────────────────────────────────────
        SettingsCard(title = "Ernährungsziele", icon = Icons.Default.TrackChanges) {
            Text("Automatisch berechnet – du kannst auch manuell anpassen.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            GoalField("Kalorienziel (kcal)",  calorieText, KeyboardType.Number) { calorieText = it }
            GoalField("Proteinziel (g)",      proteinText, KeyboardType.Number) { proteinText = it }
            GoalField("Kohlenhydratziel (g)", carbsText,   KeyboardType.Number) { carbsText   = it }
            GoalField("Fettziel (g)",         fatText,     KeyboardType.Number) { fatText     = it }
        }

        // ── Aktivitätslevel ───────────────────────────────────────────────────
        SettingsCard(title = "Aktivitätslevel", icon = Icons.Default.DirectionsRun) {
            ActivitySlider(value = activity) { activity = it; applyGoal() }
        }

        // ── TDEE Preview ──────────────────────────────────────────────────────
        val previewProfile = UserProfile(
            weightKg       = weightText.toFloatOrNull() ?: profile.weightKg,
            heightCm       = heightText.toIntOrNull()   ?: profile.heightCm,
            ageYears       = ageText.toIntOrNull()      ?: profile.ageYears,
            activityFactor = activity
        )
        previewProfile.computedTdee()?.let { tdee ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Dein Grundumsatz (TDEE)", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Basis für die Zielberechnung", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                    Text("${tdee.toInt()} kcal", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Button(
            onClick = {
                vm.save(UserProfile(
                    weightKg         = weightText.toFloatOrNull()  ?: 0f,
                    heightCm         = heightText.toIntOrNull()    ?: 0,
                    ageYears         = ageText.toIntOrNull()       ?: 0,
                    dailyCalorieGoal = calorieText.toIntOrNull()   ?: 2000,
                    proteinGoalG     = proteinText.toFloatOrNull() ?: 120f,
                    carbsGoalG       = carbsText.toFloatOrNull()   ?: 220f,
                    fatGoalG         = fatText.toFloatOrNull()     ?: 65f,
                    activityFactor   = activity
                ))
                showSaved = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Speichern")
        }

        if (showSaved) {
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); showSaved = false }
            Text("✓ Gespeichert", color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            content()
        }
    }
}

@Composable
fun GoalField(label: String, value: String, keyboardType: KeyboardType = KeyboardType.Number,
              onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
fun ActivitySlider(value: Float, onValueChange: (Float) -> Unit) {
    val levels = listOf(
        1.2f to "Sitzend (kein Sport)",
        1.375f to "Leicht aktiv (1-3x/Woche)",
        1.55f  to "Mässig aktiv (3-5x/Woche)",
        1.725f to "Sehr aktiv (6-7x/Woche)",
        1.9f   to "Extrem aktiv (2x täglich)"
    )
    val currentLabel = levels.minByOrNull { kotlin.math.abs(it.first - value) }?.second ?: ""
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(currentLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onValueChange,
            valueRange = 1.2f..1.9f, steps = 3, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sitzend",    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Sehr aktiv", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
