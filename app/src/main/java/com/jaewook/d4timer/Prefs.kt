package com.jaewook.d4timer

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("d4timer", Context.MODE_PRIVATE)

    fun isOn(e: GameEvent) = sp.getBoolean("on_${e.key}", e.defaultOn)
    fun setOn(e: GameEvent, on: Boolean) = sp.edit().putBoolean("on_${e.key}", on).apply()

    fun lead(e: GameEvent) = sp.getInt("lead_${e.key}", 5)
    fun setLead(e: GameEvent, min: Int) = sp.edit().putInt("lead_${e.key}", min).apply()

    companion object {
        val LEAD_CHOICES = intArrayOf(0, 1, 3, 5, 10, 15)
    }
}
