package com.piginzoo.butler

import android.app.Activity
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏闹钟页：亮屏+大字内容+循环铃声(ALARM流，通话中也压得出声)+TTS播报，
 * 两个按钮：知道了 / 再睡5分钟。
 */
class AlarmActivity : Activity() {

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var id = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra("id", -1L)
        val label = intent.getStringExtra("label") ?: "闹钟"
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        FileLog.log("闹钟页打开 id=$id label=$label")

        val now = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF101A3D.toInt())
            setPadding(48, 48, 48, 48)
        }
        val timeView = TextView(this).apply {
            text = now
            textSize = 64f
            setTextColor(0xFF9DB1FF.toInt())
            gravity = Gravity.CENTER
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 40f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
        }
        val btnOk = Button(this).apply { text = "知道了" }
        val btnSnooze = Button(this).apply { text = "再睡5分钟" }
        row.addView(btnOk, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { margins(12) })
        row.addView(btnSnooze, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { margins(12) })
        root.addView(timeView)
        root.addView(labelView)
        root.addView(row)
        setContentView(root)

        btnOk.setOnClickListener {
            stopService(Intent(this, AlarmRingService::class.java))
            Butler.releaseScreen()
            finish()
        }
        btnSnooze.setOnClickListener {
            if (id > 0) AlarmStore.snooze(this, id, 5)
            stopService(Intent(this, AlarmRingService::class.java))
            Butler.releaseScreen()
            finish()
        }

        // 前台态启动响铃服务（铃声+TTS+亮屏锁都在服务里，页面死了铃声照响）
        try { startForegroundService(Intent(this, AlarmRingService::class.java)) } catch (e: Exception) {
            FileLog.log("响铃服务启动失败: ${e.message}")
        }

        // 循环铃声走ALARM流
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
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
            FileLog.log("铃声播放失败: ${e.message}")
        }

        // TTS播报内容
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts!!.language = Locale.SIMPLIFIED_CHINESE
                tts!!.speak("闹钟时间到，$label", TextToSpeech.QUEUE_FLUSH, null, "alarm")
            }
        }

        // 5分钟没人管自动停（防一直响），闹钟保留可再响？简化：自动停止并移除
        Handler().postDelayed({
            if (!isFinishing) finish()
        }, 5 * 60 * 1000L)
    }

    private fun LinearLayout.LayoutParams.margins(m: Int) {
        setMargins(m, 0, m, 0)
    }

    override fun onDestroy() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        // 单次闹钟响完即从清单移除
        if (id > 0) AlarmStore.remove(this, id)
        super.onDestroy()
    }
}
