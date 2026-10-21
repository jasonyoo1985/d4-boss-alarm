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
        val start = intent.getLongExtra(Alarms.EXTRA_START, 0L)
        if (!Prefs(context).isQuiet(System.currentTimeMillis())) notify(context, e, start)
        Alarms.schedule(context, e)
    }

    companion object {
        fun notify(context: Context, e: GameEvent, start: Long) {
            Alarms.ensureChannel(context, e)
            val left = ((start - System.currentTimeMillis() + 30_000) / MIN).coerceAtLeast(0)
            val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(start))
            val title = if (left > 0) "${e.name} ${left}분 전" else "${e.name} 시작!"
            val open = PendingIntent.getActivity(
                context, 100, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(context, Alarms.channelId(context, e))
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(e.color)
                .setContentTitle(title)
                .setContentText("$time 시작")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(Schedule.events.indexOf(e) + 1, n)
        }
    }
}
