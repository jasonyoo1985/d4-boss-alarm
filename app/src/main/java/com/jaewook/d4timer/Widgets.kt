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

object Widgets {
    fun refreshAll(context: Context) {
        BossWidget.refresh(context)
        SmallWidget.refresh(context)
    }

    /** Start shown for [e] (the running one while live) and the moment the widget must redraw. */
    fun next(e: GameEvent, now: Long): Triple<Boolean, Long, Long> {
        val live = Schedule.isLive(e, now)
        val start = if (live) Schedule.lastStart(e, now) else Schedule.nextStart(e, now)
        return Triple(live, start, if (live) start + e.duration else start)
    }

    fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(t))

    fun openApp(context: Context) =
        PendingIntent.getActivity(context, 400, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun countdown(views: RemoteViews, id: Int, target: Long, now: Long) {
        views.setChronometer(id, SystemClock.elapsedRealtime() + (target - now), null, true)
        views.setChronometerCountDown(id, true)
    }

    /** Redraw just after [at], when a countdown reaches zero. */
    fun tickAt(context: Context, cls: Class<*>, action: String, requestCode: Int, at: Long) {
        val tick = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, cls).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at + 5_000, tick)
    }

    fun ids(context: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))
}

/** 2x1 widget: just the next world boss countdown. */
class SmallWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK) refresh(context)
    }

    companion object {
        private const val ACTION_TICK = "com.jaewook.d4timer.WIDGET_SMALL_TICK"

        fun refresh(context: Context) {
            val ids = Widgets.ids(context, SmallWidget::class.java)
            if (ids.isEmpty()) return
            val e = Prefs(context).effective(Schedule.byKey("boss"))
            val now = System.currentTimeMillis()
            val (live, start, target) = Widgets.next(e, now)
            val views = RemoteViews(context.packageName, R.layout.widget_small).apply {
                setTextViewText(R.id.ws_label, if (live) "월드보스 진행 중" else "월드보스 " + Widgets.hm(start))
                Widgets.countdown(this, R.id.ws_count, target, now)
                setOnClickPendingIntent(R.id.ws_root, Widgets.openApp(context))
            }
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
            Widgets.tickAt(context, SmallWidget::class.java, ACTION_TICK, 403, target)
        }
    }
}
