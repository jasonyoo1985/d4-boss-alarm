package com.jaewook.d4timer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Plays the alarm sound on the alarm stream, like a clock app. Samsung mutes notification
 * sounds in vibrate mode even with alarm usage, so the channel itself stays silent.
 */
class AlarmSound : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra(Alarms.EXTRA_KEY) ?: return stop()
        val e = Schedule.byKey(key)
        val start = intent.getLongExtra(Alarms.EXTRA_START, 0L)
        val n = AlarmReceiver.build(this, e, start)
        val id = AlarmReceiver.notificationId(e)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(id, n)
            }
        } catch (_: Exception) {
            // Not allowed to run in the foreground right now; still show the alarm
            getSystemService(android.app.NotificationManager::class.java).notify(id, n)
        }
        play(e)
        return START_NOT_STICKY
    }

    private fun play(e: GameEvent) {
        player?.release()
        handler.removeCallbacksAndMessages(null)
        val uri = Alarms.soundUri(this, e) ?: run { stop(); return }
        val repeat = Prefs(this).repeat(e)
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@AlarmSound, uri)
                isLooping = repeat
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
        } catch (_: Exception) {
            stop()
            null
        }
        // Ring-until-stopped still gives up after a while on an unattended phone
        handler.postDelayed({ stop() }, if (repeat) REPEAT_LIMIT_MS else ONCE_LIMIT_MS)
    }

    private fun stop(): Int {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        // Leave the notification in place; only the sound stops
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val REPEAT_LIMIT_MS = 5 * MIN
        private const val ONCE_LIMIT_MS = 30_000L

        fun start(context: Context, e: GameEvent, start: Long) {
            val i = Intent(context, AlarmSound::class.java)
                .putExtra(Alarms.EXTRA_KEY, e.key).putExtra(Alarms.EXTRA_START, start)
            try {
                context.startForegroundService(i)
            } catch (_: Exception) {
                context.getSystemService(android.app.NotificationManager::class.java)
                    .notify(AlarmReceiver.notificationId(e), AlarmReceiver.build(context, e, start))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmSound::class.java))
        }
    }
}
