package ch.nutrisnap.app.ui.screens.diary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.FoodItemRepository
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
    val calorieGoal:   Float            = 2000f
)

class DiaryViewModel(app: Application) : AndroidViewModel(app) {
    private val db       = NutriDatabase.getInstance(app)
    private val repo     = DiaryRepository(db)
    private val foodRepo = FoodItemRepository(db)

    private val _date = MutableStateFlow(LocalDate.now())
    private val _goal = MutableStateFlow(2000f)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<DiaryUiState> = _date.flatMapLatest { date ->
        repo.getEntriesForDate(date).map { entries ->
            DiaryUiState(
                selectedDate  = date,
                entries       = entries,
                totalCalories = entries.sumOf { it.calories.toDouble() }.toFloat(),
                totalProtein  = entries.sumOf { it.protein.toDouble() }.toFloat(),
                totalCarbs    = entries.sumOf { it.carbs.toDouble() }.toFloat(),
                totalFat      = entries.sumOf { it.fat.toDouble() }.toFloat(),
                calorieGoal   = _goal.value
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    private val _searchResults = MutableStateFlow<List<FoodItem>>(emptyList())
    private val _isSearching   = MutableStateFlow(false)
    val searchResults: StateFlow<List<FoodItem>> = _searchResults
    val isSearching:   StateFlow<Boolean>        = _isSearching

    fun setDate(date: LocalDate) { _date.value = date }
    fun prevDay() { _date.value = _date.value.minusDays(1) }
    fun nextDay() { _date.value = _date.value.plusDays(1) }
    fun setCalorieGoal(goal: Float) { _goal.value = goal }

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

    fun addEntry(food: FoodItem, grams: Float, meal: MealType) {
        viewModelScope.launch { repo.addEntry(food, grams, meal, _date.value) }
    }

    fun deleteEntry(entry: DiaryEntry) {
        viewModelScope.launch { repo.deleteEntry(entry) }
    }
}
