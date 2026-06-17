package ch.nutrisnap.app.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

class FastingTimerService : Service() {

    companion object {
        const val EXTRA_GOAL_HOURS = "goal_hours"
        const val EXTRA_START_TIME = "start_time"
        const val CHANNEL_ID = "fasting_timer"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "STOP_FASTING"
    }

    private var startTime: Long = 0
    private var goalHours: Int = 16
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startTime = intent?.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis()) ?: System.currentTimeMillis()
        goalHours = intent?.getIntExtra(EXTRA_GOAL_HOURS, 16) ?: 16
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(updateRunnable)
        return START_STICKY
    }

    override fun onDestroy() { handler.removeCallbacks(updateRunnable); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val elapsed = System.currentTimeMillis() - startTime
        val elapsedHours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
        val remaining = (goalHours * 3600_000L) - elapsed
        val remainingHours = TimeUnit.MILLISECONDS.toHours(remaining.coerceAtLeast(0))
        val remainingMinutes = TimeUnit.MILLISECONDS.toMinutes(remaining.coerceAtLeast(0)) % 60
        val isCompleted = elapsed >= goalHours * 3600_000L

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FastingTimerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(if (isCompleted) "Fastenziel erreicht!" else "Fasten laeuft")
            .setContentText(
                if (isCompleted) "Du hast ${goalHours}h gefastet!"
                else "Vergangen: ${elapsedHours}h ${elapsedMinutes}min | Noch: ${remainingHours}h ${remainingMinutes}min"
            )
            .setProgress(goalHours * 60, (elapsedHours * 60 + elapsedMinutes).toInt().coerceAtMost(goalHours * 60), false)
            .addAction(android.R.drawable.ic_media_pause, "Stoppen", stopIntent)
            .setOngoing(!isCompleted)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Fasten-Timer", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Zeigt den aktuellen Fasten-Fortschritt"; setSound(null, null) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}
