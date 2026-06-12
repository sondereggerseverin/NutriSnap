package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate

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
    val foodName: String,
    val amountGrams: Float,
    val mealType: MealType,
    val dateStr: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float
)

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

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
    val sex: String? = null,
    val activityFactor: Float = 1.55f,
    val darkMode: Boolean = false
) {
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

@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val platform: String? = null,
    val ingredients: String = "",
    val instructions: String = "",
    val totalCalories: Float? = null,
    val servings: Int = 1,
    val prepTimeMinutes: Int? = null,
    val tags: String = "",
    val savedAt: Long = System.currentTimeMillis()
)

@Serializable
data class OFFSearchResponse(
    val count: Int = 0,
    val products: List<OFFProduct> = emptyList()
)

@Serializable
data class SingleProductResponse(
    val status: Int = 0,
    val product: OFFProduct? = null
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
    @kotlinx.serialization.SerialName("energy-kcal_100g")
    val kcalPer100g: Float? = null,
    @kotlinx.serialization.SerialName("proteins_100g")
    val proteins100g: Float? = null,
    @kotlinx.serialization.SerialName("carbohydrates_100g")
    val carbs100g: Float? = null,
    @kotlinx.serialization.SerialName("fat_100g")
    val fat100g: Float? = null,
    @kotlinx.serialization.SerialName("fiber_100g")
    val fiber100g: Float? = null
)

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
    val error: String? = null,
    val instagramBlocked: Boolean = false
)