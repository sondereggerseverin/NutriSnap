package ch.nutrisnap.app.domain

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Scrapes TikTok posts using a hidden WebView with TikTok-specific settings.
 *
 * Key differences from InstagramWebViewScraper:
 *  - Uses a desktop Chrome User-Agent (TikTok blocks mobile UAs for bots)
 *  - Waits longer (TikTok is heavier JS app, 4s settle time)
 *  - Different DOM selectors (TikTok uses data-e2e attributes)
 *  - Tries multiple extraction strategies in order
 */
object TikTokWebViewScraper {

    data class TikTokResult(
        val caption: String?,
        val author: String?
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(context: Context, url: String): TikTokResult =
        withTimeout(30_000L) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    val webView = WebView(context.applicationContext)
                    webView.settings.apply {
                        javaScriptEnabled      = true
                        domStorageEnabled      = true
                        // Desktop UA — TikTok serves better content to desktop browsers
                        userAgentString        =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Safari/537.36"
                        mixedContentMode       = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        allowContentAccess     = false
                        allowFileAccess        = false
                        // TikTok requires localStorage / sessionStorage
                        databaseEnabled        = true
                    }

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    var finished = false

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            if (finished) return
                            // TikTok needs more time than Instagram for JS hydration
                            mainHandler.postDelayed({
                                if (finished) return@postDelayed
                                view.evaluateJavascript(EXTRACT_JS) { rawResult ->
                                    if (finished) return@evaluateJavascript
                                    finished = true
                                    webView.destroy()

                                    // Result is a JSON string with "caption" and "author" keys
                                    val result = rawResult
                                        ?.removeSurrounding("\"")
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\\"", "\"")
                                        ?.trim()
                                        ?.takeIf { it.isNotBlank() && it != "null" }

                                    if (result == null) {
                                        cont.resume(TikTokResult(null, null))
                                        return@evaluateJavascript
                                    }

                                    // Try to parse JSON result
                                    val caption = try {
                                        org.json.JSONObject(result).optString("caption", "").ifBlank { null }
                                    } catch (_: Exception) {
                                        // Fallback: treat whole result as caption
                                        result.takeIf { it.isNotBlank() }
                                    }
                                    val author = try {
                                        org.json.JSONObject(result).optString("author", "").ifBlank { null }
                                    } catch (_: Exception) { null }

                                    cont.resume(TikTokResult(caption, author))
                                }
                            }, 4_000) // TikTok needs 4s to fully render
                        }

                        override fun onReceivedError(
                            view: WebView, request: WebResourceRequest, error: WebResourceError
                        ) {
                            if (request.isForMainFrame && !finished) {
                                finished = true
                                webView.destroy()
                                cont.resume(TikTokResult(null, null))
                            }
                        }
                    }

                    webView.loadUrl(url)

                    cont.invokeOnCancellation {
                        mainHandler.post {
                            if (!finished) { finished = true; webView.destroy() }
                        }
                    }
                }
            }
        }

    /**
     * JavaScript that extracts caption and author from TikTok's rendered DOM.
     * Returns a JSON string: {"caption": "...", "author": "..."}
     *
     * TikTok uses data-e2e attributes which are stable across redesigns.
     * Multiple fallback strategies in case the primary selector changes.
     */
    private val EXTRACT_JS = """
        (function() {
            try {
                var caption = null;
                var author = null;

                // Strategy 1: data-e2e attributes (most stable TikTok selectors)
                var descEl = document.querySelector('[data-e2e="browse-video-desc"]') ||
                             document.querySelector('[data-e2e="video-desc"]') ||
                             document.querySelector('[data-e2e="search-card-desc"]');
                if (descEl && descEl.innerText && descEl.innerText.length > 5) {
                    caption = descEl.innerText.trim();
                }

                // Strategy 2: __NEXT_DATA__ JSON (Next.js SSR data)
                if (!caption) {
                    var nextData = document.getElementById('__NEXT_DATA__');
                    if (nextData) {
                        try {
                            var nd = JSON.parse(nextData.textContent);
                            var str = JSON.stringify(nd);
                            // Look for "desc" field with reasonable length
                            var m = str.match(/"desc"\s*:\s*"((?:[^"\\]|\\.)*)"/);
                            if (m && m[1] && m[1].length > 10) caption = m[1];
                        } catch(e) {}
                    }
                }

                // Strategy 3: SIGI_STATE (TikTok's hydration state)
                if (!caption) {
                    var sigiEl = document.getElementById('SIGI_STATE');
                    if (sigiEl) {
                        try {
                            var sigi = JSON.parse(sigiEl.textContent);
                            var str = JSON.stringify(sigi);
                            var m = str.match(/"desc"\s*:\s*"((?:[^"\\]|\\.)*)"/);
                            if (m && m[1] && m[1].length > 10) caption = m[1];
                        } catch(e) {}
                    }
                }

                // Strategy 4: og:description meta
                if (!caption) {
                    var og = document.querySelector('meta[property="og:description"]');
                    if (og && og.content && og.content.length > 10) {
                        caption = og.content;
                    }
                }

                // Strategy 5: any span/div with recipe-like text
                if (!caption) {
                    var keywords = ['zutaten', 'ingredients', 'rezept', 'recipe', 'g ', 'ml ', 'tbsp', 'tsp'];
                    var all = document.querySelectorAll('span, div, p');
                    for (var el of all) {
                        var t = (el.innerText || '').trim();
                        if (t.length > 50) {
                            var lower = t.toLowerCase();
                            for (var kw of keywords) {
                                if (lower.includes(kw)) { caption = t; break; }
                            }
                            if (caption) break;
                        }
                    }
                }

                // Extract author
                var authorEl = document.querySelector('[data-e2e="browse-username"]') ||
                               document.querySelector('[data-e2e="video-author-uniqueid"]') ||
                               document.querySelector('meta[name="twitter:creator"]');
                if (authorEl) {
                    author = authorEl.getAttribute('content') || authorEl.innerText || null;
                }
                if (!author) {
                    var og2 = document.querySelector('meta[property="og:title"]');
                    if (og2 && og2.content) {
                        var m = og2.content.match(/@([\w.]+)/);
                        if (m) author = m[1];
                    }
                }

                if (!caption) return null;
                return JSON.stringify({ caption: caption, author: author });
            } catch(e) { return null; }
        })()
    """.trimIndent()
}
