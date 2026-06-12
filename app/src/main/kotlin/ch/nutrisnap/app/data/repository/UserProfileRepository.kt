package ch.nutrisnap.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ch.nutrisnap.app.data.db.NutriDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_profile")

data class UserProfile(
    val dailyCalorieGoal: Int   = 2000,
    val proteinGoalG:     Float = 120f,
    val weightKg:         Float = 0f,
    val heightCm:         Int   = 0,
    val ageYears:         Int   = 0,
    val activityFactor:   Float = 1.55f   // moderately active default
) {
    /** Returns computed TDEE if enough data is present, null otherwise */
    fun computedTdee(): Double? {
        if (weightKg <= 0f || heightCm <= 0 || ageYears <= 0) return null
        // Mifflin-St Jeor (gender-neutral average)
        val bmr = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
        return bmr * activityFactor
    }
}

class UserProfileRepository(private val db: NutriDatabase) {

    // Stored in a simple companion object for now; swap to DataStore if needed
    private val _profile = kotlinx.coroutines.flow.MutableStateFlow(UserProfile())

    fun get(): Flow<UserProfile> = _profile

    suspend fun update(profile: UserProfile) {
        _profile.value = profile
    }
}
