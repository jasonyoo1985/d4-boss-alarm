package com.jaewook.d4timer

import android.app.AlarmManager
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
            AlarmSound.stop(context)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            return
        }
        val start = intent.getLongExtra(Alarms.EXTRA_START, 0L)
        if (intent.action == ACTION_SNOOZE) {
            AlarmSound.stop(context)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            val am = context.getSystemService(AlarmManager::class.java)
            val at = System.currentTimeMillis() + MIN
            val pi = snoozeIntent(context, e, start)
            if (Alarms.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            return
        }
        if (intent.action == ACTION_DISMISS) {
            // Done with this spawn: drop its remaining early alerts and any snooze
            val prefs = Prefs(context)
            prefs.setSkip(e, start)
            context.getSystemService(AlarmManager::class.java).cancel(snoozeIntent(context, e, start))
            AlarmSound.stop(context)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            Alarms.schedule(context, e)
            return
        }
        if (intent.action == ACTION_SKIP_NEXT) {
            val prefs = Prefs(context)
            prefs.setSkip(e, Schedule.nextStart(prefs.effective(e), start + MIN))
            AlarmSound.stop(context)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            Alarms.schedule(context, e)
            return
        }
        if (intent.action == ACTION_REMIND) {
            if (start > System.currentTimeMillis() - 5 * MIN) notify(context, e, start)
            return
        }
        val prefs = Prefs(context)
        // A task in the recents list means the user hasn't closed the app
        val closed = prefs.onlyOpen() &&
            context.getSystemService(android.app.ActivityManager::class.java).appTasks.isEmpty()
        if (!closed && !prefs.isQuiet(System.currentTimeMillis())) notify(context, e, start)
        Alarms.schedule(context, e)
        Widgets.refreshAll(context)
    }

    companion object {
        private const val ACTION_STOP = "com.jaewook.d4timer.STOP"
        private const val ACTION_SKIP_NEXT = "com.jaewook.d4timer.SKIP_NEXT"
        private const val ACTION_SNOOZE = "com.jaewook.d4timer.SNOOZE"
        private const val ACTION_DISMISS = "com.jaewook.d4timer.DISMISS"
        private const val ACTION_REMIND = "com.jaewook.d4timer.REMIND"

        /** Snoozing only makes sense while at least two minutes remain before the start. */
        private const val SNOOZE_MIN_LEFT = 2 * MIN

        fun notificationId(e: GameEvent) = Schedule.events.indexOfFirst { it.key == e.key } + 1

        fun notify(context: Context, e: GameEvent, start: Long) {
            if (Alarms.soundUri(context, e) != null) {
                AlarmSound.start(context, e, start)
            } else {
                context.getSystemService(NotificationManager::class.java).notify(notificationId(e), build(context, e, start))
            }
        }

        private fun snoozeIntent(context: Context, e: GameEvent, start: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context, 500 + notificationId(e),
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_REMIND)
                    .putExtra(Alarms.EXTRA_KEY, e.key).putExtra(Alarms.EXTRA_START, start),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun action(context: Context, e: GameEvent, start: Long, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code + notificationId(e),
                Intent(context, AlarmReceiver::class.java).setAction(action)
                    .putExtra(Alarms.EXTRA_KEY, e.key).putExtra(Alarms.EXTRA_START, start),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun build(context: Context, e: GameEvent, start: Long): Notification {
            Alarms.ensureChannel(context, e)
            val prefs = Prefs(context)
            val repeat = prefs.repeat(e)
            val now = System.currentTimeMillis()
            val left = ((start - now + 30_000) / MIN).coerceAtLeast(0)
            val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(start))
            val who = if (e.key == "boss") Names.bosses(prefs.bossesAt(start)) else null
            val title = if (left > 0) "${e.name} ${left}분 전" else "${e.name} 시작!"
            val text = if (who != null) "$who · $time" else "$time 시작"
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
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(if (repeat) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
                .setDeleteIntent(stop)
            if (start - now >= SNOOZE_MIN_LEFT) {
                builder.addAction(Notification.Action.Builder(null, "1분 후 다시", action(context, e, start, ACTION_SNOOZE, 300)).build())
            }
            builder.addAction(Notification.Action.Builder(null, "다음 회 건너뛰기", action(context, e, start, ACTION_SKIP_NEXT, 700)).build())
            builder.addAction(Notification.Action.Builder(null, "다시 보지 않기", action(context, e, start, ACTION_DISMISS, 600)).build())
            return builder.build()
        }
    }
}
