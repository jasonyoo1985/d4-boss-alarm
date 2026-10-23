package com.jaewook.d4timer

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("d4timer", Context.MODE_PRIVATE)

    fun isOn(e: GameEvent) = sp.getBoolean("on_${e.key}", e.defaultOn)
    fun setOn(e: GameEvent, on: Boolean) = sp.edit().putBoolean("on_${e.key}", on).apply()

    /** Minutes before the start to ring, earliest first; up to [MAX_LEADS] alerts per event. */
    fun leads(e: GameEvent): List<Int> {
        val saved = sp.getString("leads_${e.key}", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        val list = if (saved.isNullOrEmpty()) listOf(sp.getInt("lead_${e.key}", 5)) else saved
        return list.distinct().sortedDescending().take(MAX_LEADS)
    }
    fun setLeads(e: GameEvent, mins: List<Int>) =
        sp.edit().putString("leads_${e.key}", mins.distinct().sortedDescending().take(MAX_LEADS).joinToString(",")).apply()
    /** The earliest alert. */
    fun lead(e: GameEvent) = leads(e).first()

    /** null = phone's default alarm sound, [SILENT] = no sound, otherwise a ringtone URI. */
    fun sound(e: GameEvent): String? = sp.getString("sound_${e.key}", null)
    fun setSound(e: GameEvent, uri: String?) = sp.edit().putString("sound_${e.key}", uri).apply()

    // Notification channels can't change their sound, so each pick gets a new channel id
    fun channelVersion(e: GameEvent) = sp.getInt("chver_${e.key}", 0)
    fun bumpChannelVersion(e: GameEvent) = sp.edit().putInt("chver_${e.key}", channelVersion(e) + 1).apply()

    /** A start time the user set from the in-game map; any start on the cycle works as the anchor. */
    fun hasCustomAnchor(e: GameEvent) = sp.contains("anchor_${e.key}")
    fun setAnchor(e: GameEvent, epochMs: Long) = sp.edit().putLong("anchor_${e.key}", epochMs).apply()
    fun resetAnchor(e: GameEvent) = sp.edit().remove("anchor_${e.key}").apply()

    /** The event with the user's correction applied, else the last online sync, else the built-in anchor. */
    fun effective(e: GameEvent): GameEvent {
        val base = override(e) ?: e.copy(anchor = sp.getLong("sync_anchor_${e.key}", e.anchor))
        return base.copy(anchor = sp.getLong("anchor_${e.key}", base.anchor))
    }

    /** season.json from GitHub (season info, plus an optional hand-edited schedule override). */
    fun remoteConfig(): String = sp.getString("remote_cfg", "") ?: ""
    fun setRemoteConfig(json: String) = sp.edit().putString("remote_cfg", json).apply()
    /** Feedback the user wrote, newest last, with send state and any reply; see [Feedback]. */
    fun feedback(): org.json.JSONArray = runCatching { org.json.JSONArray(sp.getString("feedback", "[]")) }.getOrDefault(org.json.JSONArray())
    fun setFeedback(list: org.json.JSONArray) = sp.edit().putString("feedback", list.toString()).apply()

    fun remoteSeason(): org.json.JSONObject? =
        runCatching { org.json.JSONObject(remoteConfig()).getJSONObject("season") }.getOrNull()

    /** {"boss": {"anchor": "2026-10-21T13:00:00Z", "periodMin": 210, "durationMin": 15}} in season.json "schedule". */
    private fun override(e: GameEvent): GameEvent? = runCatching {
        val o = org.json.JSONObject(remoteConfig()).getJSONObject("schedule").getJSONObject(e.key)
        val period = o.getLong("periodMin") * MIN
        val duration = o.optLong("durationMin", e.duration / MIN) * MIN
        require(period >= 10 * MIN && duration in MIN..period)
        e.copy(anchor = java.time.Instant.parse(o.getString("anchor")).toEpochMilli(), period = period, duration = duration)
    }.getOrNull()

    // Online sync
    fun setSyncAnchor(e: GameEvent, epochMs: Long) = sp.edit().putLong("sync_anchor_${e.key}", epochMs).apply()
    fun hasSyncAnchor(e: GameEvent) = sp.contains("sync_anchor_${e.key}")
    fun bossList(): String = sp.getString("boss_list", "[]") ?: "[]"
    fun setBossList(json: String) = sp.edit().putString("boss_list", json).apply()
    fun syncRaw(): String = sp.getString("sync_raw", "") ?: ""
    fun setSyncRaw(text: String) = sp.edit().putString("sync_raw", text).apply()
    fun syncOk() = sp.getBoolean("sync_ok", false)
    fun syncMessage(): String = sp.getString("sync_msg", "") ?: ""
    fun syncTime() = sp.getLong("sync_time", 0L)
    fun setSyncResult(ok: Boolean, message: String) = sp.edit()
        .putBoolean("sync_ok", ok).putString("sync_msg", message).putLong("sync_time", System.currentTimeMillis()).apply()

    /** true = keep ringing until the user stops it, false = ring once. */
    fun repeat(e: GameEvent) = sp.getBoolean("repeat_${e.key}", e.key == "boss")
    fun setRepeat(e: GameEvent, on: Boolean) = sp.edit().putBoolean("repeat_${e.key}", on).apply()

    // Quiet hours: minutes from local midnight; the window may cross midnight
    fun quietOn() = sp.getBoolean("quiet_on", false)
    fun quietStart() = sp.getInt("quiet_start", 0)
    fun quietEnd() = sp.getInt("quiet_end", 8 * 60)
    fun setQuiet(on: Boolean, start: Int, end: Int) =
        sp.edit().putBoolean("quiet_on", on).putInt("quiet_start", start).putInt("quiet_end", end).apply()

    /** Whether quiet hours apply to this event (all events by default). */
    fun quietFor(e: GameEvent) = sp.getBoolean("quiet_ev_${e.key}", true)
    fun setQuietFor(e: GameEvent, on: Boolean) = sp.edit().putBoolean("quiet_ev_${e.key}", on).apply()

    fun isQuiet(time: Long, e: GameEvent? = null): Boolean {
        if (!quietOn()) return false
        if (e != null && !quietFor(e)) return false
        val s = quietStart()
        val e = quietEnd()
        if (s == e) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (s < e) m in s until e else m >= s || m < e
    }

    /** Ring only while the app is still in the recent apps list. */
    fun onlyOpen() = sp.getBoolean("only_open", false)
    fun setOnlyOpen(on: Boolean) = sp.edit().putBoolean("only_open", on).apply()

    /** Alarm volume in percent used while this event rings; -1 keeps the phone's alarm volume. */
    fun alarmVolume(e: GameEvent) = sp.getInt("alarm_vol_${e.key}", -1)
    fun setAlarmVolume(e: GameEvent, v: Int) = sp.edit().putInt("alarm_vol_${e.key}", v).apply()

    /** A single start time the user chose to skip; alarms resume with the start after it. */
    fun skip(e: GameEvent) = sp.getLong("skip_${e.key}", 0L)
    fun setSkip(e: GameEvent, start: Long) = sp.edit().putLong("skip_${e.key}", start).apply()

    /** Name and zone of each world boss spawning at [start] (two on a double spawn), if a sync supplied them. */
    fun bossesAt(start: Long): List<Pair<String, String>> {
        val list = runCatching { org.json.JSONArray(bossList()) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { Math.abs(it.optLong("t") - start) < 5 * MIN && it.optString("name").isNotBlank() }
            .map { it.optString("name") to it.optString("zone") }
            .distinctBy { it.first.lowercase() }
    }

    companion object {
        const val SILENT = "silent"
        const val MAX_LEADS = 3
    }
}
