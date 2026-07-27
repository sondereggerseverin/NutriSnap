package ch.nutrisnap.app.data.api

import android.util.Log
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SwissFoodApi {

    private const val TAG = "SwissFoodApi"

    // "naehrwertdaten.ch" ist nur die WordPress-Marketingseite — die eigentliche
    // Web-App/API läuft auf diesem Host (bestätigt über og:image/canonical-URLs
    // der offiziellen Seiten). Der genaue REST-Pfad ist nur als .docx dokumentiert
    // ("The-Swiss-Food-Composition-Database-API-descripton_V3...docx", verlinkt
    // unter naehrwertdaten.ch/en/downloads/) und konnte hier nicht programmatisch
    // gelesen werden. Falls die Suche weiterhin leer bleibt: Logcat-Tag "SwissFoodApi"
    // prüfen (HTTP-Code + Body werden jetzt geloggt statt verschluckt) und Pfad anhand
    // der echten Antwort/des Docx anpassen.
    private const val BASE_URL = "https://webapp.prod.blv.foodcase-services.com/api/v1"
    private const val LANG = "de"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = 20): List<FoodItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(query.take(80), "UTF-8")
                val url = "$BASE_URL/food?lang=$LANG&search=$encoded&pageSize=$limit"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NutriSnap/1.0 (Android)")
                    .build()
                val response = client.newCall(req).execute()
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w(TAG, "Suche fehlgeschlagen (HTTP ${response.code}) für \"$query\": ${body.take(300)}")
                    return@runCatching emptyList()
                }
                val root = JSONObject(body)
                val items = root.optJSONArray("data")
                if (items == null) {
                    Log.w(TAG, "Unerwartetes Antwortformat für \"$query\": ${body.take(300)}")
                    return@runCatching emptyList()
                }
                (0 until items.length()).mapNotNull { i ->
                    parseFoodItem(items.getJSONObject(i))
                }
            }.onFailure { e -> Log.w(TAG, "Suche für \"$query\" fehlgeschlagen: ${e.message}") }
                .getOrDefault(emptyList())
        }

    suspend fun getById(id: String): FoodItem? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE_URL/food/$id?lang=$LANG"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NutriSnap/1.0 (Android)")
                    .build()
                val body = client.newCall(req).execute().use {
                    it.body?.string() ?: return@runCatching null
                }
                parseFoodItem(JSONObject(body))
            }.getOrNull()
        }

    /** Liest ein Naehrwertfeld als Float, oder null wenn nicht vorhanden. Unterscheidet
     *  damit "wirklich 0" von "von der Quelle nicht geliefert" - im Gegensatz zu einem
     *  Default-Wert oder einem takeIf{it>0}-Filter, der echte Nullen mitverwerfen wuerde. */
    private fun JSONObject.g(key: String): Float? =
        if (has(key) && !isNull(key)) optDouble(key, Double.NaN).toFloat().takeIf { !it.isNaN() } else null

    private fun parseFoodItem(obj: JSONObject): FoodItem? {
        return try {
            val name = obj.optString("name", "").ifBlank { return null }
            val nutrients = obj.optJSONObject("nutrients") ?: return null
            val calories = nutrients.optDouble("energy_kcal", -1.0).toFloat()
            if (calories < 0) return null
            val brand = obj.optString("brand", "").ifBlank {
                obj.optString("company", "").ifBlank { null }
            }
            FoodItem(
                name = name,
                brand = brand,
                calories = calories,
                protein = nutrients.g("protein"),
                carbs = nutrients.g("carbohydrates"),
                fat = nutrients.g("fat"),
                fiber = nutrients.g("fiber"),
                sugar = nutrients.g("sugar"),
                saturatedFat = nutrients.g("saturated_fat"),
                sodium = nutrients.g("sodium"),
                servingSize = 100f,
                servingUnit = "g",
                source = FoodSource.SWISS_FSVO,
                completenessScore = 85
            )
        } catch (e: Exception) {
            null
        }
    }
}
