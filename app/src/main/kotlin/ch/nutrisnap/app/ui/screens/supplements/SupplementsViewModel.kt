package ch.nutrisnap.app.ui.screens.supplements

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.Supplement
import ch.nutrisnap.app.data.model.SupplementCategory
import ch.nutrisnap.app.data.model.SupplementConflictGroup
import ch.nutrisnap.app.data.model.SupplementStatus
import ch.nutrisnap.app.data.model.SupplementTiming
import ch.nutrisnap.app.data.repository.SupplementRepository
import ch.nutrisnap.app.domain.DailySupplementPlan
import ch.nutrisnap.app.domain.SupplementRecommendationEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

class SupplementsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SupplementRepository(NutriDatabase.getInstance(app))

    val supplements: StateFlow<List<Supplement>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyPlan: StateFlow<DailySupplementPlan> = supplements
        .map { SupplementRecommendationEngine.plan(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            DailySupplementPlan(emptyList(), emptyList(), emptyList())
        )

    init {
        viewModelScope.launch { seedFromAssetsIfEmpty() }
    }

    private suspend fun seedFromAssetsIfEmpty() {
        if (!repo.isEmpty()) return
        val jsonText = runCatching {
            getApplication<Application>().assets.open("supplements_seed.json")
                .bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return
        val items = (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val expiryRaw = o.optString("expiryDateStr", "")
                Supplement(
                    name = o.getString("name"),
                    brand = o.optString("brand", ""),
                    category = SupplementCategory.valueOf(o.getString("category")),
                    activeIngredients = o.optString("activeIngredients", ""),
                    servingSize = o.optString("servingSize", ""),
                    effects = o.optString("effects", ""),
                    pros = o.optString("pros", ""),
                    cons = o.optString("cons", ""),
                    dosageRecommendation = o.optString("dosageRecommendation", ""),
                    warnings = o.optString("warnings", ""),
                    expiryDateStr = expiryRaw.takeIf { it.isNotBlank() && it != "null" },
                    status = SupplementStatus.valueOf(o.getString("status")),
                    conflictGroup = SupplementConflictGroup.valueOf(
                        o.optString("conflictGroup", "NONE")
                    ),
                    preferredTiming = SupplementTiming.valueOf(
                        o.optString("preferredTiming", "MORGENS")
                    ),
                    requiresMedicalConfirmation = o.optBoolean("requiresMedicalConfirmation", false),
                    isSeedData = true
                )
            }.getOrNull()
        }
        if (items.isNotEmpty()) repo.seedIfEmpty(items)
    }

    fun add(item: Supplement) = viewModelScope.launch { repo.add(item) }
    fun update(item: Supplement) = viewModelScope.launch { repo.update(item) }
    fun delete(item: Supplement) = viewModelScope.launch { repo.delete(item) }
}
