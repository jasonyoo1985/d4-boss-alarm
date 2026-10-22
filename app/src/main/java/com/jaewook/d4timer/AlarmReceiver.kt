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
        if (intent.action == ACTION_SKIP_NEXT) {
            val prefs = Prefs(context)
            val after = intent.getLongExtra(Alarms.EXTRA_START, 0L)
            prefs.setSkip(e, Schedule.nextStart(prefs.effective(e), after + MIN))
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            Alarms.schedule(context, e)
            return
        }
        val start = intent.getLongExtra(Alarms.EXTRA_START, 0L)
        val prefs = Prefs(context)
        // A task in the recents list means the user hasn't closed the app
        val closed = prefs.onlyOpen() &&
            context.getSystemService(android.app.ActivityManager::class.java).appTasks.isEmpty()
        if (!closed && !prefs.isQuiet(System.currentTimeMillis())) notify(context, e, start)
        Alarms.schedule(context, e)
        BossWidget.refresh(context)
    }

    companion object {
        private const val ACTION_STOP = "com.jaewook.d4timer.STOP"
        private const val ACTION_SKIP_NEXT = "com.jaewook.d4timer.SKIP_NEXT"

        // Safety net so a repeating alarm on an unattended phone doesn't ring forever
        private const val REPEAT_LIMIT_MS = 5 * MIN

        private fun notificationId(e: GameEvent) = Schedule.events.indexOfFirst { it.key == e.key } + 1

        fun notify(context: Context, e: GameEvent, start: Long) {
            Alarms.ensureChannel(context, e)
            val repeat = Prefs(context).repeat(e)
            val left = ((start - System.currentTimeMillis() + 30_000) / MIN).coerceAtLeast(0)
            val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(start))
            val boss = if (e.key == "boss") Prefs(context).bossAt(start) else null
            val who = boss?.let { (n, z) -> Names.ko(n) + (if (z.isNotBlank()) " · " + Names.ko(z) else "") }
            val label = who ?: e.name
            val title = if (left > 0) "$label ${left}분 전" else "$label 시작!"
            val open = PendingIntent.getActivity(
                context, 100, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getBroadcast(
                context, 200 + notificationId(e),
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_STOP).putExtra(Alarms.EXTRA_KEY, e.key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val skip = PendingIntent.getBroadcast(
                context, 300 + notificationId(e),
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_SKIP_NEXT)
                    .putExtra(Alarms.EXTRA_KEY, e.key).putExtra(Alarms.EXTRA_START, start),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = Notification.Builder(context, Alarms.channelId(context, e))
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(e.color)
                .setContentTitle(title)
                .setContentText(if (who != null) "${e.name} · $time 시작" else "$time 시작")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(if (repeat) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
                .addAction(Notification.Action.Builder(null, "소리 끄기", stop).build())
                .addAction(Notification.Action.Builder(null, "다음 회차 건너뛰기", skip).build())
            if (repeat) builder.setTimeoutAfter(REPEAT_LIMIT_MS)
            val n = builder.build()
            // Insistent: the sound loops until the notification is dismissed or stopped
            if (repeat) n.flags = n.flags or Notification.FLAG_INSISTENT
            context.getSystemService(NotificationManager::class.java).notify(notificationId(e), n)
        }
    }
}
