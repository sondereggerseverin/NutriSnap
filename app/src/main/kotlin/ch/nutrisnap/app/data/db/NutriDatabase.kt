package ch.nutrisnap.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.FavoriteFoodEntity
import ch.nutrisnap.app.data.model.FastingSession
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCollection
import ch.nutrisnap.app.data.model.WaterEntry
import ch.nutrisnap.app.data.model.WeightEntry
import ch.nutrisnap.app.data.repository.UserProfile

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val weightKg: Float = 0f,
    val heightCm: Int = 0,
    val ageYears: Int = 0,
    val dailyCalorieGoal: Int = 2000,
    val proteinGoalG: Float = 120f,
    val carbsGoalG: Float = 220f,
    val fatGoalG: Float = 65f,
    val activityFactor: Float = 1.55f
)

fun UserProfileEntity.toDomain() = UserProfile(
    weightKg = weightKg, heightCm = heightCm, ageYears = ageYears,
    dailyCalorieGoal = dailyCalorieGoal, proteinGoalG = proteinGoalG,
    carbsGoalG = carbsGoalG, fatGoalG = fatGoalG, activityFactor = activityFactor
)

fun UserProfile.toEntity() = UserProfileEntity(
    weightKg = weightKg, heightCm = heightCm, ageYears = ageYears,
    dailyCalorieGoal = dailyCalorieGoal, proteinGoalG = proteinGoalG,
    carbsGoalG = carbsGoalG, fatGoalG = fatGoalG, activityFactor = activityFactor
)

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1") suspend fun get(): UserProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(profile: UserProfileEntity)
}

@Database(
    entities = [
        FoodItem::class,
        DiaryEntry::class,
        Recipe::class,
        RecipeCollection::class,
        UserProfileEntity::class,
        WeightEntry::class,
        FavoriteFoodEntity::class,
        WaterEntry::class,
        FastingSession::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NutriDatabase : RoomDatabase() {
    abstract fun foodItemDao(): FoodItemDao
    abstract fun diaryDao(): DiaryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun weightDao(): WeightDao
    abstract fun favoriteFoodDao(): FavoriteFoodDao
    abstract fun waterEntryDao(): WaterEntryDao
    abstract fun recipeCollectionDao(): RecipeCollectionDao

    companion object {
        @Volatile private var INSTANCE: NutriDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS user_profile (id INTEGER NOT NULL PRIMARY KEY, weightKg REAL NOT NULL DEFAULT 0, heightCm INTEGER NOT NULL DEFAULT 0, ageYears INTEGER NOT NULL DEFAULT 0, dailyCalorieGoal INTEGER NOT NULL DEFAULT 2000, proteinGoalG REAL NOT NULL DEFAULT 120, activityFactor REAL NOT NULL DEFAULT 1.55)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN carbsGoalG REAL NOT NULL DEFAULT 220")
                db.execSQL("ALTER TABLE user_profile ADD COLUMN fatGoalG REAL NOT NULL DEFAULT 65")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS weight_entries (
                        dateStr TEXT NOT NULL PRIMARY KEY,
                        weightKg REAL NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_foods (
                        foodKey TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        brand TEXT,
                        caloriesPer100g REAL NOT NULL,
                        proteinPer100g REAL NOT NULL,
                        carbsPer100g REAL NOT NULL,
                        fatPer100g REAL NOT NULL,
                        fiberPer100g REAL NOT NULL DEFAULT 0,
                        addedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Extended food_items with barcode, micros, source
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS food_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        brand TEXT,
                        barcode TEXT,
                        calories REAL NOT NULL,
                        protein REAL NOT NULL,
                        carbs REAL NOT NULL,
                        fat REAL NOT NULL,
                        servingSize REAL NOT NULL DEFAULT 100,
                        servingUnit TEXT NOT NULL DEFAULT 'g',
                        fiber REAL,
                        sugar REAL,
                        saturatedFat REAL,
                        sodium REAL,
                        potassium REAL,
                        calcium REAL,
                        iron REAL,
                        vitaminC REAL,
                        vitaminD REAL,
                        vitaminB12 REAL,
                        source TEXT NOT NULL DEFAULT 'MANUAL',
                        completenessScore INTEGER NOT NULL DEFAULT 0,
                        timesUsed INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Water tracking
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS water_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        amountMl INTEGER NOT NULL,
                        timestamp TEXT NOT NULL
                    )
                """.trimIndent())

                // Fasting sessions
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS fasting_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startTime TEXT NOT NULL,
                        endTime TEXT,
                        goalHours INTEGER NOT NULL DEFAULT 16,
                        isCompleted INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS recipe_collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        emoji TEXT NOT NULL DEFAULT '📁',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                // Recipe gained collection/favorite/nutrition-visibility fields
                db.execSQL("ALTER TABLE recipes ADD COLUMN collectionId INTEGER")
                db.execSQL("ALTER TABLE recipes ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recipes ADD COLUMN showNutrition INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE recipes ADD COLUMN savedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): NutriDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    NutriDatabase::class.java,
                    "nutrisnap.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
