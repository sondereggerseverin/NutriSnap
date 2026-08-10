package ch.nutrisnap.app.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schreibt unbehandelte Abstürze in eine lokale Datei. Es gibt keine Crashlytics-
 * Anbindung, daher wäre ein Absturz auf dem Gerät sonst nicht nachvollziehbar
 * (kein PC/adb im Alltag). Sichtbar unter Einstellungen > Absturzprotokoll.
 */
object CrashLogger {
    private const val MAX_ENTRIES = 10
    private const val DELIMITER = "\n═════════════════════\n"
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)

    private fun logFile(context: Context): File = File(context.filesDir, "crash_log.txt")

    fun record(context: Context, thread: Thread, throwable: Throwable) {
        val entry = "${dateFormat.format(Date())} · Thread: ${thread.name}\n" +
            Log.getStackTraceString(throwable)
        val file = logFile(context)
        val previous = runCatching { file.readText() }.getOrDefault("")
        val entries = (listOf(entry) + previous.split(DELIMITER).filter { it.isNotBlank() })
            .take(MAX_ENTRIES)
        runCatching { file.writeText(entries.joinToString(DELIMITER)) }
    }

    fun read(context: Context): String = runCatching { logFile(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }
}
