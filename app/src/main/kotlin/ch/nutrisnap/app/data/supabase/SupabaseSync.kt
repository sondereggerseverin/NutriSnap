package ch.nutrisnap.app.data.supabase

import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.WeightEntry
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── DTOs (match Supabase table columns) ─────────────────────────────────────

@Serializable
data class DiaryEntryDto(
    val id: Long? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("food_name") val foodName: String,
    @SerialName("amount_grams") val amountGrams: Float,
    @SerialName("meal_type") val mealType: String,
    @SerialName("date_str") val dateStr: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    @SerialName("local_id") val localId: Long? = null
)

@Serializable
data class RecipeDto(
    val id: Long? = null,
    @SerialName("user_id") val userId: String? = null,
    val title: String,
    val description: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    val platform: String? = null,
    val ingredients: String = "",
    val instructions: String = "",
    @SerialName("total_calories") val totalCalories: Float? = null,
    @SerialName("protein_per_serving") val proteinPerServing: Float? = null,
    @SerialName("carbs_per_serving") val carbsPerServing: Float? = null,
    @SerialName("fat_per_serving") val fatPerServing: Float? = null,
    val servings: Int = 1,
    @SerialName("prep_time_minutes") val prepTimeMinutes: Int? = null,
    val tags: String = "",
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("saved_at") val savedAt: Long = System.currentTimeMillis(),
    @SerialName("local_id") val localId: Long? = null
)

@Serializable
data class WeightEntryDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("date_str") val dateStr: String,
    @SerialName("weight_kg") val weightKg: Float
)

// Ein Profil pro Nutzer (kein local_id-Linking nötig, da 1:1 über user_id).
// NOTE: braucht UNIQUE (user_id) auf der user_profiles-Tabelle in Supabase, sonst
// schlägt der onConflict-Upsert fehl.
@Serializable
data class UserProfileDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("weight_kg") val weightKg: Float = 0f,
    @SerialName("height_cm") val heightCm: Int = 0,
    @SerialName("age_years") val ageYears: Int = 0,
    @SerialName("daily_calorie_goal") val dailyCalorieGoal: Int = 2000,
    @SerialName("protein_goal_g") val proteinGoalG: Float = 120f,
    @SerialName("carbs_goal_g") val carbsGoalG: Float = 220f,
    @SerialName("fat_goal_g") val fatGoalG: Float = 65f,
    @SerialName("activity_factor") val activityFactor: Float = 1.55f,
    val sex: String = "UNSPECIFIED",
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// Aktivkalorien pro Tag (Health Connect / Samsung Health Tier 0), fuer die Web-App:
// erlaubt den Sport-Bonus im adaptiven Kalorienziel auch dort zu berechnen.
// weightKg = Health-Connect-Cache-Gewicht (z.B. Waage via Samsung Health) - das ist die
// echte Gewichtsquelle des adaptiven Trend-Algorithmus, NICHT die manuelle Eingabe aus
// weight_entries. Ohne dieses Feld hat die Web-App nie eine Gewichtsquelle fuers Trend-TDEE.
@Serializable
data class HealthDailyDto(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("date_str") val dateStr: String,
    @SerialName("active_calories_kcal") val activeCaloriesKcal: Double? = null,
    val steps: Long? = null,
    @SerialName("weight_kg") val weightKg: Double? = null
)

// ─── Sync functions ───────────────────────────────────────────────────────────
// NOTE: upsert onConflict requires matching UNIQUE constraints in Supabase:
//   diary_entries: UNIQUE (user_id, local_id)
//   recipes:       UNIQUE (user_id, local_id)
//   weight_entries: UNIQUE (user_id, date_str)   [already used by the web app]
//   user_profiles:  UNIQUE (user_id)

object SupabaseSync {

    private val sb get() = SupabaseClient.client
    private fun userId() = sb.auth.currentUserOrNull()?.id

    // ── Diary ──────────────────────────────────────────────────────────────
    suspend fun upsertDiaryEntry(entry: DiaryEntry) {
        val uid = userId() ?: return
        sb.postgrest["diary_entries"].upsert(
            DiaryEntryDto(
                userId = uid,
                foodName = entry.foodName,
                amountGrams = entry.amountGrams,
                mealType = entry.mealType.name,
                dateStr = entry.dateStr,
                calories = entry.calories,
                protein = entry.protein,
                carbs = entry.carbs,
                fat = entry.fat,
                localId = entry.id
            )
        ) {
            onConflict = "user_id,local_id"
        }
    }

    suspend fun deleteDiaryEntry(localId: Long) {
        val uid = userId() ?: return
        sb.postgrest["diary_entries"].delete {
            filter {
                eq("user_id", uid)
                eq("local_id", localId)
            }
        }
    }

    /** Fetch all remote diary entries for the current user. */
    suspend fun fetchDiaryEntries(): List<DiaryEntryDto> {
        val uid = userId() ?: return emptyList()
        return sb.postgrest["diary_entries"].select {
            filter { eq("user_id", uid) }
        }.decodeList()
    }

    // ── Recipes ────────────────────────────────────────────────────────────
    suspend fun upsertRecipe(recipe: Recipe) {
        val uid = userId() ?: return
        sb.postgrest["recipes"].upsert(
            RecipeDto(
                userId = uid,
                title = recipe.title,
                description = recipe.description,
                imageUrl = recipe.imageUrl,
                sourceUrl = recipe.sourceUrl,
                platform = recipe.platform,
                ingredients = recipe.ingredients,
                instructions = recipe.instructions,
                totalCalories = recipe.totalCalories,
                proteinPerServing = recipe.proteinPerServing,
                carbsPerServing = recipe.carbsPerServing,
                fatPerServing = recipe.fatPerServing,
                servings = recipe.servings,
                prepTimeMinutes = recipe.prepTimeMinutes,
                tags = recipe.tags,
                isFavorite = recipe.isFavorite,
                savedAt = recipe.savedAt,
                localId = recipe.id
            )
        ) {
            onConflict = "user_id,local_id"
        }
    }

    suspend fun deleteRecipe(localId: Long) {
        val uid = userId() ?: return
        sb.postgrest["recipes"].delete {
            filter {
                eq("user_id", uid)
                eq("local_id", localId)
            }
        }
    }

    /** Fetch all remote recipes for the current user. */
    suspend fun fetchRecipes(): List<RecipeDto> {
        val uid = userId() ?: return emptyList()
        return sb.postgrest["recipes"].select {
            filter { eq("user_id", uid) }
        }.decodeList()
    }

    // ── Weight ─────────────────────────────────────────────────────────────
    suspend fun upsertWeight(entry: WeightEntry) {
        val uid = userId() ?: return
        sb.postgrest["weight_entries"].upsert(
            WeightEntryDto(userId = uid, dateStr = entry.dateStr, weightKg = entry.weightKg)
        ) {
            onConflict = "user_id,date_str"
        }
    }

    suspend fun deleteWeight(dateStr: String) {
        val uid = userId() ?: return
        sb.postgrest["weight_entries"].delete {
            filter {
                eq("user_id", uid)
                eq("date_str", dateStr)
            }
        }
    }

    /** Fetch all remote weight entries for the current user. */
    suspend fun fetchWeightEntries(): List<WeightEntryDto> {
        val uid = userId() ?: return emptyList()
        return sb.postgrest["weight_entries"].select {
            filter { eq("user_id", uid) }
        }.decodeList()
    }

    // ── User Profile (1 Zeile pro Nutzer) ────────────────────────────────────
    // "Last write wins" über updatedAt: erlaubt der Web-App (NutriSnap-Web), das
    // Profil ebenfalls zu bearbeiten, ohne dass ein älterer Android-Stand ein
    // frischeres Web-Update beim nächsten Sync wieder überschreibt.
    suspend fun upsertUserProfile(
        weightKg: Float, heightCm: Int, ageYears: Int,
        dailyCalorieGoal: Int, proteinGoalG: Float, carbsGoalG: Float, fatGoalG: Float,
        activityFactor: Float, sex: String
    ) {
        val uid = userId() ?: return
        sb.postgrest["user_profiles"].upsert(
            UserProfileDto(
                userId = uid, weightKg = weightKg, heightCm = heightCm, ageYears = ageYears,
                dailyCalorieGoal = dailyCalorieGoal, proteinGoalG = proteinGoalG,
                carbsGoalG = carbsGoalG, fatGoalG = fatGoalG,
                activityFactor = activityFactor, sex = sex
            )
        ) {
            onConflict = "user_id"
        }
    }

    /** Fetch the remote profile row for the current user, if any. */
    suspend fun fetchUserProfile(): UserProfileDto? {
        val uid = userId() ?: return null
        return sb.postgrest["user_profiles"].select {
            filter { eq("user_id", uid) }
        }.decodeSingleOrNull()
    }

    // ── Health (Aktivkalorien / Schritte pro Tag) ────────────────────────────
    // NOTE: onConflict requires UNIQUE (user_id, date_str) on the health_daily table.
    // Android ist die alleinige Quelle (Health Connect / Samsung Health SDK) — es wird
    // nur gepusht, nie von hier zurueckgelesen.
    suspend fun upsertHealthDaily(
        dateStr: String,
        activeCaloriesKcal: Double?,
        steps: Long?,
        weightKg: Double? = null
    ) {
        val uid = userId() ?: return
        sb.postgrest["health_daily"].upsert(
            HealthDailyDto(
                userId = uid, dateStr = dateStr,
                activeCaloriesKcal = activeCaloriesKcal, steps = steps, weightKg = weightKg
            )
        ) {
            onConflict = "user_id,date_str"
        }
    }
}
