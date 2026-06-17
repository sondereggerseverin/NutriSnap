package ch.nutrisnap.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import ch.nutrisnap.app.service.NotificationScheduler
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val android.content.Context.notifDataStore by preferencesDataStore("notification_prefs")
val KEY_MEAL_REMINDERS  = booleanPreferencesKey("meal_reminders")
val KEY_WATER_REMINDERS = booleanPreferencesKey("water_reminders")
val KEY_DAILY_RECAP     = booleanPreferencesKey("daily_recap")
val KEY_BIOMETRIC_LOCK  = booleanPreferencesKey("biometric_lock")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val mealReminders  by context.notifDataStore.data.map { it[KEY_MEAL_REMINDERS]  ?: true  }.collectAsState(true)
    val waterReminders by context.notifDataStore.data.map { it[KEY_WATER_REMINDERS] ?: true  }.collectAsState(true)
    val dailyRecap     by context.notifDataStore.data.map { it[KEY_DAILY_RECAP]     ?: true  }.collectAsState(true)
    val biometricLock  by context.notifDataStore.data.map { it[KEY_BIOMETRIC_LOCK]  ?: false }.collectAsState(false)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Benachrichtigungen & Sicherheit") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurueck") } }
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            item { SectionTitle("Benachrichtigungen") }
            item { ToggleCard("Mahlzeiten-Erinnerungen", "Fruehstueck 08:00 | Mittag 12:30 | Abend 18:30", mealReminders) {
                scope.launch {
                    context.notifDataStore.edit { p -> p[KEY_MEAL_REMINDERS] = it }
                    if (it) NotificationScheduler.scheduleAll(context) else NotificationScheduler.cancelAll(context)
                }
            }}
            item { ToggleCard("Wasser-Erinnerungen", "3x taeglich: 10:00, 14:00, 17:00", waterReminders) {
                scope.launch { context.notifDataStore.edit { p -> p[KEY_WATER_REMINDERS] = it } }
            }}
            item { ToggleCard("Tagesrueckblick", "Jeden Abend um 21:00 Uhr", dailyRecap) {
                scope.launch { context.notifDataStore.edit { p -> p[KEY_DAILY_RECAP] = it } }
            }}
            item { Spacer(Modifier.height(16.dp)); SectionTitle("Sicherheit") }
            item { ToggleCard("Biometrie-Sperre", "App mit Fingerabdruck / PIN entsperren", biometricLock) {
                scope.launch { context.notifDataStore.edit { p -> p[KEY_BIOMETRIC_LOCK] = it } }
            }}
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
