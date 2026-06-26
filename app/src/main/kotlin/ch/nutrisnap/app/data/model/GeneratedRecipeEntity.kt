package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "generated_recipes")
data class GeneratedRecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String = "",
    val ingredients: String = "",   // JSON array as string
    val steps: String = "",         // JSON array as string
    val servings: Int = 2,
    val prepTimeMinutes: Int = 30,
    val calories: Int = 0,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val generatedAt: Long = System.currentTimeMillis()
)
