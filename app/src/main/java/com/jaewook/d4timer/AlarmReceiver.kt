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
        // Handled from the notification or the alarm screen: the alarm screen goes away too
        if (intent.action != null && intent.action != ACTION_REMIND) AlarmActivity.close(e.key)
        if (intent.action == ACTION_STOP) {
            AlarmSound.stop(context, e.key)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            return
        }
        val start = intent.getLongExtra(Alarms.EXTRA_START, 0L)
        if (intent.action == ACTION_SNOOZE) {
            AlarmSound.stop(context, e.key)
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
            AlarmSound.stop(context, e.key)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            Alarms.schedule(context, e)
            return
        }
        if (intent.action == ACTION_SKIP_NEXT) {
            val prefs = Prefs(context)
            // Skips this spawn's remaining alerts and the next spawn (skip covers every start up to that time)
            prefs.setSkip(e, Schedule.nextStart(prefs.effective(e), start + MIN))
            context.getSystemService(AlarmManager::class.java).cancel(snoozeIntent(context, e, start))
            AlarmSound.stop(context, e.key)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(e))
            Alarms.schedule(context, e)
            return
        }
        if (intent.action == ACTION_REMIND) {
            if (start > System.currentTimeMillis() - 5 * MIN && !dndSilenced(context)) notify(context, e, start, this)
            return
        }
        val prefs = Prefs(context)
        // A task in the recents list means the user hasn't closed the app
        val closed = prefs.onlyOpen() &&
            context.getSystemService(android.app.ActivityManager::class.java).appTasks.isEmpty()
        if (!closed && !prefs.isQuiet(System.currentTimeMillis(), e) && !dndSilenced(context)) notify(context, e, start, this)
        Alarms.schedule(context, e)
        Widgets.refreshAll(context)
    }

    companion object {
        const val ACTION_STOP = "com.jaewook.d4timer.STOP"
        const val ACTION_SKIP_NEXT = "com.jaewook.d4timer.SKIP_NEXT"
        const val ACTION_SNOOZE = "com.jaewook.d4timer.SNOOZE"
        const val ACTION_DISMISS = "com.jaewook.d4timer.DISMISS"
        const val ACTION_REMIND = "com.jaewook.d4timer.REMIND"

        /** Snoozing only makes sense while at least two minutes remain before the start. */
        private const val SNOOZE_MIN_LEFT = 2 * MIN

        /** Phone Do Not Disturb is on in any mode (priority only, alarms only, total silence). */
        fun phoneDnd(context: Context): Boolean {
            val f = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
            return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }

        /** The user chose to follow the phone's Do Not Disturb and it is on now; same for every event. */
        fun dndSilenced(context: Context) = Prefs(context).followDnd() && phoneDnd(context)

        /** Whether this spawn still has early alerts (or its start alert) coming after the one ringing now. */
        fun moreAlerts(context: Context, e: GameEvent, start: Long): Boolean {
            val now = System.currentTimeMillis()
            return Prefs(context).leads(e).any { start - it * MIN > now + 10_000 }
        }

        fun notificationId(e: GameEvent) = Schedule.events.indexOfFirst { it.key == e.key } + 1

        fun notify(context: Context, e: GameEvent, start: Long, receiver: BroadcastReceiver? = null) {
            if (Alarms.soundUri(context, e) != null && AlarmSound.start(context, e, start)) return
            context.getSystemService(NotificationManager::class.java).notify(notificationId(e), build(context, e, start))
            // The ringing service couldn't start (exact alarms off): still make a sound, not just a buzz
            if (Alarms.soundUri(context, e) != null && receiver != null) AlarmSound.playBrief(context, e, receiver.goAsync())
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

        private fun fullScreen(context: Context, e: GameEvent, start: Long, title: String, text: String): PendingIntent =
            PendingIntent.getActivity(
                context, 900 + notificationId(e),
                Intent(context, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    .putExtra(Alarms.EXTRA_KEY, e.key).putExtra(Alarms.EXTRA_START, start)
                    .putExtra(AlarmActivity.EXTRA_TITLE, title).putExtra(AlarmActivity.EXTRA_TEXT, text)
                    .putExtra(AlarmActivity.EXTRA_SNOOZE, start - System.currentTimeMillis() >= SNOOZE_MIN_LEFT)
                    .putExtra(AlarmActivity.EXTRA_MORE, moreAlerts(context, e, start)),
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
                context, 100, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_ALARM, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
                // Readable and answerable on the lock screen
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                // Locked or dark screen: the full alarm screen. In use: Android shows a pop-up at the top instead
                .setFullScreenIntent(fullScreen(context, e, start, title, text), true)
            // Left to right: C (this + next spawn), B (this spawn's remaining alerts), A (just this ring, most used)
            builder.addAction(Notification.Action.Builder(null, "다음 회까지 건너뛰기", action(context, e, start, ACTION_SKIP_NEXT, 700)).build())
            if (moreAlerts(context, e, start)) {
                builder.addAction(Notification.Action.Builder(null, "이번 회 건너뛰기", action(context, e, start, ACTION_DISMISS, 600)).build())
            }
            builder.addAction(Notification.Action.Builder(null, "끄기", action(context, e, start, ACTION_STOP, 800)).build())
            return builder.build()
        }
    }
}
