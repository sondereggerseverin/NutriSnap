package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe

/**
 * Baut aus verifizierten Makro-Werten pro Portion ein aktualisiertes [Recipe].
 * Pure Funktion – keine DB/ViewModel-Abhängigkeit.
 */
object RecipeVerifiedNutrition {

    fun applyToRecipe(
        recipe: Recipe,
        kcalPerServ: Float,
        protPerServ: Float,
        carbsPerServ: Float,
        fatPerServ: Float,
        fiberPerServ: Float? = null,
        sugarPerServ: Float? = null,
        satFatPerServ: Float? = null,
        saltPerServ: Float? = null,
        sodiumPerServ: Float? = null,
        totalIngredientWeightG: Float? = null,
        ingredientsText: String? = null
    ): Recipe {
        val macroLine = "📊 Pro Portion: ${kcalPerServ.toInt()} kcal" +
            " · ${protPerServ.toInt()}g Protein" +
            " · ${carbsPerServ.toInt()}g Kohlenhydrate" +
            " · ${fatPerServ.toInt()}g Fett (verifiziert)"
        val baseDesc = recipe.description.lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("📊") ||
                    t.startsWith("Pro Stück:", ignoreCase = true) ||
                    t.startsWith("Pro Portion:", ignoreCase = true) ||
                    (t.contains("kcal", ignoreCase = true) && t.contains("Protein", ignoreCase = true))
            }
            .joinToString("\n").trim()
        val newDesc = if (baseDesc.isNotBlank()) "$baseDesc\n\n$macroLine" else macroLine
        return recipe.copy(
            totalCalories = kcalPerServ * recipe.servings,
            proteinPerServing = protPerServ,
            carbsPerServing = carbsPerServ,
            fatPerServing = fatPerServ,
            fiberPerServing = fiberPerServ ?: recipe.fiberPerServing,
            sugarPerServing = sugarPerServ ?: recipe.sugarPerServing,
            saturatedFatPerServing = satFatPerServ ?: recipe.saturatedFatPerServing,
            saltPerServing = saltPerServ ?: recipe.saltPerServing,
            sodiumPerServing = sodiumPerServ ?: recipe.sodiumPerServing,
            totalIngredientWeightG = totalIngredientWeightG ?: recipe.totalIngredientWeightG,
            description = newDesc,
            ingredients = ingredientsText?.takeIf { it.isNotBlank() } ?: recipe.ingredients
        )
    }
}
