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

    // Makronaehrstoffe (pro 100g)
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val servingSize: Float = 100f,
    val servingUnit: String = "g",

    // Mikronaehrstoffe (pro 100g, nullable wenn nicht bekannt)
    val fiber: Float? = null,
    val sugar: Float? = null,
    val saturatedFat: Float? = null,
    val monoFat: Float? = null,
    val polyFat: Float? = null,
    val transFat: Float? = null,
    val salt: Float? = null,
    val sodium: Float? = null,
    val alcohol: Float? = null,
    val cholesterol: Float? = null,
    val water: Float? = null,

    // Vitamine (pro 100g)
    val vitaminA: Float? = null,
    val vitaminB1: Float? = null,
    val vitaminB2: Float? = null,
    val vitaminB3: Float? = null,
    val vitaminB5: Float? = null,
    val vitaminB6: Float? = null,
    val vitaminB7: Float? = null,
    val vitaminB11: Float? = null,
    val vitaminB12: Float? = null,
    val vitaminC: Float? = null,
    val vitaminD: Float? = null,
    val vitaminE: Float? = null,
    val vitaminK: Float? = null,

    // Mineralstoffe (pro 100g)
    val potassium: Float? = null,
    val calcium: Float? = null,
    val iron: Float? = null,
    val magnesium: Float? = null,
    val zinc: Float? = null,
    val phosphorus: Float? = null,
    val copper: Float? = null,
    val manganese: Float? = null,
    val fluoride: Float? = null,
    val iodine: Float? = null,
    val selenium: Float? = null,
    val chromium: Float? = null,
    val molybdenum: Float? = null,
    val chloride: Float? = null,
    val choline: Float? = null,
    // Spurenelemente (pro 100g) - von keiner angebundenen Quelle (OpenFoodFacts/
    // Groq) zuverlässig geliefert, Felder bestehen für manuelle Eingabe/zukünftige Quellen.
    val arsenic: Float? = null,
    val boron: Float? = null,
    val cobalt: Float? = null,
    val rubidium: Float? = null,
    val silicon: Float? = null,
    val sulfur: Float? = null,
    val tin: Float? = null,
    val vanadium: Float? = null,

    // Metadaten
    val source: FoodSource = FoodSource.MANUAL,
    val completenessScore: Int = 0,
    val timesUsed: Int = 0
)

enum class FoodSource {
    OPEN_FOOD_FACTS,
    USDA,
    NUTRITIONIX,
    MANUAL,
    SWISS_FSVO
}
