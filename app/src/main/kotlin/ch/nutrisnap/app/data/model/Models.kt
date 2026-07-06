package ch.nutrisnap.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// ─── Diary ───────────────────────────────────────────────────────────────────
@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodItemId: Int,           // Int to match FoodItem.id
    val foodName: String,
    val amountGrams: Float,
    val mealType: MealType,
    val dateStr: String,           // "yyyy-MM-dd"
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0
)

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

// ─── Rezept-Tags / Diät-Filter ───────────────────────────────────────────────
enum class DietTag(val label: String, val emoji: String) {
    VEGAN("Vegan", "🌱"),
    VEGETARIAN("Vegetarisch", "🥗"),
    LOW_CARB("Low Carb", "🥩"),
    HIGH_PROTEIN("Proteinreich", "💪"),
    LOW_CALORIE("Kalorienarm", "⚡"),
    GLUTEN_FREE("Glutenfrei", "🌾"),
    DAIRY_FREE("Laktosefrei", "🥛"),
    QUICK("Schnell (<30 Min)", "⏱️")
}

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
    val proteinPerServing: Float? = null,
    val carbsPerServing: Float? = null,
    val fatPerServing: Float? = null,
    val servings: Int = 1,
    val prepTimeMinutes: Int? = null,
    val tags: String = "",          // Komma-separierte DietTag-Namen
    val collectionId: Long? = null,
    val isFavorite: Boolean = false,
    val showNutrition: Boolean = true,
    val savedAt: Long = System.currentTimeMillis()
) {
    fun getDietTags(): List<DietTag> =
        tags.split(",").mapNotNull { tag ->
            DietTag.entries.firstOrNull { it.name == tag.trim() }
        }
}

// ─── Rezept-Sammlungen ───────────────────────────────────────────────────────
@Entity(tableName = "recipe_collections")
data class RecipeCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "📁",
    val createdAt: Long = System.currentTimeMillis()
)

// ─── OpenFoodFacts API ───────────────────────────────────────────────────────
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
    @SerialName("image_front_small_url") val image_front_small_url: String? = null,
    @SerialName("image_url") val image_url: String? = null
)

@Serializable
data class OFFNutriments(
    @SerialName("energy-kcal_100g") val energyKcal100g: Float? = null,
    @SerialName("energy-kcal") val energyKcal: Float? = null,
    @SerialName("energy_kcal_100g") val energyKcalAlt: Float? = null,
    @SerialName("proteins_100g") val proteins100g: Float? = null,
    @SerialName("carbohydrates_100g") val carbs100g: Float? = null,
    @SerialName("fat_100g") val fat100g: Float? = null,
    @SerialName("fiber_100g") val fiber100g: Float? = null
) {
    val kcalPer100g: Float? get() = energyKcal100g ?: energyKcalAlt ?: energyKcal
}

@Serializable
data class SingleProductResponse(val status: Int = 0, val product: OFFProduct? = null)

// ─── UI helpers ──────────────────────────────────────────────────────────────
data class DailyNutrition(
    val date: LocalDate,
    val calories: Float, val protein: Float, val carbs: Float, val fat: Float,
    val goalCalories: Float = 2000f
) { val progress get() = (calories / goalCalories).coerceIn(0f, 1f) }

data class RecipeScrapeResult(
    val success: Boolean,
    val recipe: Recipe? = null,
    val error: String? = null,
    val instagramBlocked: Boolean = false
)

// ─── Weight Tracking ─────────────────────────────────────────────────────────
@Entity(tableName = "weight_entries")
data class WeightEntry(@PrimaryKey val dateStr: String, val weightKg: Float)

// ─── Favorites ───────────────────────────────────────────────────────────────
// Note: DB column names kept as-is for backward compatibility with Migration 2→3 SQL
@Entity(tableName = "favorite_foods")
data class FavoriteFoodEntity(
    @PrimaryKey val foodKey: String,
    val name: String,
    val brand: String? = null,
    val caloriesPer100g: Float,   // column name kept for DB compat
    val proteinPer100g: Float,
    val carbsPer100g: Float,
    val fatPer100g: Float,
    val fiberPer100g: Float = 0f,
    val addedAt: Long = System.currentTimeMillis()
)

// Extension functions updated to use new FoodItem schema (FoodItem.kt)
fun FoodItem.favoriteKey(): String = "${name.trim().lowercase()}|${brand?.trim()?.lowercase() ?: ""}"

fun FoodItem.toFavoriteEntity() = FavoriteFoodEntity(
    foodKey        = favoriteKey(),
    name           = name,
    brand          = brand,
    caloriesPer100g = calories,
    proteinPer100g  = protein,
    carbsPer100g    = carbs,
    fatPer100g      = fat,
    fiberPer100g    = fiber ?: 0f
)

fun FavoriteFoodEntity.toFoodItem() = FoodItem(
    id       = 0,
    name     = name,
    brand    = brand,
    calories = caloriesPer100g,
    protein  = proteinPer100g,
    carbs    = carbsPer100g,
    fat      = fatPer100g,
    fiber    = fiberPer100g,
    source   = FoodSource.MANUAL
)
