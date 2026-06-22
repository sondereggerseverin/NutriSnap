package ch.nutrisnap.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.IngredientMatchDao
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MatchSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IngredientMatchViewModel @Inject constructor(
    private val dao: IngredientMatchDao
) : ViewModel() {

    private var currentRecipeId: Long = -1L

    fun getMatchesFor(recipeId: Long): StateFlow<List<IngredientMatch>> {
        currentRecipeId = recipeId
        return dao.getMatchesForRecipe(recipeId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    /** Initialisiert Matches aus rohem Zutaten-String des Rezepts (einmalig) */
    fun initMatchesFromRecipe(recipeId: Long, ingredientsRaw: String) {
        viewModelScope.launch {
            val existing = dao.getMatchesForRecipeOnce(recipeId)
            if (existing.isNotEmpty()) return@launch // bereits initialisiert

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

    /** Speichert Match via Barcode oder Datenbanksuche */
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

    /** Speichert manuell eingegebene Nährwerte */
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

    // ── Hilfsfunktionen ──────────────────────────────────────────────────────

    private fun extractIngredientName(raw: String): String {
        // Entfernt Mengenangaben: "200g Haferflocken" → "Haferflocken"
        return raw
            .replace(Regex("^\\d+[\\.,]?\\d*\\s*(g|ml|kg|l|EL|TL|Stück|Stk\\.?|Prise|Bund)?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .replaceFirstChar { it.uppercase() }
            .ifBlank { raw.trim() }
    }

    private fun extractAmountGrams(raw: String): Float {
        val match = Regex("(\\d+[\\.,]?\\d*)\\s*(g|ml)?", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.get(1)?.replace(",", ".")?.toFloatOrNull() ?: 0f
    }
}
