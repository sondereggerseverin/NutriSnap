package ch.nutrisnap.app.ui.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.UserProfile
import ch.nutrisnap.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = UserProfileRepository(NutriDatabase.getInstance(app))

    val profile: StateFlow<UserProfile> = repo.get()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    fun save(profile: UserProfile) {
        viewModelScope.launch { repo.save(profile) }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    onDarkModeChange: (Boolean) -> Unit = {}
) {
    val profile by vm.profile.collectAsState()

    // Local edit state – mirrors profile, saved on each field change
    var calorieGoal   by remember(profile) { mutableStateOf(profile.dailyCalorieGoal.toString()) }
    var proteinGoal   by remember(profile) { mutableStateOf(profile.proteinGoalG.toInt().toString()) }
    var carbsGoal     by remember(profile) { mutableStateOf(profile.carbsGoalG.toInt().toString()) }
    var fatGoal       by remember(profile) { mutableStateOf(profile.fatGoalG.toInt().toString()) }
    var weight        by remember(profile) { mutableStateOf(profile.weightKg?.toString() ?: "") }
    var height        by remember(profile) { mutableStateOf(profile.heightCm?.toString() ?: "") }
    var age           by remember(profile) { mutableStateOf(profile.age?.toString() ?: "") }
    var sex           by remember(profile) { mutableStateOf(profile.sex ?: "male") }
    var activity      by remember(profile) { mutableStateOf(profile.activityFactor) }
    var darkMode      by remember(profile) { mutableStateOf(profile.darkMode) }

    fun commit() {
        val updated = profile.copy(
            dailyCalorieGoal = calorieGoal.toIntOrNull() ?: profile.dailyCalorieGoal,
            proteinGoalG     = proteinGoal.toFloatOrNull() ?: profile.proteinGoalG,
            carbsGoalG       = carbsGoal.toFloatOrNull() ?: profile.carbsGoalG,
            fatGoalG         = fatGoal.toFloatOrNull() ?: profile.fatGoalG,
            weightKg         = weight.toFloatOrNull(),
            heightCm         = height.toFloatOrNull(),
            age              = age.toIntOrNull(),
            sex              = sex,
            activityFactor   = activity,
            darkMode         = darkMode
        )
        vm.save(updated)
        onDarkModeChange(darkMode)
    }

    val tdee = remember(weight, height, age, sex, activity) {
        val w = weight.toFloatOrNull() ?: return@remember null
        val h = height.toFloatOrNull() ?: return@remember null
        val a = age.toIntOrNull() ?: return@remember null
        val bmr = if (sex == "male") 10 * w + 6.25 * h - 5 * a + 5
                  else               10 * w + 6.25 * h - 5 * a - 161
        (bmr * activity).toInt()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Einstellungen", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                modifier = Modifier.padding(bottom = 4.dp))
        }

        // ── Appearance ───────────────────────────────────────────────────────
        item {
            SettingsCard(title = "Erscheinungsbild", icon = Icons.Default.Palette) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dark Mode", Modifier.weight(1f))
                    Switch(
                        checked         = darkMode,
                        onCheckedChange = { darkMode = it; commit() }
                    )
                }
            }
        }

        // ── Calorie goals ─────────────────────────────────────────────────────
        item {
            SettingsCard(title = "Kalorienziel", icon = Icons.Default.LocalFireDepartment) {
                tdee?.let {
                    Surface(
                        color  = MaterialTheme.colorScheme.primaryContainer,
                        shape  = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "Berechneter TDEE: $it kcal/Tag (Mifflin-St Jeor)",
                            fontSize = 13.sp,
                            color    = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                GoalField("Kalorien (kcal)", calorieGoal) { calorieGoal = it; commit() }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalField("Protein (g)", proteinGoal, Modifier.weight(1f)) { proteinGoal = it; commit() }
                    GoalField("Kohlenhydrate (g)", carbsGoal, Modifier.weight(1f)) { carbsGoal = it; commit() }
                    GoalField("Fett (g)", fatGoal, Modifier.weight(1f)) { fatGoal = it; commit() }
                }
            }
        }

        // ── Body stats ────────────────────────────────────────────────────────
        item {
            SettingsCard(title = "Körperdaten (TDEE-Berechnung)", icon = Icons.Default.Person) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalField("Gewicht (kg)", weight, Modifier.weight(1f)) { weight = it; commit() }
                    GoalField("Größe (cm)", height, Modifier.weight(1f)) { height = it; commit() }
                    GoalField("Alter", age, Modifier.weight(1f)) { age = it; commit() }
                }
                Spacer(Modifier.height(8.dp))
                // Sex selector
                Text("Geschlecht", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("male" to "Männlich", "female" to "Weiblich").forEach { (key, label) ->
                        FilterChip(
                            selected = sex == key,
                            onClick  = { sex = key; commit() },
                            label    = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Activity selector
                Text("Aktivitätslevel", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                ActivitySlider(value = activity) { activity = it; commit() }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title:   String,
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            content()
        }
    }
}

@Composable
private fun GoalField(
    label:    String,
    value:    String,
    modifier: Modifier = Modifier,
    onValue:  (String) -> Unit
) {
    OutlinedTextField(
        value           = value,
        onValueChange   = onValue,
        label           = { Text(label, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier        = modifier.fillMaxWidth(),
        singleLine      = true
    )
}

private val activityLevels = listOf(
    1.2f  to "Sitzend",
    1.375f to "Leicht aktiv",
    1.55f  to "Mäßig aktiv",
    1.725f to "Sehr aktiv",
    1.9f   to "Extrem aktiv"
)

@Composable
private fun ActivitySlider(value: Float, onChange: (Float) -> Unit) {
    val index = activityLevels.indexOfFirst { it.first == value }.takeIf { it >= 0 } ?: 2
    var sliderPos by remember(value) { mutableStateOf(index.toFloat()) }
    val label = activityLevels.getOrNull(sliderPos.toInt())?.second ?: ""

    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary)
    Slider(
        value         = sliderPos,
        onValueChange = { sliderPos = it },
        onValueChangeFinished = {
            onChange(activityLevels[sliderPos.toInt()].first)
        },
        valueRange    = 0f..4f,
        steps         = 3,
        modifier      = Modifier.fillMaxWidth()
    )
}
