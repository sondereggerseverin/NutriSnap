package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.*
import kotlinx.coroutines.flow.Flow

// NOTE: FoodItemDao is defined in FoodItemDao.kt — do NOT redeclare it here.

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE dateStr = :dateStr ORDER BY mealType, sortOrder, id")
    fun getEntriesForDate(dateStr: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE dateStr = :dateStr ORDER BY mealType, sortOrder, id")
    suspend fun getEntriesForDateOnce(dateStr: String): List<DiaryEntry>

    @Query("UPDATE diary_entries SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("""
        SELECT dateStr, 
               SUM(calories) as calories, 
               SUM(protein) as protein,
               SUM(carbs) as carbs,
               SUM(fat) as fat
        FROM diary_entries 
        WHERE dateStr >= :fromDate 
        GROUP BY dateStr 
        ORDER BY dateStr DESC
    """)
    fun getWeeklySummary(fromDate: String): Flow<List<DailySummary>>

    // Trotz des Namens von getWeeklySummary auch fuer Monats-/Kalenderansicht nutzbar,
    // aber ohne obere Grenze — bei alten Konten mit viel Historie unnoetig teuer.
    // Deshalb bounded Variante mit fromDate UND toDate fuer beliebige Zeitraeume.
    @Query("""
        SELECT dateStr, 
               SUM(calories) as calories, 
               SUM(protein) as protein,
               SUM(carbs) as carbs,
               SUM(fat) as fat
        FROM diary_entries 
        WHERE dateStr BETWEEN :fromDate AND :toDate
        GROUP BY dateStr 
        ORDER BY dateStr ASC
    """)
    fun getSummaryBetween(fromDate: String, toDate: String): Flow<List<DailySummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DiaryEntry): Long

    @Update
    suspend fun update(entry: DiaryEntry)

    @Delete
    suspend fun delete(entry: DiaryEntry)

    @Query("SELECT SUM(calories) FROM diary_entries WHERE dateStr = :dateStr")
    suspend fun totalCaloriesForDate(dateStr: String): Float?

    @Query("SELECT EXISTS(SELECT 1 FROM diary_entries WHERE dateStr = :dateStr)")
    suspend fun hasEntriesForDate(dateStr: String): Boolean

    @Query("SELECT * FROM diary_entries ORDER BY dateStr, mealType")
    suspend fun getAllOnce(): List<DiaryEntry>

    /** Rohe (nicht aggregierte) Einträge ab [fromDate], u.a. für die Mustererkennung
     *  wiederkehrender Mahlzeiten (Feature 5), die pro Eintrag foodItemId/mealType braucht. */
    @Query("SELECT * FROM diary_entries WHERE dateStr >= :fromDate ORDER BY dateStr, mealType")
    suspend fun getEntriesSince(fromDate: String): List<DiaryEntry>

    @Query("SELECT * FROM diary_entries WHERE id = :id")
    suspend fun getById(id: Long): DiaryEntry?

    @Query("DELETE FROM diary_entries")
    suspend fun deleteAll()
}

data class DailySummary(
    val dateStr: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
)

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY savedAt DESC")
    fun getAll(): Flow<List<Recipe>>

    @Query(
        """
        SELECT * FROM recipes WHERE
            title LIKE '%' || :query || '%'
            OR tags LIKE '%' || :query || '%'
            OR ingredients LIKE '%' || :query || '%'
            OR description LIKE '%' || :query || '%'
            OR mealCategory LIKE '%' || :query || '%'
        ORDER BY savedAt DESC
        """
    )
    fun search(query: String): Flow<List<Recipe>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: Recipe): Long

    @Update
    suspend fun update(recipe: Recipe)

    @Delete
    suspend fun delete(recipe: Recipe)

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: Long): Recipe?

    @Query("SELECT * FROM recipes ORDER BY savedAt DESC")
    suspend fun getAllOnce(): List<Recipe>
}

@Dao
interface RecipeComponentDao {
    @Query("SELECT * FROM recipe_components WHERE recipeId = :recipeId ORDER BY sortOrder ASC, id ASC")
    fun getForRecipe(recipeId: Long): Flow<List<RecipeComponent>>

    @Query("SELECT * FROM recipe_components WHERE recipeId = :recipeId ORDER BY sortOrder ASC, id ASC")
    suspend fun getForRecipeOnce(recipeId: Long): List<RecipeComponent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(component: RecipeComponent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(components: List<RecipeComponent>)

    @Update
    suspend fun update(component: RecipeComponent)

    @Delete
    suspend fun delete(component: RecipeComponent)

    @Query("DELETE FROM recipe_components WHERE recipeId = :recipeId")
    suspend fun deleteForRecipe(recipeId: Long)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries ORDER BY dateStr ASC")
    fun getAll(): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weight_entries WHERE dateStr >= :fromDate ORDER BY dateStr ASC")
    fun getSince(fromDate: String): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weight_entries ORDER BY dateStr DESC LIMIT 1")
    suspend fun getLatest(): WeightEntry?

    @Query("SELECT * FROM weight_entries WHERE dateStr = :dateStr LIMIT 1")
    suspend fun getByDate(dateStr: String): WeightEntry?

    @Query("SELECT * FROM weight_entries ORDER BY dateStr ASC")
    suspend fun getAllOnce(): List<WeightEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WeightEntry)

    @Delete
    suspend fun delete(entry: WeightEntry)
}

@Dao
interface ManualActivityDao {
    @Query("SELECT * FROM manual_activity WHERE dateStr = :dateStr LIMIT 1")
    fun getForDate(dateStr: String): Flow<ManualActivityEntry?>

    @Query("SELECT * FROM manual_activity WHERE dateStr >= :fromDate ORDER BY dateStr ASC")
    fun getSince(fromDate: String): Flow<List<ManualActivityEntry>>

    @Query("SELECT * FROM manual_activity WHERE dateStr >= :fromDate ORDER BY dateStr ASC")
    suspend fun getSinceOnce(fromDate: String): List<ManualActivityEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ManualActivityEntry)

    @Query("DELETE FROM manual_activity WHERE dateStr = :dateStr")
    suspend fun delete(dateStr: String)
}

@Dao
interface FavoriteFoodDao {
    @Query("SELECT * FROM favorite_foods ORDER BY addedAt DESC")
    fun getAll(): Flow<List<FavoriteFoodEntity>>

    @Query("SELECT * FROM favorite_foods WHERE foodKey = :key LIMIT 1")
    suspend fun getByKey(key: String): FavoriteFoodEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_foods WHERE foodKey = :key)")
    fun isFavoriteFlow(key: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FavoriteFoodEntity)

    @Query("DELETE FROM favorite_foods WHERE foodKey = :key")
    suspend fun deleteByKey(key: String)
}
