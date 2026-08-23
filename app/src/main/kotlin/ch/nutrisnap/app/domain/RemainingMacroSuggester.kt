package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.FoodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Vorschläge für „Was passt noch?“ anhand der offenen Makros.
 *
 * Kandidaten:
 *  - Eigene Rezepte (1 Portion, nur mit bekannten kcal)
 *  - Custom Foods (übliche Portion)
 *  - Häufige FoodItems aus dem Tagebuch
 *
 * Scoring priorisiert Protein-Lücke, bestraft starke kcal-Überschreitung
 * und belohnt Favoriten / häufige Nutzung.
 */
data class MacroRemaining(
    val kcal: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
) {
    val hasMeaningfulGap: Boolean
        get() = kcal >= 80f || protein >= 8f
}

enum class MacroSuggestionKind {
    RECIPE,
    CUSTOM_FOOD,
    FREQUENT_FOOD
}

data class MacroSuggestion(
    val key: String,
    val kind: MacroSuggestionKind,
    val title: String,
    val subtitle: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val score: Float,
    /** Rezept-ID, falls kind == RECIPE */
    val recipeId: Long? = null,
    /** FoodItem-ID für Tagebuch-Eintrag (FOOD) */
    val foodItemId: Int? = null,
    /** CustomFood-ID, falls aus custom_foods */
    val customFoodId: Int? = null,
    /** Gramm-Menge für Food-/Custom-Vorschläge (null = 1 Portion Rezept) */
    val amountGrams: Float? = null,
    /** Portionsfaktor für Rezepte (meist 1f) */
    val servings: Float = 1f
)

class RemainingMacroSuggester(private val db: NutriDatabase) {

    companion object {
        private const val MAX_SUGGESTIONS = 4
        private const val MAX_CANDIDATES_PER_SOURCE = 40
        /** kcal-Überschreitung bis zu diesem Faktor wird noch akzeptiert (weich). */
        private const val KCAL_OVERSHOOT_SOFT = 1.25f
        private const val MIN_KCAL = 40f
        private const val MAX_KCAL = 900f
    }

    /**
     * Liefert bis [MAX_SUGGESTIONS] gerankte Vorschläge für die offenen Makros.
     * Leer, wenn kaum Rest übrig oder keine brauchbaren Kandidaten.
     */
    suspend fun suggest(remaining: MacroRemaining): List<MacroSuggestion> =
        withContext(Dispatchers.IO) {
            if (!remaining.hasMeaningfulGap) return@withContext emptyList()

            val candidates = mutableListOf<MacroSuggestion>()
            candidates += fromRecipes(remaining)
            candidates += fromCustomFoods(remaining)
            candidates += fromFrequentFoods(remaining)

            candidates
                .distinctBy { it.key }
                .sortedByDescending { it.score }
                .take(MAX_SUGGESTIONS)
        }

    private suspend fun fromRecipes(remaining: MacroRemaining): List<MacroSuggestion> {
        val recipes = db.recipeDao().getAllOnce()
            .asSequence()
            .filter { it.totalCalories != null && it.totalCalories!! > 0f }
            .sortedByDescending { it.isFavorite }
            .take(MAX_CANDIDATES_PER_SOURCE)
            .toList()

        return recipes.mapNotNull { recipe ->
            val serv = recipe.servings.coerceAtLeast(1)
            val kcal = (recipe.totalCalories ?: return@mapNotNull null) / serv
            if (kcal < MIN_KCAL || kcal > MAX_KCAL) return@mapNotNull null
            // Zu weit über Rest → nur behalten wenn Protein stark hilft
            if (kcal > remaining.kcal * KCAL_OVERSHOOT_SOFT && remaining.kcal >= 120f) {
                val p = recipe.proteinPerServing ?: 0f
                if (p < remaining.protein * 0.4f) return@mapNotNull null
            }
            val protein = recipe.proteinPerServing ?: 0f
            val carbs = recipe.carbsPerServing ?: 0f
            val fat = recipe.fatPerServing ?: 0f
            val score = scoreCandidate(
                remaining = remaining,
                kcal = kcal,
                protein = protein,
                carbs = carbs,
                fat = fat,
                favoriteBoost = if (recipe.isFavorite) 12f else 0f,
                sourceBoost = 4f // Rezepte etwas bevorzugen
            )
            MacroSuggestion(
                key = "recipe:${recipe.id}",
                kind = MacroSuggestionKind.RECIPE,
                title = recipe.displayTitle(),
                subtitle = "1 Portion · ${kcal.toInt()} kcal · P ${protein.toInt()}g",
                calories = kcal,
                protein = protein,
                carbs = carbs,
                fat = fat,
                score = score,
                recipeId = recipe.id,
                servings = 1f
            )
        }
    }

    private suspend fun fromCustomFoods(remaining: MacroRemaining): List<MacroSuggestion> {
        val customs = db.customFoodDao().getAllOnce()
            .asSequence()
            .filter { it.calories > 0f }
            .take(MAX_CANDIDATES_PER_SOURCE)
            .toList()

        return customs.mapNotNull { food ->
            // CustomFood speichert Werte pro portionSizeG (oft 100g)
            val portionG = food.portionSizeG.coerceAtLeast(1f)
            val factor = portionG / 100f
            // Viele Custom Foods sind pro 100g hinterlegt; portionSizeG steuert die übliche Menge
            val kcal: Float
            val protein: Float
            val carbs: Float
            val fat: Float
            if (food.portionSizeG <= 0f || food.portionSizeG == 100f) {
                // pro 100g → 1 übliche Portion = 100g
                kcal = food.calories
                protein = food.protein
                carbs = food.carbs
                fat = food.fat
            } else {
                // Werte sind pro 100g, Portion = portionSizeG
                kcal = food.calories * factor
                protein = food.protein * factor
                carbs = food.carbs * factor
                fat = food.fat * factor
            }
            if (kcal < MIN_KCAL || kcal > MAX_KCAL) return@mapNotNull null
            if (kcal > remaining.kcal * KCAL_OVERSHOOT_SOFT && remaining.kcal >= 120f) {
                if (protein < remaining.protein * 0.35f) return@mapNotNull null
            }
            val score = scoreCandidate(
                remaining = remaining,
                kcal = kcal,
                protein = protein,
                carbs = carbs,
                fat = fat,
                favoriteBoost = if (food.verified) 6f else 0f,
                sourceBoost = 2f
            )
            val brand = food.brand?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            MacroSuggestion(
                key = "custom:${food.id}",
                kind = MacroSuggestionKind.CUSTOM_FOOD,
                title = food.name,
                subtitle = "${portionG.toInt()} g$brand · ${kcal.toInt()} kcal · P ${protein.toInt()}g",
                calories = kcal,
                protein = protein,
                carbs = carbs,
                fat = fat,
                score = score,
                customFoodId = food.id,
                amountGrams = portionG
            )
        }
    }

    private suspend fun fromFrequentFoods(remaining: MacroRemaining): List<MacroSuggestion> {
        val frequent = runCatching {
            db.foodItemDao().getFrequentFoodsOnce(15)
        }.getOrDefault(emptyList())

        return frequent.mapNotNull { food ->
            val kcal100 = food.calories ?: return@mapNotNull null
            if (kcal100 <= 0f) return@mapNotNull null
            val servingG = food.servingSize.takeIf { it > 0f } ?: 100f
            val factor = servingG / 100f
            val kcal = kcal100 * factor
            if (kcal < MIN_KCAL || kcal > MAX_KCAL) return@mapNotNull null
            val protein = (food.protein ?: 0f) * factor
            val carbs = (food.carbs ?: 0f) * factor
            val fat = (food.fat ?: 0f) * factor
            if (kcal > remaining.kcal * KCAL_OVERSHOOT_SOFT && remaining.kcal >= 120f) {
                if (protein < remaining.protein * 0.35f) return@mapNotNull null
            }
            val score = scoreCandidate(
                remaining = remaining,
                kcal = kcal,
                protein = protein,
                carbs = carbs,
                fat = fat,
                favoriteBoost = 0f,
                sourceBoost = (food.timesUsed.coerceAtMost(20)).toFloat() * 0.4f
            )
            MacroSuggestion(
                key = "food:${food.id}",
                kind = MacroSuggestionKind.FREQUENT_FOOD,
                title = food.name,
                subtitle = "${servingG.toInt()} g · ${kcal.toInt()} kcal · P ${protein.toInt()}g",
                calories = kcal,
                protein = protein,
                carbs = carbs,
                fat = fat,
                score = score,
                foodItemId = food.id,
                amountGrams = servingG
            )
        }
    }

    /**
     * Höher = besser.
     * - Protein-Füllgrad stark gewichtet
     * - kcal möglichst nah am Rest (nicht weit drunter und nicht weit drüber)
     * - leichte Bonusse für Favoriten / Quelle
     */
    private fun scoreCandidate(
        remaining: MacroRemaining,
        kcal: Float,
        protein: Float,
        carbs: Float,
        fat: Float,
        favoriteBoost: Float,
        sourceBoost: Float
    ): Float {
        val proteinGap = max(remaining.protein, 1f)
        val proteinFill = min(protein / proteinGap, 1.2f) // bis 120 % ok
        val proteinScore = proteinFill * 40f

        val kcalGap = max(remaining.kcal, 1f)
        val kcalRatio = kcal / kcalGap
        val kcalScore = when {
            kcalRatio in 0.55f..1.05f -> 30f
            kcalRatio in 0.35f..1.25f -> 18f
            kcalRatio < 0.35f -> 8f
            else -> max(0f, 12f - (kcalRatio - 1.25f) * 20f) // Überhang bestrafen
        }

        // Leichte KH/Fett-Nähe (niedrig gewichtet)
        val carbScore = if (remaining.carbs > 5f) {
            min(carbs / remaining.carbs, 1f) * 5f
        } else 0f
        val fatScore = if (remaining.fat > 3f) {
            min(fat / remaining.fat, 1f) * 5f
        } else 0f

        // Zu weit unter Protein-Ziel → Abzug
        val proteinPenalty = if (remaining.protein >= 20f && protein < remaining.protein * 0.15f) -8f else 0f

        return proteinScore + kcalScore + carbScore + fatScore + favoriteBoost + sourceBoost + proteinPenalty
    }
}
