package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.ui.screens.recipes.RecipeSort

/**
 * Filtert und sortiert die Rezeptliste (Plattform, Kategorie, Zutaten-Nadeln).
 * Text-Query kommt separat über [RecipeRepository.search].
 */
object RecipeListFilter {

    fun filterAndSort(
        recipes: List<Recipe>,
        platformFilter: String?,
        categoryFilter: RecipeCategory?,
        needles: List<String>,
        sort: RecipeSort
    ): List<Recipe> {
        var filtered = recipes
        if (platformFilter != null) {
            filtered = filtered.filter { (it.platform ?: "web").lowercase() == platformFilter }
        }
        if (categoryFilter != null) {
            filtered = filtered.filter { it.category() == categoryFilter }
        }
        if (needles.isNotEmpty()) {
            filtered = filtered
                .filter { r ->
                    val hay = "${r.title}\n${r.ingredients}\n${r.description}\n${r.tags}".lowercase()
                    needles.all { n -> n.lowercase() in hay }
                }
                .sortedByDescending { r ->
                    val hay = "${r.title}\n${r.ingredients}".lowercase()
                    needles.count { it.lowercase() in hay }
                }
        } else {
            filtered = when (sort) {
                RecipeSort.NEWEST -> filtered.sortedByDescending { it.savedAt }
                RecipeSort.NAME -> filtered.sortedBy { it.title.lowercase() }
                RecipeSort.CALORIES -> filtered.sortedByDescending { it.totalCalories ?: -1f }
            }
        }
        return filtered
    }
}
