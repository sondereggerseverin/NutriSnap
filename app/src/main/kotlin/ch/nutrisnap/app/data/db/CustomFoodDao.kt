package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.CustomFoodItem
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomFoodDao {

    @Query("SELECT * FROM custom_foods ORDER BY createdAt DESC")
    fun getAll(): Flow<List<CustomFoodItem>>

    @Query("SELECT * FROM custom_foods WHERE name LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<CustomFoodItem>>

    /** Einmalige (nicht-reaktive) Variante von [search] fuer die Verwendung in
     *  [ch.nutrisnap.app.data.repository.FoodItemRepository.searchAll], die einen
     *  einzelnen Ergebnis-Snapshot statt eines Flows braucht. */
    @Query("SELECT * FROM custom_foods WHERE name LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT 50")
    suspend fun searchOnce(query: String): List<CustomFoodItem>



    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CustomFoodItem): Long

    @Update
    suspend fun update(item: CustomFoodItem)

    @Delete
    suspend fun delete(item: CustomFoodItem)
}
