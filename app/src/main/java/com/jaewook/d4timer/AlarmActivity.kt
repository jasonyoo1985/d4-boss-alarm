package com.jaewook.d4timer

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Full-screen alarm shown over the lock screen, like a clock app. When the phone is unlocked and in use
 * the system shows the same alarm as a pop-up at the top instead, so this only appears on a locked or
 * dark screen. Every button does exactly what the notification's buttons do.
 */
class AlarmActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var key: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(i: Intent) {
        key = i.getStringExtra(Alarms.EXTRA_KEY) ?: run { finishAndRemoveTask(); return }
        current = WeakReference(this)
        val e = Schedule.byKey(key)
        val start = i.getLongExtra(Alarms.EXTRA_START, 0L)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0D0B0A"))
            setPadding(px(24), px(48), px(24), px(40))
        }
        fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(label("디아4 보스알람", 14f, Color.parseColor("#8A817A")))
        root.addView(label(i.getStringExtra(EXTRA_TITLE) ?: e.name, 34f, e.color, true).apply { setPadding(0, px(14), 0, px(8)) })
        root.addView(label(i.getStringExtra(EXTRA_TEXT) ?: "", 18f, Color.parseColor("#E8E2DC")))

        fun button(text: String, fill: Int, textColor: Int, big: Boolean, onTap: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = if (big) 20f else 13f
            setTextColor(textColor)
            if (big) typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = px(if (big) 36 else 14).toFloat(); setColor(fill) }
            stateListAnimator = null
            setOnClickListener { onTap() }
        }
        root.addView(View(this), LinearLayout.LayoutParams(1, px(56)))
        root.addView(
            button("끄기", e.color, Color.parseColor("#120D0A"), true) { send(AlarmReceiver.ACTION_STOP, start) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(72)),
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val grey = Color.parseColor("#26211E")
        val light = Color.parseColor("#E8E2DC")
        val small = mutableListOf<Pair<String, String>>()
        if (i.getBooleanExtra(EXTRA_SNOOZE, false)) small += "1분 후 다시" to AlarmReceiver.ACTION_SNOOZE
        small += "다음 회 건너뛰기" to AlarmReceiver.ACTION_SKIP_NEXT
        small += "다시 보지 않기" to AlarmReceiver.ACTION_DISMISS
        small.forEachIndexed { n, (text, action) ->
            row.addView(
                button(text, grey, light, false) { send(action, start) },
                LinearLayout.LayoutParams(0, px(56), 1f).apply { if (n > 0) marginStart = px(8) },
            )
        }
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
        root.addView(
            label("앱 열기", 14f, Color.parseColor("#8A817A")).apply {
                setPadding(0, px(22), 0, px(8))
                setOnClickListener { openApp() }
            },
        )
        setContentView(root)

        // The screen goes away on its own, like the sound does
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finishAndRemoveTask() }, if (Prefs(this).repeat(e)) 5 * MIN else 60_000L)
    }

    private fun send(action: String, start: Long) {
        sendBroadcast(
            Intent(this, AlarmReceiver::class.java).setAction(action)
                .putExtra(Alarms.EXTRA_KEY, key).putExtra(Alarms.EXTRA_START, start),
        )
        finishAndRemoveTask()
    }

    private fun openApp() {
        AlarmSound.stop(this, key)
        val go = { startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_ALARM, true)); finishAndRemoveTask() }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = go()
            })
        } else {
            go()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SNOOZE = "snooze"

        private var current: WeakReference<AlarmActivity>? = null

        /** Closes the alarm screen when the alarm was handled from the notification instead. */
        fun close(key: String) {
            val a = current?.get() ?: return
            if (a.key == key) a.runOnUiThread { a.finishAndRemoveTask() }
        }

        /** True when Android lets this app show the alarm screen over the lock screen. */
        fun allowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 34) return true
            return context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
        }
    }
}
