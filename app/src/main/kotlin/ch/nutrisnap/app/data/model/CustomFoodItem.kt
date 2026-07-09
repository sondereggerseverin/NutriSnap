package ch.nutrisnap.app.data.model

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
    /** Barcode (EAN / QR), falls aus YAZIO-Export vorhanden. */
    val barcode: String? = null,
    /** Markenname, z. B. "Felfel". Wird beim Import aus dem brand-Feld übernommen. */
    val brand: String? = null,
    /** YAZIO-Kategorie, z. B. "dishes", "dairy", etc. */
    val category: String? = null,
    /** Übliche Portionsgröße in Gramm (Default 100 g = YAZIO per-100g-Basis). */
    val portionSizeG: Float = 100f,
    val createdAt: Long = System.currentTimeMillis(),
    val userId: String = ""
)
