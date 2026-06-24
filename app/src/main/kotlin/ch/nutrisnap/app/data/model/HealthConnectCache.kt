package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

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
    val totalActivityCalories: Int get() = activeCaloriesKcal.toInt()
    val sleepFormatted: String get() {
        val h = sleepMinutes / 60
        val m = sleepMinutes % 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
    val sleepQuality: SleepQuality get() = when {
        sleepMinutes >= 450 -> SleepQuality.GOOD
        sleepMinutes >= 360 -> SleepQuality.OK
        sleepMinutes > 0    -> SleepQuality.POOR
        else                -> SleepQuality.NO_DATA
    }
}

enum class SleepQuality(val label: String, val emoji: String) {
    GOOD("Gut", "😴"),
    OK("Ok", "😐"),
    POOR("Wenig", "😴"),
    NO_DATA("Keine Daten", "❓")
}
