package ch.nutrisnap.app.domain

/**
 * Einfacher, transparenter Health-Score (0–100) für Tag und optional Mahlzeit.
 *
 * Gewichtung v1:
 * - Kalorienziel-Nähe 40 %
 * - Makro-Balance (P/C/F) 30 %
 * - Priority-Mikronährstoffe 20 %
 * - Ballaststoffe 10 %
 *
 * Bewusst keine Blackbox: gleiche Formel in UI erklärbar.
 */

data class HealthScoreInput(
    val caloriesEaten: Float,
    val calorieGoal: Float,
    val proteinEaten: Float,
    val proteinGoal: Float,
    val carbsEaten: Float,
    val carbsGoal: Float,
    val fatEaten: Float,
    val fatGoal: Float,
    val fiberEaten: Float,
    val fiberGoal: Float = FIBER_GOAL_G_DOMAIN,
    /** Prozent der Priority-Nährstoffe (0–100+), leer = dieser Teil fällt weg und wird umverteilt. */
    val priorityNutrientPcts: List<Int> = emptyList()
)

data class HealthScoreBreakdown(
    val score: Int,
    val caloriePart: Int,
    val macroPart: Int,
    val microPart: Int?,
    val fiberPart: Int,
    val hasEnoughIntake: Boolean
)

object HealthScoreCalculator {

    /** Unter dieser kcal-Summe gilt der Tag als „noch zu wenig geloggt“ (Score optional ausblenden). */
    const val MIN_KCAL_FOR_SCORE = 200f

    fun compute(input: HealthScoreInput): HealthScoreBreakdown {
        val hasEnough = input.caloriesEaten >= MIN_KCAL_FOR_SCORE

        val calorieScore = adherenceScore(input.caloriesEaten, input.calorieGoal)
        val proteinScore = adherenceScore(input.proteinEaten, input.proteinGoal)
        val carbsScore = adherenceScore(input.carbsEaten, input.carbsGoal)
        val fatScore = adherenceScore(input.fatEaten, input.fatGoal)
        val macroScore = ((proteinScore + carbsScore + fatScore) / 3f).toInt()
        val fiberScore = progressScore(input.fiberEaten, input.fiberGoal)

        val microScore: Int? = if (input.priorityNutrientPcts.isNotEmpty()) {
            input.priorityNutrientPcts.map { it.coerceIn(0, 100) }.average().toInt()
        } else null

        // Gewichte: wenn keine Micros, 20 % auf Kalorien + Makros verteilen
        val score = if (microScore != null) {
            (calorieScore * 0.40f + macroScore * 0.30f + microScore * 0.20f + fiberScore * 0.10f).toInt()
        } else {
            (calorieScore * 0.50f + macroScore * 0.35f + fiberScore * 0.15f).toInt()
        }.coerceIn(0, 100)

        return HealthScoreBreakdown(
            score = score,
            caloriePart = calorieScore,
            macroPart = macroScore,
            microPart = microScore,
            fiberPart = fiberScore,
            hasEnoughIntake = hasEnough
        )
    }

    /**
     * Nähe zum Ziel: 100 bei exakt Ziel, linear abfallend.
     * Unter 50 % oder über 150 % des Ziels → 0.
     */
    fun adherenceScore(actual: Float, goal: Float): Int {
        if (goal <= 0f) return 0
        val ratio = actual / goal
        return when {
            ratio in 0.95f..1.05f -> 100
            ratio < 0.5f || ratio > 1.5f -> 0
            ratio < 1f -> ((ratio - 0.5f) / 0.45f * 100f).toInt().coerceIn(0, 100)
            else -> ((1.5f - ratio) / 0.45f * 100f).toInt().coerceIn(0, 100)
        }
    }

    /** Fortschritt zum Mindestziel (mehr ist besser, Cap 100). */
    fun progressScore(actual: Float, goal: Float): Int {
        if (goal <= 0f) return 0
        return ((actual / goal) * 100f).toInt().coerceIn(0, 100)
    }
}
