package ch.nutrisnap.app.ui.screens.scan

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.domain.GroqVisionService
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.ui.screens.recipes.IngredientOverride
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Sichtbare Stufen der mehrstufigen KI-Foto-Analyse — jede Stufe wird im UI
 *  mit eigenem Status-Text angezeigt, damit der Prozess transparent bleibt. */
enum class PhotoAnalysisStage {
    IDENTIFYING_INGREDIENTS,
    SEPARATING_INGREDIENTS,
    SEARCHING_NUTRITION_DATABASE,
    BREAKING_DOWN_MACROS,
    FINALIZING_RESULTS
}

sealed class FoodScanState {
    object Capturing : FoodScanState()
    data class Analyzing(val stage: PhotoAnalysisStage) : FoodScanState()
    /** Zutaten aus dem Foto separiert + Nährwerte gesucht — bereit für den
     *  bekannten "Zutaten verifizieren"-Screen (IngredientVerifySheet). */
    data class Verify(
        val dishName: String,
        val analysisResult: RecipeNutritionAnalyzer.AnalysisResult
    ) : FoodScanState()
    data class Error(val message: String) : FoodScanState()
    object Saved : FoodScanState()
}

class FoodScanViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val diaryRepo = DiaryRepository(db)
    private val visionService = GroqVisionService()

    private val _state = MutableStateFlow<FoodScanState>(FoodScanState.Capturing)
    val state: StateFlow<FoodScanState> = _state

    /** Manuelle Zutaten-Korrekturen aus dem Verify-Sheet (Löschen/Ersetzen/Menge) —
     *  bleiben erhalten, falls der Nutzer im Sheet "Neu berechnen" o.ä. auslöst. */
    private var overrides: Map<String, IngredientOverride> = emptyMap()
    fun getOverrides(): Map<String, IngredientOverride> = overrides
    fun setOverrides(o: Map<String, IngredientOverride>) { overrides = o }

    /** Mindestdauer pro Stufe, damit der Prozess für den User sichtbar/nachvollziehbar
     *  bleibt statt sofort durchzurauschen (Stufen ohne eigenen Netzwerk-Call). */
    private suspend fun showStage(stage: PhotoAnalysisStage, minDelayMs: Long = 450) {
        _state.value = FoodScanState.Analyzing(stage)
        delay(minDelayMs)
    }

    fun analyzePhoto(bitmap: Bitmap) {
        overrides = emptyMap()
        viewModelScope.launch {
            showStage(PhotoAnalysisStage.IDENTIFYING_INGREDIENTS)
            val base64 = visionService.bitmapToBase64Jpeg(bitmap)

            _state.value = FoodScanState.Analyzing(PhotoAnalysisStage.SEPARATING_INGREDIENTS)
            val dish = visionService.analyzeDishIngredients(base64).getOrElse { e ->
                _state.value = FoodScanState.Error(e.message ?: "Unbekannter Fehler bei der Bilderkennung")
                return@launch
            }
            if (dish.ingredients.isEmpty()) {
                _state.value = FoodScanState.Error("Keine Zutaten erkannt – bitte anderes Foto versuchen")
                return@launch
            }

            showStage(PhotoAnalysisStage.SEARCHING_NUTRITION_DATABASE)
            // Wandelt die erkannten Zutaten in Zeilen um, die RecipeNutritionAnalyzer
            // (DB-Abgleich + AI-Fallback) genau wie manuell eingegebene Rezeptzeilen
            // verarbeiten kann. Unsichere Erkennungen bleiben im Namen sichtbar,
            // damit sie im Verify-Screen transparent markiert sind.
            val lines = dish.ingredients.map { ing ->
                val grams = ing.estimatedGrams.coerceAtLeast(1f).toInt()
                // Komma vor dem Hinweis: parseIngredientLine() kappt Zutatennamen ab dem
                // ersten Komma fuer die DB-Suche (bestehendes Verhalten) — so bleibt der
                // Hinweis in der Anzeige sichtbar, verfaelscht aber nie den Suchbegriff.
                val label = if (ing.confidence.equals("niedrig", ignoreCase = true))
                    "${ing.name}, Unsichere Erkennung – bitte prüfen" else ing.name
                "${grams}g $label"
            }

            _state.value = FoodScanState.Analyzing(PhotoAnalysisStage.BREAKING_DOWN_MACROS)
            val analysisResult = RecipeNutritionAnalyzer.analyzeIngredientLines(lines)

            showStage(PhotoAnalysisStage.FINALIZING_RESULTS)
            _state.value = FoodScanState.Verify(
                dishName = dish.dishName.ifBlank { "Gescanntes Essen" },
                analysisResult = analysisResult
            )
        }
    }

    fun retake() {
        overrides = emptyMap()
        _state.value = FoodScanState.Capturing
    }

    /** Übernimmt die vom Verify-Sheet summierten, finalen Werte (inkl. Ballaststoffe)
     *  als EINEN Tagebuch-Eintrag für das gesamte Gericht. */
    fun saveToDiary(
        dishName: String,
        kcal: Float, protein: Float, carbs: Float, fat: Float,
        fiber: Float?, sugar: Float?, saturatedFat: Float?, salt: Float?, sodium: Float?,
        mealType: MealType
    ) {
        viewModelScope.launch {
            diaryRepo.addManualEntry(
                name = dishName,
                kcal = kcal, protein = protein, carbs = carbs, fat = fat,
                mealType = mealType, date = LocalDate.now(),
                fiber = fiber ?: 0f, sugar = sugar ?: 0f,
                saturatedFat = saturatedFat ?: 0f, salt = salt ?: 0f, sodium = sodium ?: 0f
            )
            _state.value = FoodScanState.Saved
        }
    }
}
