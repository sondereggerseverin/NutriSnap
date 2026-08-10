package ch.nutrisnap.app

import android.app.Application
import ch.nutrisnap.app.utils.CrashLogger

/**
 * Fängt unbehandelte Exceptions global ab, protokolliert sie lokal (siehe
 * CrashLogger) und reicht sie danach an den Standard-Handler weiter – der
 * Absturz selbst passiert weiterhin wie vorher, nur ist er jetzt sichtbar.
 */
class NutriSnapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { CrashLogger.record(this, thread, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
