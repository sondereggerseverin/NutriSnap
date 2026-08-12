package ch.nutrisnap.app.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.domain.IngredientNutritionDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class YazioImportResult(
    val importedDays: Int = 0,
    val importedEntries: Int = 0,
    val skippedEntries: Int = 0,
    val autoCreatedFoods: Int = 0
)

sealed class YazioImportState {
    object Idle : YazioImportState()
    object Loading : YazioImportState()
    data class Success(val result: YazioImportResult) : YazioImportState()
    data class Error(val message: String) : YazioImportState()
}

data class YazioRecipeImportResult(
    val importedRecipes: Int = 0,
    val skippedRecipes: Int = 0,
    val autoCreatedIngredientFoods: Int = 0
)

data class YazioFoodImportResult(
    val importedFoods: Int = 0,
    val updatedFoods: Int = 0,
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

    /** name|brand|barcode (lowercase/getrimmt) - Identitätsschlüssel für Dedup. */
    private fun identityKey(name: String, brand: String?, barcode: String?): String =
        "${name.trim().lowercase()}|${(brand ?: "").trim().lowercase()}|${(barcode ?: "").trim()}"

    /** Lowercase + Whitespace kollabieren + trailing Satzzeichen entfernen. */
    private fun normalizeFoodName(raw: String): String {
        var s = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        s = s.trimEnd('.', ',', ';', ' ')
        return s
    }

    /** Name ohne abschliessende Klammer-Marke, z.B. "Magerquark (Milfina)" -> "magerquark". */
    /** Entfernt alle Klammerzusätze und hängende Marken-Suffixe für robustes Matching. */
    private fun baseName(raw: String): String {
        var n = normalizeFoodName(raw)
        // Alle (...) entfernen: "Eier, roh (ohne Schale) (Hühnerei)" → "eier, roh"
        n = n.replace(Regex("\\s*\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()
        // Trailing ", roh"/", gekocht" etc. behalten – lookup matcht per Substring
        return n
    }

    /**
     * 1) Exakte Duplikate entfernen
     * 2) Rezept-Einträge korrigieren, bei denen Gesamt-kcal als 1 Portion gespeichert wurden
     * onDone(removedDupes, repairedRecipeEntries)
     */
    fun deduplicateDiary(onDone: (removed: Int, repaired: Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val removed = diaryRepo.deduplicateEntries()
            val recipes = try {
                db.recipeDao().getAll().first()
            } catch (_: Exception) {
                emptyList()
            }
            val repaired = diaryRepo.repairInflatedRecipeEntries(recipes)
            onDone(removed, repaired)
        }
    }

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
                val allLines = reader.readLines()
                reader.close()
                _state.value = YazioImportState.Success(runNutritionLogImport(allLines))
            } catch (e: Exception) {
                _state.value = YazioImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /**
     * Importiert den mitgelieferten Diary-Export (app/src/main/assets/yazio_nutrition_log.csv)
     * direkt aus den Assets, ohne dass der Nutzer die Datei manuell ueber den Dateipicker
     * auswaehlen muss. Der Datei-Picker-Import [importNutritionLog] setzt eine vom Nutzer
     * ausgewaehlte Datei via [Uri] voraus - die im Repo mitgelieferte, aktualisierte CSV
     * (z.B. nach einem frischen Yazio-Export) wurde dadurch nie automatisch uebernommen.
     *
     * Dedup: bereits vorhandene Eintraege (gleiches Datum + gleicher Produktname + gleiche
     * Kalorienzahl) werden uebersprungen, damit ein wiederholter Import (oder ein bereits
     * per Dateipicker teilweise erfolgter Import) keine doppelten Tagebuch-Eintraege erzeugt.
     */
    fun importBundledNutritionLog() {
        viewModelScope.launch {
            _state.value = YazioImportState.Loading
            try {
                val context = getApplication<Application>()
                val text = context.assets.open("yazio_nutrition_log.csv")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val lines = text.lineSequence().toList()
                if (lines.isEmpty()) throw Exception("Leere CSV-Datei")
                _state.value = YazioImportState.Success(runNutritionLogImport(lines.drop(1)))
            } catch (e: Exception) {
                _state.value = YazioImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /**
     * Gemeinsame Import-Logik fuer Datei-Picker- und Bundled-Diary-Import.
     * Verknuepft jede Zeile mit einem bereits importierten Food/Rezept (per Name);
     * existiert weder Food noch Rezept, wird automatisch ein neues Food aus den
     * CSV-Totalwerten abgeleitet (source = yazio_diary_only), damit kein Eintrag
     * verloren geht. Dedup ueber Datum+Produktname+Kalorien macht den Import idempotent.
     */
    private suspend fun runNutritionLogImport(dataLines: List<String>): YazioImportResult {
        val existingKeys = mutableSetOf<String>()
        for (entry in diaryRepo.getAllEntriesOnce()) {
            existingKeys.add("${entry.dateStr}|${entry.foodName.trim().lowercase()}|${entry.calories.toInt()}")
        }

        // Mehrstufiges Lookup: exact name -> baseName; Rezepte normalisiert.
        val allFoods = db.customFoodDao().getAllOnce()
        val foodByName = mutableMapOf<String, Int>()
        val foodByBase = mutableMapOf<String, Int>()
        for (f in allFoods) {
            val exact = normalizeFoodName(f.name)
            foodByName.putIfAbsent(exact, f.id)
            foodByBase.putIfAbsent(baseName(f.name), f.id)
            val withoutParen = exact.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
            foodByName.putIfAbsent(withoutParen, f.id)
        }
        val recipeByTitle = db.recipeDao().getAll().first()
            .associate { normalizeFoodName(it.title) to it.id }

        var imported = 0
        var skipped = 0
        var autoCreatedFoods = 0
        val days = mutableSetOf<LocalDate>()

        for (rawLine in dataLines) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            val cols = splitCsvLine(line)
            if (cols.size < 8) { skipped++; continue }
            try {
                val dateStr = cols[0].trim()
                val mealStr = cols[1].trim().lowercase()
                val product = cols[2].trim().ifBlank { "Unbekannt" }
                val mengeRaw = cols[3].trim()
                val kcal = cols[4].trim().toFloatOrNull() ?: 0f
                val protein = cols[5].trim().toFloatOrNull() ?: 0f
                val fat = cols[6].trim().toFloatOrNull() ?: 0f
                val carbs = cols[7].trim().toFloatOrNull() ?: 0f
                val date = parseYazioDate(dateStr)
                    ?: throw DateTimeParseException("Unbekanntes Datumsformat", dateStr, 0)

                val mealType = when (mealStr) {
                    "breakfast", "fruehstueck", "fruehstuck", "frühstück" -> MealType.BREAKFAST
                    "lunch", "mittagessen" -> MealType.LUNCH
                    "dinner", "abendessen" -> MealType.DINNER
                    else -> MealType.SNACK
                }

                val quantityG = parseGrams(mengeRaw)
                val key = "${date}|${mealType.name}|${product.trim().lowercase()}|${"%.1f".format(quantityG ?: 0f)}|${kcal.toInt()}"
                if (key in existingKeys) { skipped++; continue }
                val nameKey = normalizeFoodName(product)
                val baseKey = baseName(product)
                // Rezept hat Vorrang vor Food bei Titel-Match – verhindert, dass
                // Rezept-Portionen als lose Lebensmittel angelegt werden.
                val matchedRecipeId = recipeByTitle[nameKey]
                var matchedFoodId = if (matchedRecipeId != null) null
                    else foodByName[nameKey] ?: foodByBase[baseKey]

                if (matchedFoodId == null && matchedRecipeId == null) {
                    // Weder Food noch Rezept vorhanden -> neues Food aus den CSV-Werten
                    // ableiten, damit kein Eintrag verloren geht. Bei bekannter Grammmenge
                    // wird auf 100g hochgerechnet, sonst der Totalwert als Naeherung
                    // gespeichert (source = yazio_diary_only macht das transparent).
                    val factor = if (quantityG != null && quantityG > 0f) 100f / quantityG else 1f
                    val newId = db.customFoodDao().insert(
                        CustomFoodItem(
                            name = product,
                            calories = kcal * factor,
                            protein = protein * factor,
                            carbs = carbs * factor,
                            fat = fat * factor,
                            portionSizeG = quantityG ?: 100f,
                            source = "yazio_diary_only"
                        )
                    )
                    matchedFoodId = newId.toInt()
                    foodByName[nameKey] = matchedFoodId
                    foodByBase.putIfAbsent(baseKey, matchedFoodId)
                    autoCreatedFoods++
                }

                diaryRepo.addManualEntry(
                    name = product,
                    kcal = kcal,
                    protein = protein,
                    carbs = carbs,
                    fat = fat,
                    mealType = mealType,
                    date = date,
                    amountGrams = quantityG ?: 0f,
                    matchedCustomFoodId = if (matchedRecipeId != null) null else matchedFoodId,
                    matchedRecipeId = matchedRecipeId
                )
                existingKeys.add(key)
                days.add(date)
                imported++
            } catch (e: Exception) {
                skipped++
            }
        }

        // Extra-Sicherheit: nach Import nochmals exakte Duplikate entfernen
        val extraDupes = diaryRepo.deduplicateEntries()
        return YazioImportResult(
            importedDays = days.size,
            importedEntries = imported,
            skippedEntries = skipped + extraDupes,
            autoCreatedFoods = autoCreatedFoods
        )
    }

    /** Parst die "Menge"-Spalte ("140.0g" -> 140.0). Andere Formate (z.B. Portionsangaben)
     *  liefern null, da daraus keine Grammmenge abgeleitet werden kann. */
    private fun parseGrams(raw: String): Float? {
        val match = Regex("^([\\d.,]+)\\s*g$").find(raw) ?: return null
        return match.groupValues[1].replace(",", ".").toFloatOrNull()
    }

    /**
     * Importiert die mitgelieferten Yazio-Rezepte (app/src/main/assets/yazio_recipes.json)
     * direkt in die "recipes" Tabelle. Ueberspringt Rezepte deren Titel bereits existiert.
     * Zutaten, die noch nicht als eigenes Lebensmittel existieren, werden automatisch
     * angelegt (Makros unbekannt, da die Rezeptdatei nur Name/Menge/Einheit/Produzent
     * liefert), damit kein Rezept unvollstaendig importiert wird.
     */
    fun importBundledRecipes() {
        viewModelScope.launch {
            _recipeState.value = YazioRecipeImportState.Loading
            try {
                val context = getApplication<Application>()
                val jsonText = context.assets.open("yazio_recipes.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(jsonText)

                val existing = mutableSetOf<String>()
                try {
                    db.recipeDao().getAll().first().forEach { existing.add(it.title.trim().lowercase()) }
                } catch (e: Exception) { /* ignore */ }

                // Bestehende Custom-Foods: ID-Lookup + Makro-Lookup (nach Basisname),
                // damit Rezeptzutaten nicht mit 0-kcal-Platzhaltern landen, wenn das
                // Produkt schon als Yazio-Food/manuell mit echten Nährwerten existiert.
                val allFoods = db.customFoodDao().getAllOnce()
                val foodIdByName = allFoods
                    .associate { it.name.trim().lowercase() to it.id }
                    .toMutableMap()
                val macrosByBaseName = allFoods
                    .filter { it.calories > 0f || it.protein > 0f || it.carbs > 0f || it.fat > 0f }
                    .groupBy { baseName(it.name) }
                    .mapValues { (_, list) ->
                        // Bevorzuge yazio_import / verified vor leeren Platzhaltern
                        list.maxWithOrNull(
                            compareBy<CustomFoodItem> { if (it.source == "yazio_import") 2 else if (it.verified) 1 else 0 }
                                .thenByDescending { it.calories }
                        )!!
                    }

                var imported = 0
                var skipped = 0
                var autoCreatedIngredients = 0

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val title = obj.optString("title", "Unbenanntes Rezept")
                    if (title.trim().lowercase() in existing) { skipped++; continue }

                    val servings = obj.optInt("servings", 1).coerceAtLeast(1)
                    val caloriesPerServing = obj.optDouble("caloriesPerServing", 0.0).toFloat()
                    val proteinPerServing = obj.optDouble("proteinPerServing", 0.0).toFloat()
                    val carbsPerServing = obj.optDouble("carbsPerServing", 0.0).toFloat()
                    val fatPerServing = obj.optDouble("fatPerServing", 0.0).toFloat()
                    val fiberPerServing = obj.optDouble("fiberPerServing", 0.0).toFloat()
                    val sugarPerServing = obj.optDouble("sugarPerServing", 0.0).toFloat()
                    val imageUrl = obj.optString("imageUrl", null).takeUnless { it.isNullOrBlank() }

                    val ingredientsArr = obj.optJSONArray("ingredients")
                    val ingredientsText = StringBuilder()
                    if (ingredientsArr != null) {
                        for (j in 0 until ingredientsArr.length()) {
                            val ing = ingredientsArr.getJSONObject(j)
                            val name = ing.optString("name", "").trim()
                            val amount = ing.optDouble("amount", 0.0)
                            val unit = ing.optString("unit", "g")
                            val producer = ing.optString("producer", null).takeUnless { it.isNullOrBlank() }

                            if (name.isNotBlank() && foodIdByName[name.lowercase()] == null
                                && foodIdByName[baseName(name)] == null
                            ) {
                                val displayName = if (!producer.isNullOrBlank()) "$name ($producer)" else name
                                // 1) bestehendes Custom-Food, 2) kuratierte Referenz-DB
                                val donor = macrosByBaseName[baseName(name)]
                                    ?: macrosByBaseName[baseName(displayName)]
                                val ref = if (donor == null) {
                                    IngredientNutritionDatabase.lookup(name)
                                        ?: IngredientNutritionDatabase.lookup(baseName(name))
                                } else null
                                val newId = db.customFoodDao().insert(
                                    CustomFoodItem(
                                        name = displayName,
                                        calories = donor?.calories ?: ref?.calories ?: 0f,
                                        protein = donor?.protein ?: ref?.protein ?: 0f,
                                        carbs = donor?.carbs ?: ref?.carbs ?: 0f,
                                        fat = donor?.fat ?: ref?.fat ?: 0f,
                                        fiber = donor?.fiber ?: ref?.fiber ?: 0f,
                                        sugar = donor?.sugar ?: 0f,
                                        salt = donor?.salt ?: 0f,
                                        barcode = donor?.barcode,
                                        brand = producer ?: donor?.brand,
                                        source = when {
                                            donor != null -> "yazio_import"
                                            ref != null -> "yazio_recipe_ingredient"
                                            else -> "yazio_recipe_ingredient"
                                        }
                                    )
                                )
                                foodIdByName[name.lowercase()] = newId.toInt()
                                foodIdByName[displayName.lowercase()] = newId.toInt()
                                foodIdByName[baseName(name)] = newId.toInt()
                                autoCreatedIngredients++
                            }

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
                        fiberPerServing = fiberPerServing,
                        sugarPerServing = sugarPerServing,
                        servings = servings,
                        prepTimeMinutes = null,
                        tags = "",
                        showNutrition = true
                    )
                    db.recipeDao().insert(recipe)
                    existing.add(title.trim().lowercase())
                    imported++
                }
                // Bestehende leere Zutaten (auch aus früheren Imports) nachfüllen
                val backfilled = backfillEmptyRecipeIngredients()

                _recipeState.value = YazioRecipeImportState.Success(
                    YazioRecipeImportResult(
                        importedRecipes = imported,
                        skippedRecipes = skipped,
                        autoCreatedIngredientFoods = autoCreatedIngredients + backfilled
                    )
                )
            } catch (e: Exception) {
                _recipeState.value = YazioRecipeImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /**
     * Importiert die mitgelieferten eigenen Yazio-Produkte (yazio_foods.json)
     * als eigene Lebensmittel (custom_foods). Nährwerte sind bereits pro 100 g.
     *
     * Dedup-Identität = name+brand+barcode (lowercase). Existiert der Eintrag bereits
     * mit identischen Makros -> übersprungen. Bei abweichenden Werten -> aktualisiert
     * (Yazio-Wert gewinnt, source = yazio_import). Existiert er nicht -> neu angelegt.
     * Damit ist der Import idempotent und Konflikte werden zugunsten von Yazio gelöst.
     */
    fun importBundledFoods() {
        viewModelScope.launch {
            _foodState.value = YazioFoodImportState.Loading
            try {
                val context = getApplication<Application>()
                val jsonText = context.assets.open("yazio_foods.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONArray(jsonText)

                val existing = db.customFoodDao().getAllOnce()
                // Match-Reihenfolge: Barcode > identity(name+brand+barcode) > baseName+brand
                val byBarcode = existing
                    .filter { !it.barcode.isNullOrBlank() }
                    .associateBy { it.barcode!!.trim() }
                    .toMutableMap()
                val byIdentity = existing
                    .associateBy { identityKey(it.name, it.brand, it.barcode) }
                    .toMutableMap()
                val byBaseBrand = existing
                    .associateBy { "${baseName(it.name)}|${(it.brand ?: "").trim().lowercase()}" }
                    .toMutableMap()

                var imported = 0
                var updated = 0
                var skipped = 0

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name", "Unbekanntes Produkt").trim()
                    val brand = obj.optString("brand", null).takeUnless { it.isNullOrBlank() }
                    val barcode = obj.optString("barcode", null).takeUnless { it.isNullOrBlank() }
                    val category = obj.optString("category", null).takeUnless { it.isNullOrBlank() }

                    val protein = obj.optDouble("proteinPer100g", 0.0).toFloat()
                    val carbs = obj.optDouble("carbsPer100g", 0.0).toFloat()
                    val fat = obj.optDouble("fatPer100g", 0.0).toFloat()
                    val fiber = obj.optDouble("fiberPer100g", 0.0).toFloat()
                    val sugar = obj.optDouble("sugarPer100g", 0.0).toFloat()
                    val salt = obj.optDouble("saltPer100g", 0.0).toFloat()
                    // Atwater/EU: kcal aus Makros, wenn gespeicherter Wert >15% abweicht
                    // (Yazio-Rohdaten hatten ~85 Einträge mit inkonsistenten kcal).
                    val calories = reconcileCalories(
                        obj.optDouble("caloriesPer100g", 0.0).toFloat(),
                        protein, carbs, fat, fiber
                    )

                    // displayName fuer Anzeige/Suche (Marke in Klammern);
                    // Dedup-Identitaet laeuft getrennt ueber name+brand+barcode.
                    val displayName = if (!brand.isNullOrBlank()) "$name ($brand)" else name
                    val key = identityKey(name, brand, barcode)
                    val baseBrandKey = "${baseName(name)}|${(brand ?: "").trim().lowercase()}"

                    val existingItem = when {
                        !barcode.isNullOrBlank() && byBarcode.containsKey(barcode.trim()) ->
                            byBarcode[barcode.trim()]
                        byIdentity.containsKey(key) -> byIdentity[key]
                        byBaseBrand.containsKey(baseBrandKey) -> byBaseBrand[baseBrandKey]
                        else -> null
                    }

                    if (existingItem == null) {
                        val newId = db.customFoodDao().insert(
                            CustomFoodItem(
                                name = displayName,
                                calories = calories,
                                protein = protein,
                                carbs = carbs,
                                fat = fat,
                                fiber = fiber,
                                sugar = sugar,
                                salt = salt,
                                barcode = barcode,
                                brand = brand,
                                category = category,
                                portionSizeG = 100f,
                                source = "yazio_import"
                            )
                        )
                        val inserted = CustomFoodItem(
                            id = newId.toInt(),
                            name = displayName,
                            calories = calories,
                            protein = protein,
                            carbs = carbs,
                            fat = fat,
                            fiber = fiber,
                            sugar = sugar,
                            salt = salt,
                            barcode = barcode,
                            brand = brand,
                            category = category,
                            portionSizeG = 100f,
                            source = "yazio_import"
                        )
                        if (!barcode.isNullOrBlank()) byBarcode[barcode.trim()] = inserted
                        byIdentity[key] = inserted
                        byBaseBrand[baseBrandKey] = inserted
                        imported++
                    } else {
                        val macrosDiffer = existingItem.calories != calories ||
                            existingItem.protein != protein || existingItem.carbs != carbs ||
                            existingItem.fat != fat || existingItem.fiber != fiber ||
                            existingItem.sugar != sugar || existingItem.salt != salt
                        // Barcode/Brand nachziehen, falls bisher fehlte (z.B. diary_only -> yazio_import)
                        val barcodeMissing = existingItem.barcode.isNullOrBlank() && !barcode.isNullOrBlank()
                        val brandMissing = existingItem.brand.isNullOrBlank() && !brand.isNullOrBlank()
                        if (macrosDiffer || barcodeMissing || brandMissing) {
                            val updatedItem = existingItem.copy(
                                calories = calories, protein = protein, carbs = carbs, fat = fat,
                                fiber = fiber, sugar = sugar, salt = salt,
                                barcode = barcode ?: existingItem.barcode,
                                brand = brand ?: existingItem.brand,
                                category = category ?: existingItem.category,
                                source = "yazio_import"
                            )
                            db.customFoodDao().update(updatedItem)
                            if (!updatedItem.barcode.isNullOrBlank()) {
                                byBarcode[updatedItem.barcode!!.trim()] = updatedItem
                            }
                            byIdentity[key] = updatedItem
                            byBaseBrand[baseBrandKey] = updatedItem
                            updated++
                        } else {
                            skipped++
                        }
                    }
                }
                // Leere Yazio-Rezeptzutaten (0-Makros) mit echten Werten aus diesem Import auffüllen
                val backfilled = backfillEmptyRecipeIngredients()

                _foodState.value = YazioFoodImportState.Success(
                    YazioFoodImportResult(
                        importedFoods = imported,
                        updatedFoods = updated + backfilled,
                        skippedFoods = skipped
                    )
                )
            } catch (e: Exception) {
                _foodState.value = YazioFoodImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /**
     * Füllt bestehende Custom-Foods mit 0-Makros:
     * 1) aus anderen Custom-Foods mit gleichem Basisnamen
     * 2) Fallback: [IngredientNutritionDatabase] (Ei, Linsen, Mehl, …)
     * @return Anzahl aktualisierter Einträge
     */
    private suspend fun backfillEmptyRecipeIngredients(): Int {
        val all = db.customFoodDao().getAllOnce()
        val donors = all
            .filter { it.calories > 0f || it.protein > 0f || it.carbs > 0f || it.fat > 0f }
            .groupBy { baseName(it.name) }
            .mapValues { (_, list) ->
                list.maxWithOrNull(
                    compareBy<CustomFoodItem> { if (it.source == "yazio_import") 2 else if (it.verified) 1 else 0 }
                        .thenByDescending { it.calories }
                )!!
            }
        var updated = 0
        for (item in all) {
            val empty = item.calories == 0f && item.protein == 0f && item.carbs == 0f && item.fat == 0f
            if (!empty) continue
            if (item.source != "yazio_recipe_ingredient" && item.source != "yazio_diary_only") continue

            val donor = donors[baseName(item.name)]
                ?.takeIf { it.id != item.id }
            if (donor != null) {
                db.customFoodDao().update(
                    item.copy(
                        calories = donor.calories,
                        protein = donor.protein,
                        carbs = donor.carbs,
                        fat = donor.fat,
                        fiber = donor.fiber,
                        sugar = donor.sugar,
                        salt = donor.salt,
                        barcode = item.barcode ?: donor.barcode,
                        brand = item.brand ?: donor.brand,
                        source = if (donor.source == "yazio_import") "yazio_import" else item.source
                    )
                )
                updated++
                continue
            }

            // Fallback: kuratierte Referenz-DB (Ei, Linsen, Mehl, …)
            val ref = IngredientNutritionDatabase.lookup(item.name)
                ?: IngredientNutritionDatabase.lookup(baseName(item.name))
            if (ref != null) {
                db.customFoodDao().update(
                    item.copy(
                        calories = ref.calories,
                        protein = ref.protein,
                        carbs = ref.carbs,
                        fat = ref.fat,
                        fiber = ref.fiber
                    )
                )
                updated++
            }
        }
        return updated
    }

    fun resetRecipeState() { _recipeState.value = YazioRecipeImportState.Idle }
    fun resetFoodState() { _foodState.value = YazioFoodImportState.Idle }

    /**
     * Yazio's App-CSV-Export liefert Daten im Format dd/MM/yyyy (z.B. "10/01/2026"),
     * nicht ISO (yyyy-MM-dd). [LocalDate.parse] ohne Formatter erwartet ISO und ist bei
     * jeder Zeile fehlgeschlagen -> der komplette Import wurde stillschweigend uebersprungen.
     * Versucht daher mehrere bekannte Formate, ISO zuletzt als Fallback (z.B. fuer Dateien
     * aus [importBundledRecipes] o.ae. Quellen, die bereits ISO liefern).
     */
    private fun parseYazioDate(raw: String): LocalDate? {
        val formatters = listOf(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE
        )
        for (fmt in formatters) {
            try {
                return LocalDate.parse(raw, fmt)
            } catch (e: DateTimeParseException) { /* naechstes Format probieren */ }
        }
        return null
    }

    /** Einfacher CSV-Split, der Anführungszeichen-umschlossene Felder mit Kommas korrekt behandelt. */
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

    fun reset() { _state.value = YazioImportState.Idle }

    /** Löscht ALLE Tagebuch-Einträge (manuell, importiert, Rezepte). Unwiderruflich. */
    fun clearAllDiaryEntries(onDone: () -> Unit) {
        viewModelScope.launch {
            diaryRepo.deleteAllEntries()
            onDone()
        }
    }

    companion object {
        /**
         * EU-Atwater: 4 kcal/g Protein + 4 KH + 9 Fett + 2 Ballaststoffe.
         * Weicht der gespeicherte Wert >15 % ab, gewinnt der berechnete Wert
         * (korrigiert fehlerhafte Yazio-Rohdaten).
         */
        fun reconcileCalories(
            stored: Float,
            protein: Float,
            carbs: Float,
            fat: Float,
            fiber: Float = 0f
        ): Float {
            val calc = 4f * protein + 4f * carbs + 9f * fat + 2f * fiber
            if (calc <= 0f) return stored
            if (protein + carbs + fat + fiber <= 0f) return stored
            val dev = kotlin.math.abs(stored - calc) / calc
            return if (dev > 0.15f) {
                // eine Nachkommastelle, analog zu den meisten JSON-Einträgen
                (calc * 10f).toInt() / 10f
            } else stored
        }
    }
}
