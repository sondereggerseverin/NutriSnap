package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "meal_templates")
data class MealTemplate(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val mealType: MealType = MealType.LUNCH,
    val createdAt: Long = System.currentTimeMillis(),
    val userId: String = ""
)

@Entity(
    tableName = "meal_template_items",
    foreignKeys = [ForeignKey(
        entity = MealTemplate::class,
        parentColumns = ["id"],
        childColumns = ["templateId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class MealTemplateItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val templateId: Int,
    val foodName: String,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val quantityGrams: Float
)
