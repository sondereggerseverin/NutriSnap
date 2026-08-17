package ch.nutrisnap.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
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
        // nicht vorbereitet sind. Deren Toolbar (Bestätigen/Rotieren/Spiegeln) rutschte dadurch
        // unter die Statusleiste und war weder sichtbar noch antippbar ("croppen geht, aber
        // nicht bestätigen"). MainActivity handhabt Insets selbst über Compose, daher hier
        // gezielt nur für die Cropper-Activity: Content-Root um die System-Bar-Insets abpolstern,
        // damit die Toolbar wieder unterhalb der Statusleiste landet.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name != "com.canhub.cropper.CropImageActivity") return
                val content = activity.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.updatePadding(top = bars.top, bottom = bars.bottom)
                    insets
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
