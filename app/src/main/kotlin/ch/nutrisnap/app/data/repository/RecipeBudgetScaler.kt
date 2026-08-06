package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.domain.AdaptiveTdeeCalculator
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import kotlinx.coroutines.flow.first
import java.time.LocalDate

// ============================================================
// FEATURE 1: Rezept-Skalierung auf Tages-Restbudget
//
// Integration:
//  1. RecipeBudgetScaler(db) an der Stelle instanziieren, wo ein Rezept
//     "auf mein Restbudget anpassen" angeboten werden soll (z.B. RecipesViewModel).
//  2. scaleToRemainingBudget(recipe) aufrufen -> RecipeBudgetScaleResult? (null nur
//     wenn das Rezept keine totalCalories hat).
//  3. UI zeigt scaledServings / scaledKcal / scaledProtein-Carbs-Fat sowie die
//     skalierten Zutatenmengen (ingredients) an; addRecipeAsMeal() weiterhin mit
//     result.scaleFactor als servingsFactor aufrufen, um den Diary-Eintrag anzulegen.
// ============================================================

/** Eine einzelne Zutatenzeile nach der Skalierung. `parsed = false` heisst: die Zeile
 *  (z.B. eine Abschnitts-Überschrift wie "Für die Sauce:") konnte nicht als Menge+Name
 *  erkannt werden und wird unskaliert unverändert durchgereicht. */
data class ScaledIngredientLine(
    val originalLine: String,
    val name: String?,
    val originalAmountG: Float?,
    val scaledAmountG: Float?,
    val parsed: Boolean
)

data class RecipeBudgetScaleResult(
    val recipe: Recipe,
    val remainingKcal: Float,
    val kcalPerOriginalServing: Float,
    /** z.B. 0.7 = 70% der gespeicherten Portionsgrösse */
    val scaleFactor: Float,
    val scaledServings: Float,
    val scaledKcal: Float,
    val scaledProtein: Float,
    val scaledCarbs: Float,
    val scaledFat: Float,
    val ingredients: List<ScaledIngredientLine>
)

/**
 * Skaliert ein gespeichertes Rezept so, dass eine Portion genau ins heutige
 * Kalorien-Restbudget passt (z.B. "300 kcal übrig, Rezept hat 600 kcal/Portion"
 * -> 0.5x-Portion mit halbierten Mengen).
 *
 * Nutzt exakt dieselbe Restbudget-Berechnung wie [ch.nutrisnap.app.ui.screens.home.HomeViewModel]
 * (adaptives TDEE-Ziel wenn genug Verlaufsdaten vorhanden sind, sonst statisches Tagesziel +
 * verbrannte Aktivitätskalorien), damit "Rest" hier und auf dem Home-Screen übereinstimmen.
 *
 * Mengen-Skalierung nutzt [RecipeNutritionAnalyzer.parseIngredientLine] — denselben Parser,
 * der auch für die Nährwert-Analyse verwendet wird. `Recipe.ingredients` ist reiner Freitext
 * ohne strukturierte Mengenangaben, es gibt also keine fertige Ingredient-Liste zum Skalieren;
 * die Best-Effort-Zeilenerkennung ist bereits vorhanden und wird hier wiederverwendet statt
 * neu erfunden.
 */
class RecipeBudgetScaler(private val db: NutriDatabase) {

    private val diaryRepo = DiaryRepository(db)
    private val profileRepo = UserProfileRepository(db)
    private val weightRepo = WeightRepository(db)
    private val hcDao = db.healthConnectDao()

    // Gleiches Fenster wie HomeViewModel: lang genug, um Rauschen zu glätten, kurz
    // genug, um eine kürzlich geänderte Routine (z.B. mehr Sport) noch abzubilden.
    private val trendWindowDays = 21

    /** Untere Grenze: nie auf "praktisch nichts" runterskalieren. Obere Grenze: nie über
     *  die Originalgrösse hoch — das wäre keine Budget-Anpassung mehr, sondern eine
     *  Portionsvergrösserung. */
    private val minScaleFactor = 0.1f
    private val maxScaleFactor = 1f

    suspend fun scaleToRemainingBudget(recipe: Recipe): RecipeBudgetScaleResult? {
        val remaining = computeRemainingKcal() ?: return scaleToTargetKcal(recipe, 0f)
        return scaleToTargetKcal(recipe, remaining, allowUpscale = false)
    }

    /**
     * Skaliert eine Portion auf [targetKcal]. Bei allowUpscale=true auch über 1×
     * (z.B. „ich habe 600 kcal fürs Abendessen“).
     */
    fun scaleToTargetKcal(
        recipe: Recipe,
        targetKcal: Float,
        allowUpscale: Boolean = true
    ): RecipeBudgetScaleResult? {
        val kcalPerServing = recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
        if (kcalPerServing == null || kcalPerServing <= 0f) return null
        if (targetKcal <= 0f) return null

        val maxF = if (allowUpscale) 3f else maxScaleFactor
        val scaleFactor = (targetKcal / kcalPerServing).coerceIn(minScaleFactor, maxF)

        val scaledIngredients = recipe.ingredients.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parsed = RecipeNutritionAnalyzer.parseIngredientLine(line)
                ScaledIngredientLine(
                    originalLine = line,
                    name = parsed?.name,
                    originalAmountG = parsed?.amountG,
                    scaledAmountG = parsed?.amountG?.times(scaleFactor),
                    parsed = parsed != null
                )
            }

        return RecipeBudgetScaleResult(
            recipe = recipe,
            remainingKcal = targetKcal,
            kcalPerOriginalServing = kcalPerServing,
            scaleFactor = scaleFactor,
            scaledServings = recipe.servings * scaleFactor,
            scaledKcal = kcalPerServing * scaleFactor,
            scaledProtein = (recipe.proteinPerServing ?: 0f) * scaleFactor,
            scaledCarbs = (recipe.carbsPerServing ?: 0f) * scaleFactor,
            scaledFat = (recipe.fatPerServing ?: 0f) * scaleFactor,
            ingredients = scaledIngredients
        )
    }

    private suspend fun computeRemainingKcal(): Float? {
        val today = LocalDate.now()
        val profile = profileRepo.get().first()
        val trendWeights = weightRepo.getRecent(trendWindowDays).first()
        val dailySummaries = diaryRepo.getWeeklySummary(today.minusDays(trendWindowDays.toLong())).first()
        val todayEntries = diaryRepo.getEntriesForDate(today).first()
        val hcCache = hcDao.getCacheForDateOnce(today)
        val activityDays = hcDao.getRangeOnce(today.minusDays(29), today)

        val manualWeightByDate = trendWeights.associate { LocalDate.parse(it.dateStr) to it.weightKg }
        val hcWeightByDate = activityDays
            .mapNotNull { c -> c.weightKg?.let { kg -> c.date to kg.toFloat() } }
            .toMap()
        val weightByDate = AdaptiveTdeeCalculator.mergeWeightByDate(manualWeightByDate, hcWeightByDate)
        val intakeByDate = dailySummaries.associate { LocalDate.parse(it.dateStr) to it.calories }
        val trend = AdaptiveTdeeCalculator.computeTrendTdee(weightByDate, intakeByDate)

        val todayActiveKcal = hcCache?.activeCaloriesKcal
        val avgActiveKcal = activityDays
            .mapNotNull { it.activeCaloriesKcal }
            .takeIf { it.isNotEmpty() }
            ?.average()

        val adaptiveTarget = AdaptiveTdeeCalculator.computeDailyTarget(
            trend = trend,
            formulaTdee = profile.computedTdee(),
            todayActiveKcal = todayActiveKcal,
            avgActiveKcal = avgActiveKcal
        )

        val baseGoal = adaptiveTarget?.targetKcal?.toFloat() ?: profile.dailyCalorieGoal.toFloat()
        val adjustedGoal = if (adaptiveTarget != null) baseGoal else baseGoal + (todayActiveKcal?.toFloat() ?: 0f)
        val consumed = todayEntries.sumOf { it.calories.toDouble() }.toFloat()
        return (adjustedGoal - consumed).coerceAtLeast(0f)
    }
}
