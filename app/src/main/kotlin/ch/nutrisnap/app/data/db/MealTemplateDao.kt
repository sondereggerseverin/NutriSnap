package ch.nutrisnap.app.data.db

import androidx.room.*
import ch.nutrisnap.app.data.model.MealTemplate
import ch.nutrisnap.app.data.model.MealTemplateItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MealTemplateDao {
    @Query("SELECT * FROM meal_templates ORDER BY createdAt DESC")
    fun getAll(): Flow<List<MealTemplate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: MealTemplate): Long

    @Delete
    suspend fun deleteTemplate(template: MealTemplate)

    @Query("SELECT * FROM meal_template_items WHERE templateId = :templateId")
    suspend fun getItems(templateId: Int): List<MealTemplateItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: MealTemplateItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<MealTemplateItem>)

    @Query("DELETE FROM meal_template_items WHERE templateId = :templateId")
    suspend fun deleteItemsForTemplate(templateId: Int)
}
