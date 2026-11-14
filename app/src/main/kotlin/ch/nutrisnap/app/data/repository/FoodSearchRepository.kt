package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.api.GroqFoodEstimatorApi
import ch.nutrisnap.app.data.api.NutritionixApi
import ch.nutrisnap.app.data.api.SwissFoodApi
import ch.nutrisnap.app.data.api.UsdaFoodApi
import ch.nutrisnap.app.data.db.FoodItemDao
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.data.db.FoodSearchIndex
import ch.nutrisnap.app.domain.FoodRanker
import ch.nutrisnap.app.domain.FoodSearchText
import ch.nutrisnap.app.domain.NutritionSanity
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
        // Auch die Komposita-Variante lokal suchen: "leinsamenbrot" findet so den
        // Katalogeintrag "Leinsamen-Brot" bzw. "Leinsamen Brot" (FTS trennt am Bindestrich).
        val cached = (listOf(query) + synonymQueries + listOfNotNull(compoundVariant)).distinct().flatMap { q ->
            // Normalisierter Suchindex zuerst (Präfix + Teilstring, umlaut-tolerant); nur die
            // Originalanfrage darf den Teilstring-Scan auslösen. Alte FTS/LIKE-Suche als Fallback.
            val indexed = runCatching { FoodSearchIndex.candidates(foodItemDao, q, allowInfix = q == query) }
                .getOrDefault(emptyList())
            if (indexed.isNotEmpty()) return@flatMap indexed
            val fts = SearchUtils.toFtsMatchQuery(q)
            val ftsHits = if (fts.isNotBlank())
                runCatching { foodItemDao.searchFts(fts) }.getOrDefault(emptyList())
            else emptyList()
            if (ftsHits.isNotEmpty()) ftsHits
            else runCatching { foodItemDao.searchFoods(q) }.getOrDefault(emptyList())
        }
        val queryTokens = FoodSearchText.tokens(effectiveQuery)
        val cachedDistinct = cached
            .distinctBy { normalizeKey(it) }
            .filter { hasUsableNutrition(it) || FoodRanker.isStrong(it, queryTokens) }
        // Bei vielen starken lokalen Treffern (Score ≥ EXACTISH) Remote überspringen.
        if (cachedDistinct.size >= 12 &&
            cachedDistinct.count { FoodRanker.isExactish(it, queryTokens) } >= 3
        ) {
            return FoodRanker.rank(cachedDistinct, effectiveQuery).take(20)
        }

        return coroutineScope {
            // Gestaffelter Remote-Fallback statt 8–9 paralleler Calls:
            // 1) OFF (Hauptquery) + Swiss parallel
            // 2) eine Variante (Kompositum oder Synonym), nur wenn noch dünn
            // 3) USDA + Nutritionix nur als letzte Reserve
            // 4) KI-Schätzung nur wenn weiterhin nichts Brauchbares da ist
            val offDeferred = async { runCatching { openFoodFactsSearch(effectiveQuery) }.getOrDefault(emptyList()) }
            val swissDeferred = async { runCatching { SwissFoodApi.search(query) }.getOrDefault(emptyList()) }
            val off = offDeferred.await()
            val swiss = swissDeferred.await()

            val compoundHead = compoundVariant?.split(" ")?.firstOrNull()?.takeIf { it.length >= 4 }
            fun hasHead(items: List<FoodItem>): List<FoodItem> {
                if (compoundHead == null) return items
                val h = SearchUtils.normalize(compoundHead)
                return items.filter { item ->
                    val n = SearchUtils.normalize(item.name).replace(" ", "")
                    n.contains(h) || FoodRanker.isStrong(item, queryTokens)
                }
            }

            var combined = cachedDistinct + swiss + off

            fun usableCount(list: List<FoodItem>): Int =
                list.count { hasUsableNutrition(it) && FoodRanker.isStrong(it, queryTokens) }

            // Eine zusätzliche OFF-Variante, nur wenn lokal+OFF+Swiss noch dünn sind
            if (usableCount(combined) < 6) {
                val variantQuery = compoundVariant ?: synonymQueries.firstOrNull()
                if (variantQuery != null && !variantQuery.equals(effectiveQuery, ignoreCase = true)) {
                    val variantHits = runCatching { openFoodFactsSearch(variantQuery) }.getOrDefault(emptyList())
                    combined = combined + hasHead(variantHits)
                }
            }

            // USDA + Nutritionix nur als Reserve (keine Parallel-Flut mehr)
            if (usableCount(combined) < 5) {
                val usdaDeferred = async { runCatching { usdaApi.search(effectiveQuery) }.getOrDefault(emptyList()) }
                val nixDeferred = async {
                    runCatching { nutritionixApi.searchBranded(effectiveQuery) }.getOrDefault(emptyList())
                }
                combined = combined + usdaDeferred.await() + nixDeferred.await()
            }

            // Echte Treffer (FoodRanker STRONG) haben Vorrang vor der KI-Schätzung.
            if (combined.none { hasUsableNutrition(it) && FoodRanker.isStrong(it, queryTokens) }) {
                GroqFoodEstimatorApi.estimate(effectiveQuery)?.let { combined = combined + it }
            }

            // Lokale Referenz (IngredientNutritionDatabase): Ausreißer wie
            // "Mais 433 kcal/100g" statt ~86 verwerfen, solange genug Alternativen bleiben.
            val localRef = ch.nutrisnap.app.domain.IngredientNutritionDatabase.lookup(effectiveQuery)
                ?: ch.nutrisnap.app.domain.IngredientNutritionDatabase.lookup(query)

            val result = combined
                .distinctBy { normalizeKey(it) }
                .let { list ->
                    val usable = list.filter { hasUsableNutrition(it) }
                    val plausible = if (localRef != null && localRef.calories > 0f) {
                        val filtered = usable.filter { item ->
                            val kcal = item.calories ?: return@filter true
                            val ratio = kcal / localRef.calories
                            // etwas toleranter (0.3–2.5), damit Markenbrote nicht rausfallen
                            ratio in 0.3f..2.5f
                        }
                        if (filtered.isNotEmpty()) filtered else usable
                    } else usable
                    // Harte Spezifität über FoodRanker: STRONG zuerst, WEAK nur auffüllen.
                    val specific = plausible.filter { FoodRanker.isStrong(it, queryTokens) }
                    val weak = plausible.filter {
                        !FoodRanker.isStrong(it, queryTokens) && FoodRanker.isWeakOrBetter(it, queryTokens)
                    }
                    when {
                        specific.size >= 5 -> specific
                        specific.isNotEmpty() -> (specific + weak).distinct().take(15)
                        weak.isNotEmpty() -> weak.take(10)
                        plausible.isNotEmpty() -> plausible.take(10)
                        else -> list
                    }
                }
                .let { FoodRanker.rank(it, effectiveQuery) }
                .take(20) // UI soll nicht endlos scrollen, aber ~10+ sind drin

            foodItemDao.insertAll(result.filter { it.source != FoodSource.MANUAL }.take(40))
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

    /** Markenprodukte mit Barcode (Retail) vor generischen Einträgen. */
    private fun retailSignal(item: FoodItem): Int {
        var s = 0
        if (!item.brand.isNullOrBlank()) s += 2
        if (!item.barcode.isNullOrBlank()) s += 2
        // KI-Schätzung ans Ende
        val brandLower = item.brand?.lowercase().orEmpty()
        if (brandLower.contains("ki-geschätzt") || brandLower.contains("ki-gericht")) s -= 5
        return s
    }

    /**
     * Wie viele Mikronährstoffe sind befüllt? USDA/Swiss schlagen OFF oft um Längen,
     * weil OFF bei Markenprodukten Vitamine meist weglässt → Priority-Home zeigte 0 µg.
     */
    private fun microRichness(item: FoodItem): Int {
        var n = 0
        // Kern-Vitamine/Minerale stärker gewichten
        if (item.vitaminD != null) n += 3
        if (item.vitaminB12 != null) n += 2
        if (item.vitaminA != null) n += 1
        if (item.vitaminC != null) n += 1
        if (item.vitaminE != null) n += 1
        if (item.vitaminK != null) n += 1
        if (item.vitaminB1 != null) n += 1
        if (item.vitaminB2 != null) n += 1
        if (item.vitaminB6 != null) n += 1
        if (item.vitaminB11 != null) n += 1
        if (item.calcium != null) n += 1
        if (item.iron != null) n += 1
        if (item.magnesium != null) n += 1
        if (item.zinc != null) n += 1
        if (item.potassium != null) n += 1
        if (item.selenium != null) n += 1
        if (item.fiber != null) n += 1
        return n
    }

    /** USDA/Swiss/Nutritionix vor OFF – OFF hat selten Vitamine. */
    private fun sourcePriority(source: FoodSource): Int = when (source) {
        FoodSource.USDA -> 5
        FoodSource.SWISS_FSVO -> 4
        FoodSource.NUTRITIONIX -> 3
        FoodSource.MANUAL -> 3
        FoodSource.OPEN_FOOD_FACTS -> 1
        else -> 0
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



    companion object {
        /**
         * Räumt Suchergebnisse auf: Einträge ganz ohne Nährwerte raus, und Einträge, deren
         * kcal den Makros widersprechen, nur dann raus, wenn genug brauchbare Alternativen
         * bleiben (sonst würden z. B. Süssstoffe/Polyole mit 0 kcal aber hohen "Kohlenhydraten"
         * komplett verschwinden). Nie leer, solange die Eingabe nicht leer ist.
         */
        fun cleanSearchResults(items: List<FoodItem>): List<FoodItem> = with(NutritionSanity) {
            val usable = items.filter { it.hasSearchableNutrition() }
            val base = if (usable.isNotEmpty()) usable else items
            val consistent = base.filterNot { it.looksInconsistent() }
            if (consistent.size >= 3) consistent else base
        }
    }

    suspend fun searchNaturalLanguage(query: String): List<FoodItem> {
        return runCatching { nutritionixApi.parseNaturalLanguage(query) }.getOrDefault(emptyList())
    }

    suspend fun searchByBarcode(barcode: String): FoodItem? {
        val cached = foodItemDao.searchByBarcode(barcode)
        // Cache ohne Bild: OFF nachladen und imageUrl nachziehen (ältere DB-Zeilen)
        if (cached != null && !cached.imageUrl.isNullOrBlank()) return cached
        val remote = runCatching { SwissFoodApi.search("barcode:$barcode").firstOrNull() }.getOrNull()
            ?: runCatching { openFoodFactsSearch("barcode:$barcode").firstOrNull() }.getOrNull()
        if (remote == null) return cached
        return runCatching {
            if (cached != null) {
                val merged = cached.copy(imageUrl = remote.imageUrl ?: cached.imageUrl)
                foodItemDao.update(merged)
                merged
            } else {
                val id = foodItemDao.insert(remote.copy(id = 0)).toInt()
                foodItemDao.getById(id) ?: remote.copy(id = id)
            }
        }.getOrDefault(remote)
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
        // "rüebli" normalisiert zu "rueebli" (ü→ue); getippt oft "ruebli"
        "rüebli" to "karotte",
        "ruebli" to "karotte",
        "rueebli" to "karotte",
        "herdöpfel" to "kartoffel",
        "herdoepfel" to "kartoffel",
        "gschwellti" to "pellkartoffel",
        "nüssler" to "feldsalat",
        "nuessler" to "feldsalat",
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
        // Umlaut-tolerant matchen: "ruebli" und "rüebli" treffen denselben Root.
        val qRaw = query.trim().lowercase()
        val qNorm = SearchUtils.normalize(query.trim())
        val entry = swissGermanRoots.entries.firstOrNull { (root, _) ->
            qRaw.startsWith(root) || qNorm.startsWith(SearchUtils.normalize(root))
        } ?: return null
        val root = entry.key
        val standard = entry.value
        val usedRoot = when {
            qRaw.startsWith(root) -> root
            else -> SearchUtils.normalize(root)
        }
        val usedQuery = if (qRaw.startsWith(root)) qRaw else qNorm
        var suffix = usedQuery.removePrefix(usedRoot)
        if (suffix.isBlank()) return standard
        endingCorrections[suffix]?.let { suffix = it }
        // Auch normalisierte Endungen abfangen (z.B. "brus" bleibt)
        endingCorrections[SearchUtils.normalize(suffix)]?.let { suffix = it }
        return (standard + suffix).trim()
    }

    // Bekannte Lebensmittel-Substantive, an denen ein zusammengeschriebenes
    // Kompositum aufgetrennt werden kann (z.B. "süsskartoffelpommes" ->
    // "süsskartoffel pommes"). Längere Suffixe zuerst. Umlaut- und
    // ASCII-Varianten parallel, damit normalize() und Roh-Query greifen.
    private val compoundSplitWords = listOf(
        // längere Suffixe zuerst, damit "brötchen" vor "brot" greift
        "brötchen", "broetchen", "körbchen", "koerbchen", "semmel",
        "pommes", "kartoffel", "kartoffeln", "curry", "salat",
        "brötchen", "broetchen", "brot", "suppe",
        "sauce", "sosse", "gemuese", "gemüse", "reis", "nudeln",
        "wurst", "käse", "kaese", "brust",
        "fleisch", "hackfleisch", "schnitzel", "plätzli", "plaetzli", "steak", "filet",
        "braten", "voressen", "toast", "bagel", "muffin", "kuchen", "torte",
        "joghurt", "quark", "milch", "kaffee", "tee", "saft", "smoothie",
        // Nüsse, Samen, Flocken – häufige Komposita ohne Leerzeichen
        "nüsse", "nuesse", "samen", "kerne", "flocken", "riegel", "waffel",
        "chips", "sticks", "würstchen", "wuerstchen"
    )

    /**
     * Trennt ein zusammengeschriebenes Kompositum an einem bekannten Suffix-Wort
     * auf ("süsskartoffelpommes" -> "süsskartoffel pommes"), damit die Volltextsuche
     * externer Quellen (OFF etc.) auch bei zusammengeschriebenen Begriffen greift.
     * Normalisiert Umlaute, damit "hüttenkäse" → "huetten kaese" wird.
     * Gibt null zurück, wenn die Query bereits Leerzeichen enthält oder kein
     * bekanntes Suffix gefunden wird.
     */
    private fun compoundSplitVariant(query: String): String? {
        val raw = query.trim().lowercase()
        if (raw.contains(" ") || raw.length < 6) return null
        val q = SearchUtils.normalize(raw)
        for (suffix in compoundSplitWords) {
            val s = SearchUtils.normalize(suffix)
            if (q.endsWith(s) && q.length > s.length + 2) {
                val head = q.removeSuffix(s)
                if (head.length >= 2) return "$head $s"
            }
        }
        return null
    }
}
