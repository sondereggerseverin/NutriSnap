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
    val confidencePercent: Int,
    /** Mifflin-St-Jeor Grundumsatz (Ruhe), falls Profil komplett. */
    val formulaBmrKcal: Int? = null,
    /** Formel-TDEE = BMR × Aktivitätsfaktor. */
    val formulaTdeeKcal: Int? = null,
    /** Aus Gewicht+Intake abgeleiteter Erhaltungsbedarf. */
    val trendTdeeKcal: Int? = null,
    /** Gewählter Erhaltungsbedarf vor Defizit (Trend bevorzugt). */
    val maintenanceKcal: Int = 0,
    /** Gewichtsänderung im Trendfenster (kg), negativ = abgenommen. */
    val weightChangeKg: Float? = null,
    /** Durchschnittliche Tagesaufnahme im Trendfenster. */
    val avgIntakeKcal: Int? = null,
    val avgActiveKcal: Int? = null,
    val todayActiveKcal: Int? = null,
    val trendOverlapDays: Int = 0,
    val trendSpanDays: Int = 0
)

/** [computeTrendTdee] result, carrying how much data backed the estimate so
 *  [computeDailyTarget] can turn that into a confidence score. */
data class TrendTdeeResult(
    val tdee: Double,
    val overlapDays: Int,
    val spanDays: Long,
    val weightChangeKg: Double = 0.0,
    val avgIntakeKcal: Double = 0.0
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

    /**
     * Wearables überschätzen Energieverbrauch typisch um ~20–30% (Reviews zu
     * Apple/Garmin/Fitbit). Zusätzlich steckt im TDEE (Formel-PAL oder Trend)
     * schon die durchschnittliche Bewegung. Deshalb zählen wir nur die
     * Abweichung vom Ø, und nur mit Faktor 0.5 — großer Sporttag bleibt spürbar,
     * ohne TDEE + volle Tracker-kcal zu doppeln.
     */
    const val ACTIVITY_ADJUSTMENT_FACTOR = 0.5

    /** Soft-Cap: Ruhetag darf Ziel senken, großer Tag (z.B. 100 km) anheben. */
    const val ACTIVITY_BONUS_MIN_KCAL = -600.0
    const val ACTIVITY_BONUS_MAX_KCAL = 2000.0

    /** EWMA-α für Gewichtsglättung im Trendfenster (0.1–0.25 üblich). */
    const val WEIGHT_EWMA_ALPHA = 0.15

    // Need at least this many days with *both* a weight reading (manual weight_entries
    // and/or Health Connect body mass) and logged intake, spread over at least this
    // many calendar days, before trusting the trend over the formula fallback.
    const val MIN_TREND_DAYS = 5

    /**
     * Merged Gewichtsquelle für den Trend: manuelle Einträge + Health Connect.
     * Bei Konflikt gewinnt Health Connect (gleiche Priorität wie in der Analyse).
     */
    fun mergeWeightByDate(
        manualByDate: Map<LocalDate, Float>,
        healthConnectByDate: Map<LocalDate, Float>
    ): Map<LocalDate, Float> {
        if (healthConnectByDate.isEmpty()) return manualByDate
        if (manualByDate.isEmpty()) return healthConnectByDate
        // HC überschreibt manuell am gleichen Tag (Analyse: hc ?: manual → HC first)
        return manualByDate + healthConnectByDate
    }

    // Hard safety floor: never suggest a target below this, regardless of computed
    // deficit/activity adjustment. Guards against a tracker glitch (e.g. a bad low
    // active-calories reading) producing an unsafely low recommendation.
    const val SAFETY_FLOOR_KCAL = 1500.0


    /** Exponentiell gewichteter gleitender Durchschnitt für eine Zeitreihe. */
    fun ewmaSeries(values: List<Double>, alpha: Double = WEIGHT_EWMA_ALPHA): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<Double>(values.size)
        var prev = values.first()
        out.add(prev)
        for (i in 1 until values.size) {
            prev = prev + alpha * (values[i] - prev)
            out.add(prev)
        }
        return out
    }

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

        // EWMA-geglättetes Gewicht (Hacker's Diet / Trendweight-Prinzip):
        // Tagesgewicht ist durch Wasser/Verdauung verrauscht (±1–2 kg).
        val rawWeights = days.map { weightByDate.getValue(it).toDouble() }
        val smoothed = ewmaSeries(rawWeights, WEIGHT_EWMA_ALPHA)
        val weightChangeKg = smoothed.last() - smoothed.first()

        val avgIntake = days.map { intakeByDate.getValue(it) }.average()

        // Energiebilanz rückwärts: ΔGewicht × 7700 ≈ Zufuhr − Verbrauch
        // => TDEE ≈ Ø-Zufuhr − (ΔGewicht × 7700) / Tage
        val tdee = avgIntake - (weightChangeKg * KCAL_PER_KG) / spanDays
        return TrendTdeeResult(
            tdee = tdee,
            overlapDays = days.size,
            spanDays = spanDays,
            weightChangeKg = weightChangeKg,
            avgIntakeKcal = avgIntake
        )
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
     * Combines maintenance (prefer adaptive trend TDEE from intake vs. weight change;
     * else Mifflin-St-Jeor × PAL), a moderate deficit, and a *partial* same-day
     * activity adjustment. Wearable kcal are not added 1:1 on top of TDEE (tracker
     * error + double-counting average activity already in TDEE).
     *
     * Returns null only if neither a trend nor a formula TDEE is available at all
     * (e.g. brand-new profile with no weight/height/age set and no history yet).
     */
    fun computeDailyTarget(
        trend: TrendTdeeResult?,
        formulaTdee: Double?,
        todayActiveKcal: Double?,
        avgActiveKcal: Double?,
        deficitKcal: Double = DEFAULT_DEFICIT_KCAL,
        formulaBmr: Double? = null,
        /** 0.5 = Standard (Wearable-Vorsicht); 1.0 = aggressiver Sporttag. */
        activityFactor: Double = ACTIVITY_ADJUSTMENT_FACTOR
    ): AdaptiveCalorieTarget? {
        val trustedTrend = trend?.takeIf {
            it.tdee >= TREND_MIN_PLAUSIBLE_KCAL &&
                (formulaTdee == null ||
                    kotlin.math.abs(it.tdee - formulaTdee) <= formulaTdee * TREND_PLAUSIBILITY_RATIO)
        }
        val maintenance = trustedTrend?.tdee ?: formulaTdee ?: return null
        // Moderates Defizit (NIH/ACSM ~500–1000 kcal/Tag); max 25% der Erhaltung
        val safeDeficit = deficitKcal.coerceIn(0.0, maintenance * 0.25)
        val base = maintenance - safeDeficit

        // Nur Abweichung vom Ø-Aktivitätsniveau, halb gewichtet (Wearable-Fehler).
        // Beispiel: 3268 heute, Ø 800 → Roh-Delta 2468 → Bonus ~1234 (nicht +3268).
        val factor = activityFactor.coerceIn(0.25, 1.0)
        val rawBonus = if (todayActiveKcal != null && avgActiveKcal != null && avgActiveKcal > 0) {
            (todayActiveKcal - avgActiveKcal) * factor
        } else {
            0.0
        }
        val bonus = rawBonus.coerceIn(ACTIVITY_BONUS_MIN_KCAL, ACTIVITY_BONUS_MAX_KCAL)

        val target = (base + bonus).coerceAtLeast(SAFETY_FLOOR_KCAL)

        return AdaptiveCalorieTarget(
            targetKcal = target.toInt(),
            baseKcal = base.toInt(),
            activityBonusKcal = bonus.toInt(),
            isTrendBased = trustedTrend != null,
            deficitKcal = safeDeficit.toInt(),
            confidencePercent = computeConfidence(trustedTrend, formulaTdee),
            formulaBmrKcal = formulaBmr?.toInt(),
            formulaTdeeKcal = formulaTdee?.toInt(),
            trendTdeeKcal = trend?.tdee?.toInt(),
            maintenanceKcal = maintenance.toInt(),
            weightChangeKg = trend?.weightChangeKg?.toFloat(),
            avgIntakeKcal = trend?.avgIntakeKcal?.toInt(),
            avgActiveKcal = avgActiveKcal?.toInt(),
            todayActiveKcal = todayActiveKcal?.toInt(),
            trendOverlapDays = trend?.overlapDays ?: 0,
            trendSpanDays = trend?.spanDays?.toInt() ?: 0
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
