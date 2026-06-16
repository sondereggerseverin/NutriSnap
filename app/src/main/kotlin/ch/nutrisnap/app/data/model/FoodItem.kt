package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "food_items")
data class FoodItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,

    // Makronährstoffe (pro 100g)
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val servingSize: Float = 100f,
    val servingUnit: String = "g",

    // Mikronährstoffe (pro 100g, nullable wenn nicht bekannt)
    val fiber: Float? = null,
    val sugar: Float? = null,
    val saturatedFat: Float? = null,
    val sodium: Float? = null,
    val potassium: Float? = null,
    val calcium: Float? = null,
    val iron: Float? = null,
    val vitaminC: Float? = null,
    val vitaminD: Float? = null,
    val vitaminB12: Float? = null,

    // Metadaten
    val source: FoodSource = FoodSource.MANUAL,
    val completenessScore: Int = 0,
    val timesUsed: Int = 0
)

enum class FoodSource {
    OPEN_FOOD_FACTS,
    USDA,
    NUTRITIONIX,
    MANUAL
}
