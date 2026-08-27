package ch.nutrisnap.app.data.repository

import android.content.Context
import android.util.Log
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.api.NutritionixApi
import ch.nutrisnap.app.data.api.OpenFoodFactsApi
import ch.nutrisnap.app.data.api.UsdaFoodApi
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.supabase.SupabaseSync
import ch.nutrisnap.app.data.supabase.SyncStatusHolder
import ch.nutrisnap.app.domain.RecipeScraper
import ch.nutrisnap.app.domain.SearchUtils
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_FAST_AI_PARSE
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_FAST_SCRAPE
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_PERSISTENT_CACHE
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_VIDEO_TRANSCRIPT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fire-and-forget scope for pushing local changes to Supabase. Local save
 *  bleibt immer erfolgreich; Cloud-Push läuft parallel mit Retry. */
private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Einzel-Push mit bis zu 4 Versuchen (Session/Netz kurz flackern oft).
 * Banner nur bei finalem Fehler — kein Dauer-„Synchronisiert…“ pro Tipp.
 */
private fun pushSafely(block: suspend () -> Unit) {
    syncScope.launch {
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            val result = runCatching { block() }
            if (result.isSuccess) {
                if (attempt > 0) {
                    Log.i("NutriSync", "Push OK nach ${attempt + 1}. Versuch")
                }
                return@launch
            }
            lastError = result.exceptionOrNull()
            Log.w(
                "NutriSync",
                "Push Versuch ${attempt + 1}/4 fehlgeschlagen: ${lastError?.message}"
            )
            kotlinx.coroutines.delay(400L * (attempt + 1))
        }
        Log.e("NutriSync", "Push endgültig fehlgeschlagen: ${lastError?.message}", lastError)
        SyncStatusHolder.opFailed(
            lastError?.message?.take(120) ?: "Cloud-Sync fehlgeschlagen"
        )
    }
}

class DiaryRepository(db: NutriDatabase) {
    private val dao = db.diaryDao()

    fun getEntriesForDate(date: LocalDate): Flow<List<DiaryEntry>> =
        dao.getEntriesForDate(date.toString())

    fun getWeeklySummary(from: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getWeeklySummary(from.toString())

    /** Fuer Tages-/Wochen-/Monats-/Kalenderansicht in der Analyse: begrenzter Zeitraum. */
    fun getSummaryBetween(from: LocalDate, to: LocalDate): Flow<List<ch.nutrisnap.app.data.db.DailySummary>> =
        dao.getSummaryBetween(from.toString(), to.toString())

    /** Fuer Quick-Add: nach dem Insert den vollen Eintrag laden (fuer Undo-Snackbar). */
    suspend fun getById(id: Long): DiaryEntry? = dao.getById(id)

    suspend fun addEntry(food: FoodItem, amountGrams: Float, mealType: MealType, date: LocalDate): Long {
        val f = amountGrams / 100f
        // Ein Tagebuch-Eintrag braucht konkrete Zahlen (fuer Tagessummen/Ziele) - anders als
        // FoodItem selbst darf DiaryEntry keine unbekannten Makros haben. Das 0-Fallback passiert
        // deshalb bewusst genau hier, an der Erfassungsgrenze, statt still in der Datenquelle.
        val id = dao.insert(
            DiaryEntry(
                foodItemId  = food.id,
                foodName    = food.name + (food.brand?.let { " ($it)" } ?: ""),
                amountGrams = amountGrams,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = (food.calories ?: 0f) * f,
                protein     = (food.protein  ?: 0f) * f,
                carbs       = (food.carbs    ?: 0f) * f,
                fat         = (food.fat      ?: 0f) * f,
                fiber       = (food.fiber ?: 0f) * f,
                sugar       = (food.sugar ?: 0f) * f,
                saturatedFat = (food.saturatedFat ?: 0f) * f,
                salt        = (food.salt ?: 0f) * f,
                sodium      = (food.sodium ?: 0f) * f,
                snapshotBrand = food.brand,
                snapshotBarcode = food.barcode,
                snapshotCaloriesPer100g = food.calories,
                snapshotProteinPer100g = food.protein,
                snapshotCarbsPer100g = food.carbs,
                snapshotFatPer100g = food.fat,
                snapshotSource = food.source.name
            )
        )
        dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
        return id
    }

    suspend fun getEntriesForDateOnce(date: LocalDate): List<DiaryEntry> =
        dao.getEntriesForDateOnce(date.toString())

    /** Kopiert einen bestehenden Eintrag auf ein anderes Datum (gleiche Makros/Menge). */
    suspend fun duplicateEntryToDate(entry: DiaryEntry, targetDate: LocalDate): Long {
        val copy = entry.copy(
            id = 0,
            dateStr = targetDate.toString()
        )
        val id = dao.insert(copy)
        dao.getById(id)?.let { e -> pushSafely { SupabaseSync.upsertDiaryEntry(e) } }
        return id
    }

    /**
     * Add a recipe as a diary entry.
     * amountGrams stores the servingsFactor (e.g. 1.0 = 1 portion, 2.0 = 2 portions)
     * so the edit dialog can show and modify portions correctly.
     * foodItemId = -(recipe.id) to mark as recipe-type entry.
     */
    suspend fun addRecipeAsMeal(
        recipe: Recipe,
        servingsFactor: Float,
        mealType: MealType,
        date: LocalDate,
        gramsAmount: Float? = null
    ): Long {
        val perServing  = recipe.servings.coerceAtLeast(1).toFloat()
        // Gramm-Modus: Anteil am Gesamtgericht (Roh- oder Kochgewicht).
        // Nur explizite Gramm-Angaben ≥ 10 g; kleinere Werte sind Portionsfaktoren
        // (nie als Gramm speichern — sonst „1 g“ bei voller Portions-kcal).
        val yieldG = recipe.yieldWeightG()
            ?: ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.estimateTotalGrams(recipe.ingredients)
                .takeIf { it > 0f }
        val realGrams = gramsAmount?.takeIf { it >= 10f }
        val factor = when {
            realGrams != null && yieldG != null && yieldG > 0f ->
                (realGrams / yieldG * perServing).coerceAtLeast(0.05f)
            // Kein stiller Portions-Fallback aus „Gramm < 10“: Aufrufer muss servingsFactor setzen
            else -> servingsFactor.coerceAtLeast(0.05f)
        }
        val calsPerServ = recipe.totalCalories?.let { it / perServing } ?: 0f
        val calories    = calsPerServ * factor
        val protein     = (recipe.proteinPerServing ?: 0f) * factor
        val carbs       = (recipe.carbsPerServing   ?: 0f) * factor
        val fat         = (recipe.fatPerServing     ?: 0f) * factor
        val fiber       = (recipe.fiberPerServing   ?: 0f) * factor
        val sugar       = (recipe.sugarPerServing   ?: 0f) * factor
        val saturatedFat = (recipe.saturatedFatPerServing ?: 0f) * factor
        val salt        = (recipe.saltPerServing    ?: 0f) * factor
        val sodium      = (recipe.sodiumPerServing  ?: 0f) * factor

        // amountGrams = immer Portionsfaktor (Skalierung); recipeGrams = Anzeige in g
        val storedAmount = factor
        val storedRecipeGrams = realGrams

        val id = dao.insert(
            DiaryEntry(
                foodItemId  = -(recipe.id.toInt()).coerceAtMost(-1), // negative = recipe entry
                foodName    = recipe.title,
                amountGrams = storedAmount,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = calories,
                protein     = protein,
                carbs       = carbs,
                fat         = fat,
                fiber       = fiber,
                sugar       = sugar,
                saturatedFat = saturatedFat,
                salt        = salt,
                sodium      = sodium,
                recipeGrams = storedRecipeGrams
            )
        )
        dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
        return id
    }

    /**
     * Multi-Komponenten-Rezept ins Tagebuch: eine DiaryEntry pro Komponente.
     * [gramsByComponentId] = Map componentId → abgewogene Gramm (nach dem Kochen).
     * Leere/0-Gramm-Einträge werden übersprungen.
     * @return IDs der erzeugten Einträge
     */
    suspend fun addRecipeComponentsAsMeal(
        recipe: Recipe,
        components: List<RecipeComponent>,
        gramsByComponentId: Map<Long, Float>,
        mealType: MealType,
        date: LocalDate
    ): List<Long> {
        val ids = mutableListOf<Long>()
        for (c in components) {
            val grams = gramsByComponentId[c.id]?.takeIf { it >= 1f } ?: continue
            val scaled = c.scaledTo(grams)
            // amountGrams = Anteil am Komponenten-Batch (für spätere Skalierung);
            // recipeGrams = Anzeige in g
            val factor = if (c.cookedWeightG > 0f) (grams / c.cookedWeightG).coerceAtLeast(0.001f) else 1f
            val id = dao.insert(
                DiaryEntry(
                    foodItemId = -(recipe.id.toInt()).coerceAtMost(-1),
                    foodName = "${recipe.displayTitle()} – ${c.name}",
                    amountGrams = factor,
                    mealType = mealType,
                    dateStr = date.toString(),
                    calories = scaled.calories,
                    protein = scaled.protein,
                    carbs = scaled.carbs,
                    fat = scaled.fat,
                    fiber = scaled.fiber,
                    recipeGrams = grams,
                    matchedRecipeId = recipe.id
                )
            )
            dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
            ids.add(id)
        }
        return ids
    }

    /**
     * Manual entry: user types name + kcal + optional macros directly.
     * foodItemId = [MANUAL_FOOD_ITEM_ID] marks manual entries. amountGrams = 0
     * (Portionsbasis 1 beim ersten Edit).
     */
    suspend fun addManualEntry(
        name: String,
        kcal: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        mealType: MealType,
        date: LocalDate,
        fiber: Float = 0f,
        sugar: Float = 0f,
        saturatedFat: Float = 0f,
        salt: Float = 0f,
        sodium: Float = 0f,
        amountGrams: Float = 0f,
        matchedCustomFoodId: Int? = null,
        matchedRecipeId: Long? = null
    ): Long {
        val id = dao.insert(
            DiaryEntry(
                foodItemId  = MANUAL_FOOD_ITEM_ID,
                foodName    = name,
                amountGrams = amountGrams,
                mealType    = mealType,
                dateStr     = date.toString(),
                calories    = kcal,
                protein     = protein,
                carbs       = carbs,
                fat         = fat,
                fiber        = fiber,
                sugar        = sugar,
                saturatedFat = saturatedFat,
                salt         = salt,
                sodium       = sodium,
                matchedCustomFoodId = matchedCustomFoodId,
                matchedRecipeId     = matchedRecipeId
            )
        )
        dao.getById(id)?.let { entry -> pushSafely { SupabaseSync.upsertDiaryEntry(entry) } }
        return id
    }

    /**
     * Generischer Insert-Pfad fuer Aufrufer ausserhalb dieses Repositories (z.B.
     * KI-Tagesplan in RecipeGeneratorViewModel), die einen fertigen DiaryEntry
     * (foodItemId bereits gesetzt, z.B. -999) direkt anlegen wollen. Stellt sicher,
     * dass JEDER Insert-Pfad ueber Supabase synct statt db.diaryDao().insert()
     * direkt aufzurufen und den Push zu umgehen.
     */
    suspend fun insertAndSync(entry: DiaryEntry): Long {
        val id = dao.insert(entry)
        dao.getById(id)?.let { saved -> pushSafely { SupabaseSync.upsertDiaryEntry(saved) } }
        return id
    }

    suspend fun updateEntry(entry: DiaryEntry) {
        dao.update(entry)
        pushSafely { SupabaseSync.upsertDiaryEntry(entry) }
    }

    suspend fun deleteEntry(entry: DiaryEntry) {
        dao.delete(entry)
        pushSafely { SupabaseSync.deleteDiaryEntry(entry.id) }
    }

    /**
     * Entfernt exakte Tagebuch-Duplikate (gleiches Datum, Mahlzeit, Name, Menge, kcal).
     * Behält jeweils den Eintrag mit der kleinsten ID. Löscht auch in Supabase.
     * @return Anzahl gelöschter Duplikate
     */
    suspend fun deduplicateEntries(): Int {
        val all = dao.getAllOnce()
        val seen = mutableSetOf<String>()
        var removed = 0
        // Stabil nach id sortieren, damit der älteste Eintrag bleibt
        for (entry in all.sortedBy { it.id }) {
            val key = contentFingerprint(entry)
            if (key in seen) {
                dao.delete(entry)
                pushSafely { SupabaseSync.deleteDiaryEntry(entry.id) }
                removed++
            } else {
                seen.add(key)
            }
        }
        return removed
    }

    /**
     * Korrigiert Tagebuch-Einträge, bei denen fälschlich die **Gesamt-Rezeptkalorien**
     * als eine Portion gespeichert wurden (z.B. 5169 kcal statt ~500).
     * Erkennt: Name matcht Rezept, kcal ≈ totalCalories (±3%), amount ≈ 1 Portion.
     * @return Anzahl korrigierter Einträge
     */
    suspend fun repairInflatedRecipeEntries(recipes: List<Recipe>): Int {
        if (recipes.isEmpty()) return 0
        val byTitle = recipes
            .filter { (it.totalCalories ?: 0f) > 200f && it.servings > 1 }
            .associateBy { it.title.trim().lowercase() }
        if (byTitle.isEmpty()) return 0

        var fixed = 0
        for (entry in dao.getAllOnce()) {
            val recipe = byTitle[entry.foodName.trim().lowercase()] ?: continue
            val total = recipe.totalCalories ?: continue
            val serv = recipe.servings.coerceAtLeast(1)
            // Nur wenn Eintrag ungefähr der Gesamtmenge entspricht (1:1 total)
            if (kotlin.math.abs(entry.calories - total) > total * 0.03f) continue
            // amountGrams: 1 Portion oder 0 (manuell)
            val looksLikeOnePortion = entry.amountGrams in 0f..1.5f || entry.recipeGrams == null
            if (!looksLikeOnePortion) continue

            val factor = 1f / serv
            val updated = entry.scaledBy(factor).copy(amountGrams = 1f)
            dao.update(updated)
            pushSafely { SupabaseSync.upsertDiaryEntry(updated) }
            fixed++
        }
        return fixed
    }

    companion object {
        /**
         * Inhaltlicher Fingerprint für Dedup (Import + Sync-Pull).
         * foodItemId und feinere Rundung reduzieren False-Positives bei ähnlichen Mengen.
         */
        fun contentFingerprint(entry: DiaryEntry): String =
            listOf(
                entry.dateStr,
                entry.mealType.name,
                entry.foodItemId.toString(),
                entry.foodName.trim().lowercase(),
                "%.2f".format(entry.amountGrams),
                "%.1f".format(entry.calories),
                entry.recipeGrams?.let { "%.1f".format(it) } ?: "-"
            ).joinToString("|")
    }

    /** Für Undo nach Löschen: legt den Eintrag mit neuer ID erneut an. */
    suspend fun restoreEntry(entry: DiaryEntry): Long {
        val id = dao.insert(entry.copy(id = 0))
        pushSafely { SupabaseSync.upsertDiaryEntry(entry.copy(id = id)) }
        return id
    }

    suspend fun deleteAllEntries() = dao.deleteAll()

    /** Einmaliger Snapshot aller Tagebuch-Eintraege, u.a. fuer Dedup-Checks beim Bulk-Import. */
    suspend fun getAllEntriesOnce() = dao.getAllOnce()

    /** Rohe Einträge der letzten [days] Tage (heute mitgezählt), u.a. für die
     *  Mustererkennung wiederkehrender Mahlzeiten. */
    suspend fun getDiaryEntriesLastNDays(days: Int): List<DiaryEntry> =
        dao.getEntriesSince(LocalDate.now().minusDays((days - 1).toLong()).toString())

    /**
     * Persistiert die manuell per Drag-Handle geänderte Reihenfolge innerhalb einer Mahlzeit.
     * orderedIds = Einträge in der neuen Anzeigereihenfolge (Index = neue sortOrder).
     * Kein Supabase-Sync nötig, da sortOrder rein lokale UI-Präferenz ist.
     */
    suspend fun updateSortOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> dao.updateSortOrder(id, index) }
    }
}

class RecipeRepository(db: NutriDatabase, private val context: Context) {
    private val dao           = db.recipeDao()
    private val componentDao  = db.recipeComponentDao()
    private val matchDao      = db.ingredientMatchDao()
    private val scraper       = RecipeScraper(context)

    fun getAll():          Flow<List<Recipe>> = dao.getAll()
    fun search(q: String): Flow<List<Recipe>> = dao.search(q)

    fun getComponents(recipeId: Long): Flow<List<RecipeComponent>> =
        componentDao.getForRecipe(recipeId)

    suspend fun getComponentsOnce(recipeId: Long): List<RecipeComponent> =
        componentDao.getForRecipeOnce(recipeId)

    /**
     * Merged Komponenten eines Rezepts per ID (kein blindes delete-all → insert-all).
     * - Bestehende Zeilen mit id > 0 werden per @Update aktualisiert
     * - Neue Zeilen (id == 0) werden eingefügt
     * - DB-Zeilen, die in [components] nicht mehr vorkommen, werden gelöscht
     * Leere Liste = One-Pot (alle Komponenten entfernen).
     */
    suspend fun setComponents(recipeId: Long, components: List<RecipeComponent>) {
        val existing = componentDao.getForRecipeOnce(recipeId)
        if (components.isEmpty()) {
            if (existing.isNotEmpty()) componentDao.deleteForRecipe(recipeId)
            return
        }
        val incomingById = components.filter { it.id > 0L }.associateBy { it.id }
        // Update vorhandene
        for (old in existing) {
            val neu = incomingById[old.id]
            if (neu != null) {
                val idx = components.indexOfFirst { it.id == old.id }.coerceAtLeast(0)
                componentDao.update(
                    neu.copy(id = old.id, recipeId = recipeId, sortOrder = idx)
                )
            } else {
                // Nicht mehr in der Liste → entfernen
                componentDao.delete(old)
            }
        }
        // Insert neue (id == 0 oder unbekannte id)
        val existingIds = existing.map { it.id }.toSet()
        components.forEachIndexed { index, c ->
            if (c.id == 0L || c.id !in existingIds) {
                componentDao.insert(
                    c.copy(id = 0, recipeId = recipeId, sortOrder = index)
                )
            }
        }
    }

    suspend fun deleteComponents(recipeId: Long) {
        componentDao.deleteForRecipe(recipeId)
    }

    /**
     * Speichert ein Rezept. Bei gleichem Inhalts-Fingerprint (sourceUrl oder
     * Titel+Zutaten+kcal) wird das **bestehende** Rezept unverändert zurückgegeben
     * — kein stilles Full-Overwrite mehr. Bewusste Änderungen laufen über
     * [updateRecipe]. Verhindert, dass ein erneuter Import Zutaten, Matches und
     * Komponenten still überschreibt.
     */
    suspend fun saveRecipe(r: Recipe): Long {
        val clean = r.withoutNullArtifacts().withGuessedCategoryIfEmpty()
        val existing = findByFingerprint(contentFingerprint(clean))
        if (existing != null && (clean.id == 0L || clean.id == existing.id)) {
            // Bestehendes Rezept unangetastet lassen (kein dao.update)
            return existing.id
        }
        val id = dao.insert(clean)
        dao.getById(id)?.let { saved -> pushSafely { SupabaseSync.upsertRecipe(saved) } }
        return id
    }

    suspend fun updateRecipe(r: Recipe) {
        val clean = r.withoutNullArtifacts().let { if (it.mealCategory.isBlank()) it.withGuessedCategoryIfEmpty() else it }
        dao.update(clean)
        pushSafely { SupabaseSync.upsertRecipe(clean) }
    }

    /**
     * Optional Deutsch/metrisch nach Import. [force] = true erzwingt Übersetzung
     * (z.B. Instagram/TikTok/Bild), unabhängig von der User-Einstellung.
     * Speichert nur wenn [recipe.id] > 0 und sich etwas geändert hat.
     */
    suspend fun applyGermanMetricIfNeeded(recipe: Recipe, enabled: Boolean, force: Boolean = false): Recipe {
        if (!enabled && !force) return recipe
        val converted = ch.nutrisnap.app.domain.RecipeGermanMetricConverter
            .convertWithAi(recipe).getOrNull() ?: return recipe
        val updated = recipe.copy(
            title = converted.title.ifBlank { recipe.title },
            description = converted.description.ifBlank { recipe.description },
            ingredients = converted.ingredients.ifBlank { recipe.ingredients },
            instructions = converted.instructions.ifBlank { recipe.instructions },
            mealCategory = recipe.mealCategory.ifBlank {
                ch.nutrisnap.app.data.model.RecipeCategory.guess(
                    converted.title.ifBlank { recipe.title },
                    converted.ingredients.ifBlank { recipe.ingredients },
                    converted.description.ifBlank { recipe.description }
                ).name
            }
        )
        if (recipe.id > 0L && updated != recipe) updateRecipe(updated)
        return updated
    }

    /**
     * Nährwerte aus Zutaten berechnen und am Rezept persistieren.
     * Ohne UI-State — von Import-Pipeline und Detail-Screen gemeinsam nutzbar.
     * @return aktualisiertes Rezept + Analyse, oder null bei Fehler / fehlender ID.
     */
    suspend fun analyzeAndPersistNutrition(
        recipe: Recipe
    ): Pair<Recipe, ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.AnalysisResult>? {
        if (recipe.id <= 0L) return null
        val analysis = runCatching {
            ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.analyze(recipe)
        }.getOrNull() ?: return null
        val macroLine = "📊 Pro Portion: ${analysis.caloriesPerServing.toInt()} kcal" +
            " · ${analysis.proteinPerServing.toInt()}g Protein" +
            " · ${analysis.carbsPerServing.toInt()}g Kohlenhydrate" +
            " · ${analysis.fatPerServing.toInt()}g Fett"
        val baseDesc = recipe.description.lines()
            .filterNot { it.startsWith("📊") }.joinToString("\n").trim()
        val newDesc = if (baseDesc.isNotBlank()) "$baseDesc\n\n$macroLine" else macroLine
        val servDiv = recipe.servings.coerceAtLeast(1)
        // fiber/sugar/saturatedFat/salt/sodium haben eigene Spalten — der Rest (Vitamine,
        // Mineralstoffe, monoFat/polyFat/... ) wurde bisher berechnet, aber nie gespeichert.
        val dedicatedKeys = setOf("fiber", "sugar", "saturatedFat", "salt", "sodium")
        val restPerServing = analysis.totalMicros
            .filterKeys { it !in dedicatedKeys }
            .mapValues { (_, total) -> total / servDiv }
        val microJson = if (restPerServing.isEmpty()) null else {
            val obj = org.json.JSONObject()
            restPerServing.forEach { (k, v) -> obj.put(k, v.toDouble()) }
            obj.toString()
        }
        val updated = recipe.copy(
            totalCalories = analysis.totalCalories,
            proteinPerServing = analysis.proteinPerServing,
            carbsPerServing = analysis.carbsPerServing,
            fatPerServing = analysis.fatPerServing,
            fiberPerServing = analysis.totalMicros["fiber"]?.div(servDiv) ?: recipe.fiberPerServing,
            sugarPerServing = analysis.totalMicros["sugar"]?.div(servDiv) ?: recipe.sugarPerServing,
            saturatedFatPerServing = analysis.totalMicros["saturatedFat"]?.div(servDiv)
                ?: recipe.saturatedFatPerServing,
            saltPerServing = analysis.totalMicros["salt"]?.div(servDiv) ?: recipe.saltPerServing,
            sodiumPerServing = analysis.totalMicros["sodium"]?.div(servDiv) ?: recipe.sodiumPerServing,
            microNutrientsJson = microJson ?: recipe.microNutrientsJson,
            description = newDesc
        )
        updateRecipe(updated)
        return updated to analysis
    }

    /**
     * Nachladen des Rezept-Bildes via oEmbed/og:image, ohne neu zu scrapen.
     * @return aktualisiertes Rezept oder null wenn kein Bild gefunden.
     */
    suspend fun refreshRecipeImage(recipe: Recipe): Recipe? {
        val url = recipe.sourceUrl?.trim().orEmpty()
        if (url.isBlank()) return null
        val thumb = scraper.fetchThumbnailUrl(url, recipe.platform) ?: return null
        if (thumb.isBlank()) return null
        val updated = recipe.copy(imageUrl = thumb)
        updateRecipe(updated)
        return updated
    }

    /**
     * Korrigiert gespeicherte „null“-/„undefined“-Titel und -Beschreibungen
     * (Android JSONObject.optString-Artefakt bei LLM-Antworten).
     * @return Anzahl bereinigter Rezepte
     */
    suspend fun repairNullTitleArtifacts(): Int {
        var fixed = 0
        for (r in dao.getAllOnce()) {
            val t = r.title.trim()
            val d = r.description.trim()
            val badTitle = t.isEmpty() || t.equals("null", true) || t.equals("undefined", true) ||
                t.startsWith("<!DOCTYPE", true) || t.startsWith("<html", true) ||
                (t.length > 80 && t.count { it == '<' } >= 3)
            val badDesc = d.equals("null", true) || d.equals("undefined", true) ||
                d.startsWith("<!DOCTYPE", true)
            val badIng = r.ingredients.trimStart().startsWith("<!DOCTYPE", true) ||
                r.ingredients.trimStart().startsWith("<html", true)
            if (!badTitle && !badDesc && !badIng) continue
            val cleaned = r.withoutNullArtifacts().copy(
                title = if (badTitle) "Rezept" else r.displayTitle(),
                description = if (badDesc) "" else r.displayDescription(),
                ingredients = if (badIng) "Tippe ✏️ um Zutaten hinzuzufügen." else r.ingredients
            )
            dao.update(cleaned)
            pushSafely { SupabaseSync.upsertRecipe(cleaned) }
            fixed++
        }
        return fixed
    }

    suspend fun deleteRecipe(r: Recipe) {
        // Zugehörige Daten mitlöschen (sonst verwaiste Matches/Komponenten)
        runCatching { componentDao.deleteForRecipe(r.id) }
        runCatching { matchDao.deleteMatchesForRecipe(r.id) }
        dao.delete(r)
        pushSafely { SupabaseSync.deleteRecipe(r.id) }
    }

    /**
     * Rezept duplizieren inkl. verifizierter Matches und Komponenten.
     * Neuer Titel „… (Kopie)“, ohne sourceUrl, nicht favorisiert.
     * @return gespeicherte Kopie mit neuer id
     */
    suspend fun duplicateRecipe(recipe: Recipe): Recipe {
        val copy = recipe.copy(
            id = 0,
            title = recipe.displayTitle().let { base ->
                if (base.endsWith("(Kopie)")) base else "$base (Kopie)"
            },
            sourceUrl = null,
            savedAt = System.currentTimeMillis(),
            isFavorite = false
        ).withoutNullArtifacts()
        // Fingerprint-Dedup umgehen: Kopie bewusst als neues Rezept
        val newId = dao.insert(copy.withoutNullArtifacts().withGuessedCategoryIfEmpty())
        val saved = copy.copy(id = newId)
        pushSafely { SupabaseSync.upsertRecipe(saved) }

        val matches = matchDao.getMatchesForRecipeOnce(recipe.id)
        if (matches.isNotEmpty()) {
            matchDao.insertMatches(matches.map { m ->
                m.copy(id = 0, recipeId = newId)
            })
        }
        val components = componentDao.getForRecipeOnce(recipe.id)
        if (components.isNotEmpty()) {
            componentDao.insertAll(components.map { c ->
                c.copy(id = 0, recipeId = newId)
            })
        }
        return saved
    }

    /**
     * Merged verifizierte Zutaten als IngredientMatch-Zeilen (kein delete-all).
     * Primär per id, Fallback über normalisierten ingredientName.
     * Bestehende componentGroup / matchedFoodItemId bleiben erhalten, wenn die
     * neue Zeile keinen expliziten neuen Wert mitbringt.
     */
    suspend fun mergeMatchesForRecipe(
        recipeId: Long,
        matches: List<ch.nutrisnap.app.data.model.IngredientMatch>
    ) {
        val existing = matchDao.getMatchesForRecipeOnce(recipeId)
        if (matches.isEmpty()) {
            if (existing.isNotEmpty()) matchDao.deleteMatchesForRecipe(recipeId)
            return
        }
        fun norm(s: String) = s.trim().lowercase()
        val byId = existing.associateBy { it.id }
        val byName = existing
            .filter { it.ingredientName.isNotBlank() }
            .associateBy { norm(it.ingredientName) }
        val usedIds = mutableSetOf<Long>()

        for (m in matches) {
            val old = when {
                m.id > 0L && m.id in byId -> byId[m.id]
                norm(m.ingredientName).isNotBlank() && norm(m.ingredientName) in byName ->
                    byName[norm(m.ingredientName)]
                else -> null
            }
            if (old != null) {
                usedIds += old.id
                val merged = m.copy(
                    id = old.id,
                    recipeId = recipeId,
                    componentGroup = m.componentGroup ?: old.componentGroup,
                    matchedFoodItemId = m.matchedFoodItemId ?: old.matchedFoodItemId,
                    matchedFoodName = m.matchedFoodName ?: old.matchedFoodName,
                    matchedCalories = m.matchedCalories ?: old.matchedCalories,
                    matchedProtein = m.matchedProtein ?: old.matchedProtein,
                    matchedCarbs = m.matchedCarbs ?: old.matchedCarbs,
                    matchedFat = m.matchedFat ?: old.matchedFat,
                    matchedFiber = m.matchedFiber ?: old.matchedFiber,
                    matchSource = if (m.matchSource != ch.nutrisnap.app.data.model.MatchSource.UNMATCHED)
                        m.matchSource else old.matchSource,
                    manualAmountG = m.manualAmountG ?: old.manualAmountG,
                    manualFiberG = m.manualFiberG ?: old.manualFiberG,
                    isDeleted = m.isDeleted
                )
                matchDao.updateMatch(merged)
            } else {
                matchDao.insertMatch(m.copy(id = 0, recipeId = recipeId))
            }
        }
        for (old in existing) {
            if (old.id !in usedIds) matchDao.deleteMatch(old)
        }
    }

    suspend fun getById(id: Long) = dao.getById(id)

    suspend fun getAllOnce(): List<Recipe> = dao.getAllOnce()

    suspend fun findByFingerprint(fp: String): Recipe? =
        dao.getAllOnce().firstOrNull { contentFingerprint(it) == fp }

    /** Liefert ein bestehendes Rezept anhand der normalisierten sourceUrl, oder null. */
    suspend fun findBySourceUrl(url: String): Recipe? {
        val normalized = url.trim().lowercase().trimEnd('/')
        if (normalized.isBlank()) return null
        // Exact match first (häufigste Form), dann normalisierter Vergleich
        return dao.findBySourceUrlExact(url.trim())
            ?: dao.findBySourceUrlExact(normalized)
            ?: dao.getAllOnce().firstOrNull {
                it.sourceUrl?.trim()?.lowercase()?.trimEnd('/') == normalized
            }
    }

    /**
     * Entfernt lokale Rezept-Duplikate (gleicher Fingerprint). Behält den
     * ältesten Eintrag (kleinste id), löscht die restlichen inkl. Supabase-Push.
     * @return Anzahl gelöschter Duplikate
     */
    suspend fun deduplicateRecipes(): Int {
        val all = dao.getAllOnce()
        val keep = linkedMapOf<String, Recipe>()
        val toDelete = mutableListOf<Recipe>()
        for (r in all.sortedBy { it.id }) {
            val key = contentFingerprint(r)
            if (key in keep) toDelete.add(r) else keep[key] = r
        }
        for (r in toDelete) {
            dao.delete(r)
            pushSafely { SupabaseSync.deleteRecipe(r.id) }
        }
        return toDelete.size
    }

    /**
     * @param highQuality true = Score-Retry + gründlichere Pipeline (langsamer).
     *                    false = einmaliger schneller Versuch (Default).
     * @param allowOverwrite true = bestehendes Rezept unter derselben URL erneut scrapen
     *                       und überschreiben (für „Besser importieren“-Button).
     */
    suspend fun importFromUrl(
        url: String,
        onProgress: (String) -> Unit = {},
        highQuality: Boolean = false,
        allowOverwrite: Boolean = false
    ): RecipeScrapeResult {
        // Bereits vorhanden? → kein erneutes Scrapen/Extrahieren (spart API-Kosten,
        // verhindert stilles Überschreiben von Zutaten/Matches/Komponenten).
        if (!allowOverwrite) {
            val existing = findBySourceUrl(url)
            if (existing != null) {
                onProgress("Bereits gespeichert")
                return RecipeScrapeResult(success = true, recipe = existing)
            }
        }

        // Experiment-Toggles aus Settings (default = bisheriges Verhalten)
        val prefs = runCatching {
            context.notifDataStore.data.first()
        }.getOrNull()
        val fastAi = prefs?.get(KEY_RECIPE_FAST_AI_PARSE) ?: false
        val fastScrape = prefs?.get(KEY_RECIPE_FAST_SCRAPE) ?: false
        val persistentCache = prefs?.get(KEY_RECIPE_PERSISTENT_CACHE) ?: true
        val videoTranscript = prefs?.get(KEY_RECIPE_VIDEO_TRANSCRIPT) ?: false
        val result = scraper.scrape(
            url,
            onProgress,
            fastScrape = fastScrape,
            fastAi = fastAi,
            persistentCache = persistentCache,
            videoTranscript = videoTranscript,
            highQuality = highQuality
        )
        if (result.success && result.recipe != null) {
            val id = saveRecipe(result.recipe)
            // Falls per Fingerprint ein bestehendes Rezept matchte: DB-Stand zurück
            val saved = dao.getById(id) ?: result.recipe.copy(id = id)
            return result.copy(recipe = saved)
        }
        return result
    }

    companion object {
        /**
         * Inhalts-Fingerprint: bevorzugt normalisierte sourceUrl, sonst
         * Titel + Zutaten-Anfang + kcal — stabil über Sync-Runden.
         */
        fun contentFingerprint(r: Recipe): String {
            val url = r.sourceUrl?.trim()?.lowercase()?.trimEnd('/')
            if (!url.isNullOrBlank()) return "url|$url"
            return listOf(
                "t",
                r.title.trim().lowercase(),
                r.ingredients.trim().lowercase().take(120),
                r.totalCalories?.let { "%.0f".format(it) } ?: "-",
                r.servings.toString()
            ).joinToString("|")
        }

        fun contentFingerprint(
            title: String,
            sourceUrl: String?,
            ingredients: String,
            totalCalories: Float?,
            servings: Int
        ): String = contentFingerprint(
            Recipe(
                title = title,
                sourceUrl = sourceUrl,
                ingredients = ingredients,
                totalCalories = totalCalories,
                servings = servings
            )
        )
    }
}

/** Wandelt einen importierten Yazio-/Eigenprodukt-Eintrag in ein [FoodItem] fuer
 *  die Suchergebnisliste um. completenessScore = 90: verifizierte eigene Daten,
 *  vertrauenswuerdiger als generische OFF-Treffer (50), aber unter einem exakten
 *  Barcode-Treffer der Swiss-DB (85 fuer SwissFoodApi liegt sogar knapp darunter,
 *  da dies hier explizit selbst gepflegte/gegessene Produkte des Nutzers sind). */
private fun CustomFoodItem.toFoodItem(): FoodItem = FoodItem(
    name = name,
    brand = brand,
    barcode = barcode,
    calories = calories,
    protein = protein,
    carbs = carbs,
    fat = fat,
    fiber = fiber,
    sugar = sugar,
    salt = salt,
    servingSize = portionSizeG,
    source = FoodSource.MANUAL,
    completenessScore = 90
)

class FoodItemRepository(db: NutriDatabase) {
    private val dao = db.foodItemDao()
    private val customFoodDao = db.customFoodDao()

    /**
     * FoodSearchRepository wired with real API clients.
     * API keys are read from BuildConfig; empty strings are safe — both
     * UsdaFoodApi and NutritionixApi catch exceptions gracefully.
     */
    private val remoteRepo = FoodSearchRepository(
        foodItemDao         = dao,
        usdaApi             = UsdaFoodApi(apiKey = BuildConfig.USDA_API_KEY),
        nutritionixApi      = NutritionixApi(
            appId  = BuildConfig.NUTRITIONIX_APP_ID,
            apiKey = BuildConfig.NUTRITIONIX_API_KEY
        ),
        openFoodFactsSearch = { query -> OpenFoodFactsApi.search(query) }
    )

    fun getCustom(): Flow<List<FoodItem>> = dao.getAllCustom()


    /** FTS5 zuerst, LIKE als Fallback (kurze Queries / leerer FTS-Treffer). */
    private suspend fun localFoodSearch(dao: ch.nutrisnap.app.data.db.FoodItemDao, q: String): List<FoodItem> {
        val fts = SearchUtils.toFtsMatchQuery(q)
        if (fts.isNotBlank()) {
            val hits = runCatching { dao.searchFts(fts) }.getOrDefault(emptyList())
            if (hits.isNotEmpty()) return hits
        }
        return runCatching { dao.search(q) }.getOrDefault(emptyList())
    }

    suspend fun searchAll(query: String): List<FoodItem> {
        // Barcode shortcut: pure digit string 8–14 chars
        if (query.all { it.isDigit() } && query.length in 8..14) {
            val barcodeResult = remoteRepo.searchByBarcode(query)
            if (barcodeResult != null) return listOf(barcodeResult)
        }
        // Lokale Queries mit Varianten (Kompositum-Split, Tokens, Synonyme),
        // damit z.B. "kalbsplätzli" auch "Kalbs Plätzli" in custom_foods findet.
        val variants = SearchUtils.localQueryVariants(query)
        val local = variants.flatMap { q ->
            localFoodSearch(dao, q)
        }.distinctBy { it.barcode ?: it.name.lowercase().trim() }
        val custom = variants.flatMap { q ->
            runCatching { customFoodDao.searchOnce(q) }.getOrDefault(emptyList())
        }.map { it.toFoodItem() }
            .distinctBy { it.barcode ?: it.name.lowercase().trim() }
        val remoteList = remoteRepo.search(query)
        val names = (local + custom).map { it.name.lowercase() }.toSet()
        val combined = local + custom + remoteList.filter { it.name.lowercase() !in names }
        return combined
            .distinctBy { it.barcode ?: (it.name.lowercase().trim() + "|" + (it.brand?.lowercase()?.trim() ?: "")) }
            .sortedWith(FoodSearchRepository.relevanceComparator(query))
    }

    suspend fun searchBarcode(barcode: String): FoodItem? {
        val bc = ch.nutrisnap.app.utils.BarcodeUtils.normalize(barcode)
        if (bc.isEmpty()) return null
        // 1) Eigene gespeicherte Produkte (z.B. per Etikett-Foto angelegt)
        customFoodDao.getByBarcode(bc)?.let { return it.toFoodItem() }
        // Fallback: falls Alt-Daten mit Leerzeichen gespeichert wurden
        if (bc != barcode) {
            customFoodDao.getByBarcode(barcode.trim())?.let { return it.toFoodItem() }
        }
        // 2) Lokaler food_items-Cache
        dao.searchByBarcode(bc)?.let { return it }
        if (bc != barcode) {
            dao.searchByBarcode(barcode.trim())?.let { return it }
        }
        // 3) Remote (OFF / Swiss / …)
        return remoteRepo.searchByBarcode(bc) ?: remoteRepo.searchByBarcode(barcode.trim())
    }

    /**
     * Speichert CustomFood inkl. normalisiertem Barcode und spiegelt es in food_items,
     * damit Barcode-Suche lokal sofort trifft (nicht nur custom_foods).
     */
    suspend fun saveCustomFoodWithBarcode(item: CustomFoodItem): Long {
        val bc = ch.nutrisnap.app.utils.BarcodeUtils.normalize(item.barcode)
        val normalized = item.copy(barcode = bc.ifBlank { null })
        val id = customFoodDao.insert(normalized).toInt()
        // food_items-Spiegel: erneuter Scan findet das Produkt auch im lokalen Cache
        if (!bc.isNullOrBlank()) {
            val existing = dao.searchByBarcode(bc)
            val food = FoodItem(
                id = existing?.id ?: 0,
                name = normalized.name,
                brand = normalized.brand,
                barcode = bc,
                calories = normalized.calories,
                protein = normalized.protein,
                carbs = normalized.carbs,
                fat = normalized.fat,
                fiber = normalized.fiber,
                sugar = normalized.sugar,
                salt = normalized.salt,
                servingSize = normalized.portionSizeG.coerceAtLeast(1f),
                source = FoodSource.MANUAL,
                completenessScore = 95,
                timesUsed = existing?.timesUsed ?: 0
            )
            dao.insert(food)
        }
        customFoodDao.getById(id)?.let { saved ->
            pushSafely { SupabaseSync.upsertCustomFood(saved) }
        }
        return id.toLong()
    }

    suspend fun getById(id: Int): FoodItem?                = dao.getById(id)
    suspend fun saveCustomFood(item: FoodItem): Long       = dao.insert(item)
    suspend fun deleteFood(item: FoodItem)                 = dao.delete(item)
}

