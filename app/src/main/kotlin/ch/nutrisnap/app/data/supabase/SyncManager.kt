package ch.nutrisnap.app.data.supabase

import android.util.Log
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.db.UserProfileEntity
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.WeightEntry
import ch.nutrisnap.app.data.repository.Sex
import kotlinx.coroutines.flow.first

/**
 * Pulls rows created on the web app (or another device) down into the local
 * Room database, and links any web-created rows back to a local_id so future
 * pushes from this device upsert instead of duplicating.
 *
 * Call [pullAll] once after login / on app start (and optionally periodically).
 */
object SyncManager {

    suspend fun pullAll(db: NutriDatabase) {
        SyncStatusHolder.opStarted()
        var firstError: String? = null
        runCatching { pullDiary(db) }.onFailure {
            Log.e("NutriSync", "Pull diary_entries fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pullRecipes(db) }.onFailure {
            Log.e("NutriSync", "Pull recipes fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pullWeight(db) }.onFailure {
            Log.e("NutriSync", "Pull weight_entries fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pullUserProfile(db) }.onFailure {
            Log.e("NutriSync", "Pull user_profiles fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pullCustomFoods(db) }.onFailure {
            Log.e("NutriSync", "Pull custom_foods fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        if (firstError != null) SyncStatusHolder.opFailed(firstError)
        else SyncStatusHolder.opSucceeded()
    }

    private suspend fun pullUserProfile(db: NutriDatabase) {
        val dao = db.userProfileDao()
        val remote = SupabaseSync.fetchUserProfile()
        if (remote == null) {
            val local = dao.get() ?: return
            SupabaseSync.upsertUserProfile(
                weightKg = local.weightKg,
                heightCm = local.heightCm,
                ageYears = local.ageYears,
                dailyCalorieGoal = local.dailyCalorieGoal,
                proteinGoalG = local.proteinGoalG,
                carbsGoalG = local.carbsGoalG,
                fatGoalG = local.fatGoalG,
                activityFactor = local.activityFactor,
                sex = local.sex
            )
            return
        }
        dao.upsert(
            UserProfileEntity(
                weightKg = remote.weightKg,
                heightCm = remote.heightCm,
                ageYears = remote.ageYears,
                dailyCalorieGoal = remote.dailyCalorieGoal,
                proteinGoalG = remote.proteinGoalG,
                carbsGoalG = remote.carbsGoalG,
                fatGoalG = remote.fatGoalG,
                activityFactor = remote.activityFactor,
                sex = runCatching { Sex.valueOf(remote.sex) }.getOrDefault(Sex.UNSPECIFIED).name
            )
        )
    }

    private suspend fun pullDiary(db: NutriDatabase) {
        val dao = db.diaryDao()
        val remoteRows = SupabaseSync.fetchDiaryEntries()
        for (row in remoteRows) {
            val mealType = runCatching { MealType.valueOf(row.mealType) }.getOrDefault(MealType.SNACK)
            if (row.localId != null) {
                if (dao.getById(row.localId) == null) {
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
                            fat = row.fat
                        )
                    )
                }
            } else {
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
                if (row.id != null) SupabaseSync.linkDiaryLocalId(row.id, newId)
            }
        }
    }

    private suspend fun pullRecipes(db: NutriDatabase) {
        val dao = db.recipeDao()
        val remoteRows = SupabaseSync.fetchRecipes()
        for (row in remoteRows) {
            if (row.localId != null) {
                if (dao.getById(row.localId) == null) {
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
                            savedAt = row.savedAt
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
                if (row.id != null) SupabaseSync.linkRecipeLocalId(row.id, newId)
            }
        }
    }

    private suspend fun pullWeight(db: NutriDatabase) {
        val dao = db.weightDao()
        val remoteRows = SupabaseSync.fetchWeightEntries()
        for (row in remoteRows) {
            val existing = dao.getByDate(row.dateStr)
            if (existing == null || existing.weightKg != row.weightKg) {
                dao.upsert(WeightEntry(dateStr = row.dateStr, weightKg = row.weightKg))
            }
        }
    }

    /**
     * Zieht eigene Lebensmittel von Supabase herunter (custom_foods).
     * - Bereits verlinkte Eintraege (local_id != null): nur einfuegen falls lokal fehlt.
     * - Unverlinkte Eintraege (von Web-App): lokal anlegen, dann local_id zurueckschreiben.
     * Bestehende lokale Eintraege werden NICHT ueberschrieben (lokal = Quelle der Wahrheit).
     */
    private suspend fun pullCustomFoods(db: NutriDatabase) {
        val dao = db.customFoodDao()
        val remoteRows = SupabaseSync.fetchCustomFoods()

        for (row in remoteRows) {
            if (row.localId != null) {
                val existing = dao.getById(row.localId.toInt())
                if (existing == null) {
                    dao.insert(
                        CustomFoodItem(
                            id = row.localId.toInt(),
                            name = row.name,
                            calories = row.calories,
                            protein = row.protein,
                            carbs = row.carbs,
                            fat = row.fat,
                            fiber = row.fiber,
                            barcode = row.barcode,
                            brand = row.brand,
                            category = row.category,
                            portionSizeG = row.portionSizeG,
                            createdAt = row.createdAt
                        )
                    )
                }
            } else {
                // Web-App hat dieses Lebensmittel angelegt — lokal einfuegen
                val newId = dao.insert(
                    CustomFoodItem(
                        name = row.name,
                        calories = row.calories,
                        protein = row.protein,
                        carbs = row.carbs,
                        fat = row.fat,
                        fiber = row.fiber,
                        barcode = row.barcode,
                        brand = row.brand,
                        category = row.category,
                        portionSizeG = row.portionSizeG,
                        createdAt = row.createdAt
                    )
                )
                // local_id zurueckschreiben damit naechster Sync upserted statt dupliziert
                if (row.id != null) SupabaseSync.linkCustomFoodLocalId(row.id, newId.toInt())
            }
        }
    }
}
