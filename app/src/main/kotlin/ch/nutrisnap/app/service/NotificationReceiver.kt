package ch.nutrisnap.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("type")) {
            "meal_breakfast" -> NotificationHelper.showMealReminder(context, "Fruehstueck", 100)
            "meal_lunch"     -> NotificationHelper.showMealReminder(context, "Mittagessen", 101)
            "meal_dinner"    -> NotificationHelper.showMealReminder(context, "Abendessen", 102)
            "daily_recap"    -> NotificationHelper.showDailyRecap(context,
                intent.getIntExtra("calories", 0),
                intent.getIntExtra("goal", 2000),
                intent.getFloatExtra("protein", 0f))
        }
    }
}
