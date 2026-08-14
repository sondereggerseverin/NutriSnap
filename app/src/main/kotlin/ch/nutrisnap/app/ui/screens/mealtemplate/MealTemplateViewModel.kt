package ch.nutrisnap.app.ui.screens.mealtemplate

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealTemplate
import ch.nutrisnap.app.data.model.MealTemplateItem
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.MealTemplateRepository
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val KEY_AUTOPILOT_TEMPLATE_IDS = stringSetPreferencesKey("autopilot_template_ids")

class MealTemplateViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MealTemplateRepository(NutriDatabase.getInstance(app).mealTemplateDao())

    val templates: StateFlow<List<MealTemplate>> = repo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Template-IDs, die im Wochen-Autopilot (Mo–Fr) vorgeschlagen werden. */
    val autopilotIds: StateFlow<Set<Int>> = getApplication<Application>().notifDataStore.data
        .map { prefs -> prefs[KEY_AUTOPILOT_TEMPLATE_IDS].orEmpty().mapNotNull { it.toIntOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggleAutopilot(templateId: Int) {
        viewModelScope.launch {
            getApplication<Application>().notifDataStore.edit { prefs ->
                val cur = prefs[KEY_AUTOPILOT_TEMPLATE_IDS].orEmpty().toMutableSet()
                val id = templateId.toString()
                if (id in cur) cur.remove(id) else cur.add(id)
                prefs[KEY_AUTOPILOT_TEMPLATE_IDS] = cur
            }
        }
    }

    fun saveTemplate(name: String, mealType: MealType, items: List<MealTemplateItem>) =
        viewModelScope.launch { repo.saveTemplate(name, mealType, items) }

    fun delete(template: MealTemplate) =
        viewModelScope.launch { repo.delete(template) }

    suspend fun getItems(templateId: Int): List<MealTemplateItem> =
        withContext(Dispatchers.IO) { repo.getItems(templateId) }
}
