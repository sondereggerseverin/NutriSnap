package ch.nutrisnap.app.domain

/**
 * Prüft, ob eine Social-Caption tatsächlich Zutaten enthält.
 *
 * Hintergrund: Mehrere schnelle Caption-Quellen (og:description, oEmbed, Mirrors)
 * liefern bei langen Instagram-Captions nur den gekürzten Anfang (~200 Zeichen,
 * Intro-Text ohne "ZUTATEN"). Solche Texte bestehen den groben Qualitätscheck
 * (Rezept-Hinweiswort + Mindestlänge), gewinnen das Caption-Race und führten zu
 * Importen ohne Zutaten, bei denen die KI generische Zubereitungsschritte erfand.
 */
object CaptionCompleteness {

    private val QUANTITY_REGEX = Regex(
        """\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tsp|tbsp|cup|oz|lb|stück|stk|päckchen|paeckchen|c\.\s*à|scoops?)\b""",
        RegexOption.IGNORE_CASE
    )

    private val INGREDIENT_HEADER_REGEX = Regex(
        """\b(zutaten|ingredients?|ingrédients?|ingredienti)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Mindestanzahl nicht-leerer Zeilen nach einer Zutaten-Überschrift (für Captions ohne Einheiten). */
    private const val MIN_LINES_AFTER_HEADER = 3

    /** Mindestanzahl Mengenangaben mit Einheit, ab der eine Caption als vollständig gilt. */
    private const val MIN_QUANTITIES = 2

    fun hasIngredientContent(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (QUANTITY_REGEX.findAll(t).count() >= MIN_QUANTITIES) return true
        val header = INGREDIENT_HEADER_REGEX.find(t) ?: return false
        val linesAfterHeader = t.substring(header.range.last + 1)
            .lines()
            .count { it.isNotBlank() }
        return linesAfterHeader >= MIN_LINES_AFTER_HEADER
    }
}
