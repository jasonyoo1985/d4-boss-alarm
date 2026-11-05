package com.jaewook.d4timer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A plain notification 7 days and 1 day before the season ends (12:00 local time). The end date comes from
 * season.json; with no date, or one already past, nothing is scheduled and nothing is shown.
 * It is inexact on purpose: a reminder at 12:00 vs 12:20 doesn't matter, so no exact-alarm permission is used.
 */
object SeasonReminder {
    private const val CHANNEL = "season"
    private val DAYS = listOf(7, 1)

    private fun endMillis(context: Context): Long? = runCatching {
        Instant.parse(Prefs(context).remoteSeason()!!.getString("end")).toEpochMilli()
    }.getOrNull()

    private fun pending(context: Context, days: Int): PendingIntent = PendingIntent.getBroadcast(
        context, 900 + days,
        Intent(context, SeasonReceiver::class.java).putExtra("days", days),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val end = endMillis(context)
        val on = Prefs(context).seasonRemind()
        for (d in DAYS) {
            val day = end?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(d.toLong()) }
            val at = day?.atTime(LocalTime.NOON)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
            if (!on || at == null || at <= System.currentTimeMillis()) am.cancel(pending(context, d))
            else runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC, at, pending(context, d)) }
        }
    }

    fun show(context: Context, days: Int) {
        if (!Prefs(context).seasonRemind()) return
        val end = endMillis(context) ?: return
        // Stale or far-off alarms stay silent
        val left = (end - System.currentTimeMillis()) / (24 * 3600 * 1000L)
        if (end <= System.currentTimeMillis() || left > days + 1) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "시즌 종료 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val estimate = runCatching { Prefs(context).remoteSeason()!!.optBoolean("estimate") }.getOrDefault(false)
        val date = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).toLocalDate()
        val title = if (days == 1) "시즌 종료가 내일이에요" else "시즌 종료 ${days}일 전이에요"
        val text = "${date.monthValue}월 ${date.dayOfMonth}일 종료${if (estimate) " (예상)" else ""} · 시즌 목표를 마무리하세요"
        val open = PendingIntent.getActivity(
            context, 101, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(900 + days, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).build())
    }
}

class SeasonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { SeasonReminder.show(context, intent.getIntExtra("days", 7)) }
    }
}
