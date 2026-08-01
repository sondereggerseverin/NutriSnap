package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.FoodUsageContextDao
import ch.nutrisnap.app.data.db.entity.FoodUsageContext
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

data class RankedFood(
    val foodId: String,
    val foodName: String,
    val contextScore: Int,
    val isContextualHit: Boolean
)

/** Tageszeit-bewusstes Ranking der Favoriten: Foods, die üblicherweise zu dieser
 *  Uhrzeit/diesem Wochentag geloggt werden, erscheinen zuerst.
 *
 *  Integration: FavoriteFoodRepository.getAll() an der Quick-Add-Stelle durch
 *  getRankedFavoritesForNow() ersetzen; nach jedem erfolgreichen DiaryRepository.addEntry()
 *  zusätzlich recordFoodUsage(foodItemId, foodName) aufrufen. */
class ContextualFoodRankingRepository(
    private val contextDao: FoodUsageContextDao,
    private val favoriteFoodRepository: FavoriteFoodRepository
) {
    suspend fun getRankedFavoritesForNow(): List<RankedFood> {
        val now = LocalDateTime.now()
        val hour = now.hour
        val dow = now.dayOfWeek.value

        val contextualTop = contextDao.getTopFoodsForHourAndDay(hour, dow, 5)
        val contextualIds = contextualTop.map { it.foodId }.toSet()
        val allFavorites = favoriteFoodRepository.getAll().first()

        return allFavorites.map { fav ->
            val foodId = fav.id.toString()
            val ctxEntry = contextualTop.find { it.foodId == foodId }
            RankedFood(
                foodId = foodId,
                foodName = fav.name,
                contextScore = ctxEntry?.usageCount ?: 0,
                isContextualHit = foodId in contextualIds
            )
        }.sortedWith(
            compareByDescending<RankedFood> { it.isContextualHit }
                .thenByDescending { it.contextScore }
        )
    }

    suspend fun recordFoodUsage(foodId: String, foodName: String) {
        val now = LocalDateTime.now()
        val hour = now.hour
        val dow = now.dayOfWeek.value
        val updated = contextDao.incrementUsage(foodId, hour, dow)
        if (updated == 0) {
            contextDao.insert(FoodUsageContext(foodId = foodId, foodName = foodName, hourBucket = hour, dayOfWeek = dow))
        }
    }
}
