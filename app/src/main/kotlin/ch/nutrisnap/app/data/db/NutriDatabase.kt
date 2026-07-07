package ch.nutrisnap.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.repository.Sex
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
    val activityFactor: Float = 1.55f,
    @ColumnInfo(defaultValue = "'UNSPECIFIED'") val sex: String = "UNSPECIFIED",
    @ColumnInfo(defaultValue = "") val applianceModel: String = ""
)

fun UserProfileEntity.toDomain() = UserProfile(
    weightKg = weightKg, heightCm = heightCm, ageYears = ageYears,
    dailyCalorieGoal = dailyCalorieGoal, proteinGoalG = proteinGoalG,
    carbsGoalG = carbsGoalG, fatGoalG = fatGoalG, activityFactor = activityFactor,
    sex = runCatching { Sex.valueOf(sex) }.getOrDefault(Sex.UNSPECIFIED),
    applianceModel = applianceModel
)

fun UserProfile.toEntity() = UserProfileEntity(
    weightKg = weightKg, heightCm = heightCm, ageYears = ageYears,
    dailyCalorieGoal = dailyCalorieGoal, proteinGoalG = proteinGoalG,
    carbsGoalG = carbsGoalG, fatGoalG = fatGoalG, activityFactor = activityFactor,
    sex = sex.name, applianceModel = applianceModel
)

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1") suspend fun get(): UserProfileEntity?
    @Query("SELECT * FROM user_profile WHERE id = 1") fun observe(): kotlinx.coroutines.flow.Flow<UserProfileEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(profile: UserProfileEntity)
}

@Database(
    entities = [
        FoodItem::class,
        DiaryEntry::class,
        Recipe::class,
        UserProfileEntity::class,
        WeightEntry::class,
        FavoriteFoodEntity::class,
        RecipeCollection::class,
        HealthConnectCache::class,
        IngredientMatch::class,
        // Phase 1 new entities:
        CustomFoodItem::class,
        MealTemplate::class,
        MealTemplateItem::class,
        GeneratedRecipeEntity::class
    ],
    version = 14,
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
    abstract fun recipeCollectionDao(): RecipeCollectionDao
    abstract fun healthConnectDao(): HealthConnectDao
    abstract fun ingredientMatchDao(): IngredientMatchDao
    abstract fun customFoodDao(): CustomFoodDao
    abstract fun mealTemplateDao(): MealTemplateDao
    abstract fun generatedRecipeDao(): GeneratedRecipeDao

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
                db.execSQL("""CREATE TABLE IF NOT EXISTS weight_entries (dateStr TEXT NOT NULL PRIMARY KEY, weightKg REAL NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS favorite_foods (foodKey TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, brand TEXT, caloriesPer100g REAL NOT NULL, proteinPer100g REAL NOT NULL, carbsPer100g REAL NOT NULL, fatPer100g REAL NOT NULL, fiberPer100g REAL NOT NULL DEFAULT 0, addedAt INTEGER NOT NULL)""")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS food_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, brand TEXT, barcode TEXT, calories REAL NOT NULL, protein REAL NOT NULL, carbs REAL NOT NULL, fat REAL NOT NULL, servingSize REAL NOT NULL DEFAULT 100, servingUnit TEXT NOT NULL DEFAULT 'g', fiber REAL, sugar REAL, saturatedFat REAL, sodium REAL, potassium REAL, calcium REAL, iron REAL, vitaminC REAL, vitaminD REAL, vitaminB12 REAL, source TEXT NOT NULL DEFAULT 'MANUAL', completenessScore INTEGER NOT NULL DEFAULT 0, timesUsed INTEGER NOT NULL DEFAULT 0)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS water_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL, amountMl INTEGER NOT NULL, timestamp TEXT NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS fasting_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startTime TEXT NOT NULL, endTime TEXT, goalHours INTEGER NOT NULL DEFAULT 16, isCompleted INTEGER NOT NULL DEFAULT 0)""")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS recipe_collections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, emoji TEXT NOT NULL DEFAULT '📁', createdAt INTEGER NOT NULL DEFAULT 0)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS health_connect_cache (date TEXT NOT NULL PRIMARY KEY, steps INTEGER NOT NULL DEFAULT 0, activeCaloriesKcal REAL NOT NULL DEFAULT 0.0, weightKg REAL, sleepMinutes INTEGER NOT NULL DEFAULT 0, avgHeartRateBpm INTEGER, lastUpdated INTEGER NOT NULL DEFAULT 0)""")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS ingredient_matches (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recipeId INTEGER NOT NULL, ingredientRaw TEXT NOT NULL, ingredientName TEXT NOT NULL, amountGrams REAL NOT NULL DEFAULT 0.0, matchedFoodItemId INTEGER, matchedFoodName TEXT, matchedCalories REAL, matchedProtein REAL, matchedCarbs REAL, matchedFat REAL, matchSource TEXT NOT NULL DEFAULT 'UNMATCHED')""")
            }
        }
        // Phase 1: Custom Foods + Meal Templates
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS custom_foods (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        calories REAL NOT NULL,
                        protein REAL NOT NULL,
                        carbs REAL NOT NULL,
                        fat REAL NOT NULL,
                        fiber REAL NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        userId TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS meal_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        mealType TEXT NOT NULL DEFAULT 'LUNCH',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        userId TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS meal_template_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        templateId INTEGER NOT NULL,
                        foodName TEXT NOT NULL,
                        calories REAL NOT NULL,
                        protein REAL NOT NULL,
                        carbs REAL NOT NULL,
                        fat REAL NOT NULL,
                        quantityGrams REAL NOT NULL,
                        FOREIGN KEY(templateId) REFERENCES meal_templates(id) ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }
        // Phase 2: KI-Rezeptgenerator history
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS generated_recipes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        ingredients TEXT NOT NULL DEFAULT '',
                        steps TEXT NOT NULL DEFAULT '',
                        servings INTEGER NOT NULL DEFAULT 2,
                        prepTimeMinutes INTEGER NOT NULL DEFAULT 30,
                        calories INTEGER NOT NULL DEFAULT 0,
                        protein REAL NOT NULL DEFAULT 0,
                        carbs REAL NOT NULL DEFAULT 0,
                        fat REAL NOT NULL DEFAULT 0,
                        generatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // Phase 3: activeCaloriesKcal nullable machen (null = "Health Connect hat
        // noch keine Daten", statt es mit 0.0 = "wirklich 0 kcal" zu verwechseln).
        // SQLite kennt kein ALTER COLUMN -> Tabelle neu anlegen + Daten kopieren.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS health_connect_cache_new (
                        date TEXT NOT NULL PRIMARY KEY,
                        steps INTEGER NOT NULL DEFAULT 0,
                        activeCaloriesKcal REAL,
                        weightKg REAL,
                        sleepMinutes INTEGER NOT NULL DEFAULT 0,
                        avgHeartRateBpm INTEGER,
                        lastUpdated INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO health_connect_cache_new
                    SELECT date, steps, activeCaloriesKcal, weightKg, sleepMinutes, avgHeartRateBpm, lastUpdated
                    FROM health_connect_cache
                """.trimIndent())
                db.execSQL("DROP TABLE health_connect_cache")
                db.execSQL("ALTER TABLE health_connect_cache_new RENAME TO health_connect_cache")
            }
        }

        // Phase 4: Geschlecht am Profil - Mifflin-St-Jeor BMR braucht den
        // geschlechtsabhaengigen Term (+5 Maenner / -161 Frauen), der vorher komplett
        // fehlte. Bestehende Profile bekommen 'UNSPECIFIED' (neutraler Mittelwert -78).
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN sex TEXT NOT NULL DEFAULT 'UNSPECIFIED'")
            }
        }

        // Phase 5: Geräteprofil (Backofen/Kombi-Dampfgarer-Modell) fürs Rezept-Feature -
        // Grundlage, um Rezepte/Backprogramme direkt aufs vorhandene Gerät zuzuschneiden.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN applianceModel TEXT NOT NULL DEFAULT ''")
            }
        }

        // Phase 6: manuelle Reihenfolge der Diary-Einträge (Drag-Handle-Reorder).
        // sortOrder = 0 fuer Bestandsdaten -> stabiler Fallback auf Einfuegereihenfolge (id).
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Phase 7: voller Mikronaehrstoff-Ausbau (Yazio-Parität) - Vitamine,
        // Mineralstoffe und Spurenelemente pro 100g an food_items.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val newColumns = listOf(
                    "monoFat", "polyFat", "transFat", "salt", "alcohol", "cholesterol", "water",
                    "vitaminA", "vitaminB1", "vitaminB2", "vitaminB3", "vitaminB5", "vitaminB6",
                    "vitaminB7", "vitaminB11", "vitaminE", "vitaminK",
                    "magnesium", "zinc", "phosphorus", "copper", "manganese", "fluoride", "iodine",
                    "selenium", "chromium", "molybdenum", "chloride", "choline",
                    "arsenic", "boron", "cobalt", "rubidium", "silicon", "sulfur", "tin", "vanadium"
                )
                newColumns.forEach { col ->
                    db.execSQL("ALTER TABLE food_items ADD COLUMN $col REAL")
                }
            }
        }

        // Phase 8: Wassertracking und Fasten entfernt - zugehoerige Tabellen
        // werden verworfen statt still stehenzubleiben.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS water_entries")
                db.execSQL("DROP TABLE IF EXISTS fasting_sessions")
            }
        }

        fun getInstance(context: Context): NutriDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    NutriDatabase::class.java,
                    "nutrisnap.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                        MIGRATION_12_13, MIGRATION_13_14
                    )
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
