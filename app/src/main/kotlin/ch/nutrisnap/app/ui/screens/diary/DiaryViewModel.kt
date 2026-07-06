package ch.nutrisnap.app.ui.screens.diary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.FavoriteFoodRepository
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DiaryUiState(
    val selectedDate:  LocalDate        = LocalDate.now(),
    val entries:       List<DiaryEntry> = emptyList(),
    val totalCalories: Float            = 0f,
    val totalProtein:  Float            = 0f,
    val totalCarbs:    Float            = 0f,
    val totalFat:      Float            = 0f,
    val calorieGoal:   Float            = 2000f,
    val proteinGoal:   Float            = 120f,
    val carbsGoal:     Float            = 220f,
    val fatGoal:       Float            = 65f,
    val isLoading:     Boolean          = false
)

class DiaryViewModel(app: Application) : AndroidViewModel(app) {
    private val db          = NutriDatabase.getInstance(app)
    private val repo        = DiaryRepository(db)
    private val foodRepo    = FoodItemRepository(db)
    private val profileRepo = UserProfileRepository(db)
    private val favRepo     = FavoriteFoodRepository(db)

    private val _date = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<DiaryUiState> = combine(
        _date.flatMapLatest { date ->
            repo.getEntriesForDate(date).map { date to it }
        },
        profileRepo.get()
    ) { (date, entries), profile ->
        DiaryUiState(
            selectedDate  = date,
            entries       = entries,
            totalCalories = entries.sumOf { it.calories.toDouble() }.toFloat(),
            totalProtein  = entries.sumOf { it.protein.toDouble() }.toFloat(),
            totalCarbs    = entries.sumOf { it.carbs.toDouble() }.toFloat(),
            totalFat      = entries.sumOf { it.fat.toDouble() }.toFloat(),
            calorieGoal   = profile.dailyCalorieGoal.toFloat(),
            proteinGoal   = profile.proteinGoalG,
            carbsGoal     = profile.carbsGoalG,
            fatGoal       = profile.fatGoalG
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    // Food search state
    private val _searchResults  = MutableStateFlow<List<FoodItem>>(emptyList())
    private val _isSearching    = MutableStateFlow(false)
    private val _barcodeResult  = MutableStateFlow<FoodItem?>(null)

    val searchResults:  StateFlow<List<FoodItem>> = _searchResults
    val isSearching:    StateFlow<Boolean>        = _isSearching
    val barcodeResult:  StateFlow<FoodItem?>      = _barcodeResult

    // Favorites
    val favorites: StateFlow<List<FoodItem>> = favRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isFavorite(food: FoodItem): Flow<Boolean> = favRepo.isFavorite(food)
    fun toggleFavorite(food: FoodItem) = viewModelScope.launch { favRepo.toggle(food) }

    fun setDate(date: LocalDate) { _date.value = date }
    fun prevDay()                { _date.value = _date.value.minusDays(1) }
    fun nextDay()                { _date.value = _date.value.plusDays(1) }

    fun searchFood(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = foodRepo.searchAll(query)
            _isSearching.value = false
        }
    }

    fun searchBarcode(barcode: String, onResult: (FoodItem?) -> Unit) {
        viewModelScope.launch {
            _isSearching.value = true
            val food = foodRepo.searchBarcode(barcode)
            _isSearching.value = false
            onResult(food)
        }
    }

    fun setBarcodeResult(food: FoodItem) { _barcodeResult.value = food }
    fun clearBarcodeResult()             { _barcodeResult.value = null }

    fun addEntry(food: FoodItem, grams: Float, meal: MealType) {
        viewModelScope.launch { repo.addEntry(food, grams, meal, _date.value) }
    }

    /**
     * Manueller Eintrag: Name + kcal + optionale Makros, keine FoodItem-Referenz.
     * foodItemId = -999 markiert manuelle Einträge.
     */
    fun addManualEntry(
        name: String,
        kcal: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        meal: MealType
    ) {
        viewModelScope.launch {
            repo.addManualEntry(name, kcal, protein, carbs, fat, meal, _date.value)
        }
    }

    fun addRecipeAsMeal(recipe: Recipe, servingsFactor: Float, meal: MealType) {
        viewModelScope.launch { repo.addRecipeAsMeal(recipe, servingsFactor, meal, _date.value) }
    }

    fun updateEntryAmount(entry: DiaryEntry, newValue: Float) {
        viewModelScope.launch {
            if (entry.amountGrams <= 0f) {
                val ratio = if (entry.calories > 0f) newValue / entry.calories else 1f
                repo.updateEntry(entry.copy(
                    calories = newValue,
                    protein  = entry.protein * ratio,
                    carbs    = entry.carbs   * ratio,
                    fat      = entry.fat     * ratio
                ))
            } else {
                val factor = newValue / entry.amountGrams
                repo.updateEntry(entry.copy(
                    amountGrams = newValue,
                    calories    = entry.calories * factor,
                    protein     = entry.protein  * factor,
                    carbs       = entry.carbs    * factor,
                    fat         = entry.fat      * factor
                ))
            }
        }
    }

    fun deleteEntry(entry: DiaryEntry) = viewModelScope.launch { repo.deleteEntry(entry) }
    fun restoreEntry(entry: DiaryEntry) = viewModelScope.launch { repo.restoreEntry(entry) }

    /** Reihenfolge innerhalb einer Mahlzeit nach Drag-Reorder persistieren. */
    fun reorderEntries(orderedIds: List<Long>) = viewModelScope.launch { repo.updateSortOrder(orderedIds) }

    fun saveCustomFood(item: FoodItem) = viewModelScope.launch { foodRepo.saveCustomFood(item) }
}
