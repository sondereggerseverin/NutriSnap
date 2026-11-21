package ch.nutrisnap.app.ui.screens.recipes

import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.domain.IngredientSections

internal fun fmtNum(v: Float): String =
    if (v <= 0f) "—" else if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)


/**
 * Reichert gespeicherte Komponenten mit frisch aus den Abschnitten abgeleiteten
 * Nährwerten an (Zuordnung über den Abschnittsnamen). Fallback: proportionale
 * Aufteilung am Rezept-Total, wenn Matches fehlen und die Snapshot-Werte offensichtlich kaputt sind.
 */
fun enrichComponentsFromMatches(
    recipe: Recipe,
    components: List<RecipeComponent>,
    matches: List<IngredientMatch>
): List<RecipeComponent> {
    if (components.isEmpty()) return components
    val usable = matches.filter { !it.isDeleted && ((it.matchedCalories ?: 0f) > 0f || it.amountGrams > 0f) }
    if (usable.isNotEmpty()) return IngredientSections.refreshComponents(recipe.ingredients, components, matches)
    // Fallback: proportionale Heilung bei Duplikat-Snapshots
    if (components.size <= 1) return components
    val recipeTotal = recipe.totalCalories ?: 0f
    if (recipeTotal <= 0f) return components
    val duplicated = components.all { c ->
        c.totalCalories > 0f &&
            kotlin.math.abs(c.totalCalories - recipeTotal) / recipeTotal < 0.08f
    }
    val sumTooHigh = components.sumOf { it.totalCalories.toDouble() }.toFloat() > recipeTotal * 1.35f
    if (!duplicated && !sumTooHigh) return components
    val serv = recipe.servings.coerceAtLeast(1).toFloat()
    val sourceProt = (recipe.proteinPerServing ?: 0f) * serv
    val sourceCarbs = (recipe.carbsPerServing ?: 0f) * serv
    val sourceFat = (recipe.fatPerServing ?: 0f) * serv
    val sourceFiber = (recipe.fiberPerServing ?: 0f) * serv
    val wSum = components.sumOf { it.cookedWeightG.toDouble() }.toFloat().coerceAtLeast(1f)
    return components.map { c ->
        val f = if (c.cookedWeightG > 0f) c.cookedWeightG / wSum else 1f / components.size
        c.copy(
            totalCalories = recipeTotal * f,
            proteinG = sourceProt * f,
            carbsG = sourceCarbs * f,
            fatG = sourceFat * f,
            fiberG = sourceFiber * f
        )
    }
}
