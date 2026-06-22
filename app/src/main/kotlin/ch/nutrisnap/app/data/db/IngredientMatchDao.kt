package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.IngredientMatch
import kotlinx.coroutines.flow.Flow

@Dao
interface IngredientMatchDao {

    @Query("SELECT * FROM ingredient_matches WHERE recipeId = :recipeId ORDER BY id ASC")
    fun getMatchesForRecipe(recipeId: Long): Flow<List<IngredientMatch>>

    @Query("SELECT * FROM ingredient_matches WHERE recipeId = :recipeId ORDER BY id ASC")
    suspend fun getMatchesForRecipeOnce(recipeId: Long): List<IngredientMatch>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(match: IngredientMatch): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatches(matches: List<IngredientMatch>)

    @Update
    suspend fun updateMatch(match: IngredientMatch)

    @Query("DELETE FROM ingredient_matches WHERE recipeId = :recipeId")
    suspend fun deleteMatchesForRecipe(recipeId: Long)

    @Query("SELECT COUNT(*) FROM ingredient_matches WHERE recipeId = :recipeId AND matchedFoodItemId IS NOT NULL")
    fun getMatchedCount(recipeId: Long): Flow<Int>
}
