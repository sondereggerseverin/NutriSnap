package ch.nutrisnap.app.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.repository.DiaryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate

data class YazioImportResult(
    val importedDays: Int = 0,
    val importedEntries: Int = 0,
    val skippedEntries: Int = 0
)

sealed class YazioImportState {
    object Idle : YazioImportState()
    object Loading : YazioImportState()
    data class Success(val result: YazioImportResult) : YazioImportState()
    data class Error(val message: String) : YazioImportState()
}

data class YazioRecipeImportResult(
    val importedRecipes: Int = 0,
    val skippedRecipes: Int = 0
)

data class YazioFoodImportResult(
    val importedFoods: Int = 0,
    val skippedFoods: Int = 0
)

sealed class YazioRecipeImportState {
    object Idle : YazioRecipeImportState()
    object Loading : YazioRecipeImportState()
    data class Success(val result: YazioRecipeImportResult) : YazioRecipeImportState()
    data class Error(val message: String) : YazioRecipeImportState()
}

sealed class YazioFoodImportState {
    object Idle : YazioFoodImportState()
    object Loading : YazioFoodImportState()
    data class Success(val result: YazioFoodImportResult) : YazioFoodImportState()
    data class Error(val message: String) : YazioFoodImportState()
}

/**
 * Importiert den Yazio "nutrition_log.csv" Export (pro-Produkt Eintraege) als
 * manuelle DiaryEntries in NutriSnap. Importiert ausserdem die mitgelieferten
 * Yazio-Rezepte (yazio_recipes.json) und eigenen Produkte (yazio_foods.json)
 * aus den Assets direkt in die Rezepte- bzw. eigene-Lebensmittel-Datenbank.
 *
 * Erwartete CSV-Spalten:
 * Datum, Mahlzeit, Produkt, Menge (g), Kalorien total, Protein total (g),
 * Fett total (g), Kohlenhydrate total (g)
 */
class YazioImportViewModel(app: Application) : AndroidViewModel(app) {

    private val db = NutriDatabase.getInstance(app)
    private val diaryRepo = DiaryRepository(db)

    private val _state = MutableStateFlow<YazioImportState>(YazioImportState.Idle)
    val state: StateFlow<YazioImportState> = _state

    private val _recipeState = MutableStateFlow<YazioRecipeImportState>(YazioRecipeImportState.Idle)
    val recipeState: StateFlow<YazioRecipeImportState> = _recipeState

    private val _foodState = MutableStateFlow<YazioFoodImportState>(YazioFoodImportState.Idle)
    val foodState: StateFlow<YazioFoodImportState> = _foodState

    fun importNutritionLog(uri: Uri) {
        viewModelScope.launch {
            _state.value = YazioImportState.Loading
            try {
                val context = getApplication<Application>()
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Datei konnte nicht geoeffnet werden")
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))

                // BOM ueberspringen, falls vorhanden
                reader.mark(4)
                val first = reader.read()
                if (first != 0xFEFF) reader.reset()

                val header = reader.readLine() // Header ueberspringen
                if (header == null) throw Exception("Leere CSV-Datei")

                var imported = 0
                var skipped = 0
                val days = mutableSetOf<LocalDate>()

                val allLines = reader.readLines()
                reader.close()

                for (rawLine in allLines) {
                    val line = rawLine.trim()
                    if (line.isBlank()) continue
                    val cols = splitCsvLine(line)
                    if (cols.size < 8) { skipped++; continue }

                    try {
                        val dateStr = cols[0].trim()
                        val mealStr = cols[1].trim().lowercase()
                        val product = cols[2].trim().ifBlank { "Unbekannt" }
                        val kcal    = cols[4].trim().toFloatOrNull() ?: 0f
                        val protein = cols[5].trim().toFloatOrNull() ?: 0f
                        val fat     = cols[6].trim().toFloatOrNull() ?: 0f
                        val carbs   = cols[7].trim().toFloatOrNull() ?: 0f

                        val date = LocalDate.parse(dateStr)

                        val mealType = when (mealStr) {
                            "breakfast", "fruehstueck", "fruehstuck" -> MealType.BREAKFAST
                            "lunch", "mittagessen" -> MealType.LUNCH
                            "dinner", "abendessen" -> MealType.DINNER
                            else -> MealType.SNACK
                        }

                        // Sequenziell und mit await: garantiert, dass jeder Eintrag
                        // wirklich in der DB landet, bevor der naechste verarbeitet wird
                        // und bevor der Erfolgs-State gesetzt wird.
                        diaryRepo.addManualEntry(
                            name = product,
                            kcal = kcal,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            mealType = mealType,
                            date = date
                        )
                        days.add(date)
                        imported++
                    } catch (e: Exception) {
                        skipped++
                    }
                }
                _state.value = YazioImportState.Success(
                    YazioImportResult(
                        importedDays = days.size,
                        importedEntries = imported,
                        skippedEntries = skipped
                    )
                )
            } catch (e: Exception) {
                _state.value = YazioImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /**
     * Importiert die mitgelieferten Yazio-Rezepte (app/src/main/assets/yazio_recipes.json)
     * direkt in die "recipes" Tabelle. Ueberspringt Rezepte deren Titel bereits existiert.
     */
    fun importBundledRecipes() {
        viewModelScope.launch {
            _recipeState.value = YazioRecipeImportState.Loading
            try {
                val context = getApplication<Application>()
                val jsonText = context.assets.open("yazio_recipes.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(jsonText)

                // Bereits vorhandene Rezept-Titel laden, um Duplikate beim erneuten
                // Import zu vermeiden.
                val existing = mutableSetOf<String>()
                try {
                    db.recipeDao().getAll().first().forEach {
                        existing.add(it.title.trim().lowercase())
                    }
                } catch (e: Exception) { /* ignore, Liste bleibt leer */ }

                var imported = 0
                var skipped = 0

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val title = obj.optString("title", "Unbenanntes Rezept")
                    if (title.trim().lowercase() in existing) {
                        skipped++
                        continue
                    }

                    val servings = obj.optInt("servings", 1).coerceAtLeast(1)
                    val caloriesPerServing = obj.optDouble("caloriesPerServing", 0.0).toFloat()
                    val proteinPerServing = obj.optDouble("proteinPerServing", 0.0).toFloat()
                    val carbsPerServing = obj.optDouble("carbsPerServing", 0.0).toFloat()
                    val fatPerServing = obj.optDouble("fatPerServing", 0.0).toFloat()
                    val imageUrl = obj.optString("imageUrl", null).takeUnless { it.isNullOrBlank() }

                    val ingredientsArr = obj.optJSONArray("ingredients")
                    val ingredientsText = StringBuilder()
                    if (ingredientsArr != null) {
                        for (j in 0 until ingredientsArr.length()) {
                            val ing = ingredientsArr.getJSONObject(j)
                            val name = ing.optString("name", "")
                            val amount = ing.optDouble("amount", 0.0)
                            val unit = ing.optString("unit", "g")
                            val producer = ing.optString("producer", null)
                            ingredientsText.append("- ")
                            if (amount > 0) {
                                val amountStr = if (amount == amount.toLong().toDouble())
                                    amount.toLong().toString() else amount.toString()
                                ingredientsText.append("$amountStr $unit ")
                            }
                            ingredientsText.append(name)
                            if (!producer.isNullOrBlank()) ingredientsText.append(" ($producer)")
                            ingredientsText.append("\n")
                        }
                    }

                    val recipe = Recipe(
                        title = title,
                        description = "Importiert aus Yazio",
                        imageUrl = imageUrl,
                        sourceUrl = null,
                        platform = "Yazio",
                        ingredients = ingredientsText.toString().trim(),
                        instructions = "",
                        totalCalories = caloriesPerServing * servings,
                        proteinPerServing = proteinPerServing,
                        carbsPerServing = carbsPerServing,
                        fatPerServing = fatPerServing,
                        servings = servings,
                        prepTimeMinutes = null,
                        tags = "",
                        showNutrition = true
                    )
                    db.recipeDao().insert(recipe)
                    imported++
                }

                _recipeState.value = YazioRecipeImportState.Success(
                    YazioRecipeImportResult(importedRecipes = imported, skippedRecipes = skipped)
                )
            } catch (e: Exception) {
                _recipeState.value = YazioRecipeImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /**
     * Importiert die mitgelieferten eigenen Yazio-Produkte (yazio_foods.json)
     * als eigene Lebensmittel (custom_foods). Nährwerte sind dort bereits pro
     * 100g angegeben, werden hier 1:1 als "Portion" mit 100g-Bezug abgelegt
     * (CustomFoodItem hat keine eigene Portionsgroesse -> Werte als 100g-Basis).
     * Ueberspringt Produkte deren Name bereits existiert.
     */
    fun importBundledFoods() {
        viewModelScope.launch {
            _foodState.value = YazioFoodImportState.Loading
            try {
                val context = getApplication<Application>()
                val jsonText = context.assets.open("yazio_foods.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(jsonText)

                val existing = mutableSetOf<String>()
                try {
                    db.customFoodDao().getAll().first().forEach {
                        existing.add(it.name.trim().lowercase())
                    }
                } catch (e: Exception) { /* ignore */ }

                var imported = 0
                var skipped = 0

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name", "Unbekanntes Produkt")
                    if (name.trim().lowercase() in existing) {
                        skipped++
                        continue
                    }

                    val brand = obj.optString("brand", null)
                    val displayName = if (!brand.isNullOrBlank()) "$name ($brand)" else name

                    val food = CustomFoodItem(
                        name = displayName,
                        calories = obj.optDouble("caloriesPer100g", 0.0).toFloat(),
                        protein = obj.optDouble("proteinPer100g", 0.0).toFloat(),
                        carbs = obj.optDouble("carbsPer100g", 0.0).toFloat(),
                        fat = obj.optDouble("fatPer100g", 0.0).toFloat(),
                        fiber = obj.optDouble("fiberPer100g", 0.0).toFloat()
                    )
                    db.customFoodDao().insert(food)
                    imported++
                }

                _foodState.value = YazioFoodImportState.Success(
                    YazioFoodImportResult(importedFoods = imported, skippedFoods = skipped)
                )
            } catch (e: Exception) {
                _foodState.value = YazioFoodImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    fun resetRecipeState() {
        _recipeState.value = YazioRecipeImportState.Idle
    }

    fun resetFoodState() {
        _foodState.value = YazioFoodImportState.Idle
    }

    /** Einfacher CSV-Split, der Anfuehrungszeichen-umschlossene Felder mit Kommas korrekt behandelt. */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        result.add(sb.toString())
        return result
    }

    fun reset() {
        _state.value = YazioImportState.Idle
    }

    /** Loescht ALLE Tagebuch-Eintraege (manuell, importiert, Rezepte). Unwiderruflich. */
    fun clearAllDiaryEntries(onDone: () -> Unit) {
        viewModelScope.launch {
            diaryRepo.deleteAllEntries()
            onDone()
        }
    }
}
