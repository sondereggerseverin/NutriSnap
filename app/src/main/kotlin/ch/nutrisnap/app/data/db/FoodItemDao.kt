package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.FoodItem
import kotlinx.coroutines.flow.Flow

/**
 * Primary FoodItem DAO — single source of truth.
 *
 * Combines the original simple API (search, getAllCustom, insert, delete, getById)
 * with the extended API (searchFoods, searchByBarcode, getRecentFoods,
 * getFrequentFoods, incrementUsage, insertAll).
 */
@Dao
interface FoodItemDao {

    // ── Basic search (used by FoodItemRepository.searchAll) ──────────────────
    @Query("SELECT * FROM food_items WHERE name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%' ORDER BY timesUsed DESC LIMIT 50")
    suspend fun search(query: String): List<FoodItem>

    // ── Extended search (used by FoodSearchRepository) ────────────────────────
    @Query("SELECT * FROM food_items WHERE name LIKE '%' || :query || '%' ORDER BY timesUsed DESC LIMIT 50")
    suspend fun searchFoods(query: String): List<FoodItem>

    // ── Barcode lookup ────────────────────────────────────────────────────────
    @Query("SELECT * FROM food_items WHERE barcode = :barcode LIMIT 1")
    suspend fun searchByBarcode(barcode: String): FoodItem?

    // ── Custom foods (source = MANUAL) ────────────────────────────────────────
    @Query("SELECT * FROM food_items WHERE source = 'MANUAL' ORDER BY name")
    fun getAllCustom(): Flow<List<FoodItem>>

    // ── Recently used (joined via diary_entries on dateStr) ───────────────────
    @Query("""
        SELECT fi.* FROM food_items fi
        INNER JOIN diary_entries de ON fi.id = de.foodItemId
        GROUP BY fi.id
        ORDER BY MAX(de.dateStr) DESC
        LIMIT 20
    """)
    fun getRecentFoods(): Flow<List<FoodItem>>

    // ── Frequently used ───────────────────────────────────────────────────────
    @Query("""
        SELECT fi.* FROM food_items fi
        INNER JOIN diary_entries de ON fi.id = de.foodItemId
        GROUP BY fi.id
        ORDER BY COUNT(de.id) DESC
        LIMIT 10
    """)
    fun getFrequentFoods(): Flow<List<FoodItem>>

    // ── Single item by id ─────────────────────────────────────────────────────
    @Query("SELECT * FROM food_items WHERE id = :id")
    suspend fun getById(id: Int): FoodItem?

    @Query("SELECT * FROM food_items ORDER BY id DESC LIMIT 1")
    suspend fun getLastInserted(): FoodItem?

    // ── Writes ────────────────────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(foodItem: FoodItem): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(foods: List<FoodItem>)

    @Delete
    suspend fun delete(foodItem: FoodItem)

    // ── Usage counter ─────────────────────────────────────────────────────────
    @Query("UPDATE food_items SET timesUsed = timesUsed + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Int)
}
