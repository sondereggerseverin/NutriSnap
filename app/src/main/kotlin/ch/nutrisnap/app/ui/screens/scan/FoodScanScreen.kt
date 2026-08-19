package ch.nutrisnap.app.ui.screens.scan

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.ui.screens.recipes.IngredientVerifySheet

@Composable
fun FoodScanScreen(
    onNavigateBack: () -> Unit,
    initialMeal: MealType? = null,
    vm: FoodScanViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is FoodScanState.Capturing -> PhotoCaptureScreen(
            title = "Essen scannen",
            instructions = "Auslösen → scharfes Foto → KI analysiert danach (nicht halten)",
            onPhotoCaptured = { bitmap -> vm.analyzePhoto(bitmap) },
            onNavigateBack = onNavigateBack
        )
        is FoodScanState.Analyzing -> AnalysisProgressView(stage = s.stage)
        is FoodScanState.Verify -> VerifyAndSaveFlow(
            dishName = s.dishName,
            analysisResult = s.analysisResult,
            initialOverrides = vm.getOverrides(),
            onOverridesChanged = vm::setOverrides,
            initialMeal = initialMeal,
            onSave = { name, kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium, meal ->
                vm.saveToDiary(name, kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium, meal)
            },
            onDismiss = { vm.retake() }
        )
        is FoodScanState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.retake() }) { Text("Erneut versuchen") }
            }
        }
        is FoodScanState.Saved -> LaunchedEffect(Unit) { onNavigateBack() }
    }
}

// ── Sichtbarer 5-Stufen-Analyse-Fortschritt ────────────────────────────────────

private data class StageInfo(val stage: PhotoAnalysisStage, val label: String)

private val ANALYSIS_STAGES = listOf(
    StageInfo(PhotoAnalysisStage.IDENTIFYING_INGREDIENTS, "Zutaten werden erkannt…"),
    StageInfo(PhotoAnalysisStage.SEPARATING_INGREDIENTS, "Zutaten werden getrennt…"),
    StageInfo(PhotoAnalysisStage.SEARCHING_NUTRITION_DATABASE, "Nährwerte werden gesucht…"),
    StageInfo(PhotoAnalysisStage.BREAKING_DOWN_MACROS, "Makros werden aufgeschlüsselt…"),
    StageInfo(PhotoAnalysisStage.FINALIZING_RESULTS, "Ergebnis wird finalisiert…")
)

@Composable
private fun AnalysisProgressView(stage: PhotoAnalysisStage) {
    val currentIndex = ANALYSIS_STAGES.indexOfFirst { it.stage == stage }.coerceAtLeast(0)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp).fillMaxWidth()
        ) {
            Text(
                "KI-Analyse läuft…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Dein Foto wird Schritt für Schritt in einzelne Zutaten zerlegt",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            LinearProgressIndicator(
                progress = { (currentIndex + 1) / ANALYSIS_STAGES.size.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ANALYSIS_STAGES.forEachIndexed { index, info ->
                    StageRow(
                        label = info.label,
                        status = when {
                            index < currentIndex -> StageStatus.DONE
                            index == currentIndex -> StageStatus.ACTIVE
                            else -> StageStatus.PENDING
                        }
                    )
                }
            }
        }
    }
}

private enum class StageStatus { DONE, ACTIVE, PENDING }

@Composable
private fun StageRow(label: String, status: StageStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (status) {
            StageStatus.DONE -> Icon(
                Icons.Default.Check, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            StageStatus.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp
            )
            StageStatus.PENDING -> Icon(
                Icons.Default.RadioButtonUnchecked, null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            label,
            color = when (status) {
                StageStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (status == StageStatus.ACTIVE) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ── Verify-Sheet (bestehende Komponente) + Mahlzeit-Auswahl vorm Speichern ─────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyAndSaveFlow(
    dishName: String,
    analysisResult: ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.AnalysisResult,
    initialOverrides: Map<String, ch.nutrisnap.app.ui.screens.recipes.IngredientOverride>,
    onOverridesChanged: (Map<String, ch.nutrisnap.app.ui.screens.recipes.IngredientOverride>) -> Unit,
    initialMeal: MealType? = null,
    onSave: (
        dishName: String, kcal: Float, protein: Float, carbs: Float, fat: Float,
        fiber: Float?, sugar: Float?, saturatedFat: Float?, salt: Float?, sodium: Float?,
        mealType: MealType
    ) -> Unit,
    onDismiss: () -> Unit
) {
    // Zwischenspeicher für die vom Verify-Sheet gelieferten, finalen Summen —
    // Mahlzeit-Auswahl erfolgt danach in einem leichten Dialog.
    var pendingTotals by remember { mutableStateOf<PendingTotals?>(null) }

    IngredientVerifySheet(
        analysisResult = analysisResult,
        recipeName = dishName,
        servings = 1,
        initialOverrides = initialOverrides,
        onOverridesChanged = onOverridesChanged,
        onDismiss = onDismiss,
        onConfirm = { kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium, _totalWeightG, _ingredientsText ->
            pendingTotals = PendingTotals(kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium)
        }
    )

    pendingTotals?.let { t ->
        MealTypePickerDialog(
            initialMeal = initialMeal,
            onDismiss = { pendingTotals = null },
            onConfirm = { meal ->
                onSave(dishName, t.kcal, t.protein, t.carbs, t.fat, t.fiber, t.sugar, t.saturatedFat, t.salt, t.sodium, meal)
                pendingTotals = null
            }
        )
    }
}

private data class PendingTotals(
    val kcal: Float, val protein: Float, val carbs: Float, val fat: Float,
    val fiber: Float?, val sugar: Float?, val saturatedFat: Float?, val salt: Float?, val sodium: Float?
)

@Composable
private fun MealTypePickerDialog(
    initialMeal: MealType? = null,
    onDismiss: () -> Unit,
    onConfirm: (MealType) -> Unit
) {
    var mealType by remember {
        mutableStateOf(
            initialMeal ?: when (java.time.LocalTime.now().hour) {
                in 5..10  -> MealType.BREAKFAST
                in 11..14 -> MealType.LUNCH
                in 17..21 -> MealType.DINNER
                else      -> MealType.SNACK
            }
        )
    }
    fun label(t: MealType) = when (t) {
        MealType.BREAKFAST -> "Frühstück"
        MealType.LUNCH     -> "Mittagessen"
        MealType.DINNER    -> "Abendessen"
        MealType.SNACK     -> "Snack"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mahlzeit wählen") },
        text = {
            Column {
                MealType.entries.forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = mealType == type, onClick = { mealType = type })
                        Text(label(type), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(mealType) }) { Text("Ins Tagebuch übernehmen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
