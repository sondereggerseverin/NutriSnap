package ch.nutrisnap.app.ui.screens.scan

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.data.repository.CustomFoodRepository
import ch.nutrisnap.app.domain.GroqVisionService
import ch.nutrisnap.app.domain.NutritionLabelResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _state = MutableStateFlow<LabelScanState>(LabelScanState.Capturing)
    val state: StateFlow<LabelScanState> = _state

    fun analyzePhoto(bitmap: Bitmap) {
        _state.value = LabelScanState.Analyzing
        viewModelScope.launch {
            val base64 = visionService.bitmapToBase64JpegForText(bitmap)
            visionService.analyzeNutritionLabel(base64).fold(
                onSuccess = { result -> _state.value = LabelScanState.Result(result) },
                onFailure = { e -> _state.value = LabelScanState.Error(e.message ?: "Unbekannter Fehler") }
            )
        }
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
            // Über FoodItemRepository, damit Barcode auch in food_items liegt
            ch.nutrisnap.app.data.repository.FoodItemRepository(
                ch.nutrisnap.app.data.db.NutriDatabase.getInstance(getApplication())
            ).saveCustomFoodWithBarcode(item)
            _state.value = LabelScanState.Saved
        }
    }
}
