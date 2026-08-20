package ch.nutrisnap.app.health

import android.content.Context
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.MealType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Best-effort Spiegelung von Tagebuch-Mahlzeiten nach Health Connect.
 * Ohne WRITE_NUTRITION oder wenn HC fehlt → still no-op.
 */
object HealthConnectNutritionSync {

    suspend fun pushMeal(
        context: Context,
        name: String,
        mealType: MealType,
        energyKcal: Float,
        proteinG: Float,
        carbsG: Float,
        fatG: Float,
        fiberG: Float = 0f,
        sugarG: Float = 0f,
        saturatedFatG: Float = 0f,
        sodiumG: Float = 0f,
        date: LocalDate = LocalDate.now(),
        at: Instant? = null
    ) {
        if (HealthConnectManager.getStatus(context) != HealthConnectStatus.AVAILABLE) return
        val instant = at ?: defaultInstantFor(date, mealType)
        HealthConnectManager(context.applicationContext).writeNutritionEntry(
            name = name,
            mealType = mealType,
            energyKcal = energyKcal.toDouble(),
            proteinG = proteinG.toDouble(),
            carbsG = carbsG.toDouble(),
            fatG = fatG.toDouble(),
            fiberG = fiberG.toDouble(),
            sugarG = sugarG.toDouble(),
            saturatedFatG = saturatedFatG.toDouble(),
            sodiumG = sodiumG.toDouble(),
            at = instant
        )
    }

    suspend fun pushEntry(context: Context, entry: DiaryEntry) {
        val date = runCatching { LocalDate.parse(entry.dateStr) }.getOrDefault(LocalDate.now())
        pushMeal(
            context = context,
            name = entry.foodName,
            mealType = entry.mealType,
            energyKcal = entry.calories,
            proteinG = entry.protein,
            carbsG = entry.carbs,
            fatG = entry.fat,
            fiberG = entry.fiber,
            sugarG = entry.sugar,
            saturatedFatG = entry.saturatedFat,
            sodiumG = entry.sodium,
            date = date
        )
    }

    /** Grobe Tageszeit pro Mahlzeit, damit HC-Einträge nicht alle auf "jetzt" fallen. */
    private fun defaultInstantFor(date: LocalDate, mealType: MealType): Instant {
        val time = when (mealType) {
            MealType.BREAKFAST -> LocalTime.of(8, 0)
            MealType.LUNCH -> LocalTime.of(12, 30)
            MealType.DINNER -> LocalTime.of(19, 0)
            MealType.SNACK -> LocalTime.of(15, 30)
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        return if (date == today) Instant.now()
        else date.atTime(time).atZone(zone).toInstant()
    }
}
