package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCollection
import kotlinx.coroutines.flow.Flow

/** Hilfszeile für Rezept-Anzahl pro Sammlung. */
data class CollectionCount(
    val collectionId: Long,
    val cnt: Int
)

@Dao
interface RecipeCollectionDao {

    @Query("SELECT * FROM recipe_collections ORDER BY name ASC")
    fun getAllCollections(): Flow<List<RecipeCollection>>

    @Query("SELECT * FROM recipes WHERE collectionId = :collectionId ORDER BY savedAt DESC")
    fun getRecipesByCollection(collectionId: Long): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE isFavorite = 1 ORDER BY savedAt DESC")
    fun getFavoriteRecipes(): Flow<List<Recipe>>

    @Query(
        """
        SELECT collectionId AS collectionId, COUNT(*) AS cnt
        FROM recipes
        WHERE collectionId IS NOT NULL
        GROUP BY collectionId
        """
    )
    fun getCollectionCounts(): Flow<List<CollectionCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: RecipeCollection): Long

    @Delete
    suspend fun deleteCollection(collection: RecipeCollection)

    @Query("UPDATE recipes SET collectionId = :collectionId WHERE id = :recipeId")
    suspend fun assignToCollection(recipeId: Long, collectionId: Long?)

    @Query("UPDATE recipes SET isFavorite = :fav WHERE id = :recipeId")
    suspend fun setFavorite(recipeId: Long, fav: Boolean)

    @Query("UPDATE recipes SET showNutrition = :show WHERE id = :recipeId")
    suspend fun setShowNutrition(recipeId: Long, show: Boolean)

    @Query("UPDATE recipes SET tags = :tags WHERE id = :recipeId")
    suspend fun updateTags(recipeId: Long, tags: String)
}
