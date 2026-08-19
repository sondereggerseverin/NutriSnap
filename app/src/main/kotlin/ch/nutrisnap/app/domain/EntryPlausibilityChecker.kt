package ch.nutrisnap.app.domain

import kotlin.math.abs

/**
 * Einfache Plausibilitaetspruefung fuer Tagebuch-Eintraege. Faengt Tippfehler
 * ab (z.B. 1000g statt 100g, oder kcal/Makro-Werte die nicht zusammenpassen),
 * ohne den Nutzer bei normalen Eintraegen zu unterbrechen.
 */
object EntryPlausibilityChecker {

    private const val MAX_PLAUSIBLE_GRAMS = 1500f
    private const val MAX_PLAUSIBLE_KCAL_MANUAL = 3000f
    private const val MACRO_KCAL_DEVIATION_THRESHOLD = 0.35f

    fun checkPortion(grams: Float): String? =
        if (grams > MAX_PLAUSIBLE_GRAMS)
            "${grams.toInt()} g ist eine sehr grosse Portion. Menge wirklich korrekt?"
        else null

    fun checkManualEntry(kcal: Float, protein: Float, carbs: Float, fat: Float): String? {
        if (kcal > MAX_PLAUSIBLE_KCAL_MANUAL) {
            return "${kcal.toInt()} kcal ist fuer einen einzelnen Eintrag ungewoehnlich hoch. Wert wirklich korrekt?"
        }
        val macroKcal = protein * 4f + carbs * 4f + fat * 9f
        if (macroKcal > 0f && kcal > 0f) {
            val deviation = abs(macroKcal - kcal) / kcal
            if (deviation > MACRO_KCAL_DEVIATION_THRESHOLD) {
                return "Die Makros ergeben rechnerisch ${macroKcal.toInt()} kcal, angegeben sind ${kcal.toInt()} kcal. Bitte pruefen."
            }
        }
        return null
    }
}
