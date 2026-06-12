package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate

// ─── Food / Nutrition ────────────────────────────────────────────────────────

@Entity(tableName = "food_items")
data class FoodItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val caloriesPer100g: Float,
    val proteinPer100g: Float,
    val carbsPer100g: Float,
    val fatPer100g: Float,
    val fiberPer100g: Float = 0f,
    val isCustom: Boolean = true
)

@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodItemId: Long,
    val foodName: String,           // denormalised for fast display
    val amountGrams: Float,
    val mealType: MealType,
    val dateStr: String,            // ISO "2025-06-09"
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
)

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

// ─── User Profile ─────────────────────────────────────────────────────────────

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val dailyCalorieGoal: Int = 2000,
    val proteinGoalG: Float = 120f,
    val carbsGoalG: Float = 250f,
    val fatGoalG: Float = 65f,
    val weightKg: Float? = null,
    val heightCm: Float? = null,
    val age: Int? = null,
    /** "male" or "female" */
    val sex: String? = null,
    /** 1.2 sedentary … 1.9 very active */
    val activityFactor: Float = 1.55f,
    val darkMode: Boolean = false
) {
    /** Mifflin-St Jeor TDEE – returns null if profile is incomplete */
    fun computedTdee(): Int? {
        val w = weightKg ?: return null
        val h = heightCm ?: return null
        val a = age ?: return null
        val s = sex ?: return null
        val bmr = if (s == "male")
            10 * w + 6.25 * h - 5 * a + 5
        else
            10 * w + 6.25 * h - 5 * a - 161
        return (bmr * activityFactor).toInt()
    }
}

// ─── Recipes ─────────────────────────────────────────────────────────────────

@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val sourceUrl: String? = null,        // original Instagram / web URL
    val platform: String? = null,         // "instagram", "web", "manual"
    val ingredients: String = "",         // newline-separated
    val instructions: String = "",
    val totalCalories: Float? = null,
    val servings: Int = 1,
    val prepTimeMinutes: Int? = null,
    val tags: String = "",                // comma-separated
    val savedAt: Long = System.currentTimeMillis()
)

// ─── OpenFoodFacts API (for barcode / name search) ────────────────────────────

@Serializable
data class OFFSearchResponse(
    val count: Int = 0,
    val products: List<OFFProduct> = emptyList()
)

@Serializable
data class OFFProduct(
    val product_name: String? = null,
    val brands: String? = null,
    val nutriments: OFFNutriments? = null,
    val image_url: String? = null
)

@Serializable
data class OFFNutriments(
    val energy_kcal_100g: Float? = null,
    val proteins_100g: Float? = null,
    val carbohydrates_100g: Float? = null,
    val fat_100g: Float? = null,
    val fiber_100g: Float? = null
)

// ─── UI helpers ───────────────────────────────────────────────────────────────

data class DailyNutrition(
    val date: LocalDate,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val goalCalories: Float = 2000f
) {
    val progress get() = (calories / goalCalories).coerceIn(0f, 1f)
}

data class RecipeScrapeResult(
    val success: Boolean,
    val recipe: Recipe? = null,
    val error: String? = null
)
