package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.db.toDomain
import ch.nutrisnap.app.data.db.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class UserProfile(
    val dailyCalorieGoal: Int   = 2000,
    val proteinGoalG:     Float = 120f,
    val carbsGoalG:       Float = 220f,
    val fatGoalG:         Float = 65f,
    val weightKg:         Float = 0f,
    val heightCm:         Int   = 0,
    val ageYears:         Int   = 0,
    val activityFactor:   Float = 1.55f
) {
    fun computedBmr(): Double? {
        if (weightKg <= 0f || heightCm <= 0 || ageYears <= 0) return null
        return 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
    }

    fun computedTdee(): Double? = computedBmr()?.let { it * activityFactor }
}

class UserProfileRepository(private val db: NutriDatabase) {

    /**
     * Live-reactive: emits again whenever the profile row changes (e.g. after Settings
     * saves a new calorie/macro goal), so every screen combining this Flow (Home, Diary,
     * Analysis, Stats, ...) updates without needing to be recreated. Previously this
     * read once via a suspend query wrapped in `flow {}`, so edits never propagated to
     * already-open screens until the app/ViewModel was restarted.
     */
    fun get(): Flow<UserProfile> =
        db.userProfileDao().observe().map { it?.toDomain() ?: UserProfile() }.flowOn(Dispatchers.IO)

    suspend fun update(profile: UserProfile) = withContext(Dispatchers.IO) {
        db.userProfileDao().upsert(profile.toEntity())
    }
}
