package com.jaewook.d4timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

object Alarms {
    const val EXTRA_KEY = "event"
    const val EXTRA_START = "start"

    fun channelId(context: Context, e: GameEvent) = "evs_${e.key}_${Prefs(context).channelVersion(e)}"

    fun soundUri(context: Context, e: GameEvent): Uri? = when (val s = Prefs(context).sound(e)) {
        null -> Settings.System.DEFAULT_ALARM_ALERT_URI
        Prefs.SILENT -> null
        else -> Uri.parse(s)
    }

    fun soundName(context: Context, e: GameEvent): String {
        val s = Prefs(context).sound(e)
        if (s == Prefs.SILENT) return "무음 (진동만)"
        if (s == null) return "기본 알람음"
        return try {
            RingtoneManager.getRingtone(context, Uri.parse(s))?.getTitle(context) ?: "선택한 소리"
        } catch (_: Exception) {
            "선택한 소리"
        }
    }

    fun ensureChannel(context: Context, e: GameEvent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val id = channelId(context, e)
        if (nm.getNotificationChannel(id) != null) return
        // Remove this event's older channels and the single channel from v1.x
        for (ch in nm.notificationChannels) {
            val old = ch.id.startsWith("ev_${e.key}_") || (ch.id.startsWith("evs_${e.key}_") && ch.id != id)
            if (ch.id == "events" || old) nm.deleteNotificationChannel(ch.id)
        }
        val ch = NotificationChannel(id, "${e.name} 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "${e.name} 시작 전에 알려줍니다"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            // The sound is played by AlarmSound on the alarm stream
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    fun changeSound(context: Context, e: GameEvent, uri: String?) {
        val prefs = Prefs(context)
        prefs.setSound(e, uri)
        prefs.bumpChannelVersion(e)
        ensureChannel(context, e)
    }

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun pending(context: Context, e: GameEvent, start: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_KEY, e.key)
            .putExtra(EXTRA_START, start)
        val requestCode = Schedule.events.indexOfFirst { it.key == e.key }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, base: GameEvent) {
        val prefs = Prefs(context)
        val e = prefs.effective(base)
        val am = context.getSystemService(AlarmManager::class.java)
        if (!prefs.isOn(e)) {
            am.cancel(pending(context, e, 0))
            return
        }
        val lead = prefs.lead(e)
        var start = Schedule.nextAlarmStart(e, System.currentTimeMillis(), lead)
        // Skip starts whose alarm would ring during quiet hours (a day holds at most ~60 legion cycles)
        var guard = 0
        while ((prefs.isQuiet(start - lead * MIN) || start == prefs.skip(e)) && guard++ < 200) start += e.period
        val at = start - lead * MIN
        val pi = pending(context, e, start)
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun scheduleAll(context: Context) {
        Schedule.events.forEach { schedule(context, it) }
        BossWidget.refresh(context)
    }
}
