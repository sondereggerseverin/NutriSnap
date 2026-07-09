package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shopping_list_items")
data class ShoppingListItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val amount: Float? = null,
    val unit: String? = null,
    val checked: Boolean = false,
    val recipeTitle: String? = null,   // gesetzt, wenn aus einem Rezept hinzugefügt (für Gruppierung)
    val createdAt: Long = System.currentTimeMillis()
)
