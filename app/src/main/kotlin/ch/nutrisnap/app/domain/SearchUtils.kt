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

        // Kompositum-Toleranz: "süsskartoffelpommes" (ein Wort) soll gegen
        // "Süßkartoffel Pommes" (zwei Wörter) matchen, unabhängig davon auf
        // welcher Seite das Leerzeichen fehlt/steht.
        val qCompact = q.replace(" ", "")
        val cCompact = c.replace(" ", "")
        if (qCompact.length >= 3 && cCompact.contains(qCompact)) return true

        // Fuzzy Match für kurze Queries (ab 3 Zeichen), auf normaler und
        // kompakter Form (deckt Tippfehler UND Leerzeichen-Varianten ab).
        if (q.length >= 3) {
            if (fuzzyWindowMatch(q, c)) return true
            if (fuzzyWindowMatch(qCompact, cCompact)) return true
        }

        // Synonym-Check (beide Richtungen: Query->Synonyme und Synonym->Query,
        // damit z.B. sowohl "pommes" als auch "fritten" den jeweils anderen
        // Eintrag findet ohne die Map doppelt pflegen zu müssen)
        GERMAN_SYNONYMS[q]?.let { synonyms -> if (synonyms.any { c.contains(it) }) return true }
        GERMAN_SYNONYMS.entries.forEach { (key, values) ->
            if (values.contains(q) && c.contains(key)) return true
        }

        return false
    }

    private fun fuzzyWindowMatch(q: String, c: String): Boolean {
        val windowSize = q.length + 2
        for (i in 0..(c.length - q.length).coerceAtLeast(0)) {
            val window = c.substring(i, (i + windowSize).coerceAtMost(c.length))
            if (levenshteinDistance(q, window) <= if (q.length > 6) 2 else 1) return true
        }
        return false
    }

    /**
     * Sortiert Suchergebnisse nach Relevanz zur Suchanfrage.
     */
    fun rankResults(query: String, results: List<String>): List<Pair<String, Int>> {
        val q = normalize(query)
        val qCompact = q.replace(" ", "")
        return results.map { candidate ->
            val c = normalize(candidate)
            val cCompact = c.replace(" ", "")
            val score = when {
                c == q -> 100                    // exakter Treffer
                c.startsWith(q) -> 90            // beginnt mit Query
                c.contains(" $q") -> 80          // Wortanfang nach Leerzeichen
                c.contains(q) -> 70              // enthält Query irgendwo
                cCompact.contains(qCompact) -> 60 // Kompositum-Treffer (Leerzeichen ignoriert)
                levenshteinDistance(q, c.take(q.length + 2)) <= 1 -> 50  // fast gleich
                fuzzyMatch(q, c) -> 40            // Synonym/Fuzzy-Treffer
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
        "haehnchen" to listOf("chicken", "huhn", "haehnchenbrustfilet", "poulet"),
        "chicken" to listOf("haehnchen", "huhn", "geflügel", "poulet"),
        "kartoffel" to listOf("potato", "erdapfel"),
        "tomate" to listOf("tomato", "paradeiser"),
        "apfel" to listOf("apple"),
        "banane" to listOf("banana"),
        "joghurt" to listOf("yogurt", "yoghurt"),
        "vollkornbrot" to listOf("wholegrain bread", "vollkorn"),
        "magerquark" to listOf("quark", "topfen", "cottage cheese"),
        "haferflocken" to listOf("oats", "oatmeal", "porridge"),
        "rinderhack" to listOf("ground beef", "hackfleisch", "faschiertes"),
        "pommes" to listOf("pommes frites", "fritten", "french fries", "fries"),
        "curry" to listOf("thai curry", "currysauce", "currypaste", "currygericht"),
        "suesskartoffel" to listOf("sweet potato", "suesskartoffeln"),
        "poulet" to listOf("chicken", "haehnchen", "huhn"),
        "rueebli" to listOf("karotte", "carrot", "moehre"),
        "ruebli" to listOf("karotte", "carrot", "moehre"),
        "ei" to listOf("egg", "eier"),
        "eier" to listOf("eggs", "ei"),
        "milch" to listOf("milk"),
        "reis" to listOf("rice"),
        "nudeln" to listOf("pasta", "noodles"),
        "fisch" to listOf("fish"),
        "fleisch" to listOf("meat"),
        "broetchen" to listOf("bread roll", "bun", "semmel"),
        "zwiebel" to listOf("onion"),
        "spinat" to listOf("spinach")
    )

    /**
     * Liefert Synonym-Alternativen für eine (bereits normalisierte) Query,
     * inkl. umgekehrter Map-Richtung. Wird von der Suche genutzt, um die
     * lokale DB mit mehreren LIKE-Queries abzufragen.
     */
    fun synonymsOf(normalizedQuery: String): List<String> {
        val q = normalizedQuery.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        GERMAN_SYNONYMS[q]?.let { out.addAll(it) }
        // Präfix: "pouletbrust" → Synonyme von "poulet" + Suffix
        GERMAN_SYNONYMS.entries
            .filter { q.startsWith(it.key) && q.length > it.key.length }
            .forEach { (key, values) ->
                val suffix = q.removePrefix(key)
                values.forEach { out += it + suffix }
            }
        // Umgekehrt: Query ist ein Synonym-Wert → Key + andere Werte
        GERMAN_SYNONYMS.forEach { (key, values) ->
            if (values.any { it == q || q.startsWith(it) }) {
                out += key
                out.addAll(values)
            }
        }
        return out.filter { it.isNotBlank() && it != q }.distinct()
    }
}
