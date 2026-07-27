package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.api.GroqFoodEstimatorApi
import ch.nutrisnap.app.data.api.NutritionixApi
import ch.nutrisnap.app.data.api.SwissFoodApi
import ch.nutrisnap.app.data.api.UsdaFoodApi
import ch.nutrisnap.app.data.db.FoodItemDao
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.domain.SearchUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

class FoodSearchRepository(
    private val foodItemDao: FoodItemDao,
    private val usdaApi: UsdaFoodApi,
    private val nutritionixApi: NutritionixApi,
    private val openFoodFactsSearch: suspend (String) -> List<FoodItem>
) {

    suspend fun search(query: String): List<FoodItem> {
        // Schweizerdeutsche Begriffe (z.B. "Poulet", "Rüebli") und ihre Kurzformen
        // (z.B. "pouletbrus" statt "Pouletbrust") finden sich in OFF/USDA/Nutritionix
        // kaum, da diese Quellen hochdeutsche/englische Produktnamen führen. Für die
        // externen Quellen wird deshalb zusätzlich der übersetzte Begriff angefragt.
        // Die Swiss-DB bekommt weiterhin den Originalbegriff (kennt evtl. "Poulet" direkt).
        val swissVariant = swissGermanVariant(query)
        val effectiveQuery = swissVariant ?: query
        // Kompositum-Zerlegung: "süsskartoffelpommes" -> "süsskartoffel pommes".
        // Wird als zusätzliche Kandidaten-Query an die Remote-Quellen geschickt,
        // da deren Volltextsuche zusammengeschriebene Komposita oft nicht findet.
        val compoundVariant = compoundSplitVariant(effectiveQuery)

        val cached = foodItemDao.searchFoods(query) +
            (swissVariant?.let { foodItemDao.searchFoods(it) } ?: emptyList())
        val cachedDistinct = cached.distinctBy { normalizeKey(it) }
        // Nur ein EXAKTER Namens-Treffer zählt als "gut genug" — "beginnt mit" reicht
        // nicht, weil z.B. "Apfelringe" auch mit "apfel" beginnt und sonst faelschlich
        // als Treffer für die Anfrage "apfel" durchgeht, obwohl kein echter Apfel dabei ist.
        if (cachedDistinct.size >= 5 && cachedDistinct.any { relevance(it, effectiveQuery) == 4 }) {
            return cachedDistinct.sortedWith(relevanceComparator(effectiveQuery))
        }

        return coroutineScope {
            val offDeferred = async { runCatching { openFoodFactsSearch(effectiveQuery) }.getOrDefault(emptyList()) }
            val usdaDeferred = async { runCatching { usdaApi.search(effectiveQuery) }.getOrDefault(emptyList()) }
            val swissDeferred = async { runCatching { SwissFoodApi.search(query) }.getOrDefault(emptyList()) }
            val compoundDeferred = compoundVariant?.let { cv ->
                async { runCatching { openFoodFactsSearch(cv) }.getOrDefault(emptyList()) }
            }

            val off = offDeferred.await()
            val usda = usdaDeferred.await()
            val swiss = swissDeferred.await()
            val compound = compoundDeferred?.await() ?: emptyList()

            var combined = (cachedDistinct + swiss + off + usda + compound)

            if (combined.size < 5) {
                val nutritionix = runCatching { nutritionixApi.searchBranded(effectiveQuery) }.getOrDefault(emptyList())
                combined = combined + nutritionix
            }

            // Echte Treffer (Wort- oder Kompositum-/Fuzzy-Match, relevance >= 2) haben
            // immer Vorrang vor der KI-Schätzung. Nur wenn wirklich nichts Ähnliches
            // gefunden wurde, wird per KI grob geschätzt (klar als "KI-geschätzt"
            // markiert, s. GroqFoodEstimatorApi/FoodSource).
            if (combined.none { relevance(it, effectiveQuery) >= 2 }) {
                GroqFoodEstimatorApi.estimate(effectiveQuery)?.let { combined = combined + it }
            }

            val result = combined
                .distinctBy { normalizeKey(it) }
                .sortedWith(relevanceComparator(effectiveQuery))

            foodItemDao.insertAll(result.filter { it.source != FoodSource.MANUAL }.take(20))
            result
        }
    }



    private fun relevanceComparator(query: String): Comparator<FoodItem> = Companion.relevanceComparator(query)
    private fun relevance(item: FoodItem, query: String): Int = Companion.relevance(item, query)

    companion object {
        /**
         * Wie gut der Produktname zur Suchanfrage passt:
         * 4 = exakt, 3 = beginnt mit Anfrage, 2 = enthält als eigenes Wort,
         * 1 = enthält als Teilstring, 0 = kein Treffer.
         */
        fun relevance(item: FoodItem, query: String): Int {
            val q = SearchUtils.normalize(query)
            val name = SearchUtils.normalize(item.name)
            val qCompact = q.replace(" ", "")
            val nameCompact = name.replace(" ", "")
            return when {
                name == q -> 4
                name.startsWith(q) -> 3
                Regex("\\b${Regex.escape(q)}").containsMatchIn(name) -> 2
                name.contains(q) -> 1
                qCompact.length >= 3 && nameCompact.contains(qCompact) -> 1 // Kompositum, z.B. "süsskartoffelpommes"
                SearchUtils.fuzzyMatch(q, name) -> 1 // Tippfehler/Synonym (z.B. "fritten" -> "pommes")
                else -> 0
            }
        }

        /**
         * Sortiert zuerst danach, wie gut der Produktname zur Suchanfrage passt
         * (exakt > beginnt mit > enthält als Wort > enthält als Teilstring), erst
         * dann nach completenessScore. Ohne das landen bei mehrdeutigen API-
         * Antworten (OFF liefert keine feste Relevanz-Reihenfolge) beliebige
         * Treffer oben, und identische Suchen liefern je nach Cache-Zustand
         * unterschiedliche Ergebnisse.
         *
         * Public, damit andere Aufrufer (z.B. Repositories.searchAll, das
         * lokale DB-Treffer und Remote-Treffer separat zusammenführt) dieselbe
         * Sortierung anwenden können statt lokale Treffer unsortiert voranzustellen.
         */
        fun relevanceComparator(query: String): Comparator<FoodItem> =
            compareByDescending<FoodItem> { relevance(it, query) }.thenByDescending { it.completenessScore }
    }

    suspend fun searchNaturalLanguage(query: String): List<FoodItem> {
        return runCatching { nutritionixApi.parseNaturalLanguage(query) }.getOrDefault(emptyList())
    }

    suspend fun searchByBarcode(barcode: String): FoodItem? {
        return foodItemDao.searchByBarcode(barcode)
            ?: runCatching { SwissFoodApi.search("barcode:$barcode").firstOrNull() }.getOrNull()
            ?: runCatching { openFoodFactsSearch("barcode:$barcode").firstOrNull() }.getOrNull()
    }

    fun getRecentFoods(): Flow<List<FoodItem>> = foodItemDao.getRecentFoods()
    fun getFrequentFoods(): Flow<List<FoodItem>> = foodItemDao.getFrequentFoods()

    suspend fun incrementUsage(foodItem: FoodItem) {
        if (foodItem.id != 0) foodItemDao.incrementUsage(foodItem.id)
    }

    private fun normalizeKey(item: FoodItem): String =
        (item.barcode ?: item.name.lowercase().trim())

    // ── Schweizerdeutsch-Normalisierung ────────────────────────────────────────
    // Häufige Regionalismen, die in OFF/USDA/Nutritionix (hochdeutsche/englische
    // Produktnamen) nicht als solche gefunden werden. Bei Bedarf einfach ergänzen.
    private val swissGermanRoots = mapOf(
        "poulet" to "hähnchen",
        "gitzi" to "ziege",
        "rüebli" to "karotte",
        "herdöpfel" to "kartoffel",
        "gschwellti" to "pellkartoffel",
        "nüssler" to "feldsalat",
        "gipfeli" to "croissant",
        "silserli" to "brötchen",
        "zmorge" to "frühstück",
        "zmittag" to "mittagessen",
        "znacht" to "abendessen"
    )

    // Verkürzte/unvollständige Endungen, wie sie beim schnellen Eintippen entstehen
    // (z.B. "pouletbrus" statt "pouletbrust").
    private val endingCorrections = mapOf(
        "brus" to "brust",
        "gschnetzelt" to "geschnetzelt",
        "flügeli" to "flügel"
    )

    /**
     * Liefert die hochdeutsche Entsprechung für schweizerdeutsche Suchbegriffe
     * (auch als Wortteil, z.B. "pouletbrust" -> "hähnchenbrust"), oder null, falls
     * kein bekannter Regionalismus erkannt wurde.
     */
    private fun swissGermanVariant(query: String): String? {
        val q = query.trim().lowercase()
        val (root, standard) = swissGermanRoots.entries.firstOrNull { q.startsWith(it.key) } ?: return null
        var suffix = q.removePrefix(root)
        if (suffix.isBlank()) return standard
        endingCorrections[suffix]?.let { suffix = it }
        return (standard + suffix).trim()
    }

    // Bekannte Lebensmittel-Substantive, an denen ein zusammengeschriebenes
    // Kompositum aufgetrennt werden kann (z.B. "süsskartoffelpommes" ->
    // "süsskartoffel pommes"). Bei Bedarf einfach ergänzen.
    private val compoundSplitWords = listOf(
        "pommes", "kartoffel", "kartoffeln", "curry", "salat", "brot", "suppe",
        "sauce", "soße", "gemüse", "reis", "nudeln", "wurst", "käse", "brust"
    )

    /**
     * Trennt ein zusammengeschriebenes Kompositum an einem bekannten Suffix-Wort
     * auf ("süsskartoffelpommes" -> "süsskartoffel pommes"), damit die Volltextsuche
     * externer Quellen (OFF etc.) auch bei zusammengeschriebenen Begriffen greift.
     * Gibt null zurück, wenn die Query bereits Leerzeichen enthält oder kein
     * bekanntes Suffix gefunden wird.
     */
    private fun compoundSplitVariant(query: String): String? {
        val q = query.trim().lowercase()
        if (q.contains(" ") || q.length < 6) return null
        for (suffix in compoundSplitWords) {
            if (q.endsWith(suffix) && q.length > suffix.length + 2) {
                return "${q.removeSuffix(suffix)} $suffix"
            }
        }
        return null
    }
}
