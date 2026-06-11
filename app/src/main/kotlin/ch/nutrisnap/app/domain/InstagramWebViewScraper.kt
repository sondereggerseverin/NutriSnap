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
        withTimeout(20_000L) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    val webView = WebView(context.applicationContext)
                    webView.settings.apply {
                        javaScriptEnabled      = true
                        domStorageEnabled      = true
                        userAgentString        = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.6367.82 Mobile Safari/537.36 Instagram/323.0.0.0"
                        mixedContentMode       = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        allowContentAccess     = false
                        allowFileAccess        = false
                    }
                    // Accept cookies (needed for Instagram session sharing)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    var finished = false

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, loadedUrl: String) {
                            if (finished) return
                            // Short delay so React can finish rendering
                            mainHandler.postDelayed({
                                if (finished) return@postDelayed
                                view.evaluateJavascript(EXTRACT_JS) { rawResult ->
                                    if (finished) return@evaluateJavascript
                                    finished = true
                                    webView.destroy()
                                    val caption = rawResult
                                        ?.removeSurrounding("\"")
                                        ?.replace("\\n", "\n")
                                        ?.replace("\\\"", "\"")
                                        ?.trim()
                                        ?.takeIf { it.isNotBlank() && it != "null" }
                                    cont.resume(caption)
                                }
                            }, 2_500)
                        }

                        override fun onReceivedError(
                            view: WebView, request: WebResourceRequest, error: WebResourceError
                        ) {
                            if (request.isForMainFrame && !finished) {
                                finished = true
                                webView.destroy()
                                cont.resume(null)
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
     * JavaScript injected after page load.
     * Returns the caption string or null.
     */
    private val EXTRACT_JS = """
        (function() {
            try {
                // 1. Try Instagram's internal data object (best source)
                var scripts = document.querySelectorAll('script[type="application/json"]');
                for (var s of scripts) {
                    try {
                        var d = JSON.parse(s.textContent);
                        var str = JSON.stringify(d);
                        var m = str.match(/"text"\s*:\s*"((?:[^"\\]|\\.)*)"/);
                        if (m && m[1] && m[1].length > 20) return m[1];
                    } catch(e) {}
                }
                // 2. og:description meta tag (populated after JS render)
                var og = document.querySelector('meta[property="og:description"]');
                if (og && og.content && og.content.length > 10) return og.content;
                // 3. article text / h1
                var article = document.querySelector('article');
                if (article) {
                    var h1 = article.querySelector('h1');
                    if (h1 && h1.innerText.length > 5) return h1.innerText;
                    // span with long text
                    var spans = article.querySelectorAll('span');
                    for (var sp of spans) {
                        if (sp.innerText && sp.innerText.length > 30) return sp.innerText;
                    }
                }
                // 4. Any span/div with recipe-like content
                var all = document.querySelectorAll('span, div');
                for (var el of all) {
                    var t = el.innerText || '';
                    if (t.length > 50 && (
                        t.toLowerCase().includes('zutaten') ||
                        t.toLowerCase().includes('ingredients') ||
                        t.toLowerCase().includes('rezept') ||
                        t.toLowerCase().includes('recipe')
                    )) return t;
                }
                return null;
            } catch(e) { return null; }
        })()
    """.trimIndent()
}
