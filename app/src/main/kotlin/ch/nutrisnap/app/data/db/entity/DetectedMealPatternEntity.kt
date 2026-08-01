package ch.nutrisnap.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ch.nutrisnap.app.data.model.MealType

/** Persistente Ablage einer automatisch erkannten wiederkehrenden Mahlzeit
 *  (sortierte foodItemId-Kombination = fingerprint). */
@Entity(
    tableName = "detected_meal_patterns",
    indices = [Index(value = ["fingerprint"], unique = true)]
)
data class DetectedMealPatternEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fingerprint: String,
    val label: String,
    val mealType: MealType,
    /** Komma-separierte foodItemId-Liste (Room speichert hier bewusst keine echte
     *  Relation - foodItemIds können auf food_items oder custom_foods zeigen). */
    val foodItemIds: String,
    val avgKcal: Double,
    val occurrences: Int,
    val lastSeenAt: Long = System.currentTimeMillis()
)
