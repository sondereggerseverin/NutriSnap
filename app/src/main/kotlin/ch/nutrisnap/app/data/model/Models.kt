package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
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

// ─── Recipes ─────────────────────────────────────────────────────────────────

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

// ─── OpenFoodFacts API ────────────────────────────────────────────────────────
// Field names use dashes in JSON: "energy-kcal_100g", "proteins_100g" etc.

@Serializable
data class OFFSearchResponse(
    val count: Int = 0,
    val products: List<OFFProduct> = emptyList()
)

@Serializable
data class OFFProduct(
    @SerialName("product_name") val product_name: String? = null,
    val brands: String? = null,
    val nutriments: OFFNutriments? = null,
    @SerialName("image_url") val image_url: String? = null
)

@Serializable
data class OFFNutriments(
    // OpenFoodFacts uses dash in JSON key: "energy-kcal_100g"
    @SerialName("energy-kcal_100g")   val energyKcal100g: Float? = null,
    @SerialName("energy-kcal")        val energyKcal: Float? = null,
    @SerialName("energy_kcal_100g")   val energyKcalAlt: Float? = null,  // fallback
    @SerialName("proteins_100g")      val proteins100g: Float? = null,
    @SerialName("carbohydrates_100g") val carbs100g: Float? = null,
    @SerialName("fat_100g")           val fat100g: Float? = null,
    @SerialName("fiber_100g")         val fiber100g: Float? = null
) {
    // Pick whichever kcal field is available
    val kcalPer100g: Float? get() = energyKcal100g ?: energyKcalAlt ?: energyKcal
}

@Serializable
data class SingleProductResponse(
    val status: Int = 0,
    val product: OFFProduct? = null
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
