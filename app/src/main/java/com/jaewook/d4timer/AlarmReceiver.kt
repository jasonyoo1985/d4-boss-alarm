package com.jaewook.d4timer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(Alarms.EXTRA_KEY) ?: return
        val e = Schedule.byKey(key)
        if (intent.action == ACTION_STOP) {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            return
        }
        val start = intent.getLongExtra(Alarms.EXTRA_START, 0L)
        if (!Prefs(context).isQuiet(System.currentTimeMillis())) notify(context, e, start)
        Alarms.schedule(context, e)
    }

    companion object {
        private const val ACTION_STOP = "com.jaewook.d4timer.STOP"

        // Safety net so a repeating alarm on an unattended phone doesn't ring forever
        private const val REPEAT_LIMIT_MS = 5 * MIN

        private fun notificationId(e: GameEvent) = Schedule.events.indexOfFirst { it.key == e.key } + 1

        fun notify(context: Context, e: GameEvent, start: Long) {
            Alarms.ensureChannel(context, e)
            val repeat = Prefs(context).repeat(e)
            val left = ((start - System.currentTimeMillis() + 30_000) / MIN).coerceAtLeast(0)
            val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(start))
            val title = if (left > 0) "${e.name} ${left}분 전" else "${e.name} 시작!"
            val open = PendingIntent.getActivity(
                context, 100, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getBroadcast(
                context, 200 + notificationId(e),
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_STOP).putExtra(Alarms.EXTRA_KEY, e.key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context, Alarms.channelId(context, e))
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(e.color)
                .setContentTitle(title)
                .setContentText("$time 시작")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(if (repeat) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
                .addAction(Notification.Action.Builder(null, "소리 끄기", stop).build())
            if (repeat) builder.setTimeoutAfter(REPEAT_LIMIT_MS)
            val n = builder.build()
            // Insistent: the sound loops until the notification is dismissed or stopped
            if (repeat) n.flags = n.flags or Notification.FLAG_INSISTENT
            context.getSystemService(NotificationManager::class.java).notify(notificationId(e), n)
        }
    }
}
