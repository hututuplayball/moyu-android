package com.moyu.reader.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.moyu.reader.MainActivity
import com.moyu.reader.R

/**
 * Keeps the process alive while a book is read out loud with the screen off or the app away.
 *
 * The reading itself stays in [SpeechReader]; this service only holds the foreground slot Android
 * requires for audio played outside the activity, and shows what is being read with a way to stop.
 */
class SpeechService : Service() {
    /** What the notification currently says, so an update that changes nothing is not sent. */
    private var shownTitle = ""
    private var shownText: String? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopReading?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        show(intent?.getStringExtra(EXTRA_TITLE).orEmpty(), intent?.getStringExtra(EXTRA_TEXT))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Re-posts the foreground notification with what is being read now.
     *
     * This is the one call a running reading makes per sentence: no service start, no background
     * start check, no new notification object beyond the one the reader is looking at.
     */
    private fun show(title: String, text: String?) {
        shownTitle = title
        shownText = text
        startForeground(NOTIFICATION_ID, build(title, text))
    }

    private fun build(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SpeechService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val line = text?.takeIf { it.isNotBlank() } ?: "正在朗读…"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_moyu_monochrome)
            .setContentTitle(title.ifBlank { "墨屿" })
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setContentIntent(open)
            .addAction(R.drawable.ic_moyu_monochrome, "停止", stop)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "朗读", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL = "moyu-speech"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "com.moyu.reader.speech.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** While a reader exists, the notification's stop button reaches its own [SpeechReader]. */
        var stopReading: (() -> Unit)? = null

        /** The live service, if the reading is running in the background. Cleared in onDestroy. */
        @Volatile
        private var running: SpeechService? = null

        fun start(context: Context, title: String, text: String?) {
            val intent = Intent(context, SpeechService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Moves the notification onto the sentence being read now. A reading that is not in the
         * background has no service, and nothing to update.
         */
        fun update(title: String, text: String?) {
            val service = running ?: return
            if (title == service.shownTitle && text == service.shownText) return
            runCatching { service.show(title, text) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpeechService::class.java))
        }
    }
}
