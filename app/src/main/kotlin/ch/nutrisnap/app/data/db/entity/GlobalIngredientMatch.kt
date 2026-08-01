package ch.nutrisnap.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Rezeptübergreifendes Zutaten-Wörterbuch: einmal von Nutzer verifizierte/gefundene
 *  Zutat-Matches (Name -> OFF-Produkt) werden für alle künftigen Rezepte wiederverwendet,
 *  statt bei jedem Rezept erneut zu suchen. */
@Entity(
    tableName = "global_ingredient_matches",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class GlobalIngredientMatch(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val normalizedName: String,
    val originalName: String,
    val offProductId: String,
    val offProductName: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val matchConfidence: Float = 1.0f,
    val usageCount: Int = 1,
    val lastUsedAt: Long = System.currentTimeMillis(),
    val isVerifiedByUser: Boolean = false
)
