package ch.nutrisnap.app.ui.screens.recipes

import ch.nutrisnap.app.domain.RecipeAiParser

/**
 * Teilt Social-/Caption-Text in Zutaten- und Anleitungsblock.
 * Nutzt [RecipeAiParser.formatIngredientText] für saubere Zutatenzeilen
 * (Makros, Hashtags, Method, Promo raus).
 */
object RecipeCaptionParser {

    private val instrKeywords = listOf(
        "zubereitung", "anleitung", "so geht", "preparation", "method",
        "instructions", "steps", "how to", "zubereiten:", "preheat oven", "vorheizen",
        "entire recipe macros", "approx macros", "adjust serving", "recipe by",
        "store frozen", "full recipe below"
    )

    fun parseCaption(caption: String): Pair<String, String> {
        val cleaned = RecipeAiParser.cleanCaption(caption)
        val lower = cleaned.lowercase()

        fun isPromoIngredientsHit(idx: Int): Boolean {
            val window = lower.substring(idx, (idx + 80).coerceAtMost(lower.length))
            return window.contains("with a *") || window.contains("from @") ||
                window.contains("discount") || window.contains("will give you") ||
                window.contains("prozis")
        }

        val instrIdx = instrKeywords.firstNotNullOfOrNull { kw ->
            lower.indexOf(kw).takeIf { it > 5 }
        }
        val ingrIdx = listOf(
            "zutaten:", "zutaten", "ingredients:", "du brauchst", "das brauchst",
            "you need", "für das rezept", "✨recipe", "recipe✨"
        ).firstNotNullOfOrNull { kw ->
            val i = lower.indexOf(kw)
            if (i < 0) null
            else if (kw.contains("ingredient") && isPromoIngredientsHit(i)) null
            else i
        }
        val ingrIdxFinal = ingrIdx ?: run {
            val i = lower.indexOf("ingredients")
            if (i >= 0 && !isPromoIngredientsHit(i)) i else null
        }

        val (rawIng, rawInstr) = when {
            ingrIdxFinal != null && instrIdx != null && instrIdx > ingrIdxFinal ->
                cleaned.substring(ingrIdxFinal, instrIdx).trim() to cleaned.substring(instrIdx).trim()
            instrIdx != null ->
                cleaned.substring(0, instrIdx).trim() to cleaned.substring(instrIdx).trim()
            ingrIdxFinal != null -> cleaned.substring(ingrIdxFinal).trim() to ""
            else -> cleaned to ""
        }

        // Harte Bereinigung: nur echte Zutatenzeilen, keine Makros/Hashtags/Method
        val ingredients = RecipeAiParser.formatIngredientText(rawIng)
        val instructions = RecipeAiParser.formatInstructionsText(rawInstr)
        return ingredients to instructions
    }
}
