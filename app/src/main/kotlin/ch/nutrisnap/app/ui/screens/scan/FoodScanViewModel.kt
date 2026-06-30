package ch.nutrisnap.app.ui.screens.scan

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.domain.FoodScanResult
import ch.nutrisnap.app.domain.GroqVisionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

sealed class FoodScanState {
    object Capturing : FoodScanState()
    object Analyzing : FoodScanState()
    data class Result(val result: FoodScanResult) : FoodScanState()
    data class Error(val message: String) : FoodScanState()
    object Saved : FoodScanState()
}

class FoodScanViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val diaryRepo = DiaryRepository(db)
    private val visionService = GroqVisionService()

    private val _state = MutableStateFlow<FoodScanState>(FoodScanState.Capturing)
    val state: StateFlow<FoodScanState> = _state

    fun analyzePhoto(bitmap: Bitmap) {
        _state.value = FoodScanState.Analyzing
        viewModelScope.launch {
            val base64 = visionService.bitmapToBase64Jpeg(bitmap)
            visionService.analyzeFoodPhoto(base64).fold(
                onSuccess = { result -> _state.value = FoodScanState.Result(result) },
                onFailure = { e -> _state.value = FoodScanState.Error(e.message ?: "Unbekannter Fehler") }
            )
        }
    }

    fun retake() {
        _state.value = FoodScanState.Capturing
    }

    fun saveToDiary(result: FoodScanResult, mealType: MealType) {
        viewModelScope.launch {
            diaryRepo.addManualEntry(
                name = result.foodName.ifBlank { "Gescanntes Essen" },
                kcal = result.calories,
                protein = result.protein,
                carbs = result.carbs,
                fat = result.fat,
                mealType = mealType,
                date = LocalDate.now()
            )
            _state.value = FoodScanState.Saved
        }
    }
}
