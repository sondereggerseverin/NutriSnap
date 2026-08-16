package ch.nutrisnap.app.domain

/**
 * Kuratierte MET-Presets (Adult Compendium, gerundet) für manuelles Logging.
 * kcal ≈ MET × Körpergewicht(kg) × Dauer(h)
 */
data class ActivityPreset(val name: String, val mets: Float, val defaultDurationMin: Float)

val ACTIVITY_PRESETS = listOf(
    ActivityPreset("Gehen (5 km/h)", 3.5f, 30f),
    ActivityPreset("Laufen (8 km/h)", 8.3f, 30f),
    ActivityPreset("Radfahren moderat", 6.8f, 45f),
    ActivityPreset("Krafttraining", 5.0f, 40f),
    ActivityPreset("Schwimmen", 7.0f, 30f),
    ActivityPreset("HIIT / Intervall", 8.0f, 20f),
    ActivityPreset("Yoga", 3.0f, 45f),
    ActivityPreset("Fussball / Team", 7.0f, 60f)
)

fun ActivityPreset.estimateKcal(weightKg: Float, durationMin: Float = defaultDurationMin): Float {
    val h = durationMin.coerceAtLeast(0f) / 60f
    val w = weightKg.coerceAtLeast(40f)
    return (mets * w * h).let { if (it.isFinite()) it else 0f }
}
