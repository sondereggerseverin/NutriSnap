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

        // Synonym-Expansion für lokale DB (z.B. "poulet" → auch "hähnchen"/"chicken")
        val synonymQueries = synonymExpansionQueries(query) + listOfNotNull(swissVariant)
        val cached = (listOf(query) + synonymQueries).flatMap { q ->
            runCatching { foodItemDao.searchFoods(q) }.getOrDefault(emptyList())
        }
        val cachedDistinct = cached
            .distinctBy { normalizeKey(it) }
            .filter { hasUsableNutrition(it) || relevance(it, effectiveQuery) >= 3 }
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
            // Zusätzliche Remote-Suche mit Synonym (z.B. "chicken" bei "poulet")
            val synonymRemoteDeferred = synonymQueries.take(2).map { sq ->
                async { runCatching { openFoodFactsSearch(sq) }.getOrDefault(emptyList()) }
            }
            // Bei Mehrwort-Query auch das spezifischste Token remote suchen
            // ("kebabfleisch" statt nur "poulet kebabfleisch" → OFF findet mehr)
            val specificToken = effectiveQuery.trim().split(Regex("\\s+"))
                .map { it.lowercase() }
                .filter { it.length >= 5 }
                .firstOrNull { t ->
                    t !in setOf("poulet", "hähnchen", "haehnchen", "chicken", "fleisch", "sauce")
                }
            val specificDeferred = specificToken?.let { tok ->
                async { runCatching { openFoodFactsSearch(tok) }.getOrDefault(emptyList()) }
            }

            val off = offDeferred.await()
            val usda = usdaDeferred.await()
            val swiss = swissDeferred.await()
            val compound = compoundDeferred?.await() ?: emptyList()
            val synonymRemote = synonymRemoteDeferred.flatMap { it.await() }
            val specificRemote = specificDeferred?.await() ?: emptyList()

            var combined = (cachedDistinct + swiss + off + usda + compound + synonymRemote + specificRemote)

            if (combined.count { hasUsableNutrition(it) } < 5) {
                val nutritionix = runCatching { nutritionixApi.searchBranded(effectiveQuery) }.getOrDefault(emptyList())
                combined = combined + nutritionix
            }

            // Echte Treffer (Wort- oder Kompositum-/Fuzzy-Match, relevance >= 2) haben
            // immer Vorrang vor der KI-Schätzung. Nur wenn wirklich nichts Ähnliches
            // gefunden wurde, wird per KI grob geschätzt (klar als "KI-geschätzt"
            // markiert, s. GroqFoodEstimatorApi/FoodSource).
            if (combined.none { relevance(it, effectiveQuery) >= 2 && hasUsableNutrition(it) }) {
                GroqFoodEstimatorApi.estimate(effectiveQuery)?.let { combined = combined + it }
            }

            val result = combined
                .distinctBy { normalizeKey(it) }
                .let { list ->
                    val usable = list.filter { hasUsableNutrition(it) }
                    // Spezifische Treffer (relevance ≥ 2) bevorzugen — sonst
                    // fluten generische "Hähnchen…" die Liste bei "poulet kebabfleisch"
                    val specific = usable.filter { relevance(it, effectiveQuery) >= 2 }
                    when {
                        specific.size >= 3 -> specific
                        usable.size >= 3 -> usable
                        else -> list
                    }
                }
                .sortedWith(relevanceComparator(effectiveQuery))

            foodItemDao.insertAll(result.filter { it.source != FoodSource.MANUAL }.take(20))
            result
        }
    }

    /** Mindestens eine sinnvolle Makro-Angabe (kein komplett leerer OFF-Eintrag). */
    private fun hasUsableNutrition(item: FoodItem): Boolean {
        val kcal = item.calories ?: 0f
        val p = item.protein ?: 0f
        val c = item.carbs ?: 0f
        val f = item.fat ?: 0f
        return kcal > 0f || p > 0f || c > 0f || f > 0f
    }

    /**
     * Liefert alternative Suchbegriffe aus [SearchUtils]-Synonymen und der
     * Schweizerdeutsch-Map, damit die lokale LIKE-Suche auch "Hähnchen" findet
     * wenn der Nutzer "poulet" tippt.
     */
    private fun synonymExpansionQueries(query: String): List<String> {
        val q = SearchUtils.normalize(query.trim())
        if (q.length < 3) return emptyList()
        val out = linkedSetOf<String>()
        // Direkte Synonyme
        SearchUtils.synonymsOf(q).forEach { out += it }
        // swissGermanRoots deckt poulet→hähnchen ab
        swissGermanVariant(query)?.let { out += it }
        // Auch umgekehrt: "hähnchen" soll "poulet" in Custom-Foods finden
        swissGermanRoots.entries
            .filter { it.value == q || q.startsWith(it.value) }
            .forEach { out += it.key + q.removePrefix(it.value) }
        return out.filter { it.isNotBlank() && it != q }.take(4)
    }



    private fun relevanceComparator(query: String): Comparator<FoodItem> = Companion.relevanceComparator(query)
    private fun relevance(item: FoodItem, query: String): Int = Companion.relevance(item, query)

    companion object {
        /**
         * Wie gut der Produktname zur Suchanfrage passt:
         * 4 = exakt, 3 = beginnt mit Anfrage / alle Tokens treffen,
         * 2 = spezifisches Token + weiteres, 1 = nur generisches Token oder Teilstring,
         * 0 = kein Treffer.
         *
         * Mehrwort-Queries ("poulet kebabfleisch"): "Hähnchen-Kebabfleisch" muss
         * klar über generischem "Hähnchenbrustfilet" landen.
         */
        fun relevance(item: FoodItem, query: String): Int {
            val q = SearchUtils.normalize(query)
            val name = SearchUtils.normalize(item.name)
            val qCompact = q.replace(" ", "")
            val nameCompact = name.replace(" ", "")
            if (name == q || nameCompact == qCompact) return 4
            if (name.startsWith(q) || nameCompact.startsWith(qCompact)) return 3

            val tokens = q.split(Regex("\\s+")).filter { it.length >= 3 }
            if (tokens.size >= 2) {
                fun tokenHits(t: String): Boolean {
                    if (name.contains(t) || nameCompact.contains(t)) return true
                    // poulet ↔ hähnchen ↔ chicken (nach normalize: ae)
                    val alts = when (t) {
                        "poulet" -> listOf("haehnchen", "huhn", "chicken")
                        "haehnchen" -> listOf("poulet", "huhn", "chicken")
                        "chicken" -> listOf("poulet", "haehnchen", "huhn")
                        else -> emptyList()
                    }
                    return alts.any { name.contains(it) || nameCompact.contains(it) }
                }
                val generic = setOf(
                    "poulet", "haehnchen", "huhn", "chicken", "fleisch", "meat",
                    "sauce", "sosse", "filet", "brust"
                )
                val hitCount = tokens.count { tokenHits(it) }
                val specificTokens = tokens.filter { it !in generic }
                val specificHits = specificTokens.count { tokenHits(it) }
                return when {
                    hitCount == tokens.size -> 3
                    specificHits >= 1 && hitCount >= 2 -> 3
                    specificHits >= 1 -> 2
                    hitCount >= 1 -> 1
                    else -> 0
                }
            }

            return when {
                Regex("\\b${Regex.escape(q)}").containsMatchIn(name) -> 2
                name.contains(q) -> 1
                qCompact.length >= 3 && nameCompact.contains(qCompact) -> 1
                SearchUtils.fuzzyMatch(q, name) -> 1
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
            compareByDescending<FoodItem> { relevance(it, query) }
                .thenByDescending {
                    val kcal = it.calories ?: 0f
                    val p = it.protein ?: 0f
                    // Einträge ohne jegliche Nährwerte ganz nach unten
                    if (kcal <= 0f && p <= 0f) 0 else 1
                }
                .thenByDescending { it.completenessScore }
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
        "sauce", "sosse", "gemuese", "reis", "nudeln", "wurst", "kaese", "brust",
        "fleisch", "hackfleisch", "schnitzel", "plaetzli", "steak", "filet",
        "braten", "voressen"
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
