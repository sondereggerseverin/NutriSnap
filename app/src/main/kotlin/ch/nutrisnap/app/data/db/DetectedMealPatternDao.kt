package ch.nutrisnap.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.nutrisnap.app.data.db.entity.DetectedMealPatternEntity
import ch.nutrisnap.app.data.model.MealType

@Dao
interface DetectedMealPatternDao {

    @Query("SELECT * FROM detected_meal_patterns WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): DetectedMealPatternEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(pattern: DetectedMealPatternEntity): Long

    @Query("UPDATE detected_meal_patterns SET occurrences = :occurrences, lastSeenAt = :ts WHERE fingerprint = :fingerprint")
    suspend fun updateOccurrenceCount(fingerprint: String, occurrences: Int, ts: Long = System.currentTimeMillis())

    @Query("SELECT * FROM detected_meal_patterns WHERE mealType = :mealType ORDER BY occurrences DESC")
    suspend fun getByMealType(mealType: MealType): List<DetectedMealPatternEntity>
}
