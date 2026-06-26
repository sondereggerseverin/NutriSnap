package ch.nutrisnap.app.ui.screens.mealtemplate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealTemplate
import ch.nutrisnap.app.data.model.MealTemplateItem
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.MealTemplateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MealTemplateViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MealTemplateRepository(NutriDatabase.getInstance(app).mealTemplateDao())

    val templates: StateFlow<List<MealTemplate>> = repo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveTemplate(name: String, mealType: MealType, items: List<MealTemplateItem>) =
        viewModelScope.launch { repo.saveTemplate(name, mealType, items) }

    fun delete(template: MealTemplate) =
        viewModelScope.launch { repo.delete(template) }

    suspend fun getItems(templateId: Int): List<MealTemplateItem> =
        withContext(Dispatchers.IO) { repo.getItems(templateId) }
}
