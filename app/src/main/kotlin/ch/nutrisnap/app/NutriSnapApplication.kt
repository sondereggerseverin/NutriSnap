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
import dagger.hilt.android.HiltAndroidApp

/**
 * Fängt unbehandelte Exceptions global ab, startet den ANR-Watchdog und
 * trackt die aktuelle Activity – alles landet im lokalen Absturzprotokoll
 * (Einstellungen → Absturzprotokoll).
 */
@HiltAndroidApp
class NutriSnapApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        CrashLogger.init(this)

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
                if (isCropImageActivity(activity)) fixCropperWindow(activity)
            }

            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                CrashLogger.setCurrentActivity(activity.javaClass.simpleName)
                if (isCropImageActivity(activity)) fixCropperWindow(activity)
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

        WindowCompat.setDecorFitsSystemWindows(window, true)

        val content = activity.findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
