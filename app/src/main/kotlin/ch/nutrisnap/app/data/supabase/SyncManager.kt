package ch.nutrisnap.app.data.supabase

import android.util.Log
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.WeightEntry

/**
 * Pulls rows created on the web app (or another device) down into the local
 * Room database, and links any web-created rows back to a local_id so future
 * pushes from this device upsert instead of duplicating.
 *
 * Call [pullAll] once after login / on app start (and optionally periodically).
 */
object SyncManager {

    suspend fun pullAll(db: NutriDatabase) {
        runCatching { pullDiary(db) }.onFailure { Log.e("NutriSync", "Pull diary_entries fehlgeschlagen: ${it.message}", it) }
        runCatching { pullRecipes(db) }.onFailure { Log.e("NutriSync", "Pull recipes fehlgeschlagen: ${it.message}", it) }
        runCatching { pullWeight(db) }.onFailure { Log.e("NutriSync", "Pull weight_entries fehlgeschlagen: ${it.message}", it) }
        runCatching { pushPendingChanges(db) }.onFailure { Log.e("NutriSync", "Push pending fehlgeschlagen: ${it.message}", it) }
    }

    /**
     * Retry-Mechanismus fuer den Fall, dass ein Push beim Anlegen/Bearbeiten
     * (Repositories.kt -> pushSafely) fehlgeschlagen oder gar nicht erst
     * losgelaufen ist (App wurde vorher gekillt, kein Netz, abgelaufenes
     * Auth-Token). Alle lokal noch nicht als synced markierten Zeilen werden
     * hier erneut hochgeladen. Wird bei jedem App-Resume aufgerufen (siehe
     * MainActivity), zusaetzlich zu pullAll.
     */
    suspend fun pushPendingChanges(db: NutriDatabase) {
        val diaryDao = db.diaryDao()
        for (entry in diaryDao.getUnsynced()) {
            runCatching {
                SupabaseSync.upsertDiaryEntry(entry)
                diaryDao.markSynced(entry.id)
            }.onFailure { Log.e("NutriSync", "Retry-Push diary_entry ${entry.id} fehlgeschlagen: ${it.message}", it) }
        }

        val recipeDao = db.recipeDao()
        for (recipe in recipeDao.getUnsynced()) {
            runCatching {
                SupabaseSync.upsertRecipe(recipe)
                recipeDao.markSynced(recipe.id)
            }.onFailure { Log.e("NutriSync", "Retry-Push recipe ${recipe.id} fehlgeschlagen: ${it.message}", it) }
        }
    }

    private suspend fun pullDiary(db: NutriDatabase) {
        val dao = db.diaryDao()
        val remoteRows = SupabaseSync.fetchDiaryEntries()
        for (row in remoteRows) {
            val mealType = runCatching { MealType.valueOf(row.mealType) }.getOrDefault(MealType.SNACK)

            if (row.localId != null) {
                // Row already linked to a local row — make sure it exists locally.
                val existing = dao.getById(row.localId)
                if (existing == null) {
                    // Was deleted locally or never existed (e.g. fresh install) — recreate it.
                    // synced = true: die Zeile existiert bereits so auf Supabase.
                    dao.insert(
                        DiaryEntry(
                            id = row.localId,
                            foodItemId = -999,
                            foodName = row.foodName,
                            amountGrams = row.amountGrams,
                            mealType = mealType,
                            dateStr = row.dateStr,
                            calories = row.calories,
                            protein = row.protein,
                            carbs = row.carbs,
                            fat = row.fat,
                            synced = true
                        )
                    )
                }
            } else {
                // Web-created row with no local_id yet — insert locally, then link back.
                val newId = dao.insert(
                    DiaryEntry(
                        foodItemId = -999,
                        foodName = row.foodName,
                        amountGrams = row.amountGrams,
                        mealType = mealType,
                        dateStr = row.dateStr,
                        calories = row.calories,
                        protein = row.protein,
                        carbs = row.carbs,
                        fat = row.fat
                    )
                )
                val linked = dao.getById(newId)
                if (linked != null) {
                    SupabaseSync.upsertDiaryEntry(linked)
                    dao.markSynced(newId)
                }
            }
        }
    }

    private suspend fun pullRecipes(db: NutriDatabase) {
        val dao = db.recipeDao()
        val remoteRows = SupabaseSync.fetchRecipes()
        for (row in remoteRows) {
            if (row.localId != null) {
                val existing = dao.getById(row.localId)
                if (existing == null) {
                    dao.insert(
                        Recipe(
                            id = row.localId,
                            title = row.title,
                            description = row.description,
                            imageUrl = row.imageUrl,
                            sourceUrl = row.sourceUrl,
                            platform = row.platform,
                            ingredients = row.ingredients,
                            instructions = row.instructions,
                            totalCalories = row.totalCalories,
                            proteinPerServing = row.proteinPerServing,
                            carbsPerServing = row.carbsPerServing,
                            fatPerServing = row.fatPerServing,
                            servings = row.servings,
                            prepTimeMinutes = row.prepTimeMinutes,
                            tags = row.tags,
                            isFavorite = row.isFavorite,
                            savedAt = row.savedAt,
                            synced = true
                        )
                    )
                }
            } else {
                val newId = dao.insert(
                    Recipe(
                        title = row.title,
                        description = row.description,
                        imageUrl = row.imageUrl,
                        sourceUrl = row.sourceUrl,
                        platform = row.platform,
                        ingredients = row.ingredients,
                        instructions = row.instructions,
                        totalCalories = row.totalCalories,
                        proteinPerServing = row.proteinPerServing,
                        carbsPerServing = row.carbsPerServing,
                        fatPerServing = row.fatPerServing,
                        servings = row.servings,
                        prepTimeMinutes = row.prepTimeMinutes,
                        tags = row.tags,
                        isFavorite = row.isFavorite,
                        savedAt = row.savedAt
                    )
                )
                val linked = dao.getById(newId)
                if (linked != null) {
                    SupabaseSync.upsertRecipe(linked)
                    dao.markSynced(newId)
                }
            }
        }
    }

    private suspend fun pullWeight(db: NutriDatabase) {
        val dao = db.weightDao()
        val remoteRows = SupabaseSync.fetchWeightEntries()
        for (row in remoteRows) {
            // weight_entries uses dateStr as the primary key, so a plain upsert
            // (REPLACE on conflict) is enough — no local_id linking needed.
            val existing = dao.getByDate(row.dateStr)
            if (existing == null || existing.weightKg != row.weightKg) {
                dao.upsert(WeightEntry(dateStr = row.dateStr, weightKg = row.weightKg))
            }
        }
    }
}
