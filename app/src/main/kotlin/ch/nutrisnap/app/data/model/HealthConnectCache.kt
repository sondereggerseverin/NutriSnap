package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Lokaler Cache für Health Connect Daten.
 * Wird täglich aktualisiert – ermöglicht Offline-Ansicht.
 */
@Entity(tableName = "health_connect_cache")
data class HealthConnectCache(
    @PrimaryKey val date: LocalDate,
    val steps: Long = 0L,
    val activeCaloriesKcal: Double = 0.0,
    val weightKg: Double? = null,
    val sleepMinutes: Long = 0L,
    val avgHeartRateBpm: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /** Schritte → Kalorien Schätzung (ACSM-Formel: ~0.04 kcal/Schritt) */
    val estimatedStepCalories: Int get() = (steps * 0.04).toInt()

    /** Gesamte verbrauchte Kalorien aus Aktivität */
    val totalActivityCalories: Int get() =
        activeCaloriesKcal.toInt().coerceAtLeast(estimatedStepCalories)

    /** Schlaf in Stunden und Minuten formatiert */
    val sleepFormatted: String get() {
        val h = sleepMinutes / 60
        val m = sleepMinutes % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }

    /** Schlafqualität als Enum */
    val sleepQuality: SleepQuality get() = when {
        sleepMinutes >= 450 -> SleepQuality.GOOD      // 7.5h+
        sleepMinutes >= 360 -> SleepQuality.OK         // 6h+
        sleepMinutes > 0    -> SleepQuality.POOR       // < 6h
        else                -> SleepQuality.NO_DATA
    }
}

enum class SleepQuality(val label: String, val emoji: String) {
    GOOD("Gut", "😴"),
    OK("Ok", "😐"),
    POOR("Wenig", "😴"),
    NO_DATA("Keine Daten", "❓")
}
