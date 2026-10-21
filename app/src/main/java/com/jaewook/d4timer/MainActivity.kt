package com.jaewook.d4timer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private class Card(
        val e: GameEvent,
        val status: TextView,
        val count: TextView,
        val caption: TextView,
        val upcoming: TextView,
    )

    private val gold = 0xFFC9A24A.toInt()
    private val panel = 0xFF181412.toInt()
    private val line = 0xFF2C2420.toInt()
    private val fg = 0xFFECE4DC.toInt()
    private val muted = 0xFF9A8D82.toInt()

    private val cards = mutableListOf<Card>()
    private lateinit var prefs: Prefs
    private lateinit var exactWarning: View
    private val handler = Handler(Looper.getMainLooper())
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Alarms.ensureChannel(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Alarms.scheduleAll(this)
    }

    override fun onResume() {
        super.onResume()
        exactWarning.visibility = if (Alarms.canExact(this)) View.GONE else View.VISIBLE
        Alarms.scheduleAll(this)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
        }

        root.addView(text("SANCTUARY TIMER", 24f, gold, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        })
        root.addView(text("월드보스 · 지옥물결 · 군단", 13f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        })

        exactWarning = Button(this).apply {
            text = "정확한 알람 권한을 켜주세요 (누르면 설정으로 이동)"
            setTextColor(Color.BLACK)
            background = rounded(gold, 0, 10)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                    )
                }
            }
        }
        root.addView(exactWarning, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        for (e in Schedule.events) root.addView(buildCard(e), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        })

        root.addView(Button(this).apply {
            text = "테스트 알림 보내기"
            setTextColor(fg)
            background = rounded(panel, line, 10)
            setOnClickListener {
                val e = Schedule.events[0]
                AlarmReceiver.notify(this@MainActivity, e, Schedule.nextStart(e, System.currentTimeMillis()))
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))

        root.addView(text(
            "시간은 고정 주기로 계산합니다.\n월드보스 3시간 30분 · 지옥물결 매시 정각(55분) · 군단 25분",
            12f, muted,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun buildCard(e: GameEvent): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, line, 16)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(this).apply { background = rounded(e.color, 0, 2) }, LinearLayout.LayoutParams(dp(4), dp(22)))
        header.addView(text(e.name, 19f, fg, bold = true).apply { setPadding(dp(10), 0, 0, 0) })
        val status = text("", 12f, muted).apply { setPadding(dp(10), 0, 0, 0) }
        header.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Switch(this).apply {
            isChecked = prefs.isOn(e)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(e.color, muted),
            )
            setOnCheckedChangeListener { _, on ->
                prefs.setOn(e, on)
                Alarms.schedule(this@MainActivity, e)
            }
        })
        card.addView(header)

        val count = text("--:--:--", 44f, e.color, bold = true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(count)
        val caption = text("", 13f, muted).apply { gravity = Gravity.CENTER }
        card.addView(caption)
        val upcoming = text("", 13f, fg).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(8))
        }
        card.addView(upcoming)

        card.addView(Button(this).apply {
            setTextColor(gold)
            background = rounded(0xFF221C19.toInt(), 0, 8)
            fun label() { text = "알림: " + leadLabel(prefs.lead(e)) + "  (눌러서 변경)" }
            label()
            setOnClickListener {
                val choices = Prefs.LEAD_CHOICES
                val next = choices[(choices.indexOf(prefs.lead(e)) + 1) % choices.size]
                prefs.setLead(e, next)
                label()
                Alarms.schedule(this@MainActivity, e)
            }
        }, LinearLayout.LayoutParams(-1, dp(40)))

        cards += Card(e, status, count, caption, upcoming)
        return card
    }

    private fun leadLabel(min: Int) = if (min == 0) "시작할 때" else "시작 ${min}분 전"

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    private fun render() {
        val now = System.currentTimeMillis()
        for (c in cards) {
            val e = c.e
            val next = Schedule.nextStart(e, now)
            val live = Schedule.isLive(e, now)
            if (live) {
                c.status.text = "● 진행 중"
                c.status.setTextColor(e.color)
                c.count.text = fmt(Schedule.lastStart(e, now) + e.duration - now)
                c.caption.text = "종료까지 남은 시간"
            } else {
                c.status.text = "대기"
                c.status.setTextColor(muted)
                c.count.text = fmt(next - now)
                c.caption.text = hm.format(Date(next)) + " 시작"
            }
            val first = if (live) 0 else 1
            c.upcoming.text = (first until first + 4).joinToString("   ") {
                hm.format(Date(next + it * e.period))
            }
        }
    }
}
