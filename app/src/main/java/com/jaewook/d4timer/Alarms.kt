package com.jaewook.d4timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

object Alarms {
    const val CHANNEL = "events"
    const val EXTRA_KEY = "event"
    const val EXTRA_START = "start"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "이벤트 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "월드보스, 지옥물결, 군단 시작 알림"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
            )
        }
        nm.createNotificationChannel(ch)
    }

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun pending(context: Context, e: GameEvent, start: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_KEY, e.key)
            .putExtra(EXTRA_START, start)
        val requestCode = Schedule.events.indexOf(e)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, e: GameEvent) {
        val prefs = Prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        if (!prefs.isOn(e)) {
            am.cancel(pending(context, e, 0))
            return
        }
        val lead = prefs.lead(e)
        val start = Schedule.nextAlarmStart(e, System.currentTimeMillis(), lead)
        val at = start - lead * MIN
        val pi = pending(context, e, start)
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun scheduleAll(context: Context) = Schedule.events.forEach { schedule(context, it) }
}
