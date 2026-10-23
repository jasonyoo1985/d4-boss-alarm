package com.jaewook.d4timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

/** A short taste of an event's alarm sound at a chosen volume, so the slider can be judged by ear. */
object VolumePreview {
    private const val LENGTH_MS = 2_000L
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var setVolume = -1
    private val handler = Handler(Looper.getMainLooper())

    fun play(context: Context, e: GameEvent, percent: Int) {
        stop(context)
        val uri = Alarms.soundUri(context, e) ?: return
        try {
            val am = context.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            setVolume = (max * percent.coerceIn(10, 100) / 100.0).roundToInt().coerceIn(1, max)
            am.setStreamVolume(AudioManager.STREAM_ALARM, setVolume, 0)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                setOnCompletionListener { stop(context) }
                prepare()
                start()
            }
            handler.postDelayed({ stop(context) }, LENGTH_MS)
        } catch (_: Exception) {
            // No preview is better than an error; the real alarm is unaffected
            stop(context)
        }
    }

    fun stop(context: Context) {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (savedVolume >= 0) {
            try {
                val am = context.getSystemService(AudioManager::class.java)
                if (am.getStreamVolume(AudioManager.STREAM_ALARM) == setVolume) {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
                }
            } catch (_: Exception) {
            }
        }
        savedVolume = -1
        setVolume = -1
    }
}
