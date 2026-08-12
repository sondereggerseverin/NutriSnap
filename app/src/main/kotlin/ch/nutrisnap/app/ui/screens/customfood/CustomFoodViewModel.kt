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

    /** null = alle, true = nur verifiziert, false = nur unverifiziert */
    private val _verifiedFilter = MutableStateFlow<Boolean?>(null)
    val verifiedFilter: StateFlow<Boolean?> = _verifiedFilter.asStateFlow()

    /** null = alle Quellen, sonst exakter Source-String */
    private val _sourceFilter = MutableStateFlow<String?>(null)
    val sourceFilter: StateFlow<String?> = _sourceFilter.asStateFlow()

    val foods: StateFlow<List<CustomFoodItem>> = combine(
        _query.debounce(200),
        _verifiedFilter,
        _sourceFilter
    ) { q, verified, source -> Triple(q, verified, source) }
        .flatMapLatest { (q, verified, source) ->
            val base = if (q.isBlank()) repo.getAll() else repo.search(q)
            base.map { list ->
                list.filter { item ->
                    (verified == null || item.verified == verified) &&
                        (source == null || item.source == source)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) { _query.value = q }
    fun setVerifiedFilter(v: Boolean?) { _verifiedFilter.value = v }
    fun setSourceFilter(s: String?) { _sourceFilter.value = s }

    suspend fun getById(id: Int): CustomFoodItem? = repo.getById(id)

    fun save(
        name: String,
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        fiber: Float = 0f,
        sugar: Float = 0f,
        salt: Float = 0f,
        portionSizeG: Float = 100f,
        barcode: String? = null,
        brand: String? = null,
        verified: Boolean = true
    ) = viewModelScope.launch {
        repo.insert(
            CustomFoodItem(
                name = name.trim(),
                calories = calories,
                protein = protein,
                carbs = carbs,
                fat = fat,
                fiber = fiber,
                sugar = sugar,
                salt = salt,
                portionSizeG = portionSizeG.coerceAtLeast(1f),
                barcode = barcode,
                brand = brand,
                source = "manual",
                verified = verified
            )
        )
    }

    fun update(item: CustomFoodItem) = viewModelScope.launch { repo.update(item) }

    fun setVerified(item: CustomFoodItem, verified: Boolean) = viewModelScope.launch {
        repo.update(item.copy(verified = verified))
    }

    fun delete(item: CustomFoodItem) = viewModelScope.launch { repo.delete(item) }
}
