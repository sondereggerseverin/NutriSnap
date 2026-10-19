package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.repository.Sex
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Kalorien- und Makro-Ziele 1:1 nach Fud AI (README + App-Formel-Referenz):
 *
 * - BMR: Katch-McArdle wenn Körperfett bekannt, sonst Mifflin-St Jeor
 * - TDEE: BMR × Aktivitätsfaktor (1.2 … 1.9, inkl. moderate 1.465)
 * - Tageskalorien: TDEE + weeklyChangeKg × 7700 / 7
 * - Protein: g/kg nach Aktivitätsstufe (+0.2 bei Defizit)
 * - Fett: 0.6 g/kg Körpergewicht
 * - KH: Restkalorien / 4
 *
 * Quellen: Mifflin-St Jeor, Katch-McArdle, FAO/WHO Aktivität, Hall 2011 (7700 kcal/kg).
 */
object FudCalorieCalculator {

    const val KCAL_PER_KG = 7700.0

    data class ActivityLevel(
        val multiplier: Float,
        val proteinPerKg: Float,
        val labelDe: String
    )

    /** Sechs Stufen wie Fud AI (work/training, nicht Schrittzahl). */
    val ACTIVITY_LEVELS = listOf(
        ActivityLevel(1.2f,   0.8f, "Sitzend"),
        ActivityLevel(1.375f, 1.2f, "Leicht aktiv"),
        ActivityLevel(1.465f, 1.6f, "Mässig aktiv"),
        ActivityLevel(1.55f,  1.8f, "Aktiv"),
        ActivityLevel(1.725f, 2.0f, "Sehr aktiv"),
        ActivityLevel(1.9f,   2.2f, "Extrem aktiv")
    )

    data class Goals(
        val bmr: Int,
        val tdee: Int,
        val calories: Int,
        val proteinG: Float,
        val fatG: Float,
        val carbsG: Float,
        val usedKatchMcArdle: Boolean,
        val weeklyChangeKg: Float,
        val activityMultiplier: Float
    )

    fun nearestActivity(factor: Float): ActivityLevel =
        ACTIVITY_LEVELS.minBy { abs(it.multiplier - factor) }

    /** Mifflin-St Jeor (Fud: base = 10w+6.25h−5a−161; Männer +166 → +5 netto). */
    fun bmrMifflin(weightKg: Float, heightCm: Int, ageYears: Int, sex: Sex): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears - 161.0
        return when (sex) {
            Sex.MALE -> base + 166.0
            Sex.FEMALE -> base
            Sex.UNSPECIFIED -> base + 83.0 // Mittelwert Männer/Frauen
        }
    }

    /** Katch-McArdle: 370 + 21.6 × fettfreie Masse (kg). bodyFatPercent z.B. 20.0. */
    fun bmrKatch(weightKg: Float, bodyFatPercent: Float): Double {
        val lean = weightKg * (1.0 - bodyFatPercent.coerceIn(1f, 60f) / 100.0)
        return 370.0 + 21.6 * lean
    }

    /**
     * @param weeklyChangeKg gewünschte Wochenänderung (negativ = Abnehmen)
     * @param bodyFatPercent optional 1–60; wenn gesetzt → Katch-McArdle
     */
    fun compute(
        weightKg: Float,
        heightCm: Int,
        ageYears: Int,
        sex: Sex,
        activityFactor: Float,
        weeklyChangeKg: Float = 0f,
        bodyFatPercent: Float? = null
    ): Goals {
        if (weightKg <= 0f || heightCm <= 0 || ageYears <= 0) {
            return Goals(
                bmr = 1500, tdee = 2000, calories = 2000,
                proteinG = 120f, fatG = 65f, carbsG = 220f,
                usedKatchMcArdle = false, weeklyChangeKg = 0f, activityMultiplier = 1.55f
            )
        }

        val level = nearestActivity(activityFactor)
        val usedKatch = bodyFatPercent != null && bodyFatPercent > 0f
        val bmr = if (usedKatch) {
            bmrKatch(weightKg, bodyFatPercent!!)
        } else {
            bmrMifflin(weightKg, heightCm, ageYears, sex)
        }
        val tdee = bmr * level.multiplier
        val adjustment = weeklyChangeKg * KCAL_PER_KG / 7.0
        val calories = (tdee + adjustment).roundToInt().coerceIn(800, 6000)

        var proteinPerKg = level.proteinPerKg
        if (weeklyChangeKg < 0f) proteinPerKg += 0.2f // Helms: Defizit +0.2 g/kg
        val proteinG = (weightKg * proteinPerKg).coerceAtLeast(40f)
        val fatG = (weightKg * 0.6f).coerceAtLeast(20f)
        val carbsKcal = (calories - proteinG * 4f - fatG * 9f).coerceAtLeast(0f)
        val carbsG = (carbsKcal / 4f).coerceAtLeast(30f)

        return Goals(
            bmr = bmr.roundToInt(),
            tdee = tdee.roundToInt(),
            calories = calories,
            proteinG = proteinG,
            fatG = fatG,
            carbsG = carbsG,
            usedKatchMcArdle = usedKatch,
            weeklyChangeKg = weeklyChangeKg,
            activityMultiplier = level.multiplier
        )
    }

    /** Wochenziel kg aus Fitness-Ziel (Fallback wenn Nutzer kein weeklyTargetLossKg setzt). */
    fun defaultWeeklyChangeKg(goalName: String): Float = when (goalName) {
        "LOSE_WEIGHT" -> -0.5f
        "MAINTAIN" -> 0f
        "BUILD_MUSCLE" -> 0.25f
        "GAIN_WEIGHT" -> 0.5f
        "SPORT" -> 0f
        else -> 0f
    }
}
