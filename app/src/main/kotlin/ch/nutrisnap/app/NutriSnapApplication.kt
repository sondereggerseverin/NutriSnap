package ch.nutrisnap.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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

        // Bug-Fix: Ab targetSdk 35+ erzwingt Android Edge-to-Edge systemweit für ALLE
        // Activities — auch für Fremd-Library-Activities wie CropImageActivity, die dafür
        // nicht vorbereitet sind. Deren Toolbar (Bestätigen/Rotieren/Spiegeln) rutschte
        // dadurch unter die Statusleiste und war weder sichtbar noch antippbar
        // ("croppen geht, aber nicht bestätigen").
        //
        // Lösung:
        // 1) Edge-to-Edge für diese Activity explizit deaktivieren
        //    (WindowCompat.setDecorFitsSystemWindows = true)
        // 2) Zusätzlich Content-Root um System-Bar-Insets polstern (Fallback für OEMs)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (!isCropImageActivity(activity)) return
                fixCropperWindow(activity)
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                // Nochmal absichern: manche Geräte setzen Flags erst nach Created um
                if (!isCropImageActivity(activity)) return
                fixCropperWindow(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun isCropImageActivity(activity: Activity): Boolean =
        activity.javaClass.name == "com.canhub.cropper.CropImageActivity"

    private fun fixCropperWindow(activity: Activity) {
        val window = activity.window ?: return

        // Entscheidender Schritt: Activity soll NICHT unter Status-/Nav-Bar zeichnen
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Fallback-Padding falls ein OEM die Toolbar trotzdem verschiebt
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        // Insets ggf. sofort anfordern (Listener allein feuert nicht immer)
        ViewCompat.requestApplyInsets(content)
    }
}
