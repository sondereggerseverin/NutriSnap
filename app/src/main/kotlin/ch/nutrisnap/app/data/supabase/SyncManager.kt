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

/**
 * Pulls rows created on the web app (or another device) down into the local
 * Room database, and links any web-created rows back to a local_id so future
 * pushes from this device upsert instead of duplicating.
 *
 * Call [pullAll] once after login / on app start (and optionally periodically).
 */
object SyncManager {

    /**
     * Pushed ALLE lokalen Daten in die Cloud (Tagebuch, Rezepte, Custom-Foods, Gewicht, Profil).
     * Wird nach Login / Resume aufgerufen, damit selbst getrackte Einträge nicht nur lokal bleiben
     * und nach Reinstall wieder per Pull verfügbar sind.
     */
    suspend fun pushAllLocal(db: NutriDatabase) {
        SyncStatusHolder.opStarted()
        var firstError: String? = null
        runCatching { pushDiary(db) }.onFailure {
            Log.e("NutriSync", "Push diary_entries fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pushRecipes(db) }.onFailure {
            Log.e("NutriSync", "Push recipes fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pushCustomFoods(db) }.onFailure {
            Log.e("NutriSync", "Push custom_foods fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pushWeight(db) }.onFailure {
            Log.e("NutriSync", "Push weight_entries fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching { pushUserProfile(db) }.onFailure {
            Log.e("NutriSync", "Push user_profiles fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        if (firstError != null) SyncStatusHolder.opFailed(firstError)
        else SyncStatusHolder.opSucceeded()
    }

    /** Push lokaler Änderungen, danach Pull von anderen Geräten. */
    suspend fun syncAll(db: NutriDatabase) {
        pushAllLocal(db)
        pullAll(db)
    }

    private suspend fun pushDiary(db: NutriDatabase) {
        val entries = db.diaryDao().getAllOnce()
        Log.i("NutriSync", "Push diary: ${entries.size} Einträge")
        for (entry in entries) {
            SupabaseSync.upsertDiaryEntry(entry)
        }
    }

    private suspend fun pushRecipes(db: NutriDatabase) {
        val recipes = db.recipeDao().getAllOnce()
        Log.i("NutriSync", "Push recipes: ${recipes.size}")
        for (r in recipes) {
            SupabaseSync.upsertRecipe(r)
        }
    }

    private suspend fun pushCustomFoods(db: NutriDatabase) {
        val foods = db.customFoodDao().getAllOnce()
        Log.i("NutriSync", "Push custom_foods: ${foods.size}")
        for (f in foods) {
            SupabaseSync.upsertCustomFood(f)
        }
    }

    private suspend fun pushWeight(db: NutriDatabase) {
        val entries = db.weightDao().getAllOnce()
        for (e in entries) {
            SupabaseSync.upsertWeight(e)
        }
    }

    private suspend fun pushUserProfile(db: NutriDatabase) {
        val local = db.userProfileDao().get() ?: return
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
    }

    suspend fun pullAll(db: NutriDatabase) {
        SyncStatusHolder.opStarted()
        var firstError: String? = null
        runCatching { pullDiary(db) }.onFailure {
            Log.e("NutriSync", "Pull diary_entries fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching {
            val n = ch.nutrisnap.app.data.repository.DiaryRepository(db).deduplicateEntries()
            if (n > 0) Log.i("NutriSync", "Diary-Dedup nach Pull: $n entfernt")
        }.onFailure {
            Log.e("NutriSync", "Diary-Dedup fehlgeschlagen: ${it.message}", it)
        }
        runCatching { pullRecipes(db) }.onFailure {
            Log.e("NutriSync", "Pull recipes fehlgeschlagen: ${it.message}", it)
            firstError = firstError ?: it.message
        }
        runCatching {
            val n = deduplicateRecipesLocal(db)
            if (n > 0) Log.i("NutriSync", "Rezept-Dedup nach Pull: $n entfernt")
        }.onFailure {
            Log.e("NutriSync", "Rezept-Dedup fehlgeschlagen: ${it.message}", it)
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

    /**
     * user_profiles ist eine Singleton-Zeile pro Nutzer, kein local_id-Linking nötig.
     * NOTE: aktuell "last pull wins" — es gibt noch keinen updatedAt-Vergleich, weil das
     * eine Room-Migration bräuchte. Für Severins Ein-Geräte-Nutzung (Android ist primär,
     * Web nur gelegentlich fürs Profil) unkritisch; sollte die Web-App aktiver werden,
     * wäre ein updatedAt-Feld auf UserProfileEntity der nächste Schritt.
     */
    private suspend fun pullUserProfile(db: NutriDatabase) {
        val dao = db.userProfileDao()
        val remote = SupabaseSync.fetchUserProfile()
        if (remote == null) {
            // Kein Remote-Profil (erster Sync für diesen Nutzer) - lokalen Stand hochladen.
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
        // applianceModel gibt es (noch) nicht in Supabase (UserProfileDto hat kein Feld dafür) -
        // ohne diesen Erhalt würde jeder Remote-Pull das lokal hinterlegte Gerät auf "" zurücksetzen.
        // Ist ohnehin geräte-/küchenspezifisch, macht als Cloud-Sync-Feld wenig Sinn.
        val localApplianceModel = dao.get()?.applianceModel ?: ""
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
                sex = runCatching { Sex.valueOf(remote.sex) }.getOrDefault(Sex.UNSPECIFIED).name,
                applianceModel = localApplianceModel
            )
        )
    }

    private suspend fun pullDiary(db: NutriDatabase) {
        val dao = db.diaryDao()
        val remoteRows = SupabaseSync.fetchDiaryEntries()
        // Inhaltliche Fingerprints bereits lokal vorhandener Einträge — verhindert
        // dass Sync-Pull bei fehlendem/fehlerhaftem local_id immer neue Kopien anlegt.
        val localFingerprints = dao.getAllOnce()
            .map { ch.nutrisnap.app.data.repository.DiaryRepository.contentFingerprint(it) }
            .toMutableSet()

        for (row in remoteRows) {
            val mealType = runCatching { MealType.valueOf(row.mealType) }.getOrDefault(MealType.SNACK)
            val fingerprint = listOf(
                row.dateStr,
                mealType.name,
                row.foodName.trim().lowercase(),
                "%.1f".format(row.amountGrams),
                "%.0f".format(row.calories)
            ).joinToString("|")

            if (row.localId != null) {
                val existing = dao.getById(row.localId)
                if (existing == null) {
                    // Nur anlegen, wenn derselbe Inhalt lokal noch nicht existiert
                    if (fingerprint in localFingerprints) continue
                    dao.insert(
                        DiaryEntry(
                            id = row.localId,
                            foodItemId = ch.nutrisnap.app.data.model.MANUAL_FOOD_ITEM_ID,
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
                    localFingerprints.add(fingerprint)
                }
            } else {
                // Web-Zeile ohne local_id: nicht erneut einfügen, wenn Inhalt schon lokal da ist
                if (fingerprint in localFingerprints) {
                    // Optional: bestehenden lokalen Eintrag zurückverlinken, falls möglich
                    continue
                }
                val newId = dao.insert(
                    DiaryEntry(
                        foodItemId = ch.nutrisnap.app.data.model.MANUAL_FOOD_ITEM_ID,
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
                localFingerprints.add(fingerprint)
                if (row.id != null) SupabaseSync.linkDiaryLocalId(row.id, newId)
            }
        }
    }

    private suspend fun pullRecipes(db: NutriDatabase) {
        val dao = db.recipeDao()
        val remoteRows = SupabaseSync.fetchRecipes()
        // Fingerprints bereits vorhandener lokaler Rezepte — analog Diary-Pull.
        val localFingerprints = dao.getAllOnce()
            .map { ch.nutrisnap.app.data.repository.RecipeRepository.contentFingerprint(it) }
            .toMutableSet()
        // localId → schon gesehen (verhindert Doppel-Insert bei kaputten Remote-Daten)
        val seenLocalIds = mutableSetOf<Long>()

        for (row in remoteRows) {
            val fp = ch.nutrisnap.app.data.repository.RecipeRepository.contentFingerprint(
                title = row.title,
                sourceUrl = row.sourceUrl,
                ingredients = row.ingredients,
                totalCalories = row.totalCalories,
                servings = row.servings
            )
            val recipeFromRow = Recipe(
                id = row.localId ?: 0L,
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

            if (row.localId != null) {
                if (row.localId in seenLocalIds) continue
                seenLocalIds.add(row.localId)
                val existing = dao.getById(row.localId)
                if (existing != null) {
                    localFingerprints.add(fp)
                    continue
                }
                // Inhalt schon unter anderer ID vorhanden → nur verlinken, nicht nochmal inserten
                if (fp in localFingerprints) {
                    if (row.id != null) SupabaseSync.linkRecipeLocalId(row.id, findLocalIdByFingerprint(dao, fp) ?: row.localId)
                    continue
                }
                dao.insert(recipeFromRow.copy(id = row.localId))
                localFingerprints.add(fp)
            } else {
                // Web-Zeile ohne local_id: nicht erneut einfügen, wenn Inhalt schon lokal da ist
                if (fp in localFingerprints) {
                    val localId = findLocalIdByFingerprint(dao, fp)
                    if (row.id != null && localId != null) {
                        SupabaseSync.linkRecipeLocalId(row.id, localId)
                    }
                    continue
                }
                val newId = dao.insert(recipeFromRow.copy(id = 0))
                localFingerprints.add(fp)
                if (row.id != null) SupabaseSync.linkRecipeLocalId(row.id, newId)
            }
        }
    }

    private suspend fun findLocalIdByFingerprint(
        dao: ch.nutrisnap.app.data.db.RecipeDao,
        fp: String
    ): Long? = dao.getAllOnce()
        .firstOrNull { ch.nutrisnap.app.data.repository.RecipeRepository.contentFingerprint(it) == fp }
        ?.id

    /**
     * Entfernt lokale Rezept-Duplikate (gleicher Inhalts-Fingerprint).
     * Behält die kleinste id, löscht den Rest inkl. Remote-Delete.
     */
    private suspend fun deduplicateRecipesLocal(db: NutriDatabase): Int {
        val dao = db.recipeDao()
        val all = dao.getAllOnce()
        val keep = linkedMapOf<String, Recipe>()
        val toDelete = mutableListOf<Recipe>()
        for (r in all.sortedBy { it.id }) {
            val key = ch.nutrisnap.app.data.repository.RecipeRepository.contentFingerprint(r)
            if (key in keep) toDelete.add(r) else keep[key] = r
        }
        for (r in toDelete) {
            dao.delete(r)
            runCatching { SupabaseSync.deleteRecipe(r.id) }
        }
        return toDelete.size
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
                if (row.id != null) SupabaseSync.linkCustomFoodLocalId(row.id, newId.toInt())
            }
        }
    }
}
