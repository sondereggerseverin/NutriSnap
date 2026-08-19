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
        return listOf(
            "poulet", "huhn", "chicken", "fleisch", "tomate", "rahm", "sahne", "cream",
            "joghurt", "yogurt", "püree", "puree", "gewürz", "garam", "sauce", "butter",
            "masala", "chili", "ingwer", "knoblauch", "zwiebel", "öl", "oil", "speiseöl",
            "fromage", "rôti", "roti", "kebab"
        ).any { it in n }
    }

    fun resolveKey(m: IngredientMatch): String {
        val g = m.componentGroup?.trim().orEmpty()
        if (g.isNotEmpty()) return g
        val key = "${m.ingredientRaw} ${m.ingredientName} ${m.matchedFoodName.orEmpty()}"
        return when {
            isSide(key) && !isSauce(key) -> "side"
            isSauce(key) -> "sauce"
            isSide(key) -> "side"
            else -> "sauce"
        }
    }

    fun displayName(key: String): String = when (key) {
        "side" -> "Beilage"
        "sauce" -> "Sauce / Fleisch"
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
