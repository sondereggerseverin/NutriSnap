package ch.nutrisnap.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Protokolliert, wann (Stunde/Wochentag) ein Food geloggt wurde, um Favoriten
 *  tageszeit-bewusst zu ranken (z.B. Haferflocken morgens oben, Nudeln abends oben). */
@Entity(
    tableName = "food_usage_context",
    indices = [Index(value = ["foodId", "hourBucket", "dayOfWeek"])]
)
data class FoodUsageContext(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val foodId: String,
    val foodName: String,
    val hourBucket: Int,    // 0–23
    val dayOfWeek: Int,     // 1=Mo … 7=So (java.time.DayOfWeek.value)
    val usageCount: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis()
)
