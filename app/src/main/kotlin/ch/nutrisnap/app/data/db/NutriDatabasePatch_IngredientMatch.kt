package ch.nutrisnap.app.data.db

/**
 * NutriDatabase Patch — Ingredient Matches
 *
 * Füge folgendes in deine NutriDatabase.kt ein:
 *
 * 1. In @Database entities-Liste hinzufügen:
 *    IngredientMatch::class
 *
 * 2. version auf nächste Zahl erhöhen (z.B. 5)
 *
 * 3. Migration hinzufügen:
 *    val MIGRATION_4_5 = Migration(4, 5) {
 *        it.execSQL("""
 *            CREATE TABLE IF NOT EXISTS ingredient_matches (
 *                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *                recipeId INTEGER NOT NULL,
 *                ingredientRaw TEXT NOT NULL,
 *                ingredientName TEXT NOT NULL,
 *                amountGrams REAL NOT NULL DEFAULT 0,
 *                matchedFoodItemId INTEGER,
 *                matchedFoodName TEXT,
 *                matchedCalories REAL,
 *                matchedProtein REAL,
 *                matchedCarbs REAL,
 *                matchedFat REAL,
 *                matchSource TEXT NOT NULL DEFAULT 'UNMATCHED'
 *            )
 *        """.trimIndent())
 *    }
 *
 * 4. In .addMigrations(...) eintragen:
 *    .addMigrations(MIGRATION_4_5)
 *
 * 5. abstract fun ingredientMatchDao(): IngredientMatchDao  hinzufügen
 */
