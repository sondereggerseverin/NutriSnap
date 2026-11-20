package ch.nutrisnap.app.utils

import android.util.Log

/**
 * Zentrales, nicht-tödliches Fehler-Logging.
 *
 * Ersetzt stille `catch (_: Exception)`-Blöcke: der Fehler wird in Logcat
 * geschrieben und (sobald die App initialisiert ist) ins lokale Absturzprotokoll
 * (Einstellungen → Absturzprotokoll) übernommen.
 *
 * Unit-test-sicher: Wenn android.util.Log nicht verfügbar ist, passiert nichts.
 */
object AppLog {

    /** Wird von [CrashLogger.init] gesetzt; bis dahin (z. B. in Unit-Tests) bleibt es null. */
    @Volatile
    internal var errorSink: ((tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        } catch (_: RuntimeException) {
            // JVM-Unit-Test ohne Android-Framework
        }
    }

    /** Wie [warn], zusätzlich im lokalen Absturzprotokoll sichtbar. */
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        warn(tag, message, throwable)
        errorSink?.let { runCatching { it(tag, message, throwable) } }
    }
}
