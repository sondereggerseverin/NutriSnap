package ch.nutrisnap.app.ui.screens.scan

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.CustomFoodItem
import java.time.LocalDate
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.repository.CustomFoodRepository
import ch.nutrisnap.app.domain.GroqVisionService
import ch.nutrisnap.app.domain.IngredientNutritionDatabase
import ch.nutrisnap.app.domain.NutritionLabelResult
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_MICRO_NUTRIENT_FILL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class LabelScanState {
    object Capturing : LabelScanState()
    object Analyzing : LabelScanState()
    data class Result(val result: NutritionLabelResult) : LabelScanState()
    data class Error(val message: String) : LabelScanState()
    object Saved : LabelScanState()
}

/**
 * Fotografiert eine Nährwerttabelle, liest die Werte via Groq Vision aus und
 * speichert sie als CustomFoodItem – der Nutzer muss nur noch den Produktnamen eingeben.
 */
class NutritionLabelScanViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CustomFoodRepository(NutriDatabase.getInstance(app).customFoodDao())
    private val visionService = GroqVisionService()
    private val diaryRepo = DiaryRepository(NutriDatabase.getInstance(app))

    private val _state = MutableStateFlow<LabelScanState>(LabelScanState.Capturing)
    val state: StateFlow<LabelScanState> = _state

    fun analyzePhoto(bitmap: Bitmap) {
        _state.value = LabelScanState.Analyzing
        viewModelScope.launch {
            val base64 = visionService.bitmapToBase64JpegForText(bitmap)
            visionService.analyzeNutritionLabel(base64).fold(
                onSuccess = { result ->
                    val filled = fillMissingMicros(result)
                    _state.value = LabelScanState.Result(filled)
                },
                onFailure = { e -> _state.value = LabelScanState.Error(e.message ?: "Unbekannter Fehler") }
            )
        }
    }

    /**
     * Wenn das Etikett keine Ballaststoffe zeigt und der Mikro-Toggle an ist,
     * aus der lokalen Referenz-DB nachziehen (Produktname/Marke).
     */
    private suspend fun fillMissingMicros(result: NutritionLabelResult): NutritionLabelResult {
        val enabled = getApplication<Application>().notifDataStore.data.first()
            ?.get(KEY_MICRO_NUTRIENT_FILL) ?: true
        if (!enabled) return result
        if (result.fiberPer100g > 0f) return result
        val candidates = listOfNotNull(
            result.productName.takeIf { it.isNotBlank() },
            result.brand.takeIf { it.isNotBlank() },
            listOf(result.brand, result.productName).filter { it.isNotBlank() }.joinToString(" ")
                .takeIf { it.isNotBlank() }
        )
        for (c in candidates) {
            val entry = IngredientNutritionDatabase.lookup(c) ?: continue
            if (entry.fiber >= 0f) {
                return result.copy(fiberPer100g = entry.fiber)
            }
        }
        return result
    }

    fun retake() {
        _state.value = LabelScanState.Capturing
    }

    /**
     * Speichert das Etikett-Produkt. [barcode] optional – wenn gesetzt, ist das Produkt
     * beim nächsten Scan per Barcode findbar (custom_foods + food_items).
     */
    fun saveAsProduct(
        name: String,
        result: NutritionLabelResult,
        portionSizeG: Float = 100f,
        barcode: String? = null
    ) {
        viewModelScope.launch {
            persistProduct(name, result, portionSizeG, barcode)
            _state.value = LabelScanState.Saved
        }
    }

    /**
     * Wie Fud AI: Produkt speichern UND sofort ins Tagebuch eintragen (Menge [amountG]).
     * Das gespeicherte Produkt bleibt zusätzlich per Suche/Barcode wiederfindbar.
     */
    fun saveAndLog(
        name: String,
        result: NutritionLabelResult,
        portionSizeG: Float,
        barcode: String?,
        amountG: Float,
        meal: MealType
    ) {
        viewModelScope.launch {
            val food = persistProduct(name, result, portionSizeG, barcode)
            diaryRepo.addEntry(food, amountG.coerceAtLeast(1f), meal, LocalDate.now())
            _state.value = LabelScanState.Saved
        }
    }

    private suspend fun persistProduct(
        name: String,
        result: NutritionLabelResult,
        portionSizeG: Float,
        barcode: String?
    ): FoodItem {
        val bc = ch.nutrisnap.app.utils.BarcodeUtils.normalize(barcode).ifBlank { null }
        val item = CustomFoodItem(
            name = name.trim(),
            calories = result.caloriesPer100g,
            protein = result.proteinPer100g,
            carbs = result.carbsPer100g,
            fat = result.fatPer100g,
            fiber = result.fiberPer100g,
            sugar = result.sugarPer100g,
            salt = result.saltPer100g,
            brand = result.brand.ifBlank { null },
            barcode = bc,
            portionSizeG = portionSizeG.coerceAtLeast(1f),
            source = "label_scan",
            verified = true
        )
        val foodRepo = FoodItemRepository(NutriDatabase.getInstance(getApplication()))
        // Über FoodItemRepository, damit Barcode auch in food_items liegt
        foodRepo.saveCustomFoodWithBarcode(item)
        // Vitamine/Mineralstoffe aus Etikett in food_items (CustomFood hat keine Mikro-Spalten)
        val food = FoodItem(
            name = name.trim(),
            brand = result.brand.ifBlank { null },
            barcode = bc,
            calories = result.caloriesPer100g,
            protein = result.proteinPer100g,
            carbs = result.carbsPer100g,
            fat = result.fatPer100g,
            fiber = result.fiberPer100g,
            sugar = result.sugarPer100g,
            salt = result.saltPer100g,
            vitaminC = result.vitaminCPer100g.takeIf { it > 0f },
            vitaminD = result.vitaminDPer100g.takeIf { it > 0f },
            calcium = result.calciumPer100g.takeIf { it > 0f },
            iron = result.ironPer100g.takeIf { it > 0f },
            servingSize = portionSizeG.coerceAtLeast(1f),
            source = FoodSource.MANUAL,
            completenessScore = 95
        )
        val id = foodRepo.saveCustomFood(food).toInt()
        return food.copy(id = id)
    }
}
