package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.RecipeComponent

/**
 * Leitet Beilage/Sauce-Komponenten aus IngredientMatches ab.
 * Pure Logik – testbar ohne ViewModel/Room.
 */
object RecipeComponentSuggester {

    fun isSide(text: String): Boolean {
        val n = text.lowercase()
        return listOf(
            "reis", "basmati", "erbse", "erbsen", "peas", "kartoffel", "nudel", "pasta",
            "quinoa", "couscous", "bulgur", "beilage", "reisnudeln", "sweet potato",
            "süsskartoffel", "suesskartoffel"
        ).any { it in n }
    }

    fun isSauce(text: String): Boolean {
        val n = text.lowercase()
        // Nur echte Sauce-/Fleisch-Signale – nicht Mehl, Salz, Quark, Ei (Backrezepte!)
        return listOf(
            "poulet", "huhn", "chicken", "rindfleisch", "hackfleisch", "schweinefleisch",
            "fleischsauce", "tomatenpassata", "rahmsoße", "rahm sosse", "currysauce",
            "sojasauce", "sauce ", " soße", " sosse", "gravy", "masala-sauce"
        ).any { it in n } ||
            Regex("""(?i)\b(sauce|soße|sosse)\b""").containsMatchIn(n)
    }

    fun resolveKey(m: IngredientMatch): String {
        val g = m.componentGroup?.trim().orEmpty()
        if (g.isNotEmpty()) return g
        val key = "${m.ingredientRaw} ${m.ingredientName} ${m.matchedFoodName.orEmpty()}"
        return when {
            isSide(key) && !isSauce(key) -> "side"
            isSauce(key) -> "sauce"
            isSide(key) -> "side"
            // Kein Default auf „sauce“ – sonst landen Mehl/Salz/Quark unter „Sauce / Fleisch“
            else -> "main"
        }
    }

    fun displayName(key: String): String = when (key.lowercase()) {
        "side" -> "Beilage"
        "sauce" -> "Sauce / Fleisch"
        "main" -> "Hauptteil"
        else -> key
    }

    fun suggestFromMatches(recipeId: Long, matches: List<IngredientMatch>): List<RecipeComponent> {
        val usable = matches.filter { (it.matchedCalories ?: 0f) > 0f || it.amountGrams > 0f }
        if (usable.isEmpty()) return emptyList()
        val grouped = usable.groupBy { resolveKey(it) }
        return grouped.entries.mapIndexed { i, (key, list) ->
            RecipeComponent(
                recipeId = recipeId,
                name = displayName(key),
                cookedWeightG = 0f,
                totalCalories = list.sumOf { (it.matchedCalories ?: 0f).toDouble() }.toFloat(),
                proteinG = list.sumOf { (it.matchedProtein ?: 0f).toDouble() }.toFloat(),
                carbsG = list.sumOf { (it.matchedCarbs ?: 0f).toDouble() }.toFloat(),
                fatG = list.sumOf { (it.matchedFat ?: 0f).toDouble() }.toFloat(),
                sortOrder = i
            )
        }
    }
}
