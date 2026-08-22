package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Speichert welche Zutat eines Rezepts mit welchem FoodItem gematcht wurde.
 * Ein Eintrag pro Rezept-Zutat.
 *
 * [componentGroup]: "side" | "sauce" | null — Zuordnung Beilage/Sauce aus dem Verify-Sheet.
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
    /** Ballaststoffe aus dem Match für die verwendete Menge (nicht pro 100g).
     *  Wird beim Verifizieren mitgespeichert, damit sie beim erneuten Öffnen
     *  nicht verloren gehen. manualFiberG hat Vorrang, falls gesetzt. */
    val matchedFiber: Float? = null,
    val matchSource: MatchSource = MatchSource.UNMATCHED,
    /** "side" | "sauce" | null (Heuristik beim Split). */
    val componentGroup: String? = null,
    /** Manuell korrigierte Menge in g (überschreibt amountGrams in der UI). */
    val manualAmountG: Float? = null,
    /** Manuell nachgetragene Ballaststoffe für die verwendete Menge (nicht pro 100g). */
    val manualFiberG: Float? = null,
    /** True = Zutat vom User entfernt; bei Summen/Split übersprungen. */
    val isDeleted: Boolean = false
)

enum class MatchSource {
    UNMATCHED,   // Noch kein Match
    BARCODE,     // Via Barcode-Scanner
    DATABASE,    // Via Datenbanksuche
    MANUAL       // Manuell eingegeben
}
