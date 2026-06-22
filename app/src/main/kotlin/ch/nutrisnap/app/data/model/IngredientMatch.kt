package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Speichert welche Zutat eines Rezepts mit welchem FoodItem gematcht wurde.
 * Ein Eintrag pro Rezept-Zutat.
 */
@Entity(tableName = "ingredient_matches")
data class IngredientMatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val ingredientRaw: String,       // Original-Text aus Rezept z.B. "200g Haferflocken"
    val ingredientName: String,      // Normalisierter Name z.B. "Haferflocken"
    val amountGrams: Float = 0f,     // Menge in Gramm (0 = unbekannt)
    val matchedFoodItemId: Long? = null,  // null = noch nicht gematcht
    val matchedFoodName: String? = null,
    val matchedCalories: Float? = null,
    val matchedProtein: Float? = null,
    val matchedCarbs: Float? = null,
    val matchedFat: Float? = null,
    val matchSource: MatchSource = MatchSource.UNMATCHED
)

enum class MatchSource {
    UNMATCHED,   // Noch kein Match
    BARCODE,     // Via Barcode-Scanner
    DATABASE,    // Via Datenbanksuche
    MANUAL       // Manuell eingegeben
}
