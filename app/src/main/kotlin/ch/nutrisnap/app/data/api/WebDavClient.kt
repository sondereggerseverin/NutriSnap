package ch.nutrisnap.app.data.api

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimaler WebDAV-Client für Backup-Upload/Download (PUT/GET).
 * Credentials liegen in DataStore (notif_prefs).
 */
object WebDavClient {
    val KEY_WEBDAV_URL = stringPreferencesKey("webdav_url")
    val KEY_WEBDAV_USER = stringPreferencesKey("webdav_user")
    val KEY_WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
    val KEY_WEBDAV_PATH = stringPreferencesKey("webdav_path")

    private const val DEFAULT_PATH = "nutrisnap_backup.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Config(
        val baseUrl: String,
        val user: String,
        val password: String,
        val path: String
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank()
    }

    suspend fun loadConfig(app: Application): Config {
        val prefs = app.notifDataStore.data.first()
        return Config(
            baseUrl = prefs[KEY_WEBDAV_URL]?.trim().orEmpty().trimEnd('/'),
            user = prefs[KEY_WEBDAV_USER]?.trim().orEmpty(),
            password = prefs[KEY_WEBDAV_PASSWORD].orEmpty(),
            path = prefs[KEY_WEBDAV_PATH]?.trim()?.ifBlank { null } ?: DEFAULT_PATH
        )
    }

    suspend fun saveConfig(
        app: Application,
        url: String,
        user: String,
        password: String,
        path: String = DEFAULT_PATH
    ) {
        app.notifDataStore.edit { p ->
            p[KEY_WEBDAV_URL] = url.trim().trimEnd('/')
            p[KEY_WEBDAV_USER] = user.trim()
            if (password.isNotBlank()) p[KEY_WEBDAV_PASSWORD] = password
            p[KEY_WEBDAV_PATH] = path.trim().ifBlank { DEFAULT_PATH }
        }
    }

    suspend fun clearPassword(app: Application) {
        app.notifDataStore.edit { it.remove(KEY_WEBDAV_PASSWORD) }
    }

    private fun targetUrl(config: Config): String {
        val base = config.baseUrl.trimEnd('/')
        val path = config.path.trim().trimStart('/')
        return "$base/$path"
    }

    private fun authHeader(config: Config): String? {
        if (config.user.isBlank()) return null
        return Credentials.basic(config.user, config.password)
    }

    /** Lädt die Backup-Datei per GET. */
    suspend fun download(app: Application): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val config = loadConfig(app)
            require(config.isConfigured) { "WebDAV-URL fehlt" }
            val req = Request.Builder()
                .url(targetUrl(config))
                .get()
                .apply { authHeader(config)?.let { header("Authorization", it) } }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.message}")
                resp.body?.string() ?: error("Leere Antwort")
            }
        }
    }

    /** Speichert den JSON-String per PUT. */
    suspend fun upload(app: Application, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = loadConfig(app)
            require(config.isConfigured) { "WebDAV-URL fehlt" }
            val body = content.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(targetUrl(config))
                .put(body)
                .apply { authHeader(config)?.let { header("Authorization", it) } }
                .header("Content-Type", "application/json; charset=utf-8")
                .build()
            client.newCall(req).execute().use { resp ->
                // 200/201/204 = ok
                if (resp.code !in listOf(200, 201, 204)) {
                    error("HTTP ${resp.code}: ${resp.message}")
                }
            }
        }
    }
}
