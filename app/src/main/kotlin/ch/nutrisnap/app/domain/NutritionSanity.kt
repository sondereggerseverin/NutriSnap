package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.FoodItem
import kotlin.math.abs

/**
 * Korrigiert typische Fehler in Fremddaten (OpenFoodFacts & Co.): kJ-Werte, die als kcal
 * eingetragen wurden (z. B. Honig "1284 kcal/100 g" statt ~307 kcal).
 *
 * Regeln pro 100 g:
 * - Über 905 kcal ist physikalisch unmöglich (reines Fett ≈ 900) → vermutlich kJ.
 * - Sind Protein/Kohlenhydrate/Fett bekannt, wird die kJ-Hypothese (kcal / 4.184) gegen die
 *   Makro-Schätzung 4P + 4K + 9F geprüft und nur bei guter Übereinstimmung übernommen.
 * - Ist der Wert auch dann nicht plausibel, wird der Treffer verworfen (null).
 */
object NutritionSanity {
    private const val KJ_PER_KCAL = 4.184f
    private const val MAX_KCAL_PER_100G = 905f

    fun fixKcal(kcal: Float, protein: Float?, carbs: Float?, fat: Float?): Float? {
        if (kcal.isNaN() || kcal < 0f) return null
        val estimate = if (protein != null && carbs != null && fat != null) {
            4f * protein + 4f * carbs + 9f * fat
        } else null
        val asKcal = kcal / KJ_PER_KCAL
        fun fits(v: Float, est: Float) = abs(v - est) <= maxOf(40f, 0.3f * est)

        if (kcal > MAX_KCAL_PER_100G) {
            return when {
                estimate != null && estimate >= 20f -> if (fits(asKcal, estimate)) asKcal else null
                estimate != null -> null
                asKcal <= MAX_KCAL_PER_100G -> asKcal
                else -> null
            }
        }
        // Wert im möglichen Bereich, passt aber nicht zu den Makros, kJ-Umrechnung aber schon
        if (estimate != null && estimate >= 20f && !fits(kcal, estimate) && fits(asKcal, estimate)) {
            return asKcal
        }
        return kcal
    }

    /** FoodItem mit korrigierten kcal oder null, wenn der Eintrag nicht plausibel zu retten ist. */
    fun FoodItem.sanitized(): FoodItem? {
        val kcal = calories ?: return this
        val fixed = fixKcal(kcal, protein, carbs, fat) ?: return null
        return if (fixed == kcal) this else copy(calories = fixed)
    }
}
