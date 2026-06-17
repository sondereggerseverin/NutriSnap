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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.repository.UserProfile

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    onNavigateToNotifSettings: () -> Unit = {},
    onNavigateToWater: () -> Unit = {},
    onNavigateToFasting: () -> Unit = {},
    onNavigateToStats: () -> Unit = {}
) {
    val state by vm.uiState.collectAsState()
    val profile = state.profile

    var weightText  by remember(profile.weightKg)        { mutableStateOf(if (profile.weightKg > 0f) profile.weightKg.toInt().toString() else "") }
    var heightText  by remember(profile.heightCm)        { mutableStateOf(if (profile.heightCm > 0) profile.heightCm.toString() else "") }
    var ageText     by remember(profile.ageYears)        { mutableStateOf(if (profile.ageYears > 0) profile.ageYears.toString() else "") }
    var calorieText by remember(profile.dailyCalorieGoal){ mutableStateOf(profile.dailyCalorieGoal.toString()) }
    var proteinText by remember(profile.proteinGoalG)    { mutableStateOf(profile.proteinGoalG.toInt().toString()) }
    var carbsText   by remember(profile.carbsGoalG)      { mutableStateOf(profile.carbsGoalG.toInt().toString()) }
    var fatText     by remember(profile.fatGoalG)        { mutableStateOf(profile.fatGoalG.toInt().toString()) }
    var activity    by remember(profile.activityFactor)  { mutableStateOf(profile.activityFactor) }
    var showSaved   by remember { mutableStateOf(false) }

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
                    Spacer(Modifier.width(4.dp))
                    Text("Wasser", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToFasting, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Timer, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fasten", fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNavigateToStats, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.BarChart, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Statistik", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToNotifSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Erinnerungen", fontSize = 12.sp)
                }
            }
        }

        // Körperdaten
        SettingsCard(title = "Körperdaten", icon = Icons.Default.Person) {
            GoalField("Gewicht (kg)",  weightText, KeyboardType.Number) { weightText = it }
            GoalField("Grösse (cm)",   heightText, KeyboardType.Number) { heightText = it }
            GoalField("Alter (Jahre)", ageText,    KeyboardType.Number) { ageText    = it }
        }

        // Ernährungsziele
        SettingsCard(title = "Ernährungsziele", icon = Icons.Default.TrackChanges) {
            GoalField("Kalorienziel (kcal)",  calorieText, KeyboardType.Number) { calorieText = it }
            GoalField("Proteinziel (g)",      proteinText, KeyboardType.Number) { proteinText = it }
            GoalField("Kohlenhydratziel (g)", carbsText,   KeyboardType.Number) { carbsText   = it }
            GoalField("Fettziel (g)",         fatText,     KeyboardType.Number) { fatText     = it }
        }

        // Aktivität
        SettingsCard(title = "Aktivitätslevel", icon = Icons.Default.DirectionsRun) {
            ActivitySlider(value = activity) { activity = it }
        }

        // TDEE Vorschau
        val previewProfile = UserProfile(
            weightKg       = weightText.toFloatOrNull() ?: profile.weightKg,
            heightCm       = heightText.toIntOrNull()   ?: profile.heightCm,
            ageYears       = ageText.toIntOrNull()      ?: profile.ageYears,
            activityFactor = activity
        )
        previewProfile.computedTdee()?.let { tdee ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Berechnetes Kalorienziel (TDEE)", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
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
    Card(
        Modifier.fillMaxWidth(),
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
fun GoalField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Number,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
fun ActivitySlider(value: Float, onValueChange: (Float) -> Unit) {
    val levels = listOf(
        1.2f   to "Sitzend (kein Sport)",
        1.375f to "Leicht aktiv (1-3x/Woche)",
        1.55f  to "Mässig aktiv (3-5x/Woche)",
        1.725f to "Sehr aktiv (6-7x/Woche)",
        1.9f   to "Extrem aktiv (2x täglich)"
    )
    val currentLabel = levels.minByOrNull { Math.abs(it.first - value) }?.second ?: ""

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(currentLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value, onValueChange = onValueChange,
            valueRange = 1.2f..1.9f, steps = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sitzend",    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Sehr aktiv", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
