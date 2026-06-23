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

// ─── Sync functions ───────────────────────────────────────────────────────────

object SupabaseSync {

    private val sb get() = SupabaseClient.client
    private fun userId() = sb.auth.currentUserOrNull()?.id

    // Diary
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
        )
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

    // Recipes
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
        )
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

    // Weight
    suspend fun upsertWeight(entry: WeightEntry) {
        val uid = userId() ?: return
        sb.postgrest["weight_entries"].upsert(
            WeightEntryDto(userId = uid, dateStr = entry.dateStr, weightKg = entry.weightKg)
        )
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
}
