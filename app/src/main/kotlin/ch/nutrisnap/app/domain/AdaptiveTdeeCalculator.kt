package ch.nutrisnap.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Result of one day's adaptive calorie target computation, with the breakdown kept
 * separate so the UI can show *why* the number is what it is (e.g. "Basis 2850 kcal
 * + Sport-Bonus 420 kcal").
 */
data class AdaptiveCalorieTarget(
    val targetKcal: Int,
    val baseKcal: Int,
    val activityBonusKcal: Int,
    val isTrendBased: Boolean,   // true = real weight+intake trend, false = BMR*activityFactor formula
    val deficitKcal: Int,
    // 0-100: how much to trust targetKcal. Trend-based grows with more overlapping
    // days and agreement with the formula estimate; formula-only is capped at a fixed
    // moderate score since it ignores this person's actual metabolic response entirely.
    val confidencePercent: Int
)

/** [computeTrendTdee] result, carrying how much data backed the estimate so
 *  [computeDailyTarget] can turn that into a confidence score. */
data class TrendTdeeResult(
    val tdee: Double,
    val overlapDays: Int,
    val spanDays: Long
)

/**
 * Adaptive TDEE: instead of trusting a fixed BMR*activityFactor formula (which can't
 * account for how active someone actually is day to day), this derives the *real*
 * average maintenance calories from what actually happened — logged intake vs. actual
 * weight change over a rolling window — then layers a target deficit and a same-day
 * adjustment for above/below-average activity on top.
 *
 * Energy balance identity used for the trend calculation:
 *   weightChangeKg * 7700 kcal/kg = totalIntake - totalExpenditure  (over the window)
 *   =>  avgTDEE = avgIntake - (weightChangeKg * 7700) / days
 *
 * If someone ate 2500 kcal/day on average but still lost 0.8 kg over 5 days, their real
 * TDEE was higher than 2500 — the formula surfaces that automatically, no manual tuning.
 */
object AdaptiveTdeeCalculator {

    const val KCAL_PER_KG = 7700.0

    // Default deficit for a moderate, sustainable weight-loss rate (~0.4-0.5 kg/week).
    // Not currently user-configurable (would need a profile schema migration) — a good
    // next step, but out of scope for this pass.
    const val DEFAULT_DEFICIT_KCAL = 500.0

    // Only "give back" half of above-average activity calories, and only take back half
    // of below-average — smooths out day-to-day tracker noise instead of reacting 1:1.
    const val ACTIVITY_ADJUSTMENT_FACTOR = 0.5

    // Need at least this many days with *both* a weight entry and logged intake,
    // spread over at least this many calendar days, before trusting the trend
    // calculation over the formula fallback. Too few/too clustered points make the
    // weight-trend estimate noise-dominated.
    const val MIN_TREND_DAYS = 5

    // Hard safety floor: never suggest a target below this, regardless of computed
    // deficit/activity adjustment. Guards against a tracker glitch (e.g. a bad low
    // active-calories reading) producing an unsafely low recommendation.
    const val SAFETY_FLOOR_KCAL = 1500.0

    /**
     * Derives real average TDEE from overlapping weight + intake history.
     * Returns null if there isn't enough overlapping data to trust the trend.
     */
    fun computeTrendTdee(
        weightByDate: Map<LocalDate, Float>,
        intakeByDate: Map<LocalDate, Float>
    ): TrendTdeeResult? {
        val days = weightByDate.keys.intersect(intakeByDate.keys).sorted()
        if (days.size < MIN_TREND_DAYS) return null

        val spanDays = ChronoUnit.DAYS.between(days.first(), days.last())
        if (spanDays < MIN_TREND_DAYS - 1) return null // guard against clustered/duplicate dates

        // Average the first/last two readings to damp single-day weight noise
        // (water weight, timing of the scale, etc.) at the window's edges.
        val startWeight = days.take(2).map { weightByDate.getValue(it) }.average()
        val endWeight = days.takeLast(2).map { weightByDate.getValue(it) }.average()
        val weightChangeKg = endWeight - startWeight

        val avgIntake = days.map { intakeByDate.getValue(it) }.average()

        val tdee = avgIntake - (weightChangeKg * KCAL_PER_KG) / spanDays
        return TrendTdeeResult(tdee = tdee, overlapDays = days.size, spanDays = spanDays)
    }

    // If the trend TDEE strays further than this from the formula estimate (when one is
    // available), it's almost certainly short-window weight noise (water, digestion,
    // timing of the scale) rather than a real metabolic signal - fall back to the formula
    // instead of trusting it. This is what let a single noisy weight swing send "Basis"
    // negative and force the safety floor on an otherwise legitimate high-activity day.
    const val TREND_PLAUSIBILITY_RATIO = 0.35

    // Below this, a trend estimate isn't just "off" - it's not a physiologically
    // plausible maintenance number for an adult at all, formula-available or not.
    const val TREND_MIN_PLAUSIBLE_KCAL = 1000.0

    /**
     * Combines the base maintenance estimate (trend-based if available and plausible, else
     * the profile's BMR*activityFactor formula), a fixed deficit, and a damped adjustment
     * for how today's activity compares to the recent average.
     *
     * Returns null only if neither a trend nor a formula TDEE is available at all
     * (e.g. brand-new profile with no weight/height/age set and no history yet).
     */
    fun computeDailyTarget(
        trend: TrendTdeeResult?,
        formulaTdee: Double?,
        todayActiveKcal: Double?,
        avgActiveKcal: Double?,
        deficitKcal: Double = DEFAULT_DEFICIT_KCAL
    ): AdaptiveCalorieTarget? {
        val trustedTrend = trend?.takeIf {
            it.tdee >= TREND_MIN_PLAUSIBLE_KCAL &&
                (formulaTdee == null ||
                    kotlin.math.abs(it.tdee - formulaTdee) <= formulaTdee * TREND_PLAUSIBILITY_RATIO)
        }
        val maintenance = trustedTrend?.tdee ?: formulaTdee ?: return null
        val base = maintenance - deficitKcal

        val bonus = if (todayActiveKcal != null && avgActiveKcal != null && avgActiveKcal > 0) {
            (todayActiveKcal - avgActiveKcal) * ACTIVITY_ADJUSTMENT_FACTOR
        } else 0.0

        val target = (base + bonus).coerceAtLeast(SAFETY_FLOOR_KCAL)

        return AdaptiveCalorieTarget(
            targetKcal = target.toInt(),
            baseKcal = base.toInt(),
            activityBonusKcal = bonus.toInt(),
            isTrendBased = trustedTrend != null,
            deficitKcal = deficitKcal.toInt(),
            confidencePercent = computeConfidence(trustedTrend, formulaTdee)
        )
    }

    // Formula-only estimates get a fixed, moderate score: Mifflin-St-Jeor is a solid
    // population-average, but it has no idea how *this* body actually responds, so it
    // should never look as trustworthy as a real measured trend.
    const val FORMULA_ONLY_CONFIDENCE = 55

    // Floor/ceiling for a trusted trend estimate - even a long, clean window is still an
    // estimate (logging gaps, scale noise), so it never reads as 100% certain; even the
    // shortest trustable window (MIN_TREND_DAYS) is still worth more than the formula alone.
    const val TREND_CONFIDENCE_FLOOR = 60
    const val TREND_CONFIDENCE_CEILING = 95

    // Each overlapping day beyond the minimum window adds this many points, up to the
    // ceiling - more real data points make the weight/intake trend less noise-dominated.
    const val CONFIDENCE_PER_EXTRA_DAY = 3

    private fun computeConfidence(trustedTrend: TrendTdeeResult?, formulaTdee: Double?): Int {
        if (trustedTrend == null) return FORMULA_ONLY_CONFIDENCE

        val dataDepthScore = TREND_CONFIDENCE_FLOOR +
            (trustedTrend.overlapDays - MIN_TREND_DAYS) * CONFIDENCE_PER_EXTRA_DAY

        // Two independent methods agreeing (trend vs. formula) is itself evidence the
        // trend is real rather than noise; the closer they are, the higher the score.
        val agreementScore = if (formulaTdee != null) {
            val relativeDiff = kotlin.math.abs(trustedTrend.tdee - formulaTdee) / formulaTdee
            val agreementFactor = (1.0 - relativeDiff / TREND_PLAUSIBILITY_RATIO).coerceIn(0.0, 1.0)
            dataDepthScore * (0.7 + 0.3 * agreementFactor)
        } else {
            // No formula to cross-check against (incomplete profile) - trust the data
            // depth alone, slightly discounted for the missing second opinion.
            dataDepthScore * 0.9
        }

        return agreementScore.toInt().coerceIn(TREND_CONFIDENCE_FLOOR, TREND_CONFIDENCE_CEILING)
    }
}
