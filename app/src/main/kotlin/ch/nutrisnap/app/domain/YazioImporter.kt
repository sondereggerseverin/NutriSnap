package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class YazioImporter(private val db: NutriDatabase) {
    suspend fun importFromCsv(csvContent: String): ImportResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val lines = csvContent.split("\n").drop(1)
            var foodsCreated = 0
            var entriesCreated = 0
            val createdFoods = mutableMapOf<String, FoodItem>()
            lines.filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size >= 8) {
                    try {
                        val dateStr = parts[0]
                        val mealType = parts[1].uppercase()
                        val productName = parts[2]
                        val amountGrams = parts[3].toFloatOrNull() ?: 100f
                        val caloriesTotal = parts[4].toFloatOrNull() ?: 0f
                        val proteinTotal = parts[5].toFloatOrNull() ?: 0f
                        val fatTotal = parts[6].toFloatOrNull() ?: 0f
                        val carbsTotal = parts[7].toFloatOrNull() ?: 0f
                        val foodKey = productName.lowercase().trim()
                        val foodItem = createdFoods.getOrPut(foodKey) {
                            FoodItem(
                                name = productName,
                                calories = if (amountGrams > 0) (caloriesTotal / amountGrams) * 100 else caloriesTotal,
                                protein = if (amountGrams > 0) (proteinTotal / amountGrams) * 100 else proteinTotal,
                                carbs = if (amountGrams > 0) (carbsTotal / amountGrams) * 100 else carbsTotal,
                                fat = if (amountGrams > 0) (fatTotal / amountGrams) * 100 else fatTotal,
                                source = FoodSource.MANUAL,
                                completenessScore = 100
                            ).also {
                                db.foodItemDao().insert(it)
                                foodsCreated++
                            }
                        }
                        val mealTypeEnum = when (mealType) {
                            "BREAKFAST" -> MealType.BREAKFAST
                            "LUNCH" -> MealType.LUNCH
                            "DINNER" -> MealType.DINNER
                            "SNACK" -> MealType.SNACK
                            else -> MealType.SNACK
                        }
                        val entry = DiaryEntry(
                            foodItemId = foodItem.id,
                            foodName = productName,
                            amountGrams = amountGrams,
                            mealType = mealTypeEnum,
                            dateStr = dateStr,
                            calories = caloriesTotal,
                            protein = proteinTotal,
                            carbs = carbsTotal,
                            fat = fatTotal
                        )
                        db.diaryDao().insert(entry)
                        entriesCreated++
                    } catch (e: Exception) {}
                }
            }
            ImportResult(success = true, foodsImported = foodsCreated, entriesImported = entriesCreated)
        } catch (e: Exception) {
            ImportResult(success = false, error = e.message ?: "Unknown error")
        }
    }
}

data class ImportResult(
    val success: Boolean,
    val foodsImported: Int = 0,
    val entriesImported: Int = 0,
    val error: String? = null
)
