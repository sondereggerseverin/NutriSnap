package ch.nutrisnap.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_foods")
data class CustomFoodItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float = 0f,
    @ColumnInfo(defaultValue = "0") val sugar: Float = 0f,
    @ColumnInfo(defaultValue = "0") val salt: Float = 0f,
    /** Barcode (EAN / QR), falls aus YAZIO-Export vorhanden. */
    val barcode: String? = null,
    /** Markenname, z. B. "Felfel". Wird beim Import aus dem brand-Feld übernommen. */
    val brand: String? = null,
    /** YAZIO-Kategorie, z. B. "dishes", "dairy", etc. */
    val category: String? = null,
    /** Übliche Portionsgröße in Gramm (Default 100 g = YAZIO per-100g-Basis). */
    val portionSizeG: Float = 100f,
    /** Herkunft: "manual" (Nutzer), "yazio_import" (aus yazio_foods.json), "yazio_recipe_ingredient"
     *  (automatisch aus einer Rezept-Zutat ohne bekannte Makros angelegt), "yazio_diary_only"
     *  (nur aus dem CSV-Tagebuch bekannt, kein foods.json-Eintrag vorhanden). */
    @ColumnInfo(defaultValue = "'manual'") val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis(),
    val userId: String = ""
)
