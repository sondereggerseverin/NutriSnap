package ch.nutrisnap.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.nutrisnap.app.data.db.entity.FoodUsageContext

@Dao
interface FoodUsageContextDao {

    // min(...) mit 2 Argumenten ist SQLites Skalarfunktion (nicht Aggregat-MIN) -> pro Zeile
    // ausgewertet. Deckt den Mitternachts-Wrap ab (23 Uhr vs. 0 Uhr sind "nah beieinander").
    @Query("""
        SELECT * FROM food_usage_context
        WHERE MIN(ABS(hourBucket - :currentHour), 24 - ABS(hourBucket - :currentHour)) <= 1
          AND dayOfWeek = :dayOfWeek
        ORDER BY usageCount DESC
        LIMIT :limit
    """)
    suspend fun getTopFoodsForHourAndDay(currentHour: Int, dayOfWeek: Int, limit: Int = 5): List<FoodUsageContext>

    @Query("""
        SELECT * FROM food_usage_context
        WHERE MIN(ABS(hourBucket - :currentHour), 24 - ABS(hourBucket - :currentHour)) <= 1
        ORDER BY usageCount DESC
        LIMIT :limit
    """)
    suspend fun getTopFoodsForHour(currentHour: Int, limit: Int = 10): List<FoodUsageContext>

    @Query("""
        UPDATE food_usage_context
        SET usageCount = usageCount + 1, lastUsedAt = :ts
        WHERE foodId = :foodId AND hourBucket = :hourBucket AND dayOfWeek = :dayOfWeek
    """)
    suspend fun incrementUsage(foodId: String, hourBucket: Int, dayOfWeek: Int, ts: Long = System.currentTimeMillis()): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(ctx: FoodUsageContext): Long
}
