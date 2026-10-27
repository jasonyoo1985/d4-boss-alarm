package com.jaewook.d4timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.window.OnBackInvokedDispatcher
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
    private var pendingUpdate: String? = null
    private var updateManager: com.google.android.play.core.appupdate.AppUpdateManager? = null
    private var updateInfo: com.google.android.play.core.appupdate.AppUpdateInfo? = null
    private var fromAlarm = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromAlarm = intent?.getBooleanExtra(EXTRA_FROM_ALARM, false) == true
        // Each fresh open hands the slot to the next clan, so the approved clans share the exposure
        Clans.nextTurn(this)
        Schedule.events.forEach { Alarms.ensureChannel(this, it) }
        // Android 13+ with predictive back never calls onBackPressed, so route back through the page here too
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }

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
                    showPendingUpdate()
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

    /**
     * Offers a newer Play Store version in the page's update sheet; "later" hides it for the rest of the day.
     * Play only gives the new version code, so its name and change notes come from season.json "release"
     * when that matches; otherwise the sheet shows without them. Silent outside Play installs.
     */
    private fun checkForUpdate() {
        val manager = AppUpdateManagerFactory.create(this)
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (isFinishing || info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE ||
                !info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) return@addOnSuccessListener
            val code = info.availableVersionCode()
            if (Prefs(this).updateLaterToday(code)) return@addOnSuccessListener
            updateManager = manager
            updateInfo = info
            val release = runCatching { JSONObject(Prefs(this).remoteConfig()).getJSONObject("release") }.getOrNull()
                ?.takeIf { it.optInt("code") == code }
            val offer = JSONObject().put("code", code)
                .put("current", packageManager.getPackageInfo(packageName, 0).versionName ?: "")
                .put("name", release?.optString("name") ?: "")
                .put("notes", release?.optJSONArray("notes") ?: JSONArray())
            pendingUpdate = "window.showUpdate && showUpdate($offer)"
            showPendingUpdate()
        }
    }

    private fun showPendingUpdate() {
        val js = pendingUpdate ?: return
        if (!pageReady) return
        pendingUpdate = null
        web.evaluateJavascript(js, null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_ALARM, false)) {
            fromAlarm = true
            Clans.nextTurn(this)
        }
    }

    override fun onPause() {
        VolumePreview.stop(this)
        super.onPause()
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
    @Volatile private var lastReplyCheck = 0L

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
    override fun onBackPressed() = handleBack()

    /** Closes an open sheet or returns to the home tab; on the home tab the app goes to the background. */
    private fun handleBack() {
        web.evaluateJavascript("window.handleBack ? handleBack() : false") { handled ->
            if (handled != "true") moveTaskToBack(true)
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

    /** Current phone alarm volume in percent. */
    private fun phoneAlarmVolume(): Int = try {
        val am = getSystemService(android.media.AudioManager::class.java)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
        if (max > 0) am.getStreamVolume(android.media.AudioManager.STREAM_ALARM) * 100 / max else 100
    } catch (_: Exception) {
        100
    }

    /** An event whose alarm was turned down from a pop-up to a plain notification in the phone's settings. */
    private fun popupOffKey(): String? {
        val nm = getSystemService(NotificationManager::class.java)
        val prefs = Prefs(this)
        return Schedule.events.firstOrNull { e ->
            if (!prefs.isOn(e)) return@firstOrNull false
            Alarms.ensureChannel(this, e)
            val imp = nm.getNotificationChannel(Alarms.channelId(this, e))?.importance ?: return@firstOrNull false
            imp != NotificationManager.IMPORTANCE_NONE && imp < NotificationManager.IMPORTANCE_HIGH
        }?.key
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
                    .put("on", prefs.isOn(e)).put("leads", JSONArray(prefs.leads(e)))
                    .put("sound", Alarms.soundName(this, e))
                    .put("custom", prefs.hasCustomAnchor(e))
                    .put("repeat", prefs.repeat(e))
                    .put("vol", prefs.alarmVolume(e))
                    .put("quiet", prefs.quietFor(e))
                    .put("synced", prefs.hasSyncAnchor(e))
                    .put("skip", prefs.skip(e)),
            )
        }
        val notif = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val pkg = packageManager.getPackageInfo(packageName, 0)
        val version = pkg.versionName
        // "What's new" once after an update; never after a fresh install
        val whatsNew = prefs.newsSeen() != version && pkg.firstInstallTime != pkg.lastUpdateTime
        return JSONObject()
            .put("version", version).put("whatsNew", whatsNew).put("notif", notif).put("exact", Alarms.canExact(this))
            .put("events", events)
            .put("onlyOpen", prefs.onlyOpen())
            .put("popupOff", popupOffKey() ?: JSONObject.NULL).put("lockOff", !AlarmActivity.allowed(this))
            .put("followDnd", prefs.followDnd()).put("dndNow", AlarmReceiver.phoneDnd(this))
            .put("phoneVol", phoneAlarmVolume())
            .put("season", prefs.remoteSeason() ?: JSONObject.NULL)
            .put("clan", Clans.featured(this) ?: JSONObject.NULL)
            .put("clans", Clans.cached(this) ?: JSONObject.NULL)
            .put("clanEvents", Clans.events(this))
            .put("clanFollowing", JSONArray(prefs.clanFollowing().toList()))
            .put("clanMine", prefs.clanToken().isNotEmpty())
            .put("clanMineStatus", Clans.mineStatus(this) ?: JSONObject.NULL)
            // A one-line notice the developer can post from season.json; the page checks its end date
            .put("notice", runCatching { JSONObject(prefs.remoteConfig()).getJSONObject("notice") }.getOrNull() ?: JSONObject.NULL)
            // The card only greets someone who came in from an alarm, and only once a day if closed
            .put("fromAlarm", fromAlarm && !prefs.clanCardClosedToday())
            .put("bossList", JSONArray(prefs.bossList()))
            .put("feedback", prefs.feedback())
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

        /** Saves the message and tries to send it now; if that fails it goes out on a later sync. */
        @JavascriptInterface
        fun sendFeedback(stars: Int, text: String) {
            val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            Feedback.add(this@MainActivity, stars, text, version)
            runOnUiThread { refreshPage() }
            Thread {
                Feedback.sync(applicationContext)
                runOnUiThread { refreshPage() }
            }.start()
        }

        /** Shares the PC alarm page with this phone's alert settings in the link (lead minutes, 0 = off). */
        @JavascriptInterface
        fun sharePcLink() {
            val prefs = Prefs(this@MainActivity)
            val query = Schedule.events.joinToString("&") { e ->
                val lead = if (prefs.isOn(e)) prefs.leads(e).minOrNull() ?: 0 else 0
                "${e.key}=$lead"
            }
            val link = "$PC_PAGE?$query"
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "디아4 월드보스 알람 PC")
                .putExtra(Intent.EXTRA_TEXT, "PC에서 열면 알림이 울려요 (탭을 열어 두세요)\n$link")
            runOnUiThread { startActivity(Intent.createChooser(send, "PC로 링크 보내기")) }
        }

        @JavascriptInterface
        fun startUpdate() = runOnUiThread {
            val manager = updateManager ?: return@runOnUiThread
            val info = updateInfo ?: return@runOnUiThread
            @Suppress("DEPRECATION")
            runCatching { manager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, this@MainActivity, REQ_UPDATE) }
        }

        @JavascriptInterface
        fun updateLater() {
            updateInfo?.let { Prefs(this@MainActivity).setUpdateLater(it.availableVersionCode()) }
        }

        @JavascriptInterface
        fun markNewsSeen() {
            Prefs(this@MainActivity).setNewsSeen(packageManager.getPackageInfo(packageName, 0).versionName ?: "")
        }

        /** Opening the feedback tab looks for new replies (at most once a minute). */
        @JavascriptInterface
        fun checkReplies() {
            val now = System.currentTimeMillis()
            if (now - lastReplyCheck < MIN) return
            lastReplyCheck = now
            Thread {
                Feedback.sync(applicationContext)
                runOnUiThread { refreshPage() }
            }.start()
        }

        @JavascriptInterface
        fun markFeedbackSeen() {
            Feedback.markSeen(this@MainActivity)
            runOnUiThread { refreshPage() }
        }

        @JavascriptInterface
        fun setOnlyOpen(on: Boolean) = Prefs(this@MainActivity).setOnlyOpen(on)

        /** Opens a clan's open chat or Discord invite; any other link is ignored (see [Clans.okLink]). */
        @JavascriptInterface
        fun openClanLink(url: String) = runOnUiThread {
            if (Clans.okLink(url)) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        /** Sends the clan's name and how to join to this phone's own chat, to open on the PC. */
        @JavascriptInterface
        fun shareClan(name: String, text: String) = runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, name)
                .putExtra(Intent.EXTRA_TEXT, text)
            runCatching { startActivity(Intent.createChooser(send, "PC로 보내기")) }
        }

        @JavascriptInterface
        fun followClan(id: String, on: Boolean) {
            val prefs = Prefs(this@MainActivity)
            prefs.setClanFollowing(if (on) prefs.clanFollowing() + id else prefs.clanFollowing() - id)
            runOnUiThread { refreshPage() }
        }

        @JavascriptInterface
        fun closeClanCard() {
            Prefs(this@MainActivity).closeClanCard()
            fromAlarm = false
            runOnUiThread { refreshPage() }
        }

        /** The page gets the server's answer back through window.clanDone(tag, text). */
        private fun answer(tag: String, text: String?) = runOnUiThread {
            refreshPage()
            val payload = JSONObject().put("tag", tag).put("text", text ?: "")
            web.evaluateJavascript("window.clanDone && clanDone($payload)", null)
        }

        @JavascriptInterface
        fun applyClan(json: String) {
            Thread {
                val form = runCatching { JSONObject(json) }.getOrNull() ?: JSONObject()
                answer("apply", Clans.apply(applicationContext, form))
            }.start()
        }

        @JavascriptInterface
        fun reportClan(id: String, reason: String) {
            Thread { answer("report", Clans.report(applicationContext, id, reason)) }.start()
        }

        @JavascriptInterface
        fun clanAdmin(pw: String, action: String, id: String, extra: String) {
            Thread { answer("admin", Clans.admin(applicationContext, pw, action, id, extra)) }.start()
        }

        @JavascriptInterface
        fun syncClans() {
            Thread {
                Clans.sync(applicationContext)
                runOnUiThread { refreshPage() }
            }.start()
        }

        @JavascriptInterface
        fun setFollowDnd(on: Boolean) = Prefs(this@MainActivity).setFollowDnd(on)

        /** Percent used while this event rings, or -1 to keep the phone's alarm volume. */
        @JavascriptInterface
        fun setAlarmVolume(key: String, v: Int) =
            Prefs(this@MainActivity).setAlarmVolume(event(key), if (v < 0) -1 else v.coerceIn(10, 100))

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
        fun setLeads(key: String, mins: String) {
            val e = event(key)
            Prefs(this@MainActivity).setLeads(e, mins.split(",").mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(5) })
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
        fun setQuietFor(key: String, on: Boolean) {
            val e = event(key)
            Prefs(this@MainActivity).setQuietFor(e, on)
            Alarms.schedule(this@MainActivity, e)
        }

        /** Plays this event's sound for a moment at the given volume (percent). */
        @JavascriptInterface
        fun previewVolume(key: String, v: Int) = runOnUiThread { VolumePreview.play(this@MainActivity, event(key), v) }

        @JavascriptInterface
        fun test(key: String) {
            val e = event(key)
            AlarmReceiver.notify(this@MainActivity, e, System.currentTimeMillis() + Prefs(this@MainActivity).leads(e).last() * MIN)
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

        /** Opens a page in the phone's browser (helltides.com for checking times, the developer's site, op.gg builds). */
        @JavascriptInterface
        fun openUrl(url: String) = runOnUiThread {
            if (url.startsWith("https://helltides.com/") || url == "https://www.stackupinfra.com") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            } else if (url.startsWith("https://op.gg/ko/diablo4/")) {
                // Custom Tab extras without the androidx library: the page opens over the app and back returns here
                val tab = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
                    .putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF0F0B0A.toInt())
                runCatching { startActivity(tab) }
            }
        }

        @JavascriptInterface
        fun openNotifSettings() = runOnUiThread {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }

        @JavascriptInterface
        fun openPopupSettings() = runOnUiThread {
            val key = popupOffKey()
            val i = when {
                key != null -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.channelId(this@MainActivity, event(key)))
                Build.VERSION.SDK_INT >= 34 -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
                else -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            runCatching { startActivity(i) }.onFailure { openNotifSettings() }
        }

        @JavascriptInterface
        fun openExactSettings() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    companion object {
        /** Set on the notification's tap intent so the clan card only greets people coming from an alarm. */
        const val EXTRA_FROM_ALARM = "from_alarm"
        private const val REQ_NOTIF = 1
        private const val REQ_UPDATE = 2
        private const val REQ_SOUND = 100
        private const val SYNC_EVERY = 10 * MIN
        private const val PC_PAGE = "https://d4alarm.stackupinfra.com/"
    }
}
