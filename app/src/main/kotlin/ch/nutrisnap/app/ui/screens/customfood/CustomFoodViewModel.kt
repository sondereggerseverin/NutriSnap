package ch.nutrisnap.app.ui.screens.customfood

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.data.repository.CustomFoodRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class CustomFoodViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CustomFoodRepository(NutriDatabase.getInstance(app).customFoodDao())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val foods: StateFlow<List<CustomFoodItem>> = _query
        .debounce(200)
        .flatMapLatest { q ->
            if (q.isBlank()) repo.getAll() else repo.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) { _query.value = q }

    fun save(
        name: String, calories: Float, protein: Float,
        carbs: Float, fat: Float, fiber: Float
    ) = viewModelScope.launch {
        repo.insert(CustomFoodItem(
            name = name.trim(),
            calories = calories,
            protein = protein,
            carbs = carbs,
            fat = fat,
            fiber = fiber
        ))
    }

    fun delete(item: CustomFoodItem) = viewModelScope.launch { repo.delete(item) }
}
