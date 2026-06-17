package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.FoodItem
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodItemDao {

    @Query("SELECT * FROM food_items WHERE name LIKE '%' || :query || '%' ORDER BY timesUsed DESC LIMIT 50")
    suspend fun searchFoods(query: String): List<FoodItem>

    @Query("SELECT * FROM food_items WHERE barcode = :barcode LIMIT 1")
    suspend fun searchByBarcode(barcode: String): FoodItem?

    @Query("SELECT * FROM food_items ORDER BY id DESC LIMIT 1")
    suspend fun getLastInserted(): FoodItem?

    @Query("""
        SELECT fi.* FROM food_items fi
        INNER JOIN diary_entries de ON fi.id = de.foodItemId
        GROUP BY fi.id
        ORDER BY MAX(de.timestamp) DESC
        LIMIT 20
    """)
    fun getRecentFoods(): Flow<List<FoodItem>>

    @Query("""
        SELECT fi.* FROM food_items fi
        INNER JOIN diary_entries de ON fi.id = de.foodItemId
        GROUP BY fi.id
        ORDER BY COUNT(de.id) DESC
        LIMIT 10
    """)
    fun getFrequentFoods(): Flow<List<FoodItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(foodItem: FoodItem): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(foods: List<FoodItem>)

    @Query("UPDATE food_items SET timesUsed = timesUsed + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Int)

    @Delete
    suspend fun delete(foodItem: FoodItem)
}
