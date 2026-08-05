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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fire-and-forget scope for pushing local changes to Supabase. A failed push
 *  (e.g. offline) never breaks the local save — it's caught and swallowed. */
private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
/**
 * Einzel-Push (ein Tagebuch-Eintrag, ein Rezept, …).
 * Aktualisiert den Sync-Banner bewusst NICHT — sonst bleibt "Synchronisiert…"
 * permanent sichtbar, weil bei jedem Tippen opStarted/opSucceeded flackert und
 * parallele Pushes activeOps nie auf 0 bringen. Banner nur bei SyncManager.pushAllLocal/pullAll.
 */
private fun pushSafely(block: suspend () -> Unit) {
    syncScope.launch {
        runCatching { block() }
            .onFailure {
                Log.e("NutriSync", "Push zu Supabase fehlgeschlagen: ${it.message}", it)
                // Nur Fehler im Banner, kein Dauer-SYNCING
                SyncStatusHolder.opFailed(it.message)
            }
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
                sodium      = (food.sodium ?: 0f) * f
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

class RecipeRepository(db: NutriDatabase, context: Context) {
    private val dao     = db.recipeDao()
    private val scraper = RecipeScraper(context)

    fun getAll():          Flow<List<Recipe>> = dao.getAll()
    fun search(q: String): Flow<List<Recipe>> = dao.search(q)

    /**
     * Speichert ein Rezept. Bei gleichem Inhalts-Fingerprint (sourceUrl oder
     * Titel+Zutaten+kcal) wird das bestehende aktualisiert statt ein Duplikat
     * anzulegen — verhindert 10× denselben Import.
     */
    suspend fun saveRecipe(r: Recipe): Long {
        val existing = findByFingerprint(contentFingerprint(r))
        if (existing != null && (r.id == 0L || r.id == existing.id)) {
            val merged = r.copy(id = existing.id, savedAt = existing.savedAt)
            dao.update(merged)
            pushSafely { SupabaseSync.upsertRecipe(merged) }
            return existing.id
        }
        val id = dao.insert(r)
        dao.getById(id)?.let { saved -> pushSafely { SupabaseSync.upsertRecipe(saved) } }
        return id
    }

    suspend fun updateRecipe(r: Recipe) {
        dao.update(r)
        pushSafely { SupabaseSync.upsertRecipe(r) }
    }

    suspend fun deleteRecipe(r: Recipe) {
        dao.delete(r)
        pushSafely { SupabaseSync.deleteRecipe(r.id) }
    }

    suspend fun getById(id: Long) = dao.getById(id)

    suspend fun getAllOnce(): List<Recipe> = dao.getAllOnce()

    suspend fun findByFingerprint(fp: String): Recipe? =
        dao.getAllOnce().firstOrNull { contentFingerprint(it) == fp }

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

    suspend fun importFromUrl(url: String, onProgress: (String) -> Unit = {}): RecipeScrapeResult {
        val result = scraper.scrape(url, onProgress)
        if (result.success && result.recipe != null) {
            val id = saveRecipe(result.recipe)
            val saved = result.recipe.copy(id = id)
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

    suspend fun searchAll(query: String): List<FoodItem> {
        // Barcode shortcut: pure digit string 8–14 chars
        if (query.all { it.isDigit() } && query.length in 8..14) {
            val barcodeResult = remoteRepo.searchByBarcode(query)
            if (barcodeResult != null) return listOf(barcodeResult)
        }
        // BUG-FIX: dao.search() gecachte lokale API-Treffer koennen ueber mehrere Suchen
        // hinweg als echte Duplikate im Cache landen (insertAll nutzt OnConflictStrategy.
        // IGNORE auf einer autogenerierten ID, die bei jedem Insert neu vergeben wird -
        // "Konflikt" tritt also nie ein). distinctBy hier faengt das an der Anzeige ab,
        // ohne die Cache-Tabelle selbst migrieren zu muessen.
        val local  = dao.search(query).distinctBy { it.barcode ?: it.name.lowercase().trim() }
        // BUG-FIX: der importierte Yazio-/Eigenprodukt-Katalog (custom_foods, befuellt
        // ueber YazioImportViewModel.importBundledFoods) wurde bisher komplett ignoriert -
        // die Suche kannte nur food_items (API-Cache). Eigene Produkte wie "Sour Cream"
        // waren dadurch nie auffindbar, obwohl sie in der DB lagen.
        val custom = customFoodDao.searchOnce(query).map { it.toFoodItem() }
        val remoteList = remoteRepo.search(query)
        val names      = (local + custom).map { it.name.lowercase() }.toSet()
        val combined   = local + custom + remoteList.filter { it.name.lowercase() !in names }
        // local ist eine rohe DB-LIKE-Query ohne Relevanz-Reihenfolge — ohne diese
        // Sortierung landen zufällige lokale Cache-Treffer vor besser passenden
        // Remote-Ergebnissen (z.B. "Mini Chinois" vor "Apfel naturtrüb" bei Suche "apfel").
        return combined
            .distinctBy { it.barcode ?: (it.name.lowercase().trim() + "|" + (it.brand?.lowercase()?.trim() ?: "")) }
            .sortedWith(FoodSearchRepository.relevanceComparator(query))
    }

    suspend fun searchBarcode(barcode: String): FoodItem? {
        // 1) Eigene gespeicherte Produkte (z.B. per Etikett-Foto angelegt)
        customFoodDao.getByBarcode(barcode)?.let { return it.toFoodItem() }
        // 2) Lokaler food_items-Cache
        dao.searchByBarcode(barcode)?.let { return it }
        // 3) Remote (OFF / Swiss / …)
        return remoteRepo.searchByBarcode(barcode)
    }

    suspend fun saveCustomFoodWithBarcode(item: CustomFoodItem): Long {
        val id = customFoodDao.insert(item).toInt()
        customFoodDao.getById(id)?.let { saved ->
            pushSafely { SupabaseSync.upsertCustomFood(saved) }
        }
        return id.toLong()
    }

    suspend fun getById(id: Int): FoodItem?                = dao.getById(id)
    suspend fun saveCustomFood(item: FoodItem): Long       = dao.insert(item)
    suspend fun deleteFood(item: FoodItem)                 = dao.delete(item)
}

