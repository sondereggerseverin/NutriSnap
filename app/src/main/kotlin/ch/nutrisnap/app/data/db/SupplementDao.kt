package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.Supplement
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplementDao {
    @Query("SELECT * FROM supplements ORDER BY status ASC, name ASC")
    fun getAll(): Flow<List<Supplement>>

    @Query("SELECT * FROM supplements WHERE id = :id")
    suspend fun getById(id: Int): Supplement?

    @Query("SELECT COUNT(*) FROM supplements")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Supplement): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Supplement>)

    @Update
    suspend fun update(item: Supplement)

    @Delete
    suspend fun delete(item: Supplement)
}
