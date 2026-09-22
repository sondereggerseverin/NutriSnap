package ch.nutrisnap.app.ui.screens.scan

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.domain.DishIngredientCandidate
import ch.nutrisnap.app.domain.DishScanResult
import ch.nutrisnap.app.domain.EntryPlausibilityChecker
import ch.nutrisnap.app.domain.GroqVisionService
import ch.nutrisnap.app.domain.MealSummaryResult
import ch.nutrisnap.app.domain.OnDeviceFoodBackendRegistry
import ch.nutrisnap.app.domain.OnDeviceScanStats
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.ui.screens.recipes.IngredientOverride
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_MEAL_PHOTO_SUMMARY
import ch.nutrisnap.app.utils.NetworkMonitor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Sichtbare Stufen der mehrstufigen KI-Foto-Analyse — jede Stufe wird im UI
 *  mit eigenem Status-Text angezeigt, damit der Prozess transparent bleibt. */
enum class PhotoAnalysisStage {
    IDENTIFYING_INGREDIENTS,
    /** On-Device ML Kit Labeling (Offline- oder Cloud-Fallback). */
    ON_DEVICE_LABELING,
    SEPARATING_INGREDIENTS,
    SEARCHING_NUTRITION_DATABASE,
    BREAKING_DOWN_MACROS,
    FINALIZING_RESULTS
}

sealed class FoodScanState {
    object Capturing : FoodScanState()
    data class Analyzing(
        val stage: PhotoAnalysisStage,
        /** true = Nutzer sieht On-Device-Pfad in der Fortschrittsanzeige. */
        val onDevice: Boolean = false
    ) : FoodScanState()
    /** Zutaten aus dem Foto separiert + Nährwerte gesucht — bereit für den
     *  bekannten "Zutaten verifizieren"-Screen (IngredientVerifySheet). */
    data class Verify(
        val dishName: String,
        val analysisResult: RecipeNutritionAnalyzer.AnalysisResult,
        /** Sanfte Hinweise (grosse Portionen, unsichere Erkennungen) – nicht blockierend. */
        val warnings: List<String> = emptyList()
    ) : FoodScanState()
    data class Error(val message: String) : FoodScanState()
    object Saved : FoodScanState()
}

class FoodScanViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val diaryRepo = DiaryRepository(db)
    private val visionService = GroqVisionService()

    init {
        ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.initGlobalDictionary(
            ch.nutrisnap.app.data.repository.GlobalIngredientDictionary(db.globalIngredientMatchDao())
        )
    }

    private val _state = MutableStateFlow<FoodScanState>(FoodScanState.Capturing)
    val state: StateFlow<FoodScanState> = _state

    /** Manuelle Zutaten-Korrekturen aus dem Verify-Sheet (Löschen/Ersetzen/Menge) —
     *  bleiben erhalten, falls der Nutzer im Sheet "Neu berechnen" o.ä. auslöst. */
    private var overrides: Map<String, IngredientOverride> = emptyMap()
    fun getOverrides(): Map<String, IngredientOverride> = overrides
    fun setOverrides(o: Map<String, IngredientOverride>) { overrides = o }

    /** Mindestdauer pro Stufe, damit der Prozess für den User sichtbar/nachvollziehbar
     *  bleibt statt sofort durchzurauschen (Stufen ohne eigenen Netzwerk-Call). */
    private suspend fun showStage(
        stage: PhotoAnalysisStage,
        onDevice: Boolean = false,
        minDelayMs: Long = 450
    ) {
        _state.value = FoodScanState.Analyzing(stage, onDevice = onDevice)
        delay(minDelayMs)
    }

    fun analyzePhoto(bitmap: Bitmap) {
        overrides = emptyMap()
        viewModelScope.launch {
            val online = NetworkMonitor(getApplication()).isCurrentlyOnline()
            var usedOnDevice = false

            val onDeviceBackend = OnDeviceFoodBackendRegistry.active()
            var mealSummary: MealSummaryResult? = null
            var usedMealSummaryOnly = false

            val dish: DishScanResult = if (!online) {
                // Phase C: On-Device-Fallback ohne Cloud
                usedOnDevice = true
                showStage(PhotoAnalysisStage.ON_DEVICE_LABELING, onDevice = true)
                onDeviceBackend.analyze(bitmap).fold(
                    onSuccess = { result ->
                        OnDeviceScanStats.recordSuccess(getApplication())
                        result
                    },
                    onFailure = { e ->
                        OnDeviceScanStats.recordFailure(getApplication())
                        _state.value = FoodScanState.Error(
                            "Offline und On-Device-Erkennung (${onDeviceBackend.displayName}) ohne Treffer. " +
                                (e.message ?: "Bitte online scannen oder manuell erfassen.")
                        )
                        return@launch
                    }
                )
            } else {
                showStage(PhotoAnalysisStage.IDENTIFYING_INGREDIENTS)
                _state.value = FoodScanState.Analyzing(PhotoAnalysisStage.SEPARATING_INGREDIENTS)
                val base64 = visionService.bitmapToBase64Jpeg(bitmap)
                val mealSummaryEnabled = getApplication<Application>().notifDataStore.data.first()
                    ?.get(KEY_MEAL_PHOTO_SUMMARY) ?: true
                // Parallel: Zutaten zerlegen + optional Meal-Summary (Yazio-ähnlich)
                val (dishResult, summary) = coroutineScope {
                    val dishJob = async {
                        visionService.analyzeDishIngredients(base64).getOrNull()
                    }
                    val summaryJob = async {
                        if (mealSummaryEnabled) {
                            visionService.analyzeMealSummary(base64).getOrNull()
                        } else null
                    }
                    dishJob.await() to summaryJob.await()
                }
                mealSummary = summary
                when {
                    dishResult != null && dishResult.ingredients.any { it.name.isNotBlank() } ->
                        dishResult
                    mealSummary != null && mealSummary!!.calories > 50f -> {
                        usedMealSummaryOnly = true
                        val g = mealSummary!!.servingGrams.coerceIn(100f, 1200f)
                        val name = mealSummary!!.dishName.ifBlank { "Gericht vom Foto" }
                        DishScanResult(
                            dishName = name,
                            ingredients = listOf(
                                DishIngredientCandidate(
                                    name = name,
                                    estimatedGrams = g,
                                    confidence = "mittel"
                                )
                            )
                        )
                    }
                    else -> {
                        usedOnDevice = true
                        showStage(PhotoAnalysisStage.ON_DEVICE_LABELING, onDevice = true, minDelayMs = 300)
                        onDeviceBackend.analyze(bitmap).fold(
                            onSuccess = { result ->
                                OnDeviceScanStats.recordSuccess(getApplication())
                                result
                            },
                            onFailure = {
                                OnDeviceScanStats.recordFailure(getApplication())
                                _state.value = FoodScanState.Error(
                                    "Bilderkennung fehlgeschlagen"
                                )
                                return@launch
                            }
                        )
                    }
                }
            }

            // Leere Namen und offensichtliche Vision-Ausreisser (z.B. 5000 g) bereinigen
            val cleanedIngredients = dish.ingredients
                .map { it.copy(name = it.name.trim(), estimatedGrams = it.estimatedGrams.coerceIn(1f, MAX_INGREDIENT_GRAMS)) }
                .filter { it.name.isNotBlank() }
            if (cleanedIngredients.isEmpty()) {
                _state.value = FoodScanState.Error("Keine Zutaten erkannt – bitte anderes Foto versuchen")
                return@launch
            }

            showStage(PhotoAnalysisStage.SEARCHING_NUTRITION_DATABASE, onDevice = usedOnDevice)
            _state.value = FoodScanState.Analyzing(
                PhotoAnalysisStage.BREAKING_DOWN_MACROS,
                onDevice = usedOnDevice
            )

            val analysisResult = if (usedMealSummaryOnly && mealSummary != null) {
                // Makros aus Gericht-Summary direkt injizieren (nicht DB-Lookup)
                buildAnalysisFromMealSummary(mealSummary!!)
            } else {
                val lines = cleanedIngredients.map { ing ->
                    val grams = ing.estimatedGrams.toInt().coerceAtLeast(1)
                    val label = if (ing.confidence.equals("niedrig", ignoreCase = true) || usedOnDevice)
                        "${ing.name}, Unsichere Erkennung – bitte prüfen" else ing.name
                    "${grams}g $label"
                }
                RecipeNutritionAnalyzer.analyzeIngredientLines(
                    lines = lines,
                    allowNetwork = !usedOnDevice
                )
            }

            val warnings = buildScanWarnings(cleanedIngredients, analysisResult).toMutableList()
            if (usedMealSummaryOnly) {
                warnings.add(0, "Gesamtschätzung aus Foto (Yazio-ähnlich) – Zutaten nicht einzeln getrennt.")
            }
            if (usedOnDevice) {
                warnings.add(0, "On-Device-Erkennung (ohne Cloud) – Zutaten und Mengen bitte prüfen.")
            }
            if (usedOnDevice && analysisResult.matchedCount < analysisResult.totalCount) {
                warnings.add(
                    "Nur ${analysisResult.matchedCount}/${analysisResult.totalCount} Zutaten lokal gefunden – restliche Nährwerte fehlen offline."
                )
            }
            // Wenn beides da: Hinweis bei stark abweichenden Kalorien
            if (!usedMealSummaryOnly && mealSummary != null && mealSummary!!.calories > 50f) {
                val sumKcal = analysisResult.totalCalories
                val summaryKcal = mealSummary!!.calories
                if (sumKcal > 50f && kotlin.math.abs(sumKcal - summaryKcal) / summaryKcal > 0.35f) {
                    warnings.add(
                        "Zutaten-Summe ~${sumKcal.toInt()} kcal, Foto-Gesamtschätzung ~${summaryKcal.toInt()} kcal – bitte prüfen."
                    )
                }
            }

            val displayName = when {
                mealSummary?.dishName?.isNotBlank() == true -> mealSummary!!.dishName
                dish.dishName.isNotBlank() -> dish.dishName
                else -> "Gescanntes Essen"
            }

            showStage(PhotoAnalysisStage.FINALIZING_RESULTS, onDevice = usedOnDevice)
            _state.value = FoodScanState.Verify(
                dishName = displayName,
                analysisResult = analysisResult,
                warnings = warnings.distinct().take(6)
            )
        }
    }

    /** Baut ein AnalysisResult aus der Vision-Gerichtsschätzung (pro Portion). */
    private fun buildAnalysisFromMealSummary(summary: MealSummaryResult): RecipeNutritionAnalyzer.AnalysisResult {
        val grams = summary.servingGrams.coerceIn(100f, 1200f)
        val name = summary.dishName.ifBlank { "Gericht vom Foto" }
        val per100 = 100f / grams
        val food = FoodItem(
            name = name,
            brand = "KI-Gerichtsschätzung",
            calories = summary.calories * per100,
            protein = summary.protein * per100,
            carbs = summary.carbs * per100,
            fat = summary.fat * per100,
            fiber = summary.fiber * per100,
            servingSize = grams,
            servingUnit = "g",
            source = FoodSource.MANUAL,
            completenessScore = 30
        )
        val line = "${grams.toInt()}g $name"
        val ingredient = RecipeNutritionAnalyzer.IngredientResult(
            line = line,
            parsed = RecipeNutritionAnalyzer.ParsedIngredient(amountG = grams, name = name),
            foodItem = food,
            calories = summary.calories,
            protein = summary.protein,
            carbs = summary.carbs,
            fat = summary.fat,
            matched = true,
            estimated = true,
            micros = if (summary.fiber > 0f) mapOf("fiber" to summary.fiber) else emptyMap()
        )
        return RecipeNutritionAnalyzer.AnalysisResult(
            ingredients = listOf(ingredient),
            totalCalories = summary.calories,
            totalProtein = summary.protein,
            totalCarbs = summary.carbs,
            totalFat = summary.fat,
            caloriesPerServing = summary.calories,
            proteinPerServing = summary.protein,
            carbsPerServing = summary.carbs,
            fatPerServing = summary.fat,
            matchedCount = 1,
            totalCount = 1,
            estimatedCount = 1,
            totalMicros = if (summary.fiber > 0f) mapOf("fiber" to summary.fiber) else emptyMap(),
            fiberComplete = summary.fiber > 0f
        )
    }

    /**
     * Sanfte Hinweise vor dem Speichern – blockieren nicht, steuern Aufmerksamkeit
     * auf unsichere oder ungewöhnlich grosse Schätzungen.
     */
    private fun buildScanWarnings(
        ingredients: List<ch.nutrisnap.app.domain.DishIngredientCandidate>,
        analysis: RecipeNutritionAnalyzer.AnalysisResult
    ): List<String> {
        val out = mutableListOf<String>()
        val lowConf = ingredients.count { it.confidence.equals("niedrig", ignoreCase = true) }
        if (lowConf > 0) {
            out += if (lowConf == 1)
                "1 Zutat unsicher erkannt – bitte im Verifizieren prüfen."
            else
                "$lowConf Zutaten unsicher erkannt – bitte im Verifizieren prüfen."
        }
        val totalG = ingredients.sumOf { it.estimatedGrams.toDouble() }.toFloat()
        EntryPlausibilityChecker.checkPortion(totalG)?.let { out += it }
        ingredients.forEach { ing ->
            if (ing.estimatedGrams >= 800f) {
                out += "„${ing.name}“ mit ${ing.estimatedGrams.toInt()} g wirkt sehr gross – Menge prüfen."
            }
        }
        // Makro-Plausibilität der Analyzer-Summe (falls vorhanden)
        runCatching {
            val kcal = analysis.totalCalories
            val p = analysis.totalProtein
            val c = analysis.totalCarbs
            val f = analysis.totalFat
            EntryPlausibilityChecker.checkManualEntry(kcal, p, c, f)?.let { out += it }
        }
        if (analysis.estimatedCount > 0 && analysis.matchedCount > 0 &&
            analysis.estimatedCount >= (analysis.matchedCount + 1) / 2
        ) {
            out += "Viele Nährwerte sind KI-Schätzungen – bei Bedarf Zutaten ersetzen."
        }
        return out.distinct().take(4)
    }

    companion object {
        /** Harte Kappe pro Zutat – Vision schätzt manchmal unrealistische Massen. */
        private const val MAX_INGREDIENT_GRAMS = 1500f
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
            ch.nutrisnap.app.health.HealthConnectNutritionSync.pushMeal(
                context = getApplication(),
                name = dishName,
                mealType = mealType,
                energyKcal = kcal,
                proteinG = protein,
                carbsG = carbs,
                fatG = fat,
                fiberG = fiber ?: 0f,
                sugarG = sugar ?: 0f,
                saturatedFatG = saturatedFat ?: 0f,
                sodiumG = sodium ?: 0f
            )
            _state.value = FoodScanState.Saved
        }
    }
}
