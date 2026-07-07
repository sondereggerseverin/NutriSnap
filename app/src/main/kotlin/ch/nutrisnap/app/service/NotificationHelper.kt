package ch.nutrisnap.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import ch.nutrisnap.app.MainActivity

object NotificationHelper {

    const val CHANNEL_REMINDERS = "nutrisnap_reminders"
    const val CHANNEL_DAILY     = "nutrisnap_daily"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            listOf(
                NotificationChannel(CHANNEL_REMINDERS, "Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Mahlzeiten-Erinnerungen" },
                NotificationChannel(CHANNEL_DAILY, "Tagesrueckblick", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Abendliche Zusammenfassung" }
            ).forEach { manager.createNotificationChannel(it) }
        }
    }

    fun showMealReminder(context: Context, mealName: String, id: Int = 100) {
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        notify(context, id, CHANNEL_REMINDERS,
            "Zeit fuer $mealName!",
            "Vergiss nicht, deine Mahlzeit einzutragen.",
            intent)
    }

    fun showDailyRecap(context: Context, calories: Int, goal: Int, protein: Float) {
        val ok = calories <= goal
        notify(context, 200, CHANNEL_DAILY,
            if (ok) "Super gemacht heute!" else "Dein Tagesrueckblick",
            "Kalorien: $calories / $goal kcal | Protein: ${protein.toInt()}g")
    }

    private fun notify(
        context: Context, id: Int, channel: String,
        title: String, text: String,
        pendingIntent: PendingIntent? = null
    ) {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        pendingIntent?.let { builder.setContentIntent(it) }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, builder.build())
    }
}
