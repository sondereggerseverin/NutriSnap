package ch.nutrisnap.app.ui.screens.shopping

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.ShoppingListItem
import ch.nutrisnap.app.data.repository.ShoppingListRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShoppingListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ShoppingListRepository(NutriDatabase.getInstance(app))

    val items: StateFlow<List<ShoppingListItem>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
