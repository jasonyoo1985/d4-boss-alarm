package com.jaewook.d4timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are cleared on reboot and app update, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.scheduleAll(context)
    }
}
