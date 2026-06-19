package ch.nutrisnap.app.data.db

/**
 * Patch-Datei: Ergänzungen für NutriDatabase.kt
 *
 * 1. HealthConnectCache::class zu @Database entities-Array hinzufügen:
 *
 *    @Database(
 *        entities = [
 *            ...bestehende Entities...,
 *            HealthConnectCache::class   // <-- NEU
 *        ],
 *        version = 9   // <-- von 8 auf 9 erhöhen
 *    )
 *
 * 2. DAO-Methode ergänzen:
 *
 *    abstract fun healthConnectDao(): HealthConnectDao
 *
 * 3. Migration hinzufügen:
 *
 *    val MIGRATION_8_9 = object : Migration(8, 9) {
 *        override fun migrate(database: SupportSQLiteDatabase) {
 *            database.execSQL("""
 *                CREATE TABLE IF NOT EXISTS health_connect_cache (
 *                    date TEXT NOT NULL PRIMARY KEY,
 *                    steps INTEGER NOT NULL DEFAULT 0,
 *                    activeCaloriesKcal REAL NOT NULL DEFAULT 0.0,
 *                    weightKg REAL,
 *                    sleepMinutes INTEGER NOT NULL DEFAULT 0,
 *                    avgHeartRateBpm INTEGER,
 *                    lastUpdated INTEGER NOT NULL DEFAULT 0
 *                )
 *            """.trimIndent())
 *        }
 *    }
 *
 * 4. Migration registrieren in Room.databaseBuilder():
 *
 *    .addMigrations(
 *        ...bestehende Migrationen...,
 *        MIGRATION_8_9   // <-- NEU
 *    )
 */
