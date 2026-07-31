package ch.nutrisnap.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/** Zentrale Stelle, um alle NutriSnap-Widget-Instanzen neu zu laden. Sicher aufrufbar,
 *  auch wenn kein Widget auf dem Homescreen liegt (dann ist updateAll ein No-Op). */
object WidgetUpdater {
    // Eigener Scope statt viewModelScope: wird auch von BroadcastReceivern (Mitternacht,
    // Boot) ausserhalb jeder ViewModel-Lebensdauer aufgerufen.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { NutriSnapWidget().updateAll(appContext) }
        }
    }

    private const val MIDNIGHT_ALARM_CODE = 20

    /** Plant einen täglichen exakten Alarm um Mitternacht, damit das Widget auch dann
     *  ein neues (adaptives) Tagesziel zeigt, wenn die App gerade nicht offen ist. */
    fun scheduleMidnightRefresh(context: Context) {
        val intent = Intent(context, MidnightRefreshReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, MIDNIGHT_ALARM_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5)
        }
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }
}

class MidnightRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUpdater.requestUpdate(context)
    }
}
