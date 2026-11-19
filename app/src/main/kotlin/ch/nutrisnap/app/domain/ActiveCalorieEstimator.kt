package ch.nutrisnap.app.domain

/**
 * Wissenschaftliche Schätzung der Aktiv-kcal (netto, OHNE Grundumsatz) aus Rohdaten
 * statt aus dem vom Hersteller berechneten Wert.
 *
 * - Gehen: Schrittlänge ≈ 0,415 × Körpergrösse; Netto-Energie ≈ 0,5 kcal/kg/km
 *   (ACSM: Gehen bei ~5 km/h = 3,5 MET → (3,5 − 1) × kg × h / 5 km ≈ 0,5 kcal/kg/km).
 * - Training: (MET − 1) × kg × h (Compendium of Physical Activities); das Abziehen von
 *   1 MET entfernt den Ruheumsatz, der schon im Grundumsatz steckt.
 */
object ActiveCalorieEstimator {

    const val STRIDE_FACTOR = 0.415
    const val WALKING_NET_KCAL_PER_KG_KM = 0.5

    fun walkingKcal(steps: Long, heightCm: Int, weightKg: Float): Double {
        require(heightCm > 0) { "heightCm muss > 0 sein, war $heightCm" }
        require(weightKg > 0f) { "weightKg muss > 0 sein, war $weightKg" }
        if (steps <= 0L) return 0.0
        val km = steps * STRIDE_FACTOR * heightCm / 100_000.0
        return km * WALKING_NET_KCAL_PER_KG_KM * weightKg
    }

    fun sessionKcal(mets: Double, weightKg: Float, durationMin: Double): Double {
        require(weightKg > 0f) { "weightKg muss > 0 sein, war $weightKg" }
        if (durationMin <= 0.0) return 0.0
        return (mets - 1.0).coerceAtLeast(0.0) * weightKg * durationMin / 60.0
    }
}
