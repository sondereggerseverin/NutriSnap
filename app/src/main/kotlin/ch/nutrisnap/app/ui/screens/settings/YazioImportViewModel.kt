package ch.nutrisnap.app.ui.screens.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.DiaryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate

data class YazioImportResult(
    val importedDays: Int = 0,
    val importedEntries: Int = 0,
    val skippedEntries: Int = 0
)

sealed class YazioImportState {
    object Idle : YazioImportState()
    object Loading : YazioImportState()
    data class Success(val result: YazioImportResult) : YazioImportState()
    data class Error(val message: String) : YazioImportState()
}

/**
 * Importiert den Yazio "nutrition_log.csv" Export (pro-Produkt Eintraege) als
 * manuelle DiaryEntries in NutriSnap.
 *
 * Erwartete Spalten:
 * Datum, Mahlzeit, Produkt, Menge (g), Kalorien total, Protein total (g),
 * Fett total (g), Kohlenhydrate total (g)
 */
class YazioImportViewModel(app: Application) : AndroidViewModel(app) {

    private val db = NutriDatabase.getInstance(app)
    private val diaryRepo = DiaryRepository(db)

    private val _state = MutableStateFlow<YazioImportState>(YazioImportState.Idle)
    val state: StateFlow<YazioImportState> = _state

    fun importNutritionLog(uri: Uri) {
        viewModelScope.launch {
            _state.value = YazioImportState.Loading
            try {
                val context = getApplication<Application>()
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Datei konnte nicht geoeffnet werden")
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))

                // BOM ueberspringen, falls vorhanden
                reader.mark(4)
                val first = reader.read()
                if (first != 0xFEFF) reader.reset()

                val header = reader.readLine() // Header ueberspringen
                if (header == null) throw Exception("Leere CSV-Datei")

                var imported = 0
                var skipped = 0
                val days = mutableSetOf<LocalDate>()

                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isBlank()) return@forEachLine
                    val cols = splitCsvLine(line)
                    if (cols.size < 8) { skipped++; return@forEachLine }

                    try {
                        val dateStr = cols[0].trim()
                        val mealStr = cols[1].trim().lowercase()
                        val product = cols[2].trim().ifBlank { "Unbekannt" }
                        val kcal    = cols[4].trim().toFloatOrNull() ?: 0f
                        val protein = cols[5].trim().toFloatOrNull() ?: 0f
                        val fat     = cols[6].trim().toFloatOrNull() ?: 0f
                        val carbs   = cols[7].trim().toFloatOrNull() ?: 0f

                        val date = LocalDate.parse(dateStr)
                        days.add(date)

                        val mealType = when (mealStr) {
                            "breakfast", "fruehstueck", "fruehstuck" -> MealType.BREAKFAST
                            "lunch", "mittagessen" -> MealType.LUNCH
                            "dinner", "abendessen" -> MealType.DINNER
                            else -> MealType.SNACK
                        }

                        viewModelScope.launch {
                            diaryRepo.addManualEntry(
                                name = product,
                                kcal = kcal,
                                protein = protein,
                                carbs = carbs,
                                fat = fat,
                                mealType = mealType,
                                date = date
                            )
                        }
                        imported++
                    } catch (e: Exception) {
                        skipped++
                    }
                }

                reader.close()
                _state.value = YazioImportState.Success(
                    YazioImportResult(
                        importedDays = days.size,
                        importedEntries = imported,
                        skippedEntries = skipped
                    )
                )
            } catch (e: Exception) {
                _state.value = YazioImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /** Einfacher CSV-Split, der Anfuehrungszeichen-umschlossene Felder mit Kommas korrekt behandelt. */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        result.add(sb.toString())
        return result
    }

    fun reset() {
        _state.value = YazioImportState.Idle
    }
}
