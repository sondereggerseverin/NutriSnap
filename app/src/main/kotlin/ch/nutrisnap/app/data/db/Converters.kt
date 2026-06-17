package ch.nutrisnap.app.data.db

import androidx.room.TypeConverter
import ch.nutrisnap.app.data.model.FoodSource
import java.time.LocalDate
import java.time.LocalDateTime

class Converters {
    @TypeConverter
    fun fromLocalDate(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }

    @TypeConverter
    fun fromLocalDateTime(dt: LocalDateTime?): String? = dt?.toString()

    @TypeConverter
    fun toLocalDateTime(value: String?): LocalDateTime? = value?.let { LocalDateTime.parse(it) }

    @TypeConverter
    fun fromFoodSource(source: FoodSource?): String? = source?.name

    @TypeConverter
    fun toFoodSource(value: String?): FoodSource? = value?.let { FoodSource.valueOf(it) }
}
