package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.ShoppingListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingListDao {
    @Query("SELECT * FROM shopping_list_items ORDER BY checked ASC, createdAt DESC")
    fun getAll(): Flow<List<ShoppingListItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ShoppingListItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ShoppingListItem>)

    @Update
    suspend fun update(item: ShoppingListItem)

    @Delete
    suspend fun delete(item: ShoppingListItem)

    @Query("DELETE FROM shopping_list_items WHERE checked = 1")
    suspend fun deleteChecked()

    @Query("DELETE FROM shopping_list_items")
    suspend fun deleteAll()
}
