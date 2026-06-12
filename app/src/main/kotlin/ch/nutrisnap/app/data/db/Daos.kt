package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodItemDao {
    @Query("SELECT * FROM food_items WHERE name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%' ORDER BY name LIMIT 50")
    suspend fun search(query: String): List<FoodItem>

    @Query("SELECT * FROM food_items WHERE isCustom = 1 ORDER BY name")
    fun getAllCustom(): Flow<List<FoodItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FoodItem): Long

    @Delete
    suspend fun delete(item: FoodItem)

    @Query("SELECT * FROM food_items WHERE id = :id")
    suspend fun getById(id: Long): FoodItem?
}

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE dateStr = :dateStr ORDER BY mealType")
    fun getEntriesForDate(dateStr: String): Flow<List<DiaryEntry>>

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DiaryEntry): Long

    @Update
    suspend fun update(entry: DiaryEntry)

    @Delete
    suspend fun delete(entry: DiaryEntry)

    @Query("SELECT SUM(calories) FROM diary_entries WHERE dateStr = :dateStr")
    suspend fun totalCaloriesForDate(dateStr: String): Float?
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

    @Query("SELECT * FROM recipes WHERE title LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY savedAt DESC")
    fun search(query: String): Flow<List<Recipe>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: Recipe): Long

    @Update
    suspend fun update(recipe: Recipe)

    @Delete
    suspend fun delete(recipe: Recipe)

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: Long): Recipe?
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun get(): Flow<UserProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: UserProfile)

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun getCurrent(): UserProfile?
}
