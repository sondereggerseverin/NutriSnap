package ch.nutrisnap.app.data.db

import androidx.room.TypeConverter
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.SupplementCategory
import ch.nutrisnap.app.data.model.SupplementConflictGroup
import ch.nutrisnap.app.data.model.SupplementStatus
import ch.nutrisnap.app.data.model.SupplementTiming
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

    @TypeConverter
    fun fromMealType(mealType: MealType?): String? = mealType?.name

    @TypeConverter
    fun toMealType(value: String?): MealType? = value?.let { MealType.valueOf(it) }
}

    @TypeConverter fun fromSupplementCategory(v: SupplementCategory?): String? = v?.name
    @TypeConverter fun toSupplementCategory(v: String?): SupplementCategory? = v?.let { SupplementCategory.valueOf(it) }
    @TypeConverter fun fromSupplementTiming(v: SupplementTiming?): String? = v?.name
    @TypeConverter fun toSupplementTiming(v: String?): SupplementTiming? = v?.let { SupplementTiming.valueOf(it) }
    @TypeConverter fun fromSupplementStatus(v: SupplementStatus?): String? = v?.name
    @TypeConverter fun toSupplementStatus(v: String?): SupplementStatus? = v?.let { SupplementStatus.valueOf(it) }
    @TypeConverter fun fromSupplementConflictGroup(v: SupplementConflictGroup?): String? = v?.name
    @TypeConverter fun toSupplementConflictGroup(v: String?): SupplementConflictGroup? = v?.let { SupplementConflictGroup.valueOf(it) }

