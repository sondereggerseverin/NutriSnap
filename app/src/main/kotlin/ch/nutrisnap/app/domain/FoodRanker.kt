package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource

/**
 * Ein einziger Score statt handgepflegter Wortlisten: Textübereinstimmung dominiert,
 * danach entscheiden Beliebtheit, eigene Nutzung und Datenqualität (ähnlich Yazio/FDDB:
 * was man oft isst und was alle scannen, steht oben).
 *
 *  Textmatch:  exakt 1000 · beginnt mit 800 · alle Tokens Wortanfang 600 (+50 erstes Wort)
 *              · Kompositum zusammengeschrieben 600 · Teilstring 350 · Name+Marke 250
 *  Bonus:      exaktes Wort +30/Token (nur Mehrwort) · generisch (ohne Marke/Barcode) +40/+20
 *              · Popularität bis +150 · eigene Nutzung bis +240 · USDA/Swiss/BLS +20
 *  Malus:      lange Namen bis −40 · ohne Nährwerte −400 · KI-Schätzung −300
 */
object FoodRanker {

    fun score(item: FoodItem, queryTokens: List<String>): Int {
        if (queryTokens.isEmpty()) return 0
        val words = FoodSearchText.tokens(item.name, max = 40)
        val nameJ = words.joinToString(" ")
        val brand = FoodSearchText.fold(item.brand.orEmpty())
        val qj = queryTokens.joinToString(" ")
        val qCompact = queryTokens.joinToString("")

        var s = when {
            nameJ == qj -> 1000
            nameJ.startsWith(qj) -> 800
            queryTokens.all { t -> words.any { it.startsWith(t) } } ->
                600 + if (words.firstOrNull()?.startsWith(queryTokens.first()) == true) 50 else 0
            nameJ.replace(" ", "").contains(qCompact) -> 600
            queryTokens.all { nameJ.contains(it) } -> 350
            queryTokens.all { nameJ.contains(it) || brand.contains(it) } -> 250
            else -> queryTokens.count { nameJ.contains(it) || brand.contains(it) } * 100 / queryTokens.size
        }

        // Exakte Wörter nur bei Mehrwort-Anfragen belohnen (bei "kakao" soll "Kakaopulver"
        // nicht hinter "Kakao schwach entölt" landen).
        if (queryTokens.size >= 2) s += queryTokens.count { it in words } * 30
        s -= minOf(40, nameJ.length / 2)
        if (item.brand.isNullOrBlank() && item.barcode.isNullOrBlank()) {
            s += if (queryTokens.size == 1) 40 else 20
        }
        s += item.popularity.coerceIn(0, 100) * 3 / 2
        s += minOf(item.timesUsed, 30) * 8
        if (item.source == FoodSource.USDA || item.source == FoodSource.SWISS_FSVO || item.source == FoodSource.BLS) s += 20
        s += item.completenessScore.coerceIn(0, 100) / 10

        val hasNutrition = (item.calories ?: 0f) > 0f || (item.protein ?: 0f) > 0f ||
            (item.carbs ?: 0f) > 0f || (item.fat ?: 0f) > 0f
        if (!hasNutrition) s -= 400
        val b = item.brand.orEmpty().lowercase()
        if (b.contains("ki-gesch") || b.contains("ki-gericht")) s -= 300
        return s
    }

    /** Sortiert absteigend nach Score; bei Gleichstand kürzerer Name zuerst. */
    fun rank(items: List<FoodItem>, query: String): List<FoodItem> {
        val q = FoodSearchText.tokens(query)
        if (q.isEmpty()) return items
        return items
            .map { it to score(it, q) }
            .sortedWith(compareByDescending<Pair<FoodItem, Int>> { it.second }.thenBy { it.first.name.length })
            .map { it.first }
    }

    /**
     * Schwellen für Filter (nicht nur Sortierung):
     *  EXACTISH ≥ 800  – Name beginnt mit Query / exakt (früher relevance == 4/3)
     *  STRONG   ≥ 350  – alle Query-Tokens im Namen (früher relevance ≥ 2)
     *  WEAK     ≥ 100  – mindestens ein Token trifft (früher relevance == 1)
     */
    const val SCORE_EXACTISH = 800
    const val SCORE_STRONG = 350
    const val SCORE_WEAK = 100

    fun score(item: FoodItem, query: String): Int =
        score(item, FoodSearchText.tokens(query))

    fun isExactish(item: FoodItem, queryTokens: List<String>): Boolean =
        score(item, queryTokens) >= SCORE_EXACTISH

    fun isStrong(item: FoodItem, queryTokens: List<String>): Boolean =
        score(item, queryTokens) >= SCORE_STRONG

    fun isWeakOrBetter(item: FoodItem, queryTokens: List<String>): Boolean =
        score(item, queryTokens) >= SCORE_WEAK
}
