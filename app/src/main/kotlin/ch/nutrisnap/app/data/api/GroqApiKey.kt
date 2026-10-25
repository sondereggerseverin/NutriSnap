package ch.nutrisnap.app.data.api

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bring-Your-Own-Key für Groq: optionaler Nutzer-Key aus DataStore, sonst BuildConfig.
 * [init] einmal in Application.onCreate aufrufen – danach ist [get] überall synchro nutzbar.
 */
object GroqApiKey {
    val KEY_USER_GROQ_API_KEY = stringPreferencesKey("user_groq_api_key")

    @Volatile
    private var app: Application? = null

    /** Nutzer-Key (trim); null/leer = BuildConfig nutzen. */
    @Volatile
    private var cachedUserKey: String? = null

    fun init(application: Application) {
        app = application
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            application.notifDataStore.data
                .map { it[KEY_USER_GROQ_API_KEY]?.trim().orEmpty() }
                .collect { user ->
                    cachedUserKey = user.ifBlank { null }
                }
        }
    }

    /** Effektiver Key: Nutzer-Override oder gebündelter BuildConfig-Key. */
    fun get(): String {
        val user = cachedUserKey
        if (!user.isNullOrBlank()) return user
        return runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
    }

    fun hasUserKey(): Boolean = !cachedUserKey.isNullOrBlank()

    /** Maskierte Anzeige in Settings (z. B. gsk_…xxxx). */
    fun masked(): String {
        val k = cachedUserKey ?: return ""
        if (k.length <= 8) return "••••"
        return k.take(4) + "…" + k.takeLast(4)
    }

    suspend fun setUserKey(key: String) {
        val ctx = app ?: return
        ctx.notifDataStore.edit { prefs ->
            val t = key.trim()
            if (t.isEmpty()) prefs.remove(KEY_USER_GROQ_API_KEY)
            else prefs[KEY_USER_GROQ_API_KEY] = t
        }
        cachedUserKey = key.trim().ifBlank { null }
    }

    suspend fun clearUserKey() = setUserKey("")
}
