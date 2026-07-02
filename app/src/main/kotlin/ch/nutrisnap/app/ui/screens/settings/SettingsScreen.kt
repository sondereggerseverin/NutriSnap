package ch.nutrisnap.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.repository.UserProfile
import ch.nutrisnap.app.health.HealthConnectManager
import ch.nutrisnap.app.health.HealthConnectStatus
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import ch.nutrisnap.app.ui.theme.AppTheme
import ch.nutrisnap.app.ui.theme.KEY_APP_THEME

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
    onNavigateToStats: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToCustomFoods: () -> Unit = {},
    onNavigateToMealTemplates: () -> Unit = {},
    onNavigateToYazioImport: () -> Unit = {},
    onNavigateToFoodScan: () -> Unit = {},
    onNavigateToLabelScan: () -> Unit = {}
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

    // Theme selection
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.notifDataStore.data.collectAsState(initial = null)
    val currentThemeName = prefs?.get(ch.nutrisnap.app.ui.theme.KEY_APP_THEME) ?: AppTheme.FOREST_GREEN.name
    val currentTheme = runCatching { AppTheme.valueOf(currentThemeName) }.getOrDefault(AppTheme.FOREST_GREEN)

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

        // Theme Picker
        SettingsCard(title = "App-Design", icon = Icons.Default.Palette) {
            Spacer(Modifier.height(4.dp))
            ThemePickerSection(
                currentTheme = currentTheme,
                onThemeSelected = { theme ->
                    scope.launch {
                        context.notifDataStore.edit { prefs ->
                            prefs[ch.nutrisnap.app.ui.theme.KEY_APP_THEME] = theme.name
                        }
                    }
                }
            )
            Spacer(Modifier.height(4.dp))
        }

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

        // ── Daten & mehr ──────────────────────────────────────────────────────
        SettingsCard(title = "Daten & mehr", icon = Icons.Default.Storage) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNavigateToCustomFoods, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Eigene", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToMealTemplates, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp)); Text("Vorlagen", fontSize = 12.sp)
                }
            }
            Button(onClick = onNavigateToExport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Daten exportieren (CSV)")
            }
            OutlinedButton(onClick = onNavigateToYazioImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Yazio-Daten importieren")
            }
            Button(onClick = onNavigateToFoodScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Essen scannen (KI-Kalorienschätzung)")
            }
            OutlinedButton(onClick = onNavigateToLabelScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CameraAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Nährwerttabelle fotografieren")
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

        // Health Connect
        HealthConnectCard()

        // Samsung Health Data SDK (Tier 0 — direct read, bypasses Health Connect)
        SamsungHealthCard()

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

// ── Health Connect Card ────────────────────────────────────────────────────────

@Composable
fun HealthConnectCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope   = rememberCoroutineScope()

    val status = remember { HealthConnectManager.getStatus(context) }
    var permissionsGranted by remember { mutableStateOf<Boolean?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        isLoading = false
        permissionsGranted = granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)
    }

    LaunchedEffect(status) {
        if (status == HealthConnectStatus.AVAILABLE) {
            runCatching {
                val manager = HealthConnectManager(context)
                permissionsGranted = manager.hasAllPermissions()
            }.onFailure { permissionsGranted = false }
        }
    }

    SettingsCard(title = "Health Connect", icon = Icons.Default.Favorite) {
        when (status) {
            HealthConnectStatus.NOT_AVAILABLE -> {
                Text(
                    "Health Connect ist auf diesem Gerät nicht verfügbar.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HealthConnectStatus.NEEDS_UPDATE -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Health Connect muss aktualisiert werden.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { HealthConnectManager.openPlayStore(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Launch, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Im Play Store aktualisieren")
                    }
                }
            }
            HealthConnectStatus.AVAILABLE -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when (permissionsGranted) {
                                true  -> "✓ Verbunden"
                                false -> "Nicht verbunden"
                                null  -> "Wird geprüft…"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = when (permissionsGranted) {
                                true  -> Color(0xFF2E7D32)
                                else  -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            "Schritte, Kalorien, Schlaf, Herzrate",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (permissionsGranted != true) {
                        Button(
                            onClick = {
                                isLoading = true
                                permissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                            },
                            enabled = !isLoading
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Verbinden")
                            }
                        }
                    } else {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val manager = HealthConnectManager(context)
                                    permissionsGranted = manager.hasAllPermissions()
                                }
                            }
                        }) {
                            Text("Erneut prüfen", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Samsung Health Data SDK Card ───────────────────────────────────────────────

@Composable
fun SamsungHealthCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope   = rememberCoroutineScope()

    val supported = remember { ch.nutrisnap.app.health.SamsungHealthDataManager.isSupported() }
    var permissionsGranted by remember { mutableStateOf<Boolean?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val manager = remember { ch.nutrisnap.app.health.SamsungHealthDataManager(context) }

    LaunchedEffect(supported) {
        if (supported) {
            permissionsGranted = runCatching { manager.hasPermissions() }.getOrDefault(false)
        }
    }

    if (!supported) return // Android < 10: silently hide, Health Connect card covers this device.

    SettingsCard(title = "Samsung Health Data SDK", icon = Icons.Default.Favorite) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (permissionsGranted) {
                        true  -> "✓ Verbunden (direkte Aktivkalorien)"
                        false -> "Nicht verbunden"
                        null  -> "Wird geprüft…"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = when (permissionsGranted) {
                        true  -> Color(0xFF2E7D32)
                        else  -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    "Umgeht Health Connect für Aktivkalorien",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val activity = context as? android.app.Activity

            if (permissionsGranted != true) {
                Button(
                    onClick = {
                        val act = activity ?: return@Button
                        isLoading = true
                        scope.launch {
                            val granted = runCatching { manager.requestPermissions(act) }
                                .getOrDefault(false)
                            permissionsGranted = granted
                            isLoading = false
                        }
                    },
                    enabled = !isLoading && activity != null
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Verbinden")
                    }
                }
            } else {
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { permissionsGranted = manager.hasPermissions() }
                    }
                }) {
                    Text("Erneut prüfen", fontSize = 12.sp)
                }
            }
        }
    }
}


    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("App-Design", fontWeight = FontWeight.Bold, fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(AppTheme.entries.size) { idx ->
                val theme = AppTheme.entries[idx]
                val isSelected = theme == currentTheme
                Card(
                    modifier = Modifier
                        .width(90.dp)
                        .clickable { onThemeSelected(theme) },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) theme.primaryLight else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isSelected) BorderStroke(2.dp, theme.primary) else null,
                    elevation = CardDefaults.cardElevation(if (isSelected) 4.dp else 1.dp)
                ) {
                    Column(
                        Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(theme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(theme.emoji, fontSize = 18.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(theme.label.split(" ").first(), fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) theme.primaryDark else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = theme.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}
