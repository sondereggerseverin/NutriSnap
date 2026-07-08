package ch.nutrisnap.app.ui.screens.shopping

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.ShoppingListItem
import ch.nutrisnap.app.data.repository.ShoppingListRepository
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Eine über alle Rezepte/manuellen Einträge zusammengefasste Zutat. */
data class AggregatedShoppingItem(
    val name: String,
    val totalGrams: Float,
    val checked: Boolean,
    val itemIds: List<Int>
)

class ShoppingListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ShoppingListRepository(NutriDatabase.getInstance(app))

    val items: StateFlow<List<ShoppingListItem>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Gleiche Zutat aus mehreren Rezepten (z.B. "Magerquark" in zwei Rezepten)
     * wird hier zu einer Zeile mit addierter Menge zusammengefasst — unabhängig
     * davon, ob die Menge aus dem Rezepttext ("500 g Magerquark") oder aus den
     * strukturierten amount/unit-Feldern eines manuellen Eintrags stammt.
     */
    val aggregated: StateFlow<List<AggregatedShoppingItem>> = items.map { list ->
        list.groupBy { normalize(it) }
            .map { (key, group) ->
                val totalGrams = group.sumOf { effectiveGrams(it).toDouble() }.toFloat()
                AggregatedShoppingItem(
                    name       = key,
                    totalGrams = totalGrams,
                    checked    = group.all { it.checked },
                    itemIds    = group.map { it.id }
                )
            }
            .sortedBy { it.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun effectiveGrams(item: ShoppingListItem): Float {
        val line = if (item.amount != null) "${item.amount} ${item.unit ?: ""} ${item.name}" else item.name
        return RecipeNutritionAnalyzer.parseIngredientLine(line)?.amountG ?: 0f
    }

    /** Bereinigter, vergleichbarer Name (ohne Menge/Einheit/Marken-Klammern/"(null)"). */
    private fun normalize(item: ShoppingListItem): String {
        val line = if (item.amount != null) "${item.amount} ${item.unit ?: ""} ${item.name}" else item.name
        val parsedName = RecipeNutritionAnalyzer.parseIngredientLine(line)?.name ?: item.name
        return parsedName
            .replace(Regex("""\(null\)"""), "")
            .replace(Regex("""\([^)]*\)"""), "") // Marken-/Herkunftsangaben in Klammern
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { item.name }
    }

    /** Toggelt alle Einträge, die zu einer zusammengefassten Zutat gehören. */
    fun toggleAggregated(agg: AggregatedShoppingItem) {
        val targetChecked = !agg.checked
        viewModelScope.launch {
            items.value.filter { it.id in agg.itemIds }.forEach { item ->
                if (item.checked != targetChecked) repo.toggle(item)
            }
        }
    }

    fun addItem(name: String, amount: Float? = null, unit: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.add(name, amount, unit) }
    }

    fun addRecipeIngredients(recipeTitle: String, ingredients: List<Triple<String, Float?, String?>>) {
        viewModelScope.launch {
            repo.addAll(ingredients.map { (name, amount, unit) ->
                ShoppingListItem(name = name, amount = amount, unit = unit, recipeTitle = recipeTitle)
            })
        }
    }

    fun toggle(item: ShoppingListItem) = viewModelScope.launch { repo.toggle(item) }
    fun delete(item: ShoppingListItem) = viewModelScope.launch { repo.delete(item) }
    fun clearChecked() = viewModelScope.launch { repo.clearChecked() }
    fun clearAll() = viewModelScope.launch { repo.clearAll() }
}
