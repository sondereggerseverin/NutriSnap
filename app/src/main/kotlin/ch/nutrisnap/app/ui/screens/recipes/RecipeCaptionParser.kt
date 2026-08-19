package ch.nutrisnap.app.ui.screens.recipes

/**
 * Teilt Social-/Caption-Text in Zutaten- und Anleitungsblock.
 * Pure Funktion – testbar ohne ViewModel.
 */
object RecipeCaptionParser {
    fun parseCaption(caption: String): Pair<String, String> {
        val lower = caption.lowercase()
        val instrKw = listOf(
            "zubereitung", "anleitung", "so geht", "preparation", "method",
            "instructions", "steps", "how to", "zubereiten:", "preheat oven", "vorheizen"
        )
        // Nur Header-ähnliche Treffer – nicht Promo wie „The ingredients with a * are from …“
        fun isPromoIngredientsHit(idx: Int): Boolean {
            val window = lower.substring(idx, (idx + 80).coerceAtMost(lower.length))
            return window.contains("with a *") || window.contains("from @") ||
                window.contains("discount") || window.contains("will give you") ||
                window.contains("prozis")
        }
        val instrIdx = instrKw.firstNotNullOfOrNull { kw -> lower.indexOf(kw).takeIf { it > 5 } }
        val ingrIdx = listOf(
            "zutaten:", "zutaten", "ingredients:", "du brauchst", "das brauchst",
            "you need", "für das rezept", "✨recipe", "recipe✨"
        ).firstNotNullOfOrNull { kw ->
            val i = lower.indexOf(kw)
            if (i < 0) null
            else if (kw.contains("ingredient") && isPromoIngredientsHit(i)) null
            else i
        }
        // „ingredients“ nur wenn nicht Promo
        val ingrIdxFinal = ingrIdx ?: run {
            val i = lower.indexOf("ingredients")
            if (i >= 0 && !isPromoIngredientsHit(i)) i else null
        }
        return when {
            ingrIdxFinal != null && instrIdx != null && instrIdx > ingrIdxFinal ->
                caption.substring(ingrIdxFinal, instrIdx).trim() to caption.substring(instrIdx).trim()
            instrIdx != null -> caption.substring(0, instrIdx).trim() to caption.substring(instrIdx).trim()
            ingrIdxFinal != null -> caption.substring(ingrIdxFinal).trim() to ""
            else -> caption to ""
        }
    }
}
