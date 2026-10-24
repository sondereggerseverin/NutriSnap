package ch.nutrisnap.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.nutrisnap.app.widget.WidgetUpdater

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NotificationScheduler.scheduleAll(context)
            WidgetUpdater.scheduleMidnightRefresh(context)
            WidgetUpdater.requestUpdate(context)
        }
    }
}
