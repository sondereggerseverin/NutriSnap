package ch.nutrisnap.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object NotificationScheduler {

    fun scheduleAll(context: Context) {
        schedule(context, 8,  0,  "meal_breakfast", 10)
        schedule(context, 12, 30, "meal_lunch",     11)
        schedule(context, 18, 30, "meal_dinner",    12)
        schedule(context, 21, 0,  "daily_recap",    16)
    }

    private fun schedule(context: Context, hour: Int, minute: Int, type: String, code: Int) {
        val intent = Intent(context, NotificationReceiver::class.java).apply { putExtra("type", type) }
        val pi = PendingIntent.getBroadcast(context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .setRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        (10..16).forEach { code ->
            val pi = PendingIntent.getBroadcast(context, code,
                Intent(context, NotificationReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi)
        }
    }
}
