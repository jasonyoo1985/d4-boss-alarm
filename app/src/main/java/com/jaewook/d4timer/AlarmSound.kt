package com.jaewook.d4timer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.roundToInt

/**
 * Plays the alarm sound on the alarm stream, like a clock app. Samsung mutes notification
 * sounds in vibrate mode even with alarm usage, so the channel itself stays silent.
 */
class AlarmSound : Service() {
    private var player: MediaPlayer? = null
    // Phone alarm volume before we raised it, and the level we set; restored when the sound stops
    private var savedVolume = -1
    private var setVolume = -1
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
        // Two events at once: both notifications show, but a more important alarm keeps ringing
        val cur = playing
        if (player != null && cur != null && cur != key && rank(cur) < rank(key)) return START_NOT_STICKY
        playing = key
        play(e)
        return START_NOT_STICKY
    }

    private fun play(e: GameEvent) {
        player?.release()
        handler.removeCallbacksAndMessages(null)
        val uri = Alarms.soundUri(this, e) ?: run { stop(); return }
        val repeat = Prefs(this).repeat(e)
        applyVolume(e)
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

    private fun applyVolume(e: GameEvent) {
        val v = Prefs(this).alarmVolume(e)
        if (v < 0) return
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = (max * v / 100.0).roundToInt().coerceIn(1, max)
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            setVolume = target
        } catch (_: Exception) {
            // Do Not Disturb or the OEM may refuse; keep the phone's volume
        }
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        try {
            val am = getSystemService(AudioManager::class.java)
            // Leave it alone if the user changed the volume while it was ringing
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == setVolume) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            }
        } catch (_: Exception) {
        }
        savedVolume = -1
        setVolume = -1
    }

    private fun stop(): Int {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        playing = null
        restoreVolume()
        // Leave the notification in place; only the sound stops
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        playing = null
        restoreVolume()
        super.onDestroy()
    }

    companion object {
        private const val REPEAT_LIMIT_MS = 5 * MIN
        private const val ONCE_LIMIT_MS = 30_000L

        /** Event whose sound is playing now (same process as the receiver). */
        @Volatile private var playing: String? = null

        /** World boss first, then Helltide, then Legion. */
        private fun rank(key: String) = Schedule.events.indexOfFirst { it.key == key }

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

        /** Stops the sound; with a key, only when that event is the one ringing. */
        fun stop(context: Context, key: String? = null) {
            val cur = playing
            if (key != null && cur != null && cur != key) return
            context.stopService(Intent(context, AlarmSound::class.java))
        }
    }
}
