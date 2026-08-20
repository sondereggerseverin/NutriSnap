package ch.nutrisnap.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.GoalPrognosis
import ch.nutrisnap.app.health.HealthConnectManager
import ch.nutrisnap.app.health.HealthConnectStatus
import ch.nutrisnap.app.ui.theme.AppTheme
import ch.nutrisnap.app.ui.theme.KEY_AGGRESSIVE_SPORT_DAY
import ch.nutrisnap.app.ui.theme.KEY_APP_THEME
import ch.nutrisnap.app.ui.theme.KEY_MANUAL_ACTIVITY_ENABLED
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.launch

@Composable
fun HealthConnectCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope   = rememberCoroutineScope()

    val status = remember { HealthConnectManager.getStatus(context) }
    var permissionsGranted by remember { mutableStateOf<Boolean?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    var writeNutritionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        isLoading = false
        permissionsGranted = granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)
        writeNutritionGranted = granted.contains(HealthConnectManager.WRITE_NUTRITION_PERMISSION)
    }

    LaunchedEffect(status) {
        if (status == HealthConnectStatus.AVAILABLE) {
            runCatching {
                val manager = HealthConnectManager(context)
                permissionsGranted = manager.hasAllPermissions()
                writeNutritionGranted = manager.hasWriteNutritionPermission()
            }.onFailure {
                permissionsGranted = false
                writeNutritionGranted = false
            }
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
                            if (writeNutritionGranted)
                                "Lesen + Ernährung schreiben (Tagebuch → HC)"
                            else
                                "Schritte, Kalorien, Schlaf, Herzrate",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (permissionsGranted != true) {
                        Button(
                            onClick = {
                                isLoading = true
                                permissionLauncher.launch(HealthConnectManager.REQUESTABLE_PERMISSIONS)
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!writeNutritionGranted) {
                                OutlinedButton(onClick = {
                                    isLoading = true
                                    permissionLauncher.launch(
                                        setOf(HealthConnectManager.WRITE_NUTRITION_PERMISSION)
                                    )
                                }) {
                                    Text("Ernährung teilen", fontSize = 11.sp)
                                }
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        val manager = HealthConnectManager(context)
                                        permissionsGranted = manager.hasAllPermissions()
                                        writeNutritionGranted = manager.hasWriteNutritionPermission()
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
}


@Composable
fun AggressiveSportDayCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val enabled = prefs?.get(KEY_AGGRESSIVE_SPORT_DAY) ?: false

    SettingsCard(title = "Sporttag-Modus", icon = Icons.Default.FitnessCenter) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Aggressiver Sporttag",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "Tracker-Aktivkcal zählen 100% statt 50% auf die BMR-Basis. Für lange Einheiten (z.B. 100 km Rad), wenn du bewusst mehr essen willst.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    scope.launch {
                        context.notifDataStore.edit { it[KEY_AGGRESSIVE_SPORT_DAY] = checked }
                    }
                }
            )
        }
    }
}

@Composable
fun ManualActivitySettingsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val enabled = prefs?.get(KEY_MANUAL_ACTIVITY_ENABLED) ?: false

    SettingsCard(title = "Manuelle Aktivität", icon = Icons.Default.DirectionsRun) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Aktivitätskalorien manuell tracken",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "Zusätzlich zu Health Connect / Samsung Health. Zählt in Ziel-Rechnung und Durchschnitt.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    scope.launch {
                        context.notifDataStore.edit { it[KEY_MANUAL_ACTIVITY_ENABLED] = checked }
                    }
                }
            )
        }
        if (enabled) {
            Spacer(Modifier.height(NutriSpacing.sm))
            Text(
                "Auf der Startseite kannst du für heute Aktivitätskalorien eintragen.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
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
    val columns = 3
    // Nur kuratierte Themes; Legacy-Themes bleiben gültig, erscheinen nicht
    val themes = AppTheme.pickerThemes
    themes.chunked(columns).forEach { rowThemes ->
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
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.md),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) theme.primary.copy(alpha = 0.18f) else scheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = if (isSelected) BorderStroke(2.dp, theme.primary) else BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(if (isSelected) 2.dp else 0.dp)
    ) {
        Column(
            Modifier
                .padding(vertical = NutriSpacing.md, horizontal = NutriSpacing.xs)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(theme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(theme.emoji, fontSize = 16.sp)
            }
            Spacer(Modifier.height(NutriSpacing.sm))
            Text(
                theme.label,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) theme.primary else scheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

// Feature 3: Ziel-Prognose live unter den Zielgewicht-Feldern anzeigen (bezieht sich
// immer auf den zuletzt GESPEICHERTEN Stand, nicht auf ungespeicherte Texteingaben).
@Composable
internal fun GoalPrognosisPreview(
    vm: ch.nutrisnap.app.domain.GoalPrognosisViewModel = viewModel()
) {
    val prognosis by vm.prognosis.collectAsStateWithLifecycle()
    val p = prognosis ?: return

    Spacer(Modifier.height(NutriSpacing.sm))
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (p.isOnTrack) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(NutriRadius.md)
    ) {
        Column(Modifier.padding(NutriSpacing.md)) {
            Text(
                if (p.isOnTrack) "Im Plan" else "Vom Plan abgewichen",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                "Ziel voraussichtlich erreicht am ${p.estimatedGoalDate} (in ${p.daysRemaining} Tagen)",
                fontSize = 12.sp
            )
            Text(
                "Aktuelles Tempo: ${String.format("%.2f", p.actualWeeklyRateKg)} kg/Woche · Ziel: ${String.format("%.2f", p.targetWeeklyRateKg)} kg/Woche",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!p.isOnTrack && p.suggestedKcalAdjustment != 0) {
                Text(
                    "Empfehlung: Tagesziel um ${p.suggestedKcalAdjustment} kcal anpassen",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
