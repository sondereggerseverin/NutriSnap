package ch.nutrisnap.app.ui.screens.shopping

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.ShoppingListItem
import ch.nutrisnap.app.data.repository.InventoryRepository
import ch.nutrisnap.app.data.repository.ShoppingListRepository
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Eine über alle Rezepte/manuellen Einträge zusammengefasste Zutat. */
data class AggregatedShoppingItem(
    val name: String,
    val totalGrams: Float,
    val checked: Boolean,
    val itemIds: List<Int>,
    /** Geschätzte Menge aus dem Inventar (g), die diese Position abdeckt. */
    val inventoryGrams: Float = 0f,
    /** Noch zu kaufen nach Inventar-Abzug (g). */
    val stillNeededGrams: Float = totalGrams
)

class ShoppingListViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val repo = ShoppingListRepository(db)
    private val inventoryRepo = InventoryRepository(db)

    val items: StateFlow<List<ShoppingListItem>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Gleiche Zutat aus mehreren Rezepten wird addiert. Zusätzlich: Inventar-Match
     * (Name, case-insensitive) zieht vorhandene Mengen (g/ml ≈ g) vom Bedarf ab.
     */
    val aggregated: StateFlow<List<AggregatedShoppingItem>> =
        combine(repo.observeAll(), inventoryRepo.getAll()) { list, inventory ->
            // Inventar-Name → geschätzte Gramm (nur g/ml/l; Stück ohne Referenz bleibt 0)
            val invByNorm = mutableMapOf<String, Float>()
            inventory.forEach { item ->
                val key = normalizeName(item.name)
                val grams = inventoryToGrams(item.quantity, item.unit)
                if (key.isNotBlank() && grams > 0f) {
                    invByNorm[key] = (invByNorm[key] ?: 0f) + grams
                }
            }
            list.groupBy { normalize(it) }
                .map { (key, group) ->
                    val totalGrams = group.sumOf { effectiveGrams(it).toDouble() }.toFloat()
                    val invKey = normalizeName(key)
                    val invG = invByNorm[invKey] ?: 0f
                    val covered = if (totalGrams > 0f) invG.coerceAtMost(totalGrams) else 0f
                    AggregatedShoppingItem(
                        name = key,
                        totalGrams = totalGrams,
                        checked = group.all { it.checked },
                        itemIds = group.map { it.id },
                        inventoryGrams = covered,
                        stillNeededGrams = (totalGrams - covered).coerceAtLeast(0f)
                    )
                }
                .sortedWith(
                    compareBy<AggregatedShoppingItem> { it.stillNeededGrams <= 0f && it.totalGrams > 0f }
                        .thenBy { it.name }
                )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun inventoryToGrams(quantity: Float, unit: String): Float {
        val u = unit.trim().lowercase()
        return when {
            u in setOf("g", "gramm", "gr") -> quantity
            u in setOf("kg") -> quantity * 1000f
            u in setOf("ml") -> quantity // 1 ml ≈ 1 g Näherung
            u in setOf("l", "liter") -> quantity * 1000f
            else -> 0f // Stk/Packung ohne Referenzgewicht nicht abziehbar
        }
    }

    private fun effectiveGrams(item: ShoppingListItem): Float {
        val line = if (item.amount != null) "${item.amount} ${item.unit ?: ""} ${item.name}" else item.name
        return RecipeNutritionAnalyzer.parseIngredientLine(line)?.amountG ?: 0f
    }

    private fun normalizeName(raw: String): String =
        raw.lowercase()
            .replace(Regex("""\(null\)"""), "")
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** Bereinigter, vergleichbarer Name (ohne Menge/Einheit/Marken-Klammern/"(null)"). */
    private fun normalize(item: ShoppingListItem): String {
        val line = if (item.amount != null) "${item.amount} ${item.unit ?: ""} ${item.name}" else item.name
        val parsedName = RecipeNutritionAnalyzer.parseIngredientLine(line)?.name ?: item.name
        return parsedName
            .replace(Regex("""\(null\)"""), "")
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { item.name }
    }

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
