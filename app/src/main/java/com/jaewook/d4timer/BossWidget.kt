package com.jaewook.d4timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home screen widget: next world boss time, name and a live countdown. */
class BossWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK) refresh(context)
    }

    companion object {
        private const val ACTION_TICK = "com.jaewook.d4timer.WIDGET_TICK"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BossWidget::class.java))
            if (ids.isEmpty()) return
            val prefs = Prefs(context)
            val e = prefs.effective(Schedule.byKey("boss"))
            val now = System.currentTimeMillis()
            val live = Schedule.isLive(e, now)
            val start = if (live) Schedule.lastStart(e, now) else Schedule.nextStart(e, now)
            val target = if (live) start + e.duration else start
            val views = RemoteViews(context.packageName, R.layout.widget_boss).apply {
                setTextViewText(R.id.w_label, if (live) "월드보스 진행 중" else "다음 월드보스")
                setTextViewText(R.id.w_time, SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(start)))
                setTextViewText(R.id.w_name, Names.bosses(prefs.bossesAt(start)) ?: "")
                setChronometer(R.id.w_count, SystemClock.elapsedRealtime() + (target - now), null, true)
                setChronometerCountDown(R.id.w_count, true)
                setOnClickPendingIntent(
                    R.id.w_root,
                    PendingIntent.getActivity(context, 400, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
            }
            manager.updateAppWidget(ids, views)
            // Redraw when the countdown reaches zero so it moves on to the next spawn
            val tick = PendingIntent.getBroadcast(
                context, 401, Intent(context, BossWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, target + 5_000, tick)
        }
    }
}
