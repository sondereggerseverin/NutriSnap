package ch.nutrisnap.app.data.repository

import android.content.Context
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * Importiert den mitgelieferten OpenFoodFacts-Katalog (assets/off_catalog.jsonl.gz,
 * erzeugt von scripts/build_off_catalog.py) in die lokale food_items-Tabelle.
 *
 * - Idempotent: Dedup über Barcode und name|brand (lowercase) gegen den Bestand.
 *   Bestehende Einträge (z. B. eigene Produkte) werden nie überschrieben.
 * - Fehlt die Asset-Datei (Katalog noch nicht gebaut), passiert nichts.
 * - Über einen Versions-Marker (Dateigrösse) wird nur bei neuem Katalog erneut importiert.
 *
 * Daten: OpenFoodFacts, Open Database License (ODbL 1.0), https://world.openfoodfacts.org
 */
object OffCatalogImporter {
    const val ASSET_NAME = "off_catalog.jsonl.gz"
    private const val PREFS = "off_catalog"
    private const val KEY_VERSION = "imported_version"
    private const val BATCH = 500

    /** Gibt die Anzahl neu importierter Produkte zurück (0 = nichts zu tun). */
    suspend fun importIfNeeded(context: Context): Int {
        val version = assetVersion(context) ?: return 0 // Katalog nicht vorhanden
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_VERSION, -1L) == version) return 0

        val dao = NutriDatabase.getInstance(context).foodItemDao()
        val barcodes = dao.getAllBarcodes().toHashSet()
        val keys = dao.getAllNameBrandKeys().toHashSet()

        var imported = 0
        val batch = ArrayList<FoodItem>(BATCH)
        context.assets.open(ASSET_NAME).use { raw ->
            GZIPInputStream(raw).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val item = parseLine(line) ?: continue
                    val bc = item.barcode
                    if (bc != null && !barcodes.add(bc)) continue
                    if (!keys.add(dedupKey(item.name, item.brand))) continue
                    batch.add(item)
                    if (batch.size >= BATCH) {
                        dao.insertAll(batch.toList())
                        imported += batch.size
                        batch.clear()
                    }
                }
            }
        }
        if (batch.isNotEmpty()) {
            dao.insertAll(batch.toList())
            imported += batch.size
        }
        prefs.edit().putLong(KEY_VERSION, version).apply()
        return imported
    }

    internal fun dedupKey(name: String, brand: String?): String =
        "${name.lowercase()}|${(brand ?: "").lowercase()}"

    internal fun parseLine(line: String): FoodItem? {
        if (line.isBlank()) return null
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val name = o.str("name") ?: return null
        if (!o.has("caloriesPer100g")) return null
        return FoodItem(
            name = name,
            brand = o.str("brand"),
            barcode = o.str("barcode"),
            calories = o.optDouble("caloriesPer100g").toFloat(),
            protein = o.optDouble("proteinPer100g").toFloat(),
            carbs = o.optDouble("carbsPer100g").toFloat(),
            fat = o.optDouble("fatPer100g").toFloat(),
            fiber = o.optDouble("fiberPer100g", 0.0).toFloat(),
            sugar = o.optDouble("sugarPer100g", 0.0).toFloat(),
            salt = o.optDouble("saltPer100g", 0.0).toFloat(),
            source = FoodSource.OPEN_FOOD_FACTS
        )
    }

    // org.json liefert bei JSON-null den String "null" -> explizit abfangen.
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key, "").trim().ifEmpty { null }

    private fun assetVersion(context: Context): Long? = runCatching {
        context.assets.open(ASSET_NAME).use { it.available().toLong().coerceAtLeast(1L) }
    }.getOrNull()
}
