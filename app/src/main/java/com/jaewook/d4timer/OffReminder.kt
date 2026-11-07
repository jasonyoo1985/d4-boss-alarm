package com.jaewook.d4timer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * An event's alarm switched off ("알람 해제" or the home switch) is easy to forget. Right after turning it off
 * from the alarm, a quiet notice offers [다시 켜기]; if it is still off [DAYS] days later, one more reminder comes.
 * Inexact and silent on purpose: no exact-alarm permission, no sound, and nothing at all if the user turned it back on.
 */
object OffReminder {
    private const val CHANNEL = "off_notice"
    private const val DAYS = 3L

    private fun id(e: GameEvent) = 950 + AlarmReceiver.notificationId(e)

    private fun pending(context: Context, e: GameEvent): PendingIntent = PendingIntent.getBroadcast(
        context, 960 + AlarmReceiver.notificationId(e),
        Intent(context, OffReceiver::class.java).putExtra(Alarms.EXTRA_KEY, e.key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context, e: GameEvent) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val since = prefs.offSince(e)
        if (prefs.isOn(e) || since == 0L || prefs.offNotified(e)) {
            am.cancel(pending(context, e))
            return
        }
        val at = (since + DAYS * 24 * 3600 * 1000L).coerceAtLeast(System.currentTimeMillis() + 60_000L)
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC, at, pending(context, e)) }
    }

    fun scheduleAll(context: Context) = Schedule.events.forEach { schedule(context, it) }

    private fun channel(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "알람 꺼짐 안내", NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun enable(context: Context, e: GameEvent): PendingIntent = PendingIntent.getBroadcast(
        context, 970 + AlarmReceiver.notificationId(e),
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ENABLE).putExtra(Alarms.EXTRA_KEY, e.key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, e: GameEvent, title: String, text: String) {
        val nm = channel(context)
        val open = PendingIntent.getActivity(
            context, 102, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(id(e), Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setColor(e.color)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "다시 켜기", enable(context, e)).build())
            .build())
    }

    /** Right after "알람 해제": confirms it and offers the way back. */
    fun showJustOff(context: Context, e: GameEvent) =
        post(context, e, "${e.name} 알람을 껐어요", "다시 울리게 하려면 [다시 켜기]를 누르세요")

    fun showStillOff(context: Context, e: GameEvent) {
        val prefs = Prefs(context)
        if (prefs.isOn(e) || prefs.offNotified(e)) return
        prefs.setOffNotified(e)
        post(context, e, "${e.name} 알람이 ${DAYS}일째 꺼져 있어요", "필요하면 [다시 켜기]를 누르세요")
    }

    fun clear(context: Context, e: GameEvent) {
        context.getSystemService(NotificationManager::class.java).cancel(id(e))
        schedule(context, e)
    }
}

class OffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(Alarms.EXTRA_KEY) ?: return
        runCatching { OffReminder.showStillOff(context, Schedule.byKey(key)) }
    }
}
