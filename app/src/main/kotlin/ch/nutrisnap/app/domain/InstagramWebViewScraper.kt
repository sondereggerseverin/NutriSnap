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
 * Scrapes Instagram posts using a hidden WebView (full Chromium engine).
 *
 * Why this works when OkHttp doesn't:
 *  - WebView executes JavaScript → Instagram renders its React app fully
 *  - WebView uses the system's cookie store → may share login session
 *    with the Instagram app on the same device
 *  - After pageFinished, we inject JS to read the rendered DOM
 *
 * The JS tries multiple selectors in order:
 *  1. window.__additionalDataLoaded JSON (IG internal data object)
 *  2. <meta property="og:description"> (populated after JS render)
 *  3. article h1, [data-testid] text nodes
 */
object InstagramWebViewScraper {

    /**
     * Load [url] in a hidden WebView, wait for it to finish,
     * then extract the caption via JS injection.
     *
     * Must be called from a coroutine; internally switches to Main thread
     * for WebView operations (WebView requires main-thread access).
     *
     * @return caption string, or null if extraction failed / timed out
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractCaption(context: Context, url: String): String? =
        // Gesamttimeout 11 s — Race-Timeout (8–12 s) gibt dem WebView genug Luft
        withTimeout(11_000L) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    val webView = WebView(context.applicationContext)
                    val isEmbed = "/embed" in url
                    webView.settings.apply {
                        javaScriptEnabled      = true
                        domStorageEnabled      = true
                        databaseEnabled        = true
                        // Desktop-UA für Embed oft besser; Mobile+IG-UA für normale Posts
                        userAgentString = if (isEmbed) {
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        } else {
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/124.0.6367.82 Mobile Safari/537.36 Instagram/323.0.0.0"
                        }
                        mixedContentMode       = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        allowContentAccess     = false
                        allowFileAccess        = false
                    }
                    // System-Cookies (Chrome/WebView-Login) mitnehmen
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(webView, true)
                    cm.flush()

                    var finished = false
                    fun finish(caption: String?) {
                        if (finished) return
                        finished = true
                        runCatching { webView.destroy() }
                        cont.resume(caption)
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            if (finished) return
                            // DOM-Warten mit etwas Luft für vollständiges Rendering
                            val delayMs = if (isEmbed) 1_800L else 2_500L
                            mainHandler.postDelayed({
                                if (finished) return@postDelayed
                                view.evaluateJavascript(EXTRACT_JS) { rawResult ->
                                    val caption = decodeJsString(rawResult)
                                    if (!caption.isNullOrBlank()) {
                                        finish(caption)
                                    } else {
                                        // Zweiter Versuch nach weiterem Render
                                        mainHandler.postDelayed({
                                            if (finished) return@postDelayed
                                            view.evaluateJavascript(EXTRACT_JS) { raw2 ->
                                                finish(decodeJsString(raw2))
                                            }
                                        }, 1_500L)
                                    }
                                }
                            }, delayMs)
                        }

                        override fun onReceivedError(
                            view: WebView, request: WebResourceRequest, error: WebResourceError
                        ) {
                            if (request.isForMainFrame && !finished) finish(null)
                        }
                    }

                    webView.loadUrl(url)

                    cont.invokeOnCancellation {
                        mainHandler.post { finish(null) }
                    }
                }
            }
        }

    private fun decodeJsString(rawResult: String?): String? =
        rawResult
            ?.removeSurrounding("\"")
            ?.let { RecipeAiParser.unescapeSocialText(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * JavaScript injected after page load.
     * Returns the caption string or null.
     */
    private val EXTRACT_JS = """
        (function() {
            try {
                // Strategy 1: __additionalDataLoaded / window._sharedData (classic IG API)
                try {
                    if (window._sharedData) {
                        var str = JSON.stringify(window._sharedData);
                        var m = str.match(/"edge_media_to_caption".*?"text":"((?:[^"\\]|\\.)*)"/);
                        if (m && m[1] && m[1].length > 10) return m[1];
                    }
                } catch(e) {}

                // Strategy 2: __additionalDataLoaded (newer IG)
                try {
                    var scripts = document.querySelectorAll('script');
                    for (var s of scripts) {
                        var txt = s.textContent || '';
                        if (txt.includes('edge_media_to_caption') || txt.includes('"caption"')) {
                            var m = txt.match(/"edge_media_to_caption".*?"text":"((?:[^"\\]|\\.)*)"/);
                            if (!m) m = txt.match(/"caption"\s*:\s*\{[^}]*"text"\s*:\s*"((?:[^"\\]|\\.){20,})"/);
                            if (m && m[1] && m[1].length > 10) return m[1].replace(/\\n/g, '\n').replace(/\\t/g, ' ');
                        }
                    }
                } catch(e) {}

                // Strategy 3: application/json script tags (IG hydration data)
                var jsonScripts = document.querySelectorAll('script[type="application/json"]');
                for (var js of jsonScripts) {
                    try {
                        var str = JSON.stringify(JSON.parse(js.textContent));
                        // Look specifically for caption text patterns
                        var patterns = [
                            /"caption"\s*:\s*\{[^}]*"text"\s*:\s*"((?:[^"\\]|\\.){20,})"/,
                            /"edge_media_to_caption".*?"text":"((?:[^"\\]|\\.){20,})"/,
                            /"accessibility_caption":"((?:[^"\\]|\\.){20,})"/
                        ];
                        for (var p of patterns) {
                            var m = str.match(p);
                            if (m && m[1]) return m[1].replace(/\\n/g, '\n').replace(/\\t/g, ' ');
                        }
                    } catch(e) {}
                }

                // Strategy 4: og:description meta (populated by JS render)
                var og = document.querySelector('meta[property="og:description"]');
                if (og && og.content && og.content.length > 10) return og.content;

                // Strategy 5: h1 or article spans (rendered caption)
                var article = document.querySelector('article, main');
                if (article) {
                    var h1 = article.querySelector('h1');
                    if (h1 && h1.innerText && h1.innerText.length > 5) return h1.innerText.trim();
                    var spans = article.querySelectorAll('span, div[dir]');
                    var best = '';
                    for (var sp of spans) {
                        var t = (sp.innerText || '').trim();
                        if (t.length > best.length && t.length > 30 && t.length < 5000) best = t;
                    }
                    if (best.length > 30) return best;
                }

                // Strategy 6: recipe-keyword scan
                var keywords = ['zutaten', 'ingredients', 'rezept', 'recipe', 'g ', 'ml ', 'tbsp', 'tsp'];
                var all = document.querySelectorAll('span[dir], div[dir], p');
                for (var el of all) {
                    var t = (el.innerText || '').trim();
                    if (t.length > 50) {
                        var lower = t.toLowerCase();
                        for (var kw of keywords) {
                            if (lower.includes(kw)) return t;
                        }
                    }
                }

                return null;
            } catch(e) { return null; }
        })()
    """.trimIndent()
}
