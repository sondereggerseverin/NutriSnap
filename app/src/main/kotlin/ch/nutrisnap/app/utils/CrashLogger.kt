package ch.nutrisnap.app.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lokales Absturz-/Hänger-Protokoll (kein Crashlytics).
 *
 * Sichtbar unter Einstellungen → Absturzprotokoll.
 *
 * Erfasst:
 *  - Uncaught Exceptions (CRASH)
 *  - Main-Thread-Hänger via Watchdog (ANR-verdächtig)
 *  - Manuell gemeldete Fehler (ERROR)
 *
 * Jeder Eintrag enthält Timestamp, Typ, Thread, Activity, Speicherinfo
 * und bei ANR einen Main-Thread-Stack.
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val MAX_ENTRIES = 15
    private const val DELIMITER = "\n═════════════════════\n"

    /** Schwelle, ab der ein ausbleibender Main-Thread-Ping als ANR gilt. */
    private const val ANR_THRESHOLD_MS = 5_000L
    /** Wie oft der Watchdog den Main-Thread anpingt. */
    private const val WATCHDOG_INTERVAL_MS = 2_000L
    /** Mindestens so lange zwischen zwei ANR-Einträgen, um Spam zu vermeiden. */
    private const val ANR_COOLDOWN_MS = 30_000L

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)

    @Volatile private var appContext: Context? = null
    private val currentActivity = AtomicReference<String?>("?")
    private val lastAnrAt = AtomicLong(0L)
    private val watchdogStarted = AtomicBoolean(false)
    private val pingPending = AtomicBoolean(false)
    private val lastPingSentAt = AtomicLong(0L)

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun logFile(context: Context): File = File(context.filesDir, "crash_log.txt")

    // ── Öffentliche API ──────────────────────────────────────────────────────

    /** Einmal beim App-Start aufrufen (Application.onCreate). */
    fun init(context: Context) {
        appContext = context.applicationContext
        startWatchdog()
    }

    fun setCurrentActivity(name: String?) {
        currentActivity.set(name ?: "?")
    }

    fun record(context: Context, thread: Thread, throwable: Throwable) {
        writeEntry(
            context = context,
            type = "CRASH",
            threadName = thread.name,
            message = throwable.message ?: throwable.javaClass.simpleName,
            stack = Log.getStackTraceString(throwable)
        )
    }

    /** Nicht-tödliche Fehler / verdächtige Zustände manuell loggen. */
    fun recordError(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        val stack = if (throwable != null) {
            Log.getStackTraceString(throwable)
        } else {
            val sw = StringWriter()
            Exception("recordError@$tag").printStackTrace(PrintWriter(sw))
            sw.toString().lineSequence().take(12).joinToString("\n")
        }
        writeEntry(
            context = context,
            type = "ERROR",
            threadName = Thread.currentThread().name,
            message = "[$tag] $message",
            stack = stack
        )
    }

    fun read(context: Context): String =
        runCatching { logFile(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }

    // ── Watchdog ─────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return

        val watchdog = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            while (true) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (pingPending.get()) {
                        val sent = lastPingSentAt.get()
                        val waited = now - sent
                        if (waited >= ANR_THRESHOLD_MS) {
                            onSuspectedAnr(waited)
                            pingPending.set(false)
                        }
                    }

                    if (!pingPending.get()) {
                        lastPingSentAt.set(SystemClock.elapsedRealtime())
                        pingPending.set(true)
                        mainHandler.post {
                            pingPending.set(false)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Watchdog-Tick fehlgeschlagen", t)
                }
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "NutriSnap-ANR-Watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        Log.i(TAG, "ANR-Watchdog gestartet (Schwellwert ${ANR_THRESHOLD_MS} ms)")
    }

    private fun onSuspectedAnr(blockedMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val last = lastAnrAt.get()
        if (now - last < ANR_COOLDOWN_MS) return
        lastAnrAt.set(now)

        val ctx = appContext ?: return
        val mainStack = dumpMainThreadStack()
        writeEntry(
            context = ctx,
            type = "ANR",
            threadName = "main",
            message = "Main-Thread antwortet seit ≥${blockedMs} ms nicht (Activity: ${currentActivity.get()})",
            stack = mainStack
        )
        Log.e(TAG, "ANR-verdächtig: Main blockiert ≥${blockedMs} ms\n$mainStack")
    }

    private fun dumpMainThreadStack(): String {
        return try {
            val main = Looper.getMainLooper().thread
            val frames = main.stackTrace
            buildString {
                append("Main-Thread Stack (").append(frames.size).append(" Frames):\n")
                for (f in frames) {
                    append("  at ").append(f.toString()).append('\n')
                }
            }
        } catch (t: Throwable) {
            "Stack-Dump fehlgeschlagen: ${t.message}"
        }
    }

    // ── Schreiben ────────────────────────────────────────────────────────────

    private fun writeEntry(
        context: Context,
        type: String,
        threadName: String,
        message: String,
        stack: String
    ) {
        val header = buildString {
            append(dateFormat.format(Date()))
            append(" · ").append(type)
            append(" · Thread: ").append(threadName)
            append(" · Activity: ").append(currentActivity.get())
            append('\n')
            append(message)
            append('\n')
            append(memorySnapshot(context))
            append('\n')
        }
        val entry = header + stack.trimEnd() + "\n"

        val file = logFile(context)
        val previous = runCatching { file.readText() }.getOrDefault("")
        val entries = (listOf(entry) + previous.split(DELIMITER).filter { it.isNotBlank() })
            .take(MAX_ENTRIES)
        runCatching {
            file.writeText(entries.joinToString(DELIMITER))
        }.onFailure {
            Log.e(TAG, "Konnte crash_log.txt nicht schreiben", it)
        }
    }

    private fun memorySnapshot(context: Context): String {
        return try {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            val nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024 * 1024)
            val lowMem = memInfo.lowMemory

            "Mem: JVM ${usedMb}/${maxMb} MB · NativeHeap ${nativeMb} MB · System frei ${availMb} MB · lowMem=$lowMem"
        } catch (t: Throwable) {
            "Mem: (nicht lesbar: ${t.message})"
        }
    }
}
