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

    companion object {
        const val SILENT = "silent"
    }
}
