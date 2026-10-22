package com.jaewook.d4timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import org.json.JSONArray
import org.json.JSONObject

/** The screen is a local web page (assets/index.html); this class bridges it to alarms and settings. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var insetTop = 0
    private var insetBottom = 0
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Schedule.events.forEach { Alarms.ensureChannel(this, it) }

        // Draw behind the status and navigation bars; the page pads itself with the insets
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(7, 5, 5))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    pushInsets()
                }
            }
        }
        web.setOnApplyWindowInsetsListener { v, insets ->
            val density = resources.displayMetrics.density
            @Suppress("DEPRECATION")
            insetTop = (insets.systemWindowInsetTop / density).toInt()
            @Suppress("DEPRECATION")
            insetBottom = (insets.systemWindowInsetBottom / density).toInt()
            pushInsets()
            v.onApplyWindowInsets(insets)
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        Alarms.scheduleAll(this)
        checkForUpdate()
    }

    /** Offers a newer Play Store version; the user can always choose "later". Silent outside Play installs. */
    private fun checkForUpdate() {
        val manager = AppUpdateManagerFactory.create(this)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (isFinishing || info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE ||
                !info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) return@addOnSuccessListener
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("새 버전이 있어요")
                .setMessage("더 좋아진 버전으로 업데이트할까요?\nA new version is available.")
                .setPositiveButton("업데이트") { _, _ ->
                    @Suppress("DEPRECATION")
                    manager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, this, REQ_UPDATE)
                }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app silences a ringing alarm
        AlarmSound.stop(this)
        Alarms.scheduleAll(this)
        refreshPage()
        if (System.currentTimeMillis() - Prefs(this).syncTime() > SYNC_EVERY) startSync()
    }

    @Volatile private var syncing = false

    private fun startSync() {
        if (syncing) return
        syncing = true
        refreshPage()
        Thread {
            Sync.run(applicationContext)
            syncing = false
            runOnUiThread { refreshPage() }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPage()
    }

    private fun pushInsets() {
        if (pageReady) web.evaluateJavascript("window.setInsets && setInsets($insetTop, $insetBottom)", null)
    }

    private fun refreshPage() {
        if (pageReady) web.evaluateJavascript("window.onState && onState()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.handleBack ? handleBack() : false") { handled ->
            @Suppress("DEPRECATION")
            if (handled != "true") super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode < REQ_SOUND || resultCode != RESULT_OK || data == null) return
        val e = Schedule.events.getOrNull(requestCode - REQ_SOUND) ?: return
        @Suppress("DEPRECATION")
        val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val value = when {
            uri == null -> Prefs.SILENT
            uri == Settings.System.DEFAULT_ALARM_ALERT_URI -> null
            else -> uri.toString()
        }
        Alarms.changeSound(this, e, value)
        refreshPage()
    }

    private fun stateJson(): String {
        val prefs = Prefs(this)
        val events = JSONArray()
        for (base in Schedule.events) {
            val e = prefs.effective(base)
            events.put(
                JSONObject()
                    .put("key", e.key).put("name", e.name)
                    .put("anchor", e.anchor).put("period", e.period).put("duration", e.duration)
                    .put("on", prefs.isOn(e)).put("lead", prefs.lead(e))
                    .put("sound", Alarms.soundName(this, e))
                    .put("custom", prefs.hasCustomAnchor(e))
                    .put("repeat", prefs.repeat(e))
                    .put("synced", prefs.hasSyncAnchor(e))
                    .put("skip", prefs.skip(e)),
            )
        }
        val notif = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        return JSONObject()
            .put("version", version).put("notif", notif).put("exact", Alarms.canExact(this))
            .put("events", events)
            .put("onlyOpen", prefs.onlyOpen())
            .put("bossList", JSONArray(prefs.bossList()))
            .put(
                "sync",
                JSONObject().put("ok", prefs.syncOk()).put("msg", prefs.syncMessage()).put("time", prefs.syncTime())
                    .put("running", syncing),
            )
            .put(
                "quiet",
                JSONObject().put("on", prefs.quietOn()).put("start", prefs.quietStart()).put("end", prefs.quietEnd()),
            )
            .toString()
    }

    /** Methods the page calls as window.Android.*; they run off the UI thread. */
    inner class Bridge {
        private fun event(key: String) = Schedule.byKey(key)

        @JavascriptInterface
        fun getState(): String = stateJson()

        @JavascriptInterface
        fun setOnlyOpen(on: Boolean) = Prefs(this@MainActivity).setOnlyOpen(on)

        /** Skip (or un-skip) the alarm for one start time. */
        @JavascriptInterface
        fun setSkip(key: String, start: String) {
            val e = event(key)
            Prefs(this@MainActivity).setSkip(e, start.toLongOrNull() ?: 0L)
            Alarms.schedule(this@MainActivity, e)
            runOnUiThread { refreshPage() }
        }

        @JavascriptInterface
        fun setOn(key: String, on: Boolean) {
            val e = event(key)
            Prefs(this@MainActivity).setOn(e, on)
            Alarms.schedule(this@MainActivity, e)
        }

        @JavascriptInterface
        fun setLead(key: String, min: Int) {
            val e = event(key)
            Prefs(this@MainActivity).setLead(e, min)
            Alarms.schedule(this@MainActivity, e)
        }

        @JavascriptInterface
        fun setAnchor(key: String, epochMs: String) {
            val e = event(key)
            Prefs(this@MainActivity).setAnchor(e, epochMs.toLong())
            Alarms.schedule(this@MainActivity, e)
        }

        @JavascriptInterface
        fun resetAnchor(key: String) {
            val e = event(key)
            Prefs(this@MainActivity).resetAnchor(e)
            Alarms.schedule(this@MainActivity, e)
        }

        @JavascriptInterface
        fun syncNow() = runOnUiThread { startSync() }

        @JavascriptInterface
        fun getSyncRaw(): String = Prefs(this@MainActivity).syncRaw()

        @JavascriptInterface
        fun setRepeat(key: String, on: Boolean) = Prefs(this@MainActivity).setRepeat(event(key), on)

        @JavascriptInterface
        fun setQuiet(on: Boolean, start: Int, end: Int) {
            Prefs(this@MainActivity).setQuiet(on, start, end)
            Alarms.scheduleAll(this@MainActivity)
        }

        @JavascriptInterface
        fun test(key: String) {
            val e = event(key)
            AlarmReceiver.notify(this@MainActivity, e, System.currentTimeMillis() + Prefs(this@MainActivity).lead(e) * MIN)
        }

        @JavascriptInterface
        fun pickSound(key: String) = runOnUiThread {
            val e = event(key)
            val current = Alarms.soundUri(this@MainActivity, e)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "${e.name} 알람음")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SOUND + Schedule.events.indexOf(e))
        }

        @JavascriptInterface
        fun openNotifSettings() = runOnUiThread {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }

        @JavascriptInterface
        fun openExactSettings() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_UPDATE = 2
        private const val REQ_SOUND = 100
        private const val SYNC_EVERY = 10 * MIN
    }
}
