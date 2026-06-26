package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneratedRecipeDao {
    @Query("SELECT * FROM generated_recipes ORDER BY generatedAt DESC LIMIT 20")
    fun getHistory(): Flow<List<GeneratedRecipeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: GeneratedRecipeEntity): Long

    @Delete
    suspend fun delete(recipe: GeneratedRecipeEntity)

    @Query("DELETE FROM generated_recipes WHERE id NOT IN (SELECT id FROM generated_recipes ORDER BY generatedAt DESC LIMIT 10)")
    suspend fun trimHistory()
}
