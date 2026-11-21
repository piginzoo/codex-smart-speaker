package com.piginzoo.butler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 闹钟响铃前台服务：铃声+TTS+亮屏锁全在这里（不依赖Activity存活）。
 * AlarmActivity只是显示层；用户点"知道了"→stopAlarm；90秒无人管自动停。
 */
class AlarmRingService : Service() {

    companion object {
        const val ACTION_STOP = "com.piginzoo.butler.ALARM_STOP"
        @Volatile var instance: AlarmRingService? = null
    }

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("alarm", "闹钟响铃", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
            }
        )
        val stopPi = PendingIntent.getService(
            this, 2001, Intent(this, AlarmRingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "alarm")
            .setContentTitle("闹钟")
            .setContentText(ChatDialogue.currentLabel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .addAction(0, "停止", stopPi)
            .build()
        startForeground(2, n)
        playRing()
        Butler.holdScreen(this)   // 响铃期间强制亮屏
        // 90秒没人管自动停（闹钟记录保留在清单）
        handler.postDelayed({ stopAlarm() }, 90_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopAlarm()
        return START_NOT_STICKY
    }

    private fun playRing() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmRingService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            FileLog.log("响铃播放失败: ${e.message}")
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts!!.language = Locale.SIMPLIFIED_CHINESE
                tts!!.speak("闹钟时间到，${ChatDialogue.currentLabel}", TextToSpeech.QUEUE_FLUSH, null, "ring")
            }
        }
    }

    private fun stopAlarm() {
        instance?.stopSelf()
    }

    override fun onDestroy() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        Butler.releaseScreen()
        instance = null
        super.onDestroy()
    }
}
