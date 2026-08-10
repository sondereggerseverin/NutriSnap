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
    @ColumnInfo(defaultValue = "") val applianceModel: String = "",
    // Feature 3 (Ziel-Prognose): alle drei optional, solange kein Zielgewicht in den
    // Settings gesetzt ist, liefert GoalPrognosisCalculator bewusst keine Prognose.
    @ColumnInfo(defaultValue = "NULL") val targetWeightKg: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val weeklyTargetLossKg: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val lastPrognosisDateStr: String? = null
)

fun UserProfileEntity.toDomain() = UserProfile(
    weightKg = weightKg, heightCm = heightCm, ageYears = ageYears,
    dailyCalorieGoal = dailyCalorieGoal, proteinGoalG = proteinGoalG,
    carbsGoalG = carbsGoalG, fatGoalG = fatGoalG, activityFactor = activityFactor,
    sex = runCatching { Sex.valueOf(sex) }.getOrDefault(Sex.UNSPECIFIED),
    applianceModel = applianceModel,
    targetWeightKg = targetWeightKg, weeklyTargetLossKg = weeklyTargetLossKg,
    lastPrognosisDateStr = lastPrognosisDateStr
)

fun UserProfile.toEntity() = UserProfileEntity(
    weightKg = weightKg, heightCm = heightCm, ageYears = ageYears,
    dailyCalorieGoal = dailyCalorieGoal, proteinGoalG = proteinGoalG,
    carbsGoalG = carbsGoalG, fatGoalG = fatGoalG, activityFactor = activityFactor,
    sex = sex.name, applianceModel = applianceModel,
    targetWeightKg = targetWeightKg, weeklyTargetLossKg = weeklyTargetLossKg,
    lastPrognosisDateStr = lastPrognosisDateStr
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
        GeneratedRecipeEntity::class,
        ShoppingListItem::class,
        // Neue Entities (9-Feature-Patch):
        ch.nutrisnap.app.data.db.entity.GlobalIngredientMatch::class,       // Feature 2
        ch.nutrisnap.app.data.db.entity.FoodUsageContext::class,            // Feature 7
        ch.nutrisnap.app.data.db.entity.DetectedMealPatternEntity::class,   // Feature 5
        ManualActivityEntry::class,
        Supplement::class,
        RecipeComponent::class,
        FrozenMeal::class
    ],
    version = 29,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NutriDatabase : RoomDatabase() {
    abstract fun foodItemDao(): FoodItemDao
    abstract fun diaryDao(): DiaryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun recipeComponentDao(): RecipeComponentDao
    abstract fun frozenMealDao(): FrozenMealDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun weightDao(): WeightDao
    abstract fun favoriteFoodDao(): FavoriteFoodDao
    abstract fun recipeCollectionDao(): RecipeCollectionDao
    abstract fun healthConnectDao(): HealthConnectDao
    abstract fun ingredientMatchDao(): IngredientMatchDao
    abstract fun customFoodDao(): CustomFoodDao
    abstract fun mealTemplateDao(): MealTemplateDao
    abstract fun generatedRecipeDao(): GeneratedRecipeDao
    abstract fun shoppingListDao(): ShoppingListDao
    abstract fun globalIngredientMatchDao(): GlobalIngredientMatchDao
    abstract fun foodUsageContextDao(): FoodUsageContextDao
    abstract fun detectedMealPatternDao(): DetectedMealPatternDao
    abstract fun manualActivityDao(): ManualActivityDao
    abstract fun supplementDao(): SupplementDao

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

        // Phase 9: Einkaufsliste-Feature
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS shopping_list_items (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "amount REAL, " +
                        "unit TEXT, " +
                        "checked INTEGER NOT NULL DEFAULT 0, " +
                        "recipeTitle TEXT, " +
                        "createdAt INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        // Ballaststoff-Tracking: fiber-Spalte auf Tagebuch-Eintraegen
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN fiber REAL NOT NULL DEFAULT 0")
            }
        }

        // Phase 10: erweiterte Nährwert-Aufschlüsselung (Ballaststoffe/Zucker/
        // gesättigte Fettsäuren/Salz/Natrium) auf diary_entries und recipes,
        // damit Detailseite und Tagebuch dieselbe Tiefe wie Yazio zeigen können.
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN sugar REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN saturatedFat REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN salt REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN sodium REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recipes ADD COLUMN sugarPerServing REAL")
                db.execSQL("ALTER TABLE recipes ADD COLUMN saturatedFatPerServing REAL")
                db.execSQL("ALTER TABLE recipes ADD COLUMN saltPerServing REAL")
                db.execSQL("ALTER TABLE recipes ADD COLUMN sodiumPerServing REAL")
            }
        }

        // Portionsanzeige-Fix: recipeGrams speichert die vom Nutzer eingegebene
        // Gramm-Menge bei Rezept-Einträgen, damit das Tagebuch "180 g" statt einer
        // irreführenden Portionszahl anzeigen kann, wenn in Gramm erfasst wurde.
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN recipeGrams REAL")
            }
        }

        // Phase 11: direkte Makro-Korrektur ohne Zutaten-Umweg - globaler Override
        // der Endsumme (Kalorien/Protein/Kohlenhydrate/Fett/Ballaststoffe) je Eintrag,
        // unabhaengig von der bestehenden Zutaten-Korrektur.
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN isGloballyOverridden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN originalCalories REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN originalProtein REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN originalCarbs REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN originalFat REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN originalFiber REAL")
            }
        }

        // Phase 12: Naehrwertdaten-Qualitaet - calories/protein/carbs/fat auf food_items
        // werden nullable (fehlender Wert von OFF/BLV/USDA/Nutritionix ist "unbekannt",
        // nicht "0"). SQLite kennt kein ALTER COLUMN -> Tabelle neu anlegen + Daten kopieren
        // (gleiches Muster wie MIGRATION_8_9). Zusaetzlich: addedSugars-Spalte fuer
        // zugesetzten Zucker (aktuell nur zuverlaessig von USDA FDC geliefert).
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS food_items_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        brand TEXT,
                        barcode TEXT,
                        calories REAL,
                        protein REAL,
                        carbs REAL,
                        fat REAL,
                        servingSize REAL NOT NULL DEFAULT 100,
                        servingUnit TEXT NOT NULL DEFAULT 'g',
                        fiber REAL,
                        sugar REAL,
                        addedSugars REAL,
                        saturatedFat REAL,
                        monoFat REAL,
                        polyFat REAL,
                        transFat REAL,
                        salt REAL,
                        sodium REAL,
                        alcohol REAL,
                        cholesterol REAL,
                        water REAL,
                        vitaminA REAL,
                        vitaminB1 REAL,
                        vitaminB2 REAL,
                        vitaminB3 REAL,
                        vitaminB5 REAL,
                        vitaminB6 REAL,
                        vitaminB7 REAL,
                        vitaminB11 REAL,
                        vitaminB12 REAL,
                        vitaminC REAL,
                        vitaminD REAL,
                        vitaminE REAL,
                        vitaminK REAL,
                        potassium REAL,
                        calcium REAL,
                        iron REAL,
                        magnesium REAL,
                        zinc REAL,
                        phosphorus REAL,
                        copper REAL,
                        manganese REAL,
                        fluoride REAL,
                        iodine REAL,
                        selenium REAL,
                        chromium REAL,
                        molybdenum REAL,
                        chloride REAL,
                        choline REAL,
                        arsenic REAL,
                        boron REAL,
                        cobalt REAL,
                        rubidium REAL,
                        silicon REAL,
                        sulfur REAL,
                        tin REAL,
                        vanadium REAL,
                        source TEXT NOT NULL DEFAULT 'MANUAL',
                        completenessScore INTEGER NOT NULL DEFAULT 0,
                        timesUsed INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO food_items_new
                    SELECT id, name, brand, barcode, calories, protein, carbs, fat,
                        servingSize, servingUnit, fiber, sugar, NULL, saturatedFat,
                        monoFat, polyFat, transFat, salt, sodium, alcohol, cholesterol, water,
                        vitaminA, vitaminB1, vitaminB2, vitaminB3, vitaminB5, vitaminB6,
                        vitaminB7, vitaminB11, vitaminB12, vitaminC, vitaminD, vitaminE, vitaminK,
                        potassium, calcium, iron, magnesium, zinc, phosphorus, copper, manganese,
                        fluoride, iodine, selenium, chromium, molybdenum, chloride, choline,
                        arsenic, boron, cobalt, rubidium, silicon, sulfur, tin, vanadium,
                        source, completenessScore, timesUsed
                    FROM food_items
                """.trimIndent())
                db.execSQL("DROP TABLE food_items")
                db.execSQL("ALTER TABLE food_items_new RENAME TO food_items")

                // favorite_foods: gleiche Nullable-Korrektur fuer konsistente Semantik
                // ueber Favoriten hinweg (kann Werte aus einem FoodItem mit Luecken uebernehmen).
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_foods_new (
                        foodKey TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        brand TEXT,
                        caloriesPer100g REAL,
                        proteinPer100g REAL,
                        carbsPer100g REAL,
                        fatPer100g REAL,
                        fiberPer100g REAL,
                        addedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO favorite_foods_new
                    SELECT foodKey, name, brand, caloriesPer100g, proteinPer100g, carbsPer100g,
                        fatPer100g, fiberPer100g, addedAt
                    FROM favorite_foods
                """.trimIndent())
                db.execSQL("DROP TABLE favorite_foods")
                db.execSQL("ALTER TABLE favorite_foods_new RENAME TO favorite_foods")
            }
        }

        // Phase: vollständiger Yazio-Import. custom_foods bekommt sugar/salt (bisher aus
        // yazio_foods.json verworfen) und eine source-Spalte zur Herkunftskennzeichnung.
        // diary_entries bekommt matchedCustomFoodId/matchedRecipeId, damit importierte
        // Tagebuch-Zeilen mit bereits importierten Foods/Rezepten verknüpft werden können.
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_foods ADD COLUMN sugar REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE custom_foods ADD COLUMN salt REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE custom_foods ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN matchedCustomFoodId INTEGER")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN matchedRecipeId INTEGER")
            }
        }

        // 9-Feature-Patch: neue Tabellen für Feature 2 (globales Zutaten-Wörterbuch),
        // Feature 7 (tageszeit-bewusstes Favoriten-Ranking), Feature 5 (automatisch
        // erkannte wiederkehrende Mahlzeiten) sowie neue optionale Spalten auf user_profile
        // für Feature 3 (Ziel-Prognose).
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS global_ingredient_matches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        originalName TEXT NOT NULL,
                        offProductId TEXT NOT NULL,
                        offProductName TEXT NOT NULL,
                        kcalPer100g REAL NOT NULL,
                        proteinPer100g REAL NOT NULL,
                        carbsPer100g REAL NOT NULL,
                        fatPer100g REAL NOT NULL,
                        matchConfidence REAL NOT NULL DEFAULT 1.0,
                        usageCount INTEGER NOT NULL DEFAULT 1,
                        lastUsedAt INTEGER NOT NULL,
                        isVerifiedByUser INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_global_ingredient_matches_normalizedName ON global_ingredient_matches(normalizedName)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS food_usage_context (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        foodId TEXT NOT NULL,
                        foodName TEXT NOT NULL,
                        hourBucket INTEGER NOT NULL,
                        dayOfWeek INTEGER NOT NULL,
                        usageCount INTEGER NOT NULL DEFAULT 1,
                        lastUsedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_food_usage_context_foodId_hourBucket_dayOfWeek ON food_usage_context(foodId, hourBucket, dayOfWeek)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS detected_meal_patterns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        label TEXT NOT NULL,
                        mealType TEXT NOT NULL,
                        foodItemIds TEXT NOT NULL,
                        avgKcal REAL NOT NULL,
                        occurrences INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_detected_meal_patterns_fingerprint ON detected_meal_patterns(fingerprint)")

                db.execSQL("ALTER TABLE user_profile ADD COLUMN targetWeightKg REAL")
                db.execSQL("ALTER TABLE user_profile ADD COLUMN weeklyTargetLossKg REAL")
                db.execSQL("ALTER TABLE user_profile ADD COLUMN lastPrognosisDateStr TEXT")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN totalIngredientWeightG REAL")
                db.execSQL("ALTER TABLE recipes ADD COLUMN cookedWeightG REAL")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS manual_activity (
                        dateStr TEXT NOT NULL PRIMARY KEY,
                        activeCaloriesKcal REAL NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS supplements (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        brand TEXT NOT NULL DEFAULT '',
                        category TEXT NOT NULL DEFAULT 'SONSTIGES',
                        activeIngredients TEXT NOT NULL DEFAULT '',
                        servingSize TEXT NOT NULL DEFAULT '',
                        effects TEXT NOT NULL DEFAULT '',
                        pros TEXT NOT NULL DEFAULT '',
                        cons TEXT NOT NULL DEFAULT '',
                        dosageRecommendation TEXT NOT NULL DEFAULT '',
                        warnings TEXT NOT NULL DEFAULT '',
                        expiryDateStr TEXT,
                        status TEXT NOT NULL DEFAULT 'AKTIV',
                        conflictGroup TEXT NOT NULL DEFAULT 'NONE',
                        preferredTiming TEXT NOT NULL DEFAULT 'MORGENS',
                        requiresMedicalConfirmation INTEGER NOT NULL DEFAULT 0,
                        isSeedData INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recipes ADD COLUMN mealCategory TEXT NOT NULL DEFAULT ''")
            }
        }

        // Multi-Komponenten-Rezepte: Beilage und Sauce/Fleisch getrennt speichern
        // und beim Tracken unabhängig abwiegen (oder per Knopf gleichmässig aufteilen).
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recipe_components (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recipeId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        cookedWeightG REAL NOT NULL,
                        totalCalories REAL NOT NULL,
                        proteinG REAL NOT NULL DEFAULT 0,
                        carbsG REAL NOT NULL DEFAULT 0,
                        fatG REAL NOT NULL DEFAULT 0,
                        fiberG REAL NOT NULL DEFAULT 0,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(recipeId) REFERENCES recipes(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recipe_components_recipeId ON recipe_components(recipeId)"
                )
            }
        }

        // Gefrierschrank: eingefrorene Menüs mit Portions-Snapshot und Anzahl.
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS frozen_meals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recipeId INTEGER,
                        name TEXT NOT NULL,
                        quantity INTEGER NOT NULL DEFAULT 1,
                        frozenAt INTEGER NOT NULL,
                        notes TEXT NOT NULL DEFAULT '',
                        portionJson TEXT NOT NULL DEFAULT '[]'
                    )
                    """.trimIndent()
                )
            }
        }

        // Zutaten-Match: Beilage/Sauce-Zuordnung aus Verify-Sheet
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ingredient_matches ADD COLUMN componentGroup TEXT DEFAULT NULL"
                )
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
                        MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                        MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
                        MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
                        MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28,
                        MIGRATION_28_29
                    )
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
