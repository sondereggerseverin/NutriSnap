package ch.nutrisnap.app.ui.screens.recipes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.FrozenMeal
import ch.nutrisnap.app.data.model.FrozenPortionLine
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.FrozenMealRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class FreezerViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val repo = FrozenMealRepository(db.frozenMealDao(), DiaryRepository(db))

    val meals: StateFlow<List<FrozenMeal>> = repo.getActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun freeze(
        name: String,
        lines: List<FrozenPortionLine>,
        quantity: Int,
        recipeId: Long? = null,
        notes: String = ""
    ) {
        viewModelScope.launch {
            repo.freeze(name, lines, quantity, recipeId, notes)
        }
    }

    fun freezeFromComponents(
        recipe: Recipe,
        components: List<RecipeComponent>,
        gramsByComponentId: Map<Long, Float>,
        quantity: Int,
        notes: String = ""
    ) {
        viewModelScope.launch {
            repo.freezeFromComponents(recipe, components, gramsByComponentId, quantity, notes)
        }
    }

    fun freezeFromRecipe(recipe: Recipe, grams: Float, quantity: Int, notes: String = "") {
        viewModelScope.launch {
            repo.freezeFromRecipe(recipe, grams, quantity, notes)
        }
    }

    fun thawAndTrack(meal: FrozenMeal, mealType: MealType, date: LocalDate = LocalDate.now(), track: Boolean = true) {
        viewModelScope.launch {
            repo.thawAndTrack(meal, mealType, date, track)
        }
    }

    fun adjustQuantity(meal: FrozenMeal, quantity: Int) {
        viewModelScope.launch { repo.adjustQuantity(meal, quantity) }
    }

    fun delete(meal: FrozenMeal) {
        viewModelScope.launch { repo.delete(meal) }
    }
}
