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
import ch.nutrisnap.app.data.repository.Sex
import ch.nutrisnap.app.data.repository.UserProfile
import ch.nutrisnap.app.health.HealthConnectManager
import ch.nutrisnap.app.health.HealthConnectStatus
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import ch.nutrisnap.app.ui.theme.AppTheme
import ch.nutrisnap.app.ui.theme.KEY_APP_THEME

enum class FitnessGoal(val label: String, val emoji: String, val desc: String) {
    LOSE_WEIGHT("Abnehmen",        "\uD83D\uDD25", "–500 kcal vom TDEE · mehr Protein"),
    MAINTAIN(   "Halten",          "\u2696\uFE0F", "TDEE als Ziel · ausgewogene Makros"),
    BUILD_MUSCLE("Muskeln",        "\uD83D\uDCAA", "+250 kcal über TDEE · viel Protein"),
    GAIN_WEIGHT( "Zunehmen",       "\uD83D\uDCC8", "+500 kcal über TDEE"),
    SPORT(       "Sport & Leistung","\uD83C\uDFC3", "TDEE+300 · hohe Kohlenhydrate"),
}

private fun computeGoals(
    weightKg: Float, heightCm: Int, ageYears: Int,
    activityFactor: Float, goal: FitnessGoal, sex: Sex
): Triple<Int, Float, Float> {
    if (weightKg <= 0 || heightCm <= 0 || ageYears <= 0) return Triple(2000, 120f, 65f)
    val base = 10f * weightKg + 6.25f * heightCm - 5f * ageYears
    val bmr  = when (sex) {
        Sex.MALE        -> base + 5f
        Sex.FEMALE      -> base - 161f
        Sex.UNSPECIFIED -> base - 78f
    }
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
    onNavigateToStats: () -> Unit = {},
    onNavigateToExport: () -> Unit = {},
    onNavigateToCustomFoods: () -> Unit = {},
    onNavigateToMealTemplates: () -> Unit = {},
    onNavigateToYazioImport: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onNavigateToMealOrder: () -> Unit = {},
    onNavigateToShoppingList: () -> Unit = {}
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
    var sex         by remember(profile.sex)               { mutableStateOf(profile.sex) }
    var applianceModelText by remember(profile.applianceModel) { mutableStateOf(profile.applianceModel) }
    var selectedGoal by remember { mutableStateOf(FitnessGoal.MAINTAIN) }
    var showSaved   by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.notifDataStore.data.collectAsState(initial = null)
    val currentThemeName = prefs?.get(ch.nutrisnap.app.ui.theme.KEY_APP_THEME) ?: AppTheme.FOREST_GREEN.name
    val currentTheme = runCatching { AppTheme.valueOf(currentThemeName) }.getOrDefault(AppTheme.FOREST_GREEN)

    fun applyGoal() {
        val w = weightText.toFloatOrNull() ?: return
        val h = heightText.toIntOrNull()   ?: return
        val a = ageText.toIntOrNull()      ?: return
        val (kcal, prot, fat) = computeGoals(w, h, a, activity, selectedGoal, sex)
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
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
    ) {
        Text("Einstellungen", fontWeight = FontWeight.Bold, fontSize = 24.sp)

        // Theme Picker
        SettingsCard(title = "App-Design", icon = Icons.Default.Palette) {
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
        }

        // Feature-Shortcuts
        SettingsCard(title = "Features", icon = Icons.Default.Apps) {
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                OutlinedButton(onClick = onNavigateToStats, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.BarChart, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(NutriSpacing.xs)); Text("Statistik", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToNotifSettings, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Notifications, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(NutriSpacing.xs)); Text("Reminder", fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                OutlinedButton(onClick = onNavigateToMealOrder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DragHandle, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(NutriSpacing.xs)); Text("Reihenfolge", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToShoppingList, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ShoppingCart, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(NutriSpacing.xs)); Text("Einkaufsliste", fontSize = 12.sp)
                }
            }
        }

        // Daten & mehr
        SettingsCard(title = "Daten & mehr", icon = Icons.Default.Storage) {
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                OutlinedButton(onClick = onNavigateToCustomFoods, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(NutriSpacing.xs)); Text("Eigene", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onNavigateToMealTemplates, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(NutriSpacing.xs)); Text("Vorlagen", fontSize = 12.sp)
                }
            }
            Button(onClick = onNavigateToExport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(NutriSpacing.sm)); Text("Daten exportieren (CSV)")
            }
            OutlinedButton(onClick = onNavigateToYazioImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(NutriSpacing.sm)); Text("Yazio-Daten importieren")
            }
            Button(onClick = onNavigateToScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(NutriSpacing.sm)); Text("Scannen (Barcode, Foto, Etikett)")
            }
        }

        // Ziel-Auswahl
        SettingsCard(title = "Mein Ziel", icon = Icons.Default.Flag) {
            Text(
                "Was möchtest du erreichen?",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(NutriSpacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                FitnessGoal.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                        row.forEach { goal ->
                            val selected = goal == selectedGoal
                            FilterChip(
                                selected = selected,
                                onClick  = { selectedGoal = goal; applyGoal() },
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(NutriSpacing.xs)
                                    ) {
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
            Text(
                selectedGoal.desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = NutriSpacing.xs)
            )
        }

        // Health Connect
        HealthConnectCard()

        // Samsung Health Data SDK
        SamsungHealthCard()

        // Körperdaten
        SettingsCard(title = "Körperdaten", icon = Icons.Default.Person) {
            Text(
                "Geschlecht",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(NutriSpacing.xs))
            Row(
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                val sexOptions = listOf(Sex.FEMALE to "Weiblich", Sex.MALE to "Männlich", Sex.UNSPECIFIED to "Keine Angabe")
                sexOptions.forEach { (option, label) ->
                    FilterChip(
                        selected = sex == option,
                        onClick = { sex = option; applyGoal() },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(NutriSpacing.sm))
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

        // Ernährungsziele
        SettingsCard(title = "Ernährungsziele", icon = Icons.Default.TrackChanges) {
            Text(
                "Automatisch berechnet – du kannst auch manuell anpassen.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(NutriSpacing.xs))
            GoalField("Kalorienziel (kcal)",  calorieText, KeyboardType.Number) { calorieText = it }
            GoalField("Proteinziel (g)",      proteinText, KeyboardType.Number) { proteinText = it }
            GoalField("Kohlenhydratziel (g)", carbsText,   KeyboardType.Number) { carbsText   = it }
            GoalField("Fettziel (g)",         fatText,     KeyboardType.Number) { fatText     = it }
        }

        // Aktivitätslevel
        SettingsCard(title = "Aktivitätslevel", icon = Icons.Default.DirectionsRun) {
            ActivitySlider(value = activity) { activity = it; applyGoal() }
        }

        // Küchengerät
        SettingsCard(title = "Backofen / Dampfgarer", icon = Icons.Default.Kitchen) {
            Text(
                "Optional: Modell hinterlegen, damit KI-Rezepte für Ofen/Dampfgarer echte Programme, Temperaturen und Zeiten deines Geräts nutzen.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(NutriSpacing.xs))
            GoalField(
                "Gerätemodell (z.B. V-ZUG Combi-Steam SL CSTSLc)",
                applianceModelText,
                KeyboardType.Text
            ) { applianceModelText = it }
        }

        // TDEE Preview
        val previewProfile = UserProfile(
            weightKg       = weightText.toFloatOrNull() ?: profile.weightKg,
            heightCm       = heightText.toIntOrNull()   ?: profile.heightCm,
            ageYears       = ageText.toIntOrNull()      ?: profile.ageYears,
            activityFactor = activity,
            sex            = sex
        )
        previewProfile.computedTdee()?.let { tdee ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(NutriRadius.lg)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(NutriSpacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Dein Grundumsatz (TDEE)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Basis für die Zielberechnung",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Text(
                        "${tdee.toInt()} kcal",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                    activityFactor   = activity,
                    sex              = sex,
                    applianceModel   = applianceModelText.trim()
                ))
                showSaved = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(NutriRadius.md)
        ) {
            Icon(Icons.Default.Save, null, Modifier.size(18.dp))
            Spacer(Modifier.width(NutriSpacing.sm))
            Text("Speichern")
        }

        if (showSaved) {
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); showSaved = false }
            Text(
                "\u2713 Gespeichert",
                color = MacroColors.calories,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        Spacer(Modifier.height(NutriSpacing.xxxl))
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
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(
            Modifier.padding(NutriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
            ) {
                Icon(
                    icon, null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
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
        singleLine = true,
        shape = RoundedCornerShape(NutriRadius.md)
    )
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
    Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
        Text(
            currentLabel,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 1.2f..1.9f,
            steps = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Sitzend", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Sehr aktiv", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
                Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
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
                        Spacer(Modifier.width(NutriSpacing.sm))
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
                                true  -> "\u2713 Verbunden"
                                false -> "Nicht verbunden"
                                null  -> "Wird geprüft\u2026"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = when (permissionsGranted) {
                                true  -> MacroColors.calories
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
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
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

    if (!supported) return

    SettingsCard(title = "Samsung Health Data SDK", icon = Icons.Default.Favorite) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (permissionsGranted) {
                        true  -> "\u2713 Verbunden (direkte Aktivkalorien)"
                        false -> "Nicht verbunden"
                        null  -> "Wird geprüft\u2026"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = when (permissionsGranted) {
                        true  -> MacroColors.calories
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
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
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

@Composable
fun ThemePickerSection(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit
) {
    val columns = 4
    AppTheme.entries.chunked(columns).forEach { rowThemes ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = NutriSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
        ) {
            rowThemes.forEach { theme ->
                ThemeCard(
                    theme = theme,
                    isSelected = theme == currentTheme,
                    onClick = { onThemeSelected(theme) },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(columns - rowThemes.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.md),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) theme.primaryLight else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(2.dp, theme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(if (isSelected) 4.dp else 0.dp)
    ) {
        Column(
            Modifier
                .padding(vertical = NutriSpacing.sm, horizontal = NutriSpacing.xs)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(theme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(theme.emoji, fontSize = 14.sp)
            }
            Spacer(Modifier.height(NutriSpacing.xs))
            Text(
                theme.label.split(" ").first(),
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) theme.primaryDark else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
