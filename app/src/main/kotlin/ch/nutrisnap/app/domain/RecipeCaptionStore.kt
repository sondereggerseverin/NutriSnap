package ch.nutrisnap.app.domain

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistenter Caption-Cache über App-Neustarts hinweg.
 * Speicher: filesDir/recipe_caption_cache.json (kein Room-Migration nötig).
 */
class RecipeCaptionStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "recipe_caption_cache.json")
    private val map = ConcurrentHashMap<String, String>()
    private val maxEntries = 200

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val obj = JSONObject(file.readText())
            obj.keys().forEach { key ->
                val v = obj.optString(key).trim()
                if (v.length >= 40) map[key] = v
            }
        }
    }

    fun get(url: String): String? = map[normalize(url)]

    fun put(url: String, caption: String) {
        val c = caption.trim()
        if (c.length < 40) return
        map[normalize(url)] = c.take(8000)
        // LRU-ähnlich: bei Überlauf älteste Keys entfernen (JSON-Reihenfolge unzuverlässig → zufällig kürzen)
        if (map.size > maxEntries) {
            map.keys.take(map.size - maxEntries).forEach { map.remove(it) }
        }
        persist()
    }

    private fun persist() {
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            file.writeText(obj.toString())
        }
    }

    companion object {
        fun normalize(url: String): String =
            url.trim().lowercase().substringBefore("?").trimEnd('/')
    }
}
