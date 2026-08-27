package ch.nutrisnap.app.domain

import android.content.Context
import android.util.Log
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeScrapeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RecipeScraper(private val context: Context) {

    class InstagramBlockedException(url: String) : Exception("INSTAGRAM_BLOCKED:$url")

    companion object {
        private const val TAG = "NutriSnapImport"

        /** Caption-Cache pro normalisierter URL (Prozess-Lebensdauer). */
        private val captionCache = ConcurrentHashMap<String, String>()
        private fun cacheKey(url: String) = url.trim().lowercase().substringBefore("?").trimEnd('/')

        /**
         * Letzter Import-Diagnosebericht (Logcat + kopierbar).
         * Bei schlechter Qualität: adb logcat -s NutriSnapImport
         */
        @Volatile
        var lastImportReport: String = ""
            private set

        private fun publishReport(report: String) {
            lastImportReport = report
            Log.i(TAG, report)
        }
    }

    private val persistentStore by lazy { RecipeCaptionStore(context) }
    private var progress: (String) -> Unit = {}
    private var usePersistentCache: Boolean = true
    private var useVideoTranscript: Boolean = false

    private fun isGoodCaption(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        if (t.length < 40) return false
        // HTML/JS-Müll von Jina/Mirrors niemals als Caption akzeptieren
        if (looksLikeHtmlOrCode(t)) return false
        val lc = t.lowercase()
        val recipeHints = listOf(
            "zutaten", "ingredient", "rezept", "recipe", "anleitung", "instructions",
            "zubereitung", "method", " tbsp", " tsp", " el ", " tl ", "gramm", " ml", " cup",
            "päckchen", "paeckchen", "backpulver", "portionen"
        )
        // Mindestens 2 Mengenangaben (380g / 500 g / 1 EL) → klar strukturierte Caption
        val qtyCount = Regex(
            """\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tsp|tbsp|cup|oz|lb|stück|stk|päckchen|paeckchen)\b""",
            RegexOption.IGNORE_CASE
        ).findAll(t).count()
        if (qtyCount >= 2) return true
        return t.length >= 80 || recipeHints.any { it in lc }
    }

    /** true wenn das geparste Rezept brauchbare Zutaten hat (nicht Platzhalter/leer). */
    private fun hasUsableIngredients(recipe: Recipe): Boolean {
        val s = recipe.ingredients.trim()
        if (s.isBlank()) return false
        if (s.startsWith("Tippe") || s.equals("Zutaten nicht gefunden.", true)) return false
        if (s.length < 12) return false
        val lines = s.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false
        // Mindestens eine Zeile mit Menge oder 2+ Lebensmittel-Zeilen
        val hasQty = lines.any {
            Regex(
                """(\d+/\d+|\d+[.,]?\d*)\s*(g|kg|ml|l|el|tl|tsp|tbsp|cups?|oz|päckchen|paeckchen|packung)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(it)
        }
        return hasQty || lines.size >= 3
    }

    /**
     * Qualitäts-Score 0–100 für Zutatenliste.
     * Wenige Zeilen / kein Topping / Promo-Titel → niedriger Score → Caption nachladen.
     */
    private fun ingredientQualityScore(recipe: Recipe): Int {
        val lines = recipe.ingredients.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0
        var score = 0
        val qtyLines = lines.count {
            Regex(
                """(\d+/\d+|\d+[–\-]\d+[.,]?\d*|\d+[.,]?\d*)\s*(g|kg|ml|l|el|tl|tsp|tbsp|cups?|oz)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(it) ||
                it.contains("nach wahl", true) || it.contains("nach bedarf", true)
        }
        score += (qtyLines * 12).coerceAtMost(60)
        score += (lines.size * 4).coerceAtMost(20)
        val blob = recipe.ingredients.lowercase()
        // Typische DE-Overnight-Oats-Signale
        if ("chia" in blob) score += 8
        if ("agave" in blob || "ahorn" in blob) score += 6
        if ("milch" in blob) score += 6
        if ("topping" in blob || "haselnussmus" in blob || "schokolade" in blob) score += 8
        if (RecipeAiParser.isPromoTitle(recipe.title) ||
            Regex("""^\d+\s*g\b""", RegexOption.IGNORE_CASE).containsMatchIn(recipe.title)
        ) score -= 25
        // Einzeiler-Zubereitung + wenig Zutaten = oft unvollständig
        if (lines.size <= 4 && recipe.instructions.lines().size <= 1) score -= 10
        return score.coerceIn(0, 100)
    }

    private fun isWeakImport(recipe: Recipe): Boolean =
        !hasUsableIngredients(recipe) || ingredientQualityScore(recipe) < 45

    /**
     * AMM-tauglich: genug Zutaten UND (Schritte oder klare Mengenliste).
     * Server-Ergebnis mit diesem Score wird 1:1 übernommen.
     */
    private fun isAmmQuality(recipe: Recipe): Boolean {
        if (!hasUsableIngredients(recipe)) return false
        val score = ingredientQualityScore(recipe)
        val instrOk = recipe.instructions.trim().length >= 40
        val lines = recipe.ingredients.lines().count { it.isNotBlank() }
        // Starke Zutatenliste allein reicht; mit Schritten noch besser
        return score >= 55 || (lines >= 5 && score >= 40) || (instrOk && score >= 35)
    }

    private fun looksLikeIngredientAsTitle(title: String): Boolean {
        val t = title.trim()
        if (Regex("""^\d+([.,]\d+)?\s*(g|kg|ml|l|el|tl)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t))
            return true
        if (t.length < 40 && Regex(
                """(?i)\b(hafermehl|mehl|joghurt|milch|ei|eier|zucker|salz|öl|oel)\b"""
            ).containsMatchIn(t) && Regex("""\d""").containsMatchIn(t)
        ) return true
        return false
    }

    /** DOCTYPE, script, Cloudflare-Challenge usw. → kein Rezepttext. */
    private fun looksLikeHtmlOrCode(text: String): Boolean {
        val t = text.trimStart().lowercase()
        if (t.startsWith("<!doctype") || t.startsWith("<html") || t.startsWith("<head")) return true
        if ("</html>" in t || "</body>" in t || "<script" in t) return true
        if ("content-security-policy" in t || "challenges.cloudflare.com" in t) return true
        if ("window._shareddata" in t || ("document." in t && "cookie" in t)) return true
        val tagCount = Regex("""<[a-zA-Z/!][^>]*>""").findAll(text).count()
        return tagCount >= 5
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS) // Jina/Mirror brauchen oft länger als IG-Embed
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
                // Wichtig: Accept-Encoding NICHT manuell setzen — sonst dekomprimiert
                // OkHttp die Antwort nicht und Mirror/Embed-HTML kommt als Müll an.
                .header("Cache-Control", "no-cache")
                .build()
            chain.proceed(req)
        }
        .build()

    /**
     * @param fastScrape kürzerer IG-Race-Timeout, weniger Mirror-Quellen
     * @param fastAi     Groq 8B Instant statt 70B beim Caption-Parse
     * @param persistentCache Caption über App-Neustart speichern
     * @param videoTranscript bei schwacher Caption Whisper-Transkript holen
     */
    suspend fun scrape(
        rawUrl: String,
        onProgress: (String) -> Unit = {},
        fastScrape: Boolean = false,
        fastAi: Boolean = false,
        persistentCache: Boolean = true,
        videoTranscript: Boolean = false
    ): RecipeScrapeResult = withContext(Dispatchers.IO) {
        progress = onProgress
        usePersistentCache = persistentCache
        useVideoTranscript = videoTranscript
        runCatching {
            val url      = rawUrl.trim()
            val platform = detectPlatform(url)
            progress("Link erkennen…")
            val recipe   = when (platform) {
                "instagram" -> scrapeWithRetry { forceRefresh ->
                    scrapeInstagram(url, fastScrape, fastAi, forceRefresh)
                }
                "tiktok"    -> scrapeWithRetry { forceRefresh ->
                    scrapeTikTok(url, fastAi, forceRefresh)
                }
                else        -> {
                    progress("Seite laden…")
                    scrapeWeb(url, platform, fastAi)
                }
            }
            RecipeScrapeResult(success = true, recipe = recipe)
        }.getOrElse { e ->
            when (e) {
                is InstagramBlockedException ->
                    RecipeScrapeResult(success = false, error = e.message, instagramBlocked = true)
                else ->
                    RecipeScrapeResult(success = false, error = "Fehler: ${e.message}")
            }
        }
    }

    /**
     * Instagram/TikTok blocken Scraper gelegentlich nur kurzzeitig (Rate-Limit).
     * Ein schwaches Ergebnis (Score < 50, inkl. leerer Caption/Exception) heisst
     * oft nicht "kaputt", sondern "gerade blockiert". Ein einziger Retry nach
     * kurzer Pause – mit erzwungenem Cache-Bypass, damit nicht dieselbe dünne
     * Caption nochmal verwendet wird – bringt in der Praxis meist das gleiche
     * saubere Ergebnis wie eine manuelle Struktur (siehe Reel-Retest 09:53→10:17).
     */
    private suspend fun scrapeWithRetry(block: suspend (forceRefresh: Boolean) -> Recipe): Recipe {
        val firstAttempt = runCatching { block(false) }
        val first = firstAttempt.getOrNull()
        val firstScore = first?.let { ingredientQualityScore(it) } ?: -1

        if (first != null && firstScore >= 50) return first

        progress("Kurze Pause, erneuter Versuch…")
        delay(3_500L)

        val retryAttempt = runCatching { block(true) }
        val retry = retryAttempt.getOrNull()
        val retryScore = retry?.let { ingredientQualityScore(it) } ?: -1

        return when {
            retry != null && retryScore >= firstScore -> retry
            first != null -> first
            else -> throw (retryAttempt.exceptionOrNull() ?: firstAttempt.exceptionOrNull()!!)
        }
    }

    private fun loadCachedCaption(key: String): String {
        captionCache[key]?.let { if (isGoodCaption(it)) return it }
        if (usePersistentCache) {
            persistentStore.get(key)?.let { c ->
                if (isGoodCaption(c)) {
                    captionCache[key] = c
                    return c
                }
            }
        }
        return ""
    }

    private fun saveCachedCaption(key: String, caption: String) {
        if (!isGoodCaption(caption)) return
        captionCache[key] = caption
        if (usePersistentCache) persistentStore.put(key, caption)
    }

    /**
     * Bei schwacher Caption optional Whisper-Transkript anhängen.
     */
    private suspend fun enrichWithTranscript(
        platform: String,
        url: String,
        shortcode: String?,
        caption: String
    ): String {
        if (!useVideoTranscript) return caption
        if (!VideoTranscriptService.isWeakCaption(caption) && isGoodCaption(caption)) return caption
        progress("Video-Transkript…")
        // Reines Enrichment — darf den Hauptpfad nie blockieren. Hartes
        // Gesamtbudget statt der OkHttp-Timeouts (bis zu ~90s) durchschlagen zu lassen.
        return withTimeoutOrNull(12_000L) {
            val mediaUrl = when (platform) {
                "tiktok" -> VideoTranscriptService.resolveTikTokMediaUrl(url)
                "instagram" -> shortcode?.let { VideoTranscriptService.resolveInstagramMediaUrl(it) }
                else -> null
            } ?: return@withTimeoutOrNull caption
            val transcript = VideoTranscriptService.transcribe(mediaUrl) ?: return@withTimeoutOrNull caption
            buildString {
                if (caption.isNotBlank()) {
                    append(caption.trim())
                    append("\n\n--- Transkript ---\n")
                }
                append(transcript.trim())
            }
        } ?: caption
    }

    private fun detectPlatform(url: String) = when {
        "instagram.com" in url || "instagr.am" in url -> "instagram"
        "tiktok.com" in url                           -> "tiktok"
        "youtube.com" in url || "youtu.be" in url     -> "youtube"
        else                                          -> "web"
    }

    // ── INSTAGRAM ──────────────────────────────────────────────────────────────

    private suspend fun scrapeInstagram(
        url: String,
        fastScrape: Boolean = false,
        fastAi: Boolean = false,
        forceRefresh: Boolean = false
    ): Recipe = coroutineScope {
        val shortcode = extractInstagramShortcode(url)
        // Collection-/F12-Links sind oft /p/SHORTCODE auch bei Reels.
        // Share-Link aus der App ist korrekt /reel/… — beide Varianten versuchen.
        val canonicalUrls = instagramCanonicalUrls(url, shortcode)
        val key = shortcode?.let { "ig:$it" } ?: cacheKey(url)

        // Caption parallel zum Server starten (für Merge fehlender Zeilen wie "ca. 150 ml Milch")
        val captionJob = async {
            val cached = if (forceRefresh) "" else loadCachedCaption(key)
            if (isGoodCaption(cached)) cached
            else {
                val c = raceInstagramCaption(canonicalUrls.first(), shortcode, fastScrape)
                if (isGoodCaption(c)) saveCachedCaption(key, c)
                c
            }
        }

        // ── AMM-Pfad: Server + Caption-Merge ─────────────────────────────────
        // Bestes Server-Ergebnis wird gemerkt, auch wenn es die strenge
        // AMM-Schwelle knapp verfehlt – ein sauber übersetztes, aber leicht
        // unvollständiges Groq-Ergebnis ist einem rohen Regex-Fallback fast
        // immer vorzuziehen (siehe finaler Vergleich unten).
        var bestServerCandidate: Recipe? = null
        if (RecipeNormalizeServer.isConfigured()) {
            progress("Server-Import…")
            val serverRecipe = RecipeNormalizeServer.importFromUrl(url, "instagram")
            if (serverRecipe != null) {
                // Caption max. 6s mitnehmen, dann fehlende Qty-Zeilen (Milch, ca. ml) nachziehen
                val capForMerge = withTimeoutOrNull(6_000L) { captionJob.await() }.orEmpty()
                val merged = if (isGoodCaption(capForMerge)) {
                    serverRecipe.copy(
                        ingredients = RecipeAiParser.mergeIngredientsFromCaption(
                            serverRecipe.ingredients, capForMerge
                        )
                    )
                } else serverRecipe
                if (isAmmQuality(merged)) {
                    publishReport(
                        "path=amm-server+merge score=${ingredientQualityScore(merged)} " +
                            "ing=${merged.ingredients.lines().count { it.isNotBlank() }} " +
                            "captionChars=${capForMerge.length} url=$url"
                    )
                    return@coroutineScope merged.copy(
                        sourceUrl = url,
                        platform = "instagram",
                        tags = merged.tags.ifBlank { "instagram" }
                    )
                }
                if (hasUsableIngredients(merged)) bestServerCandidate = merged
            }
        }

        progress("Metadaten laden…")
        val oEmbedJobs = canonicalUrls.map { u ->
            async {
                runCatching {
                    fetchOEmbed("https://api.instagram.com/oembed/?url=${encode(u)}&omitscript=true")
                }.getOrNull()?.takeIf { !it["thumbnail_url"].isNullOrBlank() || !it["title"].isNullOrBlank() }
            }
        }

        var caption = withTimeoutOrNull(12_000L) { captionJob.await() }.orEmpty()
        if (!isGoodCaption(caption)) {
            progress("Caption holen…")
            caption = raceInstagramCaption(canonicalUrls.first(), shortcode, fastScrape)
            if (isGoodCaption(caption)) saveCachedCaption(key, caption)
        } else {
            progress("Caption bereit…")
        }

        // oEmbed ist an dieser Stelle praktisch immer schon fertig (lief parallel zur
        // Caption-Race); maximal 2s zusätzlich warten, Rest hart canceln.
        val oEmbed = withTimeoutOrNull(2_000L) {
            oEmbedJobs.firstNotNullOfOrNull { it.await() }
        }
        oEmbedJobs.forEach { it.cancel() }
        var thumbnail = oEmbed?.get("thumbnail_url")
        val author = oEmbed?.get("author_name")

        if (thumbnail.isNullOrBlank() && shortcode != null) {
            thumbnail = "https://www.instagram.com/p/$shortcode/media/?size=l"
        }

        val oEmbedTitle = oEmbed?.get("title")?.trim().orEmpty()
            .let { RecipeAiParser.cleanCaption(it) }

        // Letzter Fallback: oEmbed-Titel (manchmal Caption-Ausschnitt)
        if (!isGoodCaption(caption) && isGoodCaption(oEmbedTitle)) {
            caption = oEmbedTitle
        }

        // Optional: Whisper wenn Caption dünn (Toggle)
        caption = enrichWithTranscript("instagram", url, shortcode, caption)
        if (isGoodCaption(caption)) saveCachedCaption(key, caption)

        if (!isGoodCaption(caption)) throw InstagramBlockedException(url)

        var workingCaption = caption

        // Caption an Server → strukturiertes Rezept (AMM-Fallback wenn URL-Fetch dünn war)
        if (RecipeNormalizeServer.isConfigured()) {
            progress("Server-Normalisierung…")
            val serverCap = RecipeNormalizeServer.normalize(
                workingCaption, url, "instagram", thumbnail
            )
            if (serverCap != null && isAmmQuality(serverCap)) {
                publishReport(
                    "path=amm-server-caption score=${ingredientQualityScore(serverCap)} " +
                        "captionChars=${workingCaption.length} url=$url"
                )
                return@coroutineScope serverCap.copy(
                    sourceUrl = url,
                    platform = "instagram",
                    imageUrl = thumbnail ?: serverCap.imageUrl,
                    tags = listOfNotNull(
                        serverCap.tags.ifBlank { null },
                        author?.let { "@$it" }
                    ).joinToString(",").take(200)
                )
            }
            if (serverCap != null && hasUsableIngredients(serverCap) &&
                (bestServerCandidate == null ||
                    ingredientQualityScore(serverCap) > ingredientQualityScore(bestServerCandidate!!))
            ) {
                bestServerCandidate = serverCap
            }
        }

        // Nur wenn Server ausfällt: lokaler Parser + Caption-Merge
        progress("Lokal extrahieren…")
        var parsed = parseCaptionToRecipe(workingCaption, url, "instagram", thumbnail, fastAi)
        var pathUsed = "local-fallback"
        val serverUrlScore = -1

        if (isWeakImport(parsed)) {
            progress("Struktur nachbessern…")
            val fallback = RecipeAiParser.fallbackParse(workingCaption, url, "instagram", thumbnail)
            val fbMerged = fallback.copy(
                ingredients = RecipeAiParser.mergeIngredientsFromCaption(
                    fallback.ingredients, workingCaption
                )
            )
            if (ingredientQualityScore(fbMerged) > ingredientQualityScore(parsed)) {
                parsed = fbMerged
                pathUsed = "fallbackParse+merge"
            }
        }

        parsed = parsed.copy(
            ingredients = RecipeAiParser.mergeIngredientsFromCaption(
                parsed.ingredients, workingCaption
            )
        )

        // Letzter Vergleich: ein Server-Ergebnis, das die strenge AMM-Schwelle
        // knapp verfehlt hat, ist einem rohen Regex-/lokalen Fallback fast immer
        // sprachlich/inhaltlich überlegen (echte Groq-Übersetzung vs. Wort-für-
        // Wort-Heuristik). Nur übernehmen, wenn es wirklich besser bewertet ist.
        bestServerCandidate?.let { candidate ->
            val candidateMerged = candidate.copy(
                ingredients = RecipeAiParser.mergeIngredientsFromCaption(
                    candidate.ingredients, workingCaption
                )
            )
            if (ingredientQualityScore(candidateMerged) > ingredientQualityScore(parsed)) {
                parsed = candidateMerged.copy(
                    imageUrl = thumbnail ?: candidateMerged.imageUrl
                )
                pathUsed = "server-candidate-below-amm"
            }
        }

        // Titel: nie Mengen-Zeile / Promo
        var betterTitle = RecipeAiParser.cleanDishTitle(
            when {
                parsed.title.isNotBlank() &&
                    !parsed.title.equals("Rezept", true) &&
                    !looksLikeIngredientAsTitle(parsed.title) &&
                    !RecipeAiParser.isPromoTitle(parsed.title) -> parsed.title
                oEmbedTitle.isNotBlank() -> oEmbedTitle
                else -> workingCaption
            },
            ingredients = parsed.ingredients
        )
        if (looksLikeIngredientAsTitle(betterTitle) || RecipeAiParser.isPromoTitle(betterTitle)) {
            betterTitle = RecipeAiParser.inventTitleFromIngredients(
                parsed.ingredients,
                fallback = "Overnight Oats"
            )
        }

        val score = ingredientQualityScore(parsed.copy(title = betterTitle))
        val report = buildString {
            appendLine("path=$pathUsed score=$score")
            appendLine("title=${betterTitle.take(80)}")
            appendLine("captionChars=${workingCaption.length}")
            appendLine("captionHasChia=${workingCaption.contains("chia", true)}")
            appendLine("captionHasAgave=${workingCaption.contains("agave", true)}")
            appendLine("serverUrlScore=$serverUrlScore")
            appendLine("ingLines=${parsed.ingredients.lines().count { it.isNotBlank() }}")
            appendLine("ingredientsPreview=${parsed.ingredients.take(280).replace("\n", " | ")}")
            appendLine("url=$url")
        }
        publishReport(report)

        // Bei schwachem Import kurze Diagnose in Beschreibung (Screenshot-tauglich)
        val desc = if (score < 50) {
            listOfNotNull(
                parsed.description.takeIf { it.isNotBlank() },
                "⚠️ Import unvollständig (Score $score). Log: NutriSnapImport"
            ).joinToString("\n\n")
        } else parsed.description

        parsed.copy(
            title       = betterTitle,
            description = desc,
            imageUrl    = thumbnail ?: parsed.imageUrl,
            sourceUrl   = url,
            platform    = "instagram",
            tags        = listOfNotNull(parsed.tags.ifBlank { null }, author?.let { "@$it" })
                .joinToString(",").take(200)
        )
    }

    /**
     * AMM-ähnlich: zuerst Server-Normalisierung (Supabase Edge + Groq, kostenlos),
     * dann lokaler AI-Parser, dann Regex-Fallback.
     */
    private suspend fun parseCaptionToRecipe(
        caption: String,
        url: String,
        platform: String,
        thumbnail: String?,
        fastAi: Boolean
    ): Recipe {
        fun finalize(r: Recipe): Recipe {
            val merged = RecipeAiParser.mergeIngredientsFromCaption(r.ingredients, caption)
            val title = RecipeAiParser.cleanDishTitle(r.title, merged)
            return r.copy(
                ingredients = merged,
                title = if (looksLikeIngredientAsTitle(title)) {
                    RecipeAiParser.inventTitleFromIngredients(merged, title)
                } else title
            )
        }

        // 1) Sofort: Regex-Fallback + Caption-Merge (0s Netzwerk)
        progress("Struktur aus Caption…")
        val fallback = finalize(
            RecipeAiParser.fallbackParse(caption, url, platform, thumbnail)
        )
        if (ingredientQualityScore(fallback) >= 70) {
            return fallback
        }

        // 2) Server-Normalisierung (max 10s) — oft besser als lokal, aber nicht ewig warten
        val server = if (RecipeNormalizeServer.isConfigured()) {
            progress("Server-Normalisierung…")
            withTimeoutOrNull(10_000L) {
                RecipeNormalizeServer.normalize(caption, url, platform, thumbnail)
            }
        } else null
        val serverFin = server?.let { finalize(it) }
        if (serverFin != null && ingredientQualityScore(serverFin) >= 55) {
            return if (ingredientQualityScore(serverFin) >= ingredientQualityScore(fallback)) {
                serverFin
            } else fallback
        }

        // 3) Nur wenn noch schwach: lokale KI (optional, teuer)
        progress("Rezept extrahieren…")
        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
        val local = if (apiKey.isNotBlank()) {
            withTimeoutOrNull(12_000L) {
                RecipeAiParser.parse(caption, url, platform, thumbnail, apiKey, fastModel = true)
            }
        } else null
        val localFin = local?.let { finalize(it) }

        return listOfNotNull(serverFin, localFin, fallback)
            .maxByOrNull { ingredientQualityScore(it) }
            ?: fallback
    }

    /**
     * Instagram-Caption-Race: ALLE Quellen (WebViews + schnelle HTTP-Endpunkte
     * + ggf. Mirrors) starten GLEICHZEITIG ab t=0 und racen gegeneinander — die
     * erste brauchbare Caption gewinnt, der Rest wird sofort gecancelt.
     *
     * Bugfix (war die Ursache für "Instagram-Import funktioniert gar nicht mehr"):
     * Ein früheres Phasen-Design ließ WebView erst NACH Ablauf von Phase 1
     * (den HTTP-Quellen) starten und kappte das Gesamtbudget gleichzeitig auf
     * 7–12s. Die HTTP-Endpunkte (Embed/GraphQL/Legacy-JSON/Jina/AllOrigins)
     * werden von Instagram inzwischen aber fast durchgehend blockiert — WebView
     * (volles Chromium, rendert die React-App wirklich) ist der einzige Kanal,
     * der zuverlässig etwas liefert. Weil WebView dadurch effektiv nur noch
     * ~4s Restzeit für Laden+Rendern der schweren IG-Seite hatte, lief es fast
     * immer in den Timeout → Import schlug praktisch jedes Mal fehl.
     * Fix: kein Phasen-Gate mehr, alles rennt ab t=0 parallel; Budget wieder auf
     * die zuvor bewährten Werte (10s Fast / 22s Standard).
     */
    private suspend fun raceInstagramCaption(url: String, shortcode: String?, fastScrape: Boolean = false): String =
        coroutineScope {
            data class Cap(val text: String, val source: String)

            val desktopUa =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            // Fast: 8s, Standard: 12s — WebView meldet oft ~8–12s; 22s fühlte sich wie Hänger an.
            val raceTimeoutMs = if (fastScrape) 8_000L else 12_000L

            suspend fun awaitFirstGood(
                jobs: MutableList<kotlinx.coroutines.Deferred<Cap?>>,
                timeoutMs: Long,
                onSource: ((String) -> Unit)? = null
            ): String? = withTimeoutOrNull(timeoutMs) {
                val pending = jobs.toMutableList()
                while (pending.isNotEmpty()) {
                    val done = select {
                        pending.forEach { job ->
                            job.onAwait { result -> job to result }
                        }
                    }
                    pending.remove(done.first)
                    val cap = done.second
                    if (cap != null && isGoodCaption(cap.text)) {
                        onSource?.invoke(cap.source)
                        return@withTimeoutOrNull cap.text
                    }
                }
                null
            }

            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Cap?>>()

            // ── WebViews (Geräte-Cookies, volles Chromium) — ab t=0, NICHT verzögert ──
            jobs += async {
                runCatching {
                    InstagramWebViewScraper.extractCaption(context, url)?.let { Cap(it, "webview") }
                }.getOrNull()?.takeIf { isGoodCaption(it.text) }
            }
            if (shortcode != null) {
                val altPath = if ("/reel/" in url.lowercase()) {
                    "https://www.instagram.com/p/$shortcode/"
                } else {
                    "https://www.instagram.com/reel/$shortcode/"
                }
                if (altPath != url.substringBefore("?").trimEnd('/') + "/") {
                    jobs += async {
                        runCatching {
                            InstagramWebViewScraper.extractCaption(context, altPath)
                                ?.let { Cap(it, "webview-alt") }
                        }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                    }
                }
                jobs += async {
                    runCatching {
                        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
                        InstagramWebViewScraper.extractCaption(context, embedUrl)?.let { Cap(it, "webview-embed") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }
                jobs += async {
                    runCatching {
                        val embedUrl = "https://www.instagram.com/reel/$shortcode/embed/captioned/"
                        InstagramWebViewScraper.extractCaption(context, embedUrl)
                            ?.let { Cap(it, "webview-reel-embed") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }

                // ── Schnelle HTTP-Quellen — laufen parallel zu den WebViews, nicht davor ──
                jobs += async {
                    runCatching {
                        fetchInstagramEmbedCaption(shortcode)?.let { Cap(it, "embed") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }
                jobs += async {
                    runCatching {
                        fetchInstagramLegacyJsonCaption(shortcode)?.let { Cap(it, "legacy-json") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }
                jobs += async {
                    runCatching {
                        fetchInstagramGraphqlCaption(shortcode)?.let { Cap(it, "graphql") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }
                jobs += async {
                    runCatching {
                        fetchJinaReaderCaption("https://www.instagram.com/p/$shortcode/")
                            ?.let { Cap(it, "jina-p") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }
                jobs += async {
                    runCatching {
                        fetchJinaReaderCaption("https://www.instagram.com/reel/$shortcode/")
                            ?.let { Cap(it, "jina-reel") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }
                jobs += async {
                    runCatching {
                        fetchJinaReaderCaption("https://www.instagram.com/p/$shortcode/embed/captioned/")
                            ?.let { Cap(it, "jina-embed") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }
                jobs += async {
                    runCatching {
                        val embed = "https://www.instagram.com/p/$shortcode/embed/captioned/"
                        val proxied = "https://api.allorigins.win/raw?url=${encode(embed)}"
                        val html = fetchStringWithUA(proxied, desktopUa)
                        extractCaptionFromHtml(html, embed)?.let { Cap(it, "allorigins-embed") }
                    }.getOrNull()?.takeIf { isGoodCaption(it.text) }
                }

                // ── Langsame Mirrors nur im Standard-Modus, ebenfalls ab t=0 ──
                if (!fastScrape) {
                    jobs += async {
                        runCatching {
                            val doc = jsoupGet("https://imginn.com/p/$shortcode/")
                            val t = doc.select(".desc, .photo-desc, [class*=desc], [class*=caption]").text()
                                .ifBlank { doc.select("meta[property=og:description]").attr("content") }
                            Cap(t, "imginn").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    }
                    jobs += async {
                        runCatching {
                            val doc = jsoupGet("https://www.picuki.com/media/$shortcode")
                            val t = doc.select(".photo-description, .description, [class*=caption], [class*=desc]").text()
                                .ifBlank { doc.select("meta[property=og:description]").attr("content") }
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                            Cap(t, "picuki").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    }
                    jobs += async {
                        runCatching {
                            val ezUrl = url.replace("www.instagram.com", "www.instagramez.com")
                                .replace("instagram.com", "instagramez.com")
                            val doc = jsoupGetWithUA(ezUrl, desktopUa)
                            val t = doc.select("meta[property=og:description]").attr("content")
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                            Cap(t, "instagramez").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    }
                    jobs += async {
                        runCatching {
                            val ddUrl = url.replace("www.instagram.com", "www.ddinstagram.com")
                                .replace("instagram.com", "ddinstagram.com")
                            val doc = jsoupGetWithUA(ddUrl, desktopUa)
                            val t = doc.select("meta[property=og:description]").attr("content")
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                                .ifBlank { doc.select("p, .caption, [class*=caption]").text() }
                            Cap(t, "ddinstagram").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    }
                    jobs += async {
                        runCatching {
                            val doc = jsoupGetWithUA("https://ddinstagram.com/p/$shortcode", desktopUa)
                            val t = doc.select("meta[property=og:description]").attr("content")
                                .ifBlank { doc.select("meta[name=description]").attr("content") }
                            Cap(t, "ddinstagram-apex").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    }
                    jobs += async {
                        runCatching {
                            val doc = jsoupGetWithUA("https://www.instagrapi.com/p/$shortcode", desktopUa)
                            val t = doc.select("meta[property=og:description]").attr("content")
                                .ifBlank { doc.select("[class*=caption], .caption, p").text() }
                            Cap(t, "instagrapi").takeIf { isGoodCaption(it.text) }
                        }.getOrNull()
                    }
                }
            }

            progress("Caption: Embed, GraphQL & WebView…")
            val winner = awaitFirstGood(jobs, raceTimeoutMs) { src ->
                progress("Caption von $src…")
            }

            if (!winner.isNullOrBlank()) {
                jobs.forEach { it.cancel() }
                return@coroutineScope winner
            }

            // Nur valide Captions — niemals den längsten HTML-Müll
            val best = jobs.mapNotNull { d ->
                if (d.isCompleted) runCatching { d.getCompleted() }.getOrNull()?.text else null
            }.filter { isGoodCaption(it) }.maxByOrNull { it.length }.orEmpty()
            jobs.forEach { it.cancel() }
            best
        }

    /** Jina Reader: holt die Seite serverseitig und liefert Markdown/Text. */
    private fun fetchJinaReaderCaption(targetUrl: String): String? {
        val jinaUrl = "https://r.jina.ai/${targetUrl.trim()}"
        val body = fetchStringWithUA(
            jinaUrl,
            "Mozilla/5.0 (compatible; NutriSnap/1.0; +https://nutrisnap.dev)"
        ) ?: return null
        if (body.length < 40 || looksLikeHtmlOrCode(body)) return null
        // Login-Wall / leere IG-Seiten herausfiltern
        val lc = body.lowercase()
        if ("log in" in lc && "sign up" in lc && body.length < 400) return null
        // Caption aus Markdown/Text extrahieren
        val cleaned = body
            .lineSequence()
            .filterNot { line ->
                val l = line.trim()
                l.startsWith("Title:") || l.startsWith("URL Source:") ||
                    l.startsWith("Markdown Content:") || l.startsWith("Warning:") ||
                    l.startsWith("======") || l.startsWith("------") ||
                    l.startsWith("<") // HTML-Zeilen verwerfen
            }
            .joinToString("\n")
            .trim()
        if (looksLikeHtmlOrCode(cleaned)) return null
        // Längsten zusammenhängenden Block mit Rezept-Hinweisen nehmen
        val blocks = cleaned.split(Regex("\n{2,}")).map { it.trim() }
            .filter { it.length >= 40 && !looksLikeHtmlOrCode(it) }
        val recipeHints = listOf("zutaten", "ingredient", "rezept", "recipe", "anleitung", " tbsp", " el ", "g ", "ml ")
        val best = blocks
            .filter { b ->
                val l = b.lowercase()
                recipeHints.any { it in l } || b.length >= 120
            }
            .maxByOrNull { it.length }
        // Kein Fallback auf zufälligen langen Block ohne Rezept-Hinweise
        return best?.take(6000)?.takeIf { isGoodCaption(it) }
    }

    /** Instagram Legacy-JSON: /p/{code}/?__a=1&__d=dis */
    private fun fetchInstagramLegacyJsonCaption(shortcode: String): String? {
        val urls = listOf(
            "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis",
            "https://www.instagram.com/reel/$shortcode/?__a=1&__d=dis"
        )
        for (u in urls) {
            val body = runCatching {
                fetchStringWithUA(
                    u,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
            }.getOrNull() ?: continue
            if (body.isBlank() || !body.trimStart().startsWith("{")) continue
            val caption = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.){40,})"""")
                .findAll(body)
                .map { RecipeAiParser.unescapeSocialText(it.groupValues[1]) }
                .maxByOrNull { it.length }
            if (!caption.isNullOrBlank()) return caption
        }
        return null
    }

    /**
     * Öffentliche GraphQL-Query (query_hash für shortcode media).
     * Funktioniert nicht immer, aber oft noch für öffentliche Posts.
     */
    private fun fetchInstagramGraphqlCaption(shortcode: String): String? {
        val variables = """{"shortcode":"$shortcode","child_comment_count":0,"fetch_comment_count":0,"parent_comment_count":0,"has_threaded_comments":false}"""
        val queryUrl =
            "https://www.instagram.com/graphql/query/?query_hash=9f8827793ef34641b2ca9877130b1d0&variables=${encode(variables)}"
        val body = runCatching {
            fetchStringWithUA(
                queryUrl,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        }.getOrNull() ?: return null
        if (body.isBlank() || "login" in body.lowercase().take(300)) return null
        return Regex(""""text"\s*:\s*"((?:[^"\\]|\\.){40,})"""")
            .findAll(body)
            .map { RecipeAiParser.unescapeSocialText(it.groupValues[1]) }
            .maxByOrNull { it.length }
    }

    private fun extractCaptionFromHtml(html: String, baseUri: String): String? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html, baseUri)
        val candidates = listOf(
            doc.select(".Caption, .CaptionContent, [class*=Caption]").text(),
            doc.select("meta[property=og:description]").attr("content"),
            doc.select("meta[name=description]").attr("content"),
            Regex(""""text"\s*:\s*"((?:[^"\\]|\\.){40,})"""").findAll(html)
                .map { RecipeAiParser.unescapeSocialText(it.groupValues[1]) }
                .maxByOrNull { it.length }
                .orEmpty()
        )
        return candidates.map { it.trim() }.filter { it.length >= 40 }.maxByOrNull { it.length }
    }

    /**
     * Offizielle Embed-Seite: für öffentliche Posts oft ohne Login und mit Caption im HTML.
     */
    private fun fetchInstagramEmbedCaption(shortcode: String): String? {
        val urls = listOf(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/captioned/",
            "https://www.instagram.com/p/$shortcode/embed/"
        )
        for (embedUrl in urls) {
            val html = runCatching { fetchStringWithUA(
                embedUrl,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            ) }.getOrNull() ?: continue
            if (html.isBlank() || "login" in html.lowercase().take(2000) && "caption" !in html.lowercase()) continue
            val doc = Jsoup.parse(html, embedUrl)
            val candidates = listOf(
                doc.select(".Caption, .CaptionContent, [class*=Caption]").text(),
                doc.select("meta[property=og:description]").attr("content"),
                doc.select("meta[name=description]").attr("content"),
                // JSON im Embed-Script
                Regex(""""text"\s*:\s*"((?:[^"\\]|\\.){40,})"""").findAll(html)
                    .map { RecipeAiParser.unescapeSocialText(it.groupValues[1]) }
                    .maxByOrNull { it.length }
                    .orEmpty()
            )
            val best = candidates.map { it.trim() }.filter { it.length >= 40 }.maxByOrNull { it.length }
            if (best != null && isGoodCaption(best)) return best
        }
        return null
    }

    private fun extractOgImage(doc: Document): String? {
        val candidates = listOf(
            doc.select("meta[property=og:image]").attr("content"),
            doc.select("meta[property=og:image:secure_url]").attr("content"),
            doc.select("meta[name=twitter:image]").attr("content")
        )
        return candidates.firstOrNull { it.isNotBlank() && "default" !in it.lowercase() && "logo" !in it.lowercase() }
            ?.let { if (it.startsWith("http")) it else null }
    }

    private fun extractInstagramShortcode(url: String): String? =
        Regex("/(?:p|reel|tv)/([A-Za-z0-9_-]+)/?").find(url)?.groupValues?.get(1)

    /**
     * Collection-HTML liefert Reels oft als `/p/SHORTCODE`, der Share-Button
     * als `/reel/SHORTCODE/?igsh=…`. oEmbed und WebView brauchen den passenden
     * Medientyp — daher immer beide Varianten (plus Original).
     */
    private fun instagramCanonicalUrls(url: String, shortcode: String?): List<String> {
        if (shortcode.isNullOrBlank()) return listOf(url)
        val preferred = when {
            "/reel/" in url.lowercase() -> "reel"
            "/tv/" in url.lowercase() -> "tv"
            else -> "p"
        }
        val other = if (preferred == "reel") "p" else "reel"
        return listOf(
            "https://www.instagram.com/$preferred/$shortcode/",
            "https://www.instagram.com/$other/$shortcode/",
            "https://www.instagram.com/reel/$shortcode/",
            "https://www.instagram.com/p/$shortcode/",
            url.substringBefore("?").trimEnd('/') + "/"
        ).distinct()
    }

    // ── TIKTOK ─────────────────────────────────────────────────────────────────
    // Strategy order (most reliable first):
    //  1. tikwm.com API  — free, no auth, returns title + cover + author
    //  2. snaptik / musicaldown mirror sites (og:description)
    //  3. Dedicated TikTok WebView scraper (desktop UA + TikTok DOM selectors)
    //  4. Generic og:description via Jsoup
    //  5. oEmbed title (lowest quality, no body text)

    private suspend fun scrapeTikTok(url: String, fastAi: Boolean = false, forceRefresh: Boolean = false): Recipe {
        progress("Link auflösen…")
        val expandedUrl = runCatching {
            if ("vm.tiktok.com" in url || "vt.tiktok.com" in url) {
                val req = Request.Builder().url(url).head().build()
                client.newCall(req).execute().use { it.request.url.toString() }
            } else url
        }.getOrDefault(url)
        val key = cacheKey(expandedUrl)

        // AMM-Pfad: Server zuerst, Ergebnis 1:1 übernehmen
        if (RecipeNormalizeServer.isConfigured()) {
            progress("Server-Import…")
            val serverRecipe = RecipeNormalizeServer.importFromUrl(expandedUrl, "tiktok")
            if (serverRecipe != null && isAmmQuality(serverRecipe)) {
                return serverRecipe.copy(
                    sourceUrl = url,
                    platform = "tiktok",
                    tags = serverRecipe.tags.ifBlank { "tiktok" }
                )
            }
        }

        var caption: String? = if (forceRefresh) null else loadCachedCaption(key).ifBlank { null }
        var thumbnail: String? = null
        var author: String? = null

        if (isGoodCaption(caption)) {
            progress("Aus Cache…")
        } else {
            progress("Seite laden (parallel)…")
            val raced = raceTikTokCaption(expandedUrl)
            caption = raced.first
            thumbnail = raced.second
            author = raced.third
            if (isGoodCaption(caption)) saveCachedCaption(key, caption!!)
        }

        // Optional Whisper bei schwacher Caption
        val enriched = enrichWithTranscript("tiktok", expandedUrl, null, caption.orEmpty())
        if (enriched.isNotBlank()) {
            caption = enriched
            if (isGoodCaption(caption)) saveCachedCaption(key, caption!!)
        }

        if (caption.isNullOrBlank()) {
            return Recipe(
                title        = "TikTok Rezept",
                description  = "Caption konnte nicht geladen werden. Tippe auf ✏️ und füge die Zutaten manuell ein.",
                sourceUrl    = url,
                platform     = "tiktok",
                imageUrl     = thumbnail,
                ingredients  = "",
                tags         = author?.let { "@$it" } ?: "tiktok"
            )
        }

        progress("Rezept extrahieren…")
        var workingCaption = caption!!
        var parsed = parseCaptionToRecipe(workingCaption, url, "tiktok", thumbnail, fastAi)
        if (!hasUsableIngredients(parsed)) {
            progress("Struktur nachbessern…")
            val fallback = RecipeAiParser.fallbackParse(workingCaption, url, "tiktok", thumbnail)
            if (hasUsableIngredients(fallback)) parsed = fallback
        }
        if (!hasUsableIngredients(parsed)) {
            val prevToggle = useVideoTranscript
            useVideoTranscript = true
            val reEnriched = enrichWithTranscript("tiktok", expandedUrl, null, workingCaption)
            useVideoTranscript = prevToggle
            if (reEnriched != workingCaption && isGoodCaption(reEnriched)) {
                workingCaption = reEnriched
                progress("Rezept aus Transkript…")
                parsed = parseCaptionToRecipe(workingCaption, url, "tiktok", thumbnail, fastAi = false)
                if (!hasUsableIngredients(parsed)) {
                    val fb = RecipeAiParser.fallbackParse(workingCaption, url, "tiktok", thumbnail)
                    if (hasUsableIngredients(fb)) parsed = fb
                }
            }
        }
        return parsed.copy(
            title     = RecipeAiParser.extractTitle(parsed.title.ifBlank { workingCaption }, fallback = parsed.title.ifBlank { "TikTok Rezept" }),
            imageUrl  = thumbnail ?: parsed.imageUrl,
            sourceUrl = url,
            platform  = "tiktok",
            tags      = listOfNotNull(parsed.tags.ifBlank { null }, author?.let { "@$it" }).joinToString(",").take(200)
        )
    }

    /** Parallel: tikwm API, WebView, oEmbed, Jsoup — erste brauchbare Caption. */
    private suspend fun raceTikTokCaption(expandedUrl: String): Triple<String?, String?, String?> =
        coroutineScope {
            data class Pack(val caption: String?, val thumb: String?, val author: String?)

            val jobs = listOf(
                async {
                    runCatching {
                        val apiUrl = "https://www.tikwm.com/api/?url=${encode(expandedUrl)}&hd=1"
                        val raw = fetchString(apiUrl)
                        val title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                            ?.let { RecipeAiParser.unescapeSocialText(it) }
                        val cover = Regex(""""origin_cover"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                            ?: Regex(""""cover"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                        val auth = Regex(""""unique_id"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)?.groupValues?.get(1)
                        val thumb = cover?.replace("\\u0026", "&")?.replace("\\/", "/")
                            ?.takeIf { "tiktokcdn.com" !in it }
                        Pack(title, thumb, auth).takeIf { isGoodCaption(it.caption) }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val r = TikTokWebViewScraper.extract(context, expandedUrl)
                        Pack(r.caption, null, r.author).takeIf { isGoodCaption(it.caption) }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val oEmbed = fetchOEmbed("https://www.tiktok.com/oembed?url=${encode(expandedUrl)}")
                        Pack(oEmbed["title"], oEmbed["thumbnail_url"], oEmbed["author_name"])
                            .takeIf { isGoodCaption(it.caption) || !it.caption.isNullOrBlank() }
                    }.getOrNull()
                },
                async {
                    runCatching {
                        val doc = jsoupGetWithUA(
                            expandedUrl,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
                        )
                        val t = doc.select("meta[property=og:description]").attr("content")
                            .ifBlank { doc.select("meta[name=description]").attr("content") }
                        Pack(t, extractOgImage(doc), null).takeIf { isGoodCaption(it.caption) }
                    }.getOrNull()
                }
            )

            val winner = withTimeoutOrNull(14_000L) {
                val pending = jobs.toMutableList()
                while (pending.isNotEmpty()) {
                    val done = select {
                        pending.forEach { job -> job.onAwait { result -> job to result } }
                    }
                    pending.remove(done.first)
                    val p = done.second
                    if (p != null && isGoodCaption(p.caption)) return@withTimeoutOrNull p
                }
                null
            }

            if (winner != null) {
                jobs.forEach { it.cancel() }
                return@coroutineScope Triple(winner.caption, winner.thumb, winner.author)
            }

            val any = jobs.mapNotNull { d ->
                if (d.isCompleted) runCatching { d.getCompleted() }.getOrNull() else null
            }.maxByOrNull { it.caption?.length ?: 0 }
            jobs.forEach { it.cancel() }
            Triple(any?.caption, any?.thumb, any?.author)
        }

    // ── GENERIC WEB ────────────────────────────────────────────────────────────

    /**
     * 1) schema.org/Recipe JSON-LD (inkl. nutrition) – kein AI nötig
     * 2) Falls JSON-LD schwach/fehlend → AI auf vorgefiltertem Seitentext
     * 3) Letzter Fallback: og-Tags + Listen-Selektoren
     */
    private suspend fun scrapeWeb(url: String, platform: String, fastAi: Boolean = false): Recipe {
        val doc = jsoupGet(url)

        val jsonLdBlocks = doc.select("script[type='application/ld+json']").map { it.data() }
        for (raw in jsonLdBlocks) {
            val recipeJson = extractRecipeFromJsonLd(raw) ?: continue
            val fromLd = parseJsonLd(recipeJson, url, platform, doc)
            // Nur akzeptieren wenn echte Zutaten da sind – sonst AI-Fallback
            if (fromLd.ingredients.length >= 20 &&
                !fromLd.ingredients.contains("Nicht gefunden", ignoreCase = true)
            ) {
                progress("JSON-LD erkannt…")
                return fromLd
            }
        }

        // AI auf bereinigtem Seitentext (ohne Scripts/Nav/Footer)
        val pageText = extractReadableRecipeText(doc)
        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }
        if (pageText.length >= 80 && apiKey.isNotBlank()) {
            progress("Rezept per KI extrahieren…")
            val parsed = RecipeAiParser.parse(pageText, url, platform, extractOgImage(doc), apiKey, fastModel = fastAi)
            if (parsed.ingredients.length >= 20 &&
                !parsed.ingredients.contains("nicht gefunden", ignoreCase = true)
            ) {
                return parsed.copy(
                    imageUrl = parsed.imageUrl ?: extractOgImage(doc),
                    sourceUrl = url,
                    platform = platform
                )
            }
        }

        val title = doc.select("meta[property=og:title]").attr("content").ifBlank { doc.title() }
        val desc  = doc.select("meta[property=og:description]").attr("content")
        val image = extractOgImage(doc)
        val lists = doc.select("ul li, ol li").take(30)
            .map { it.text().trim() }
            .filter { it.length in 3..120 }
            .joinToString("\n") { "• $it" }

        return Recipe(
            title        = cleanTitle(title, url),
            description  = desc.take(400),
            imageUrl     = image,
            sourceUrl    = url,
            platform     = platform,
            ingredients  = lists.ifBlank { "Zutaten nicht gefunden." },
            instructions = "Anleitung nicht gefunden.",
            tags         = platform
        )
    }

    /** Sichtbarer Rezept-Text für AI – Scripts/Styles/Nav entfernt, max. ~6k Zeichen. */
    private fun extractReadableRecipeText(doc: Document): String {
        val clone = doc.clone()
        clone.select("script, style, noscript, nav, footer, header, iframe, svg, form").remove()
        val main = clone.select("article, [itemtype*=Recipe], main, .recipe, #recipe, .entry-content")
            .firstOrNull()?.text()?.trim().orEmpty()
        val body = main.ifBlank { clone.body()?.text()?.trim().orEmpty() }
        return body.take(6000)
    }

    private fun isRecipeType(obj: org.json.JSONObject): Boolean {
        val t = obj.opt("@type") ?: return false
        return when (t) {
            is String -> t.contains("Recipe", ignoreCase = true)
            is org.json.JSONArray ->
                (0 until t.length()).any { t.optString(it).contains("Recipe", ignoreCase = true) }
            else -> false
        }
    }

    private fun extractRecipeFromJsonLd(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val obj = org.json.JSONObject(raw)
            when {
                isRecipeType(obj) -> obj.toString()
                obj.has("@graph") -> {
                    val graph = obj.getJSONArray("@graph")
                    (0 until graph.length())
                        .map { graph.getJSONObject(it) }
                        .firstOrNull { isRecipeType(it) }
                        ?.toString()
                }
                else -> null
            }
        } catch (_: Exception) {
            try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { isRecipeType(it) }
                    ?.toString()
            } catch (_: Exception) { null }
        }
    }

    private fun parseJsonLd(json: String, url: String, platform: String, doc: Document): Recipe {
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull()
            ?: return Recipe(
                title = cleanTitle(doc.title(), url),
                sourceUrl = url,
                platform = platform,
                ingredients = "Nicht gefunden.",
                tags = platform
            )

        fun str(key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return when (val v = obj.opt(key)) {
                is String -> v.trim().takeIf { it.isNotBlank() }
                is Number -> v.toString()
                is org.json.JSONArray -> v.optString(0)?.trim()?.takeIf { it.isNotBlank() }
                is org.json.JSONObject ->
                    v.optString("text").ifBlank { v.optString("name") }.ifBlank { v.optString("@value") }
                        .trim().takeIf { it.isNotBlank() }
                else -> null
            }
        }

        fun listStr(key: String): List<String> {
            if (!obj.has(key) || obj.isNull(key)) return emptyList()
            return when (val v = obj.opt(key)) {
                is org.json.JSONArray -> (0 until v.length()).mapNotNull { i ->
                    when (val item = v.opt(i)) {
                        is String -> item.trim().takeIf { it.isNotBlank() }
                        is org.json.JSONObject ->
                            item.optString("text").ifBlank { item.optString("name") }
                                .trim().takeIf { it.isNotBlank() }
                        else -> item?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    }
                }
                is String -> listOf(v.trim()).filter { it.isNotBlank() }
                else -> emptyList()
            }
        }

        fun parseDur(iso: String?): Int? {
            if (iso.isNullOrBlank()) return null
            val h = Regex("""(\d+)H""", RegexOption.IGNORE_CASE).find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("""(\d+)M""", RegexOption.IGNORE_CASE).find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val total = h * 60 + m
            return total.takeIf { it > 0 }
                ?: Regex("""\d+""").find(iso)?.value?.toIntOrNull()?.takeIf { it > 0 && it < 24 * 60 }
        }

        fun parseNum(raw: String?): Float? {
            if (raw.isNullOrBlank()) return null
            return Regex("""\d+(?:[.,]\d+)?""").find(raw.replace(',', '.'))
                ?.value?.toFloatOrNull()?.takeIf { it > 0f }
        }

        // schema.org NutritionInformation – Werte sind üblicherweise pro Portion
        val nutrition = obj.optJSONObject("nutrition")
        val calPerServing = parseNum(nutrition?.optString("calories")?.ifBlank { null }
            ?: nutrition?.opt("calories")?.toString())
        val proteinG = parseNum(nutrition?.optString("proteinContent")?.ifBlank { null }
            ?: nutrition?.opt("proteinContent")?.toString())
        val carbsG = parseNum(nutrition?.optString("carbohydrateContent")?.ifBlank { null }
            ?: nutrition?.opt("carbohydrateContent")?.toString())
        val fatG = parseNum(nutrition?.optString("fatContent")?.ifBlank { null }
            ?: nutrition?.opt("fatContent")?.toString())

        val yieldRaw = str("recipeYield") ?: ""
        val servings = Regex("""\d+""").find(yieldRaw)?.value?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val ingredients = listStr("recipeIngredient")
        val instructions = listStr("recipeInstructions").ifEmpty {
            // HowToStep-Objekte oder einfacher Text
            when (val inst = obj.opt("recipeInstructions")) {
                is String -> listOf(inst)
                else -> emptyList()
            }
        }

        val image = when (val img = obj.opt("image")) {
            is String -> img.takeIf { it.startsWith("http") }
            is org.json.JSONArray -> (0 until img.length()).mapNotNull { i ->
                when (val e = img.opt(i)) {
                    is String -> e.takeIf { it.startsWith("http") }
                    is org.json.JSONObject -> e.optString("url").takeIf { it.startsWith("http") }
                    else -> null
                }
            }.firstOrNull()
            is org.json.JSONObject -> img.optString("url").takeIf { it.startsWith("http") }
            else -> null
        } ?: extractOgImage(doc)

        val prep = parseDur(str("prepTime")) ?: parseDur(str("totalTime")) ?: parseDur(str("cookTime"))

        val macroLine = buildString {
            calPerServing?.let { append("${it.toInt()} kcal") }
            proteinG?.let { append(" · ${it.toInt()}g Protein") }
            carbsG?.let { append(" · ${it.toInt()}g Kohlenhydrate") }
            fatG?.let { append(" · ${it.toInt()}g Fett") }
        }.trim()
        val baseDesc = (str("description") ?: "").take(400)
        val description = when {
            baseDesc.isNotBlank() && macroLine.isNotBlank() -> "$baseDesc\n\n📊 Pro Portion: $macroLine"
            macroLine.isNotBlank() -> "📊 Pro Portion: $macroLine"
            else -> baseDesc
        }

        return Recipe(
            title           = cleanTitle(str("name") ?: doc.title(), url),
            description     = description,
            imageUrl        = image,
            sourceUrl       = url,
            platform        = platform,
            ingredients     = ingredients.joinToString("\n") { "• $it" }.ifBlank { "Nicht gefunden." },
            instructions    = instructions.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n").ifBlank { "" },
            servings        = servings,
            prepTimeMinutes = prep,
            tags            = (str("keywords") ?: platform).take(200),
            totalCalories   = calPerServing?.let { it * servings },
            proteinPerServing = proteinG,
            carbsPerServing = carbsG,
            fatPerServing   = fatG
        )
    }

    private fun fetchOEmbed(url: String): Map<String, String> {
        val raw = fetchString(url)
        return buildMap {
            listOf("thumbnail_url", "author_name", "title", "html").forEach { key ->
                Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)
                    ?.groupValues?.get(1)?.let { put(key, it.replace("\\u0026", "&").replace("\\/", "/")) }
            }
        }
    }

    /**
     * Versucht nur das Thumbnail nachzuladen (oEmbed), ohne neu zu scrapen.
     * Für Rezepte mit sourceUrl aber ohne imageUrl.
     */
    suspend fun fetchThumbnailUrl(sourceUrl: String, platform: String?): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = sourceUrl.trim()
            if (url.isBlank()) return@withContext null
            val p = (platform ?: "").lowercase()
            runCatching {
                when {
                    p.contains("tiktok") || "tiktok.com" in url.lowercase() ->
                        fetchOEmbed("https://www.tiktok.com/oembed?url=${encode(url)}")["thumbnail_url"]
                    p.contains("instagram") || "instagram.com" in url.lowercase() || "instagr.am" in url.lowercase() -> {
                        val o = fetchOEmbed("https://api.instagram.com/oembed/?url=${encode(url)}&omitscript=true")
                        o["thumbnail_url"] ?: run {
                            val sc = Regex("""/(?:p|reel|tv)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
                            sc?.let { "https://www.instagram.com/p/$it/media/?size=l" }
                        }
                    }
                    else -> {
                        // Generisches og:image
                        val doc = jsoupGet(url)
                        doc.select("meta[property=og:image]").firstOrNull()?.attr("content")
                            ?.takeIf { it.isNotBlank() }
                    }
                }
            }.getOrNull()?.takeIf { !it.isNullOrBlank() }
        }

    private fun cleanTitle(raw: String, sourceUrl: String? = null): String {
        val cleaned = raw.replace(Regex("""\s*[-|].*$"""), "").trim()
        if (cleaned.isNotBlank()) return cleaned
        val host = sourceUrl?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }
        return if (!host.isNullOrBlank()) "Rezept von $host" else "Rezept"
    }

    private fun encode(url: String) = java.net.URLEncoder.encode(url, "UTF-8")

    private fun jsoupGet(url: String): Document = Jsoup.parse(fetchString(url), url)

    private fun jsoupGetWithUA(url: String, ua: String): Document {
        val raw = fetchStringWithUA(url, ua)
        return Jsoup.parse(raw, url)
    }

    private fun fetchString(url: String): String {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Leere Antwort von $url")
        }
    }

    private fun fetchStringWithUA(url: String, ua: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw Exception("Leere Antwort von $url")
        }
    }
}
