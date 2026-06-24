package ch.nutrisnap.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MatchSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class IngredientMatchViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = NutriDatabase.getInstance(app).ingredientMatchDao()

    fun getMatchesFor(recipeId: Long): StateFlow<List<IngredientMatch>> =
        dao.getMatchesForRecipe(recipeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun initMatchesFromRecipe(recipeId: Long, ingredientsRaw: String) {
        viewModelScope.launch {
            val existing = dao.getMatchesForRecipeOnce(recipeId)
            if (existing.isNotEmpty()) return@launch

            val lines = ingredientsRaw
                .split("\n", ",", ";")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val matches = lines.map { line ->
                IngredientMatch(
                    recipeId = recipeId,
                    ingredientRaw = line,
                    ingredientName = extractIngredientName(line),
                    amountGrams = extractAmountGrams(line)
                )
            }
            dao.insertMatches(matches)
        }
    }

    fun saveMatch(
        match: IngredientMatch,
        foodItemId: Long,
        foodName: String,
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        source: MatchSource
    ) {
        viewModelScope.launch {
            dao.updateMatch(
                match.copy(
                    matchedFoodItemId = foodItemId,
                    matchedFoodName = foodName,
                    matchedCalories = calories,
                    matchedProtein = protein,
                    matchedCarbs = carbs,
                    matchedFat = fat,
                    matchSource = source
                )
            )
        }
    }

    fun saveManualMatch(
        match: IngredientMatch,
        name: String,
        calories: Float,
        protein: Float,
        carbs: Float,
        fat: Float
    ) {
        viewModelScope.launch {
            dao.updateMatch(
                match.copy(
                    matchedFoodItemId = -1L,
                    matchedFoodName = name,
                    matchedCalories = calories,
                    matchedProtein = protein,
                    matchedCarbs = carbs,
                    matchedFat = fat,
                    matchSource = MatchSource.MANUAL
                )
            )
        }
    }

    fun resetMatch(match: IngredientMatch) {
        viewModelScope.launch {
            dao.updateMatch(
                match.copy(
                    matchedFoodItemId = null,
                    matchedFoodName = null,
                    matchedCalories = null,
                    matchedProtein = null,
                    matchedCarbs = null,
                    matchedFat = null,
                    matchSource = MatchSource.UNMATCHED
                )
            )
        }
    }

    private fun extractIngredientName(raw: String): String =
        raw
            .replace(Regex("""^\d+[\.,]?\d*\s*(g|ml|kg|l|EL|TL|Stück|Stk\.?|Prise|Bund)?\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { raw.trim() }

    private fun extractAmountGrams(raw: String): Float {
        val m = Regex("""(\d+[\.,]?\d*)\s*(g|ml)?""", RegexOption.IGNORE_CASE).find(raw)
        return m?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull() ?: 0f
    }
}
