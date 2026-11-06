package ch.nutrisnap.app.domain

/**
 * Text-Normalisierung für die Lebensmittelsuche. MUSS zur SQL-Normalisierung in
 * [ch.nutrisnap.app.data.db.FoodSearchIndex] passen (ä/ö/ü → a/o/u, ß → ss).
 */
object FoodSearchText {
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    /** Kleinschreibung + Umlaut-Faltung (ä→a, ö→o, ü→u, ß→ss). */
    fun fold(text: String): String = text.lowercase()
        .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u')
        .replace("ß", "ss")

    /** Gefaltete Wörter (an allen Nicht-Buchstaben/-Ziffern getrennt), max. [max] Stück. */
    fun tokens(text: String, max: Int = 6): List<String> =
        fold(text).split(SEPARATORS).filter { it.isNotBlank() }.take(max)

    /** "aepfel" → "apfel", "muesli" → "musli": Schreibweise ohne Umlaut-Digraph. */
    fun collapseDigraphs(token: String): String =
        token.replace("ae", "a").replace("oe", "o").replace("ue", "u")

    /**
     * FTS4-Anfragen (reines AND aus Präfix-Tokens, ohne OR/Klammern – deren Semantik hängt
     * vom SQLite-Build ab). Zweite Anfrage nur, wenn die Digraph-Variante abweicht.
     * Tokens < 2 Zeichen werden ausgelassen (zu unspezifisch für Präfixsuche).
     */
    fun matchQueries(query: String): List<String> {
        val t = tokens(query).filter { it.length >= 2 }
        if (t.isEmpty()) return emptyList()
        val primary = t.joinToString(" ") { "$it*" }
        val alt = t.map { collapseDigraphs(it) }.filter { it.length >= 2 }
        val altQuery = alt.joinToString(" ") { "$it*" }
        return if (alt.size == t.size && altQuery != primary) listOf(primary, altQuery) else listOf(primary)
    }
}
