package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.MealTemplateDao
import ch.nutrisnap.app.data.model.MealTemplate
import ch.nutrisnap.app.data.model.MealTemplateItem
import ch.nutrisnap.app.data.model.MealType
import kotlinx.coroutines.flow.Flow

class MealTemplateRepository(private val dao: MealTemplateDao) {
    fun getAll(): Flow<List<MealTemplate>> = dao.getAll()

    suspend fun getItems(templateId: Int): List<MealTemplateItem> = dao.getItems(templateId)

    suspend fun saveTemplate(name: String, mealType: MealType, items: List<MealTemplateItem>): Long {
        val id = dao.insertTemplate(MealTemplate(name = name, mealType = mealType))
        dao.insertItems(items.map { it.copy(templateId = id.toInt()) })
        return id
    }

    suspend fun delete(template: MealTemplate) {
        dao.deleteItemsForTemplate(template.id)
        dao.deleteTemplate(template)
    }
}
