package com.jaewook.d4timer

import android.content.Context
import org.json.JSONObject

/**
 * Settings backup as plain text: alarm on/off and lead times, repeat, quiet hours, volumes, DND/only-open
 * switches, corrected start times and the season reminder. Sounds are left out (ringtone addresses differ
 * between phones). Nothing leaves the phone except what the user shares or pastes themselves.
 */
object SettingsBackup {
    private const val MAGIC = "d4alarm-settings-v1"
    private val PREFIXES = listOf("on_", "leads_", "lead_", "repeat_", "quiet_", "alarm_vol_", "anchor_")
    private val NAMES = setOf("follow_dnd", "only_open", "season_remind")

    private fun wanted(key: String) = key in NAMES || PREFIXES.any { key.startsWith(it) }

    fun export(context: Context): String {
        val sp = context.getSharedPreferences("d4timer", Context.MODE_PRIVATE)
        val data = JSONObject()
        for ((k, v) in sp.all) if (wanted(k) && v != null) data.put(k, v)
        return JSONObject().put("app", MAGIC).put("version", appVersion(context)).put("data", data).toString()
    }

    private fun appVersion(context: Context) =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

    /** Returns how many settings were restored, or -1 when the text isn't a backup. */
    fun restore(context: Context, text: String): Int {
        val root = runCatching { JSONObject(text.trim().let { it.substring(it.indexOf('{').coerceAtLeast(0)) }) }.getOrNull() ?: return -1
        if (root.optString("app") != MAGIC) return -1
        val data = root.optJSONObject("data") ?: return -1
        val ed = context.getSharedPreferences("d4timer", Context.MODE_PRIVATE).edit()
        var n = 0
        for (k in data.keys()) {
            if (!wanted(k)) continue
            when (val v = data.get(k)) {
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is String -> ed.putString(k, v)
                else -> continue
            }
            n++
        }
        ed.apply()
        return n
    }
}
