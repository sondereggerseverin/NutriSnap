package ch.nutrisnap.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.UserProfile

@Database(
    entities = [FoodItem::class, DiaryEntry::class, Recipe::class, UserProfile::class],
    version = 2,
    exportSchema = false
)
abstract class NutriDatabase : RoomDatabase() {
    abstract fun foodItemDao(): FoodItemDao
    abstract fun diaryDao(): DiaryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile private var INSTANCE: NutriDatabase? = null

        // Migration from v1 (no UserProfile) to v2 (with UserProfile)
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS user_profile (
                        id INTEGER NOT NULL PRIMARY KEY,
                        dailyCalorieGoal INTEGER NOT NULL DEFAULT 2000,
                        proteinGoalG REAL NOT NULL DEFAULT 120,
                        carbsGoalG REAL NOT NULL DEFAULT 250,
                        fatGoalG REAL NOT NULL DEFAULT 65,
                        weightKg REAL,
                        heightCm REAL,
                        age INTEGER,
                        sex TEXT,
                        activityFactor REAL NOT NULL DEFAULT 1.55,
                        darkMode INTEGER NOT NULL DEFAULT 0
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
                .build()
                .also { INSTANCE = it }
            }
    }
}
