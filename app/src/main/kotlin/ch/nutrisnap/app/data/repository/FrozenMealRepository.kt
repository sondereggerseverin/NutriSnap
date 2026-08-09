package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.FrozenMealDao
import ch.nutrisnap.app.data.model.FrozenMeal
import ch.nutrisnap.app.data.model.FrozenPortionLine
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class FrozenMealRepository(
    private val dao: FrozenMealDao,
    private val diaryRepo: DiaryRepository
) {
    fun getActive(): Flow<List<FrozenMeal>> = dao.getAllActive()
    fun getAll(): Flow<List<FrozenMeal>> = dao.getAll()

    suspend fun getById(id: Long): FrozenMeal? = dao.getById(id)

    /**
     * Neue Packungen einfrieren.
     * [lines] = Inhalt **einer** Portion; [quantity] = Anzahl identischer Packungen.
     */
    suspend fun freeze(
        name: String,
        lines: List<FrozenPortionLine>,
        quantity: Int,
        recipeId: Long? = null,
        notes: String = ""
    ): Long {
        require(quantity > 0) { "quantity must be > 0" }
        require(lines.isNotEmpty()) { "portion lines required" }
        return dao.insert(
            FrozenMeal(
                recipeId = recipeId,
                name = name.trim().ifBlank { "Menü" },
                quantity = quantity,
                frozenAt = System.currentTimeMillis(),
                notes = notes,
                portionJson = FrozenMeal.encodePortionLines(lines)
            )
        )
    }

    /** Aus Multi-Komponenten-Rezept: Gramm pro Komponente für eine Portion. */
    suspend fun freezeFromComponents(
        recipe: Recipe,
        components: List<RecipeComponent>,
        gramsByComponentId: Map<Long, Float>,
        quantity: Int,
        notes: String = ""
    ): Long {
        val lines = components.mapNotNull { c ->
            val g = gramsByComponentId[c.id]?.takeIf { it >= 1f } ?: return@mapNotNull null
            val s = c.scaledTo(g)
            FrozenPortionLine(
                name = c.name,
                grams = g,
                calories = s.calories,
                protein = s.protein,
                carbs = s.carbs,
                fat = s.fat,
                fiber = s.fiber
            )
        }
        return freeze(
            name = recipe.displayTitle(),
            lines = lines,
            quantity = quantity,
            recipeId = recipe.id.takeIf { it > 0 },
            notes = notes
        )
    }

    /** One-Pot: [grams] der gesamten Portion, Nährwerte aus Rezept skaliert. */
    suspend fun freezeFromRecipe(
        recipe: Recipe,
        grams: Float,
        quantity: Int,
        notes: String = ""
    ): Long {
        val yield = recipe.yieldWeightG()?.takeIf { it > 0f }
            ?: ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.estimateTotalGrams(recipe.ingredients)
                .takeIf { it > 0f }
            ?: grams.coerceAtLeast(1f)
        val factor = grams / yield
        val perServing = recipe.servings.coerceAtLeast(1).toFloat()
        // totalCalories ist fürs ganze Rezept; pro Portion = / servings
        val cals = (recipe.totalCalories ?: 0f) / perServing * (grams / (yield / perServing))
        // einfacher: Anteil am Gesamtbatch
        val batchFactor = grams / yield
        val totalCals = (recipe.totalCalories ?: 0f) * batchFactor
        val protein = (recipe.proteinPerServing ?: 0f) * perServing * batchFactor
        val carbs = (recipe.carbsPerServing ?: 0f) * perServing * batchFactor
        val fat = (recipe.fatPerServing ?: 0f) * perServing * batchFactor
        val fiber = (recipe.fiberPerServing ?: 0f) * perServing * batchFactor
        val lines = listOf(
            FrozenPortionLine(
                name = recipe.displayTitle(),
                grams = grams,
                calories = totalCals,
                protein = protein,
                carbs = carbs,
                fat = fat,
                fiber = fiber
            )
        )
        return freeze(
            name = recipe.displayTitle(),
            lines = lines,
            quantity = quantity,
            recipeId = recipe.id.takeIf { it > 0 },
            notes = notes
        )
    }

    /**
     * Eine Packung herausnehmen und optional ins Tagebuch buchen.
     * @return true wenn gebucht / Bestand reduziert
     */
    suspend fun thawAndTrack(
        meal: FrozenMeal,
        mealType: MealType,
        date: LocalDate,
        track: Boolean = true
    ): Boolean {
        if (meal.quantity <= 0) return false
        if (track) {
            for (line in meal.portionLines()) {
                diaryRepo.addManualEntry(
                    name = "${meal.name} – ${line.name}",
                    kcal = line.calories,
                    protein = line.protein,
                    carbs = line.carbs,
                    fat = line.fat,
                    mealType = mealType,
                    date = date,
                    fiber = line.fiber,
                    amountGrams = line.grams,
                    matchedRecipeId = meal.recipeId
                )
            }
        }
        val left = meal.quantity - 1
        if (left <= 0) {
            dao.delete(meal)
        } else {
            dao.updateQuantity(meal.id, left)
        }
        return true
    }

    suspend fun adjustQuantity(meal: FrozenMeal, newQuantity: Int) {
        if (newQuantity <= 0) dao.delete(meal)
        else dao.updateQuantity(meal.id, newQuantity)
    }

    suspend fun delete(meal: FrozenMeal) = dao.delete(meal)
}
