package ch.nutrisnap.app.domain

/**
 * NEU: Fuzzy-Search Hilfsfunktionen.
 *
 * Verbessert die Suchqualität massiv:
 *  - Tippfehler-Toleranz (Levenshtein-Distanz)
 *  - Normalisierung (Umlaute, Großschreibung)
 *  - Synonym-Mapping für häufige Deutsche Lebensmittel
 */
object SearchUtils {

    /**
     * Prüft ob eine Suchanfrage auf einen Kandidaten passt (mit Tippfehler-Toleranz).
     */
    fun fuzzyMatch(query: String, candidate: String): Boolean {
        val q = normalize(query)
        val c = normalize(candidate)

        // Exakte Teilstring-Suche (schnell)
        if (c.contains(q)) return true

        // Fuzzy Match für kurze Queries (ab 3 Zeichen)
        if (q.length >= 3) {
            val windowSize = q.length + 2
            for (i in 0..(c.length - q.length).coerceAtLeast(0)) {
                val window = c.substring(i, (i + windowSize).coerceAtMost(c.length))
                if (levenshteinDistance(q, window) <= if (q.length > 6) 2 else 1) return true
            }
        }

        // Synonym-Check
        val synonyms = GERMAN_SYNONYMS[q]
        if (synonyms != null) {
            return synonyms.any { c.contains(it) }
        }

        return false
    }

    /**
     * Sortiert Suchergebnisse nach Relevanz zur Suchanfrage.
     */
    fun rankResults(query: String, results: List<String>): List<Pair<String, Int>> {
        val q = normalize(query)
        return results.map { candidate ->
            val c = normalize(candidate)
            val score = when {
                c == q -> 100                    // exakter Treffer
                c.startsWith(q) -> 90            // beginnt mit Query
                c.contains(" $q") -> 80          // Wortanfang nach Leerzeichen
                c.contains(q) -> 70              // enthält Query irgendwo
                levenshteinDistance(q, c.take(q.length + 2)) <= 1 -> 50  // fast gleich
                else -> 0
            }
            candidate to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
    }

    fun normalize(text: String): String = text
        .lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace("ß", "ss")
        .trim()

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    // Deutsche Synonyme für häufige Suchanfragen
    private val GERMAN_SYNONYMS = mapOf(
        "haehnchen" to listOf("chicken", "huhn", "haehnchenbrustfilet"),
        "chicken" to listOf("haehnchen", "huhn", "geflügel"),
        "kartoffel" to listOf("potato", "erdapfel"),
        "tomate" to listOf("tomato", "paradeiser"),
        "apfel" to listOf("apple"),
        "banane" to listOf("banana"),
        "joghurt" to listOf("yogurt", "yoghurt"),
        "vollkornbrot" to listOf("wholegrain bread", "vollkorn"),
        "magerquark" to listOf("quark", "topfen", "cottage cheese"),
        "haferflocken" to listOf("oats", "oatmeal", "porridge"),
        "rinderhack" to listOf("ground beef", "hackfleisch", "faschiertes")
    )
}
