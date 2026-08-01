package ch.nutrisnap.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.nutrisnap.app.data.db.entity.GlobalIngredientMatch
import kotlinx.coroutines.flow.Flow

@Dao
interface GlobalIngredientMatchDao {

    @Query("SELECT * FROM global_ingredient_matches WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByName(normalizedName: String): GlobalIngredientMatch?

    @Query("SELECT * FROM global_ingredient_matches WHERE normalizedName LIKE :query || '%' ORDER BY usageCount DESC LIMIT 10")
    suspend fun searchByPrefix(query: String): List<GlobalIngredientMatch>

    @Query("SELECT * FROM global_ingredient_matches WHERE isVerifiedByUser = 1 ORDER BY usageCount DESC")
    fun getAllVerified(): Flow<List<GlobalIngredientMatch>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(match: GlobalIngredientMatch): Long

    @Query("UPDATE global_ingredient_matches SET usageCount = usageCount + 1, lastUsedAt = :timestamp WHERE normalizedName = :normalizedName")
    suspend fun incrementUsage(normalizedName: String, timestamp: Long = System.currentTimeMillis())

    @Query("""UPDATE global_ingredient_matches
        SET isVerifiedByUser = 1, offProductId = :offId, offProductName = :offName,
            kcalPer100g = :kcal, proteinPer100g = :protein, carbsPer100g = :carbs, fatPer100g = :fat
        WHERE normalizedName = :normalizedName""")
    suspend fun verifyAndUpdate(normalizedName: String, offId: String, offName: String,
        kcal: Double, protein: Double, carbs: Double, fat: Double)
}
