package com.jaewook.d4timer

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("d4timer", Context.MODE_PRIVATE)

    fun isOn(e: GameEvent) = sp.getBoolean("on_${e.key}", e.defaultOn)
    fun setOn(e: GameEvent, on: Boolean) = sp.edit().putBoolean("on_${e.key}", on).apply()

    fun lead(e: GameEvent) = sp.getInt("lead_${e.key}", 5)
    fun setLead(e: GameEvent, min: Int) = sp.edit().putInt("lead_${e.key}", min).apply()

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

    /** The event with the user's correction applied. */
    fun effective(e: GameEvent) = e.copy(anchor = sp.getLong("anchor_${e.key}", e.anchor))

    /** true = keep ringing until the user stops it, false = ring once. */
    fun repeat(e: GameEvent) = sp.getBoolean("repeat_${e.key}", e.key == "boss")
    fun setRepeat(e: GameEvent, on: Boolean) = sp.edit().putBoolean("repeat_${e.key}", on).apply()

    // Quiet hours: minutes from local midnight; the window may cross midnight
    fun quietOn() = sp.getBoolean("quiet_on", false)
    fun quietStart() = sp.getInt("quiet_start", 0)
    fun quietEnd() = sp.getInt("quiet_end", 8 * 60)
    fun setQuiet(on: Boolean, start: Int, end: Int) =
        sp.edit().putBoolean("quiet_on", on).putInt("quiet_start", start).putInt("quiet_end", end).apply()

    fun isQuiet(time: Long): Boolean {
        if (!quietOn()) return false
        val s = quietStart()
        val e = quietEnd()
        if (s == e) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (s < e) m in s until e else m >= s || m < e
    }

    companion object {
        const val SILENT = "silent"
    }
}
