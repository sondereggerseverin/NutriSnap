package ch.nutrisnap.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.repository.UserProfile

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val weightKg:         Float  = 0f,
    val heightCm:         Int    = 0,
    val ageYears:         Int    = 0,
    val dailyCalorieGoal: Int    = 2000,
    val proteinGoalG:     Float  = 120f,
    val activityFactor:   Float  = 1.55f
)

fun UserProfileEntity.toDomain() = UserProfile(
    weightKg         = weightKg,
    heightCm         = heightCm,
    ageYears         = ageYears,
    dailyCalorieGoal = dailyCalorieGoal,
    proteinGoalG     = proteinGoalG,
    activityFactor   = activityFactor
)

fun UserProfile.toEntity() = UserProfileEntity(
    weightKg         = weightKg,
    heightCm         = heightCm,
    ageYears         = ageYears,
    dailyCalorieGoal = dailyCalorieGoal,
    proteinGoalG     = proteinGoalG,
    activityFactor   = activityFactor
)

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)
}

@Database(
    entities = [FoodItem::class, DiaryEntry::class, Recipe::class, UserProfileEntity::class],
    version  = 2,
    exportSchema = false
)
abstract class NutriDatabase : RoomDatabase() {
    abstract fun foodItemDao(): FoodItemDao
    abstract fun diaryDao(): DiaryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile private var INSTANCE: NutriDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_profile (
                        id INTEGER NOT NULL PRIMARY KEY,
                        weightKg REAL NOT NULL DEFAULT 0,
                        heightCm INTEGER NOT NULL DEFAULT 0,
                        ageYears INTEGER NOT NULL DEFAULT 0,
                        dailyCalorieGoal INTEGER NOT NULL DEFAULT 2000,
                        proteinGoalG REAL NOT NULL DEFAULT 120,
                        activityFactor REAL NOT NULL DEFAULT 1.55
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): NutriDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    NutriDatabase::class.java,
                    "nutrisnap.db"
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
    }
}
