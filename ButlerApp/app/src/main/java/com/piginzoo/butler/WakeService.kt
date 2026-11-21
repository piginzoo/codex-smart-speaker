package com.piginzoo.butler

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.concurrent.thread

/**
 * 唤醒服务：前台服务24小时离线监听"Codex"。
 * 听到 → 亮屏 → 拉起ChatGPT → 通知辅助功能进语音模式。
 *
 * 关键设计：语音会话期间彻底释放麦克风（ChatGPT的WebRTC需要独占，
 * 三星机器上先到先得，抢麦会导致live连接饿死）。
 * 挂断检测由独立的看守线程负责：音频模式从"通话模式"回落=用户已挂断。
 * 会话结束后重建录音器恢复监听。
 */
class WakeService : Service() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val WAKE_WORD = "codex"
        @Volatile var instance: WakeService? = null
    }

    private lateinit var workThread: HandlerThread
    private lateinit var handler: Handler
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    @Volatile private var running = false
    private var lastTriggerAt = 0L
    private var lastLoggedText = ""

    // 挂断看守：通话模式见过后回落到正常=会话结束
    private var commSeen = false
    private var normalStreak = 0
    private var lastModeLog = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startAsForeground()
        workThread = HandlerThread("wake-engine").also { it.start() }
        handler = Handler(workThread.looper)
        handler.post { initModel() }
        handler.post(hangupWatcher)   // 独立看守线程：不占麦克风
        Butler.status = "唤醒服务运行中"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        Butler.releaseScreen()
        handler.post {
            try { recognizer?.close() } catch (_: Exception) {}
            try { model?.close() } catch (_: Exception) {}
        }
        workThread.quitSafely()
        instance = null
        Butler.status = "唤醒服务已停止"
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("butler", "语音管家", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
            }
        )
        val n = Notification.Builder(this, "butler")
            .setContentTitle("语音管家")
            .setContentText("正在监听唤醒词「Codex」")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    /** 加载Vosk模型，路径：/sdcard/Android/data/com.piginzoo.butler/files/vosk-model-small-en-us-0.15 */
    private fun initModel() {
        val modelPath = File(getExternalFilesDir(null), "vosk-model-small-en-us-0.15")
        if (!File(modelPath, "am/final.mdl").exists() && !File(modelPath, "final.mdl").exists()) {
            Butler.status = "模型缺失：请先用adb推送模型到 ${modelPath.absolutePath}"
            return
        }
        try {
            model = Model(modelPath.absolutePath)
            running = true
            Butler.status = "模型加载完成，开始监听"
            thread(name = "mic-loop") { listenLoop() }
        } catch (e: Exception) {
            Butler.status = "模型加载失败：${e.message}"
        }
    }

    private fun newRecognizer(): Recognizer? {
        val m = model ?: return null
        return try { Recognizer(m, SAMPLE_RATE.toFloat()) } catch (e: Exception) { null }
    }

    private fun audioManager() = getSystemService(AudioManager::class.java)

    /**
     * 挂断看守线程：会话期间每秒查一次系统音频模式。
     * ChatGPT语音会话开着时mode=MODE_IN_COMMUNICATION；用户挂断后回落MODE_NORMAL。
     * 连续5次回落即判定会话结束。此线程完全不碰麦克风。
     */
    private val hangupWatcher = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                if (Butler.sessionActive) {
                    val mode = audioManager().mode
                    if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                        commSeen = true
                        normalStreak = 0
                    } else if (commSeen) {
                        if (++normalStreak >= 5) {
                            exitSession("音频模式回落(用户已挂断)")
                            normalStreak = 0
                            commSeen = false
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastModeLog > 5000) {
                        lastModeLog = now
                        android.util.Log.d("ButlerWake", "watcher: mode=$mode commSeen=$commSeen")
                    }
                } else {
                    commSeen = false
                    normalStreak = 0
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, 1000)
        }
    }

    private fun listenLoop() {
        val buf = ShortArray(3200) // 200ms
        while (running) {
            // 会话刚结束：歇1秒换新录音器，避免音频路由残留
            if (Butler.micRestartRequested) {
                Butler.micRestartRequested = false
                SystemClock.sleep(1000)
                continue
            }
            // 语音会话期间彻底让出麦克风（对象级释放，让ChatGPT的WebRTC独占）
            if (Butler.sessionActive) {
                SystemClock.sleep(1500)
                continue
            }
            val recorder = openRecorder()
            if (recorder == null) { SystemClock.sleep(3000); continue }
            try { recorder.startRecording() } catch (e: Exception) {
                recorder.release(); SystemClock.sleep(3000); continue
            }
            var recognizer = newRecognizer()
            var failedReads = 0
            // 本轮监听（会话一旦开始立即退出本轮）
            while (running) {
                if (Butler.sessionActive || Butler.micRestartRequested) break
                val n = try { recorder.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) {
                    if (++failedReads > 15) break
                    SystemClock.sleep(200); continue
                }
                failedReads = 0
                var rec = recognizer
                if (rec == null) { rec = newRecognizer(); recognizer = rec }
                if (rec == null) { SystemClock.sleep(1000); continue }
                val hasFinal = try { rec.acceptWaveForm(buf, n) } catch (e: Exception) { false }
                val text = extractText(rec, hasFinal)
                if (!text.isNullOrEmpty() && text != lastLoggedText) {
                    lastLoggedText = text
                    android.util.Log.d("ButlerWake", "识别: $text")
                }
                if (text != null && containsWakeWord(text)) {
                    maybeTrigger()
                }
            }
            // 收拾本轮：彻底释放
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            try { recognizer?.close() } catch (_: Exception) {}
            recognizer = null
            if (running) SystemClock.sleep(800)
        }
    }

    private fun openRecorder(): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, 32000)
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (e: Exception) { null }

    private fun extractText(rec: Recognizer, hasFinal: Boolean): String? = try {
        val json = if (hasFinal) rec.result else rec.partialResult
        JSONObject(json).optString(if (hasFinal) "text" else "partial")
    } catch (e: Exception) { null }

    /** 唤醒词匹配：按非字母切词，匹配codex及其常见误听变体（Vosk小模型对合成音不稳） */
    private val wakeVariants = setOf("codex", "kodak", "kodaks", "codecs", "codaks", "codexs")

    private fun containsWakeWord(text: String): Boolean {
        if (Butler.sessionActive || Butler.pendingEnterVoice) return false
        val words = text.lowercase().split(Regex("[^a-z]+"))
        return words.any { it in wakeVariants }
    }

    private fun maybeTrigger() {
        android.util.Log.d("ButlerWake", "maybeTrigger: sessionActive=${Butler.sessionActive} pending=${Butler.pendingEnterVoice}")
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 10_000) return  // 防抖10秒
        lastTriggerAt = now
        commSeen = false
        normalStreak = 0
        Butler.status = "听到唤醒词！"
        Butler.triggerSession(this)
    }

    /**
     * 结束会话：退出live模式+回桌面+复位状态+申请重建录音器。
     * 退出的全局动作由辅助功能服务执行（不依赖界面事件）。
     */
    private fun exitSession(reason: String) {
        if (!Butler.sessionActive) return
        Butler.sessionActive = false
        Butler.releaseScreen()
        Butler.status = "会话结束：$reason"
        android.util.Log.d("ButlerWake", "exitSession: $reason")
        Butler.micRestartRequested = true
        val s = ButlerAccessibilityService.instance
        if (s != null) {
            // BACK退出live模式 → 1.2秒后HOME回桌面
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Handler(Looper.getMainLooper()).postDelayed({
                if (!Butler.sessionActive) {
                    ButlerAccessibilityService.instance?.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_HOME
                    )
                }
            }, 1200)
        } else {
            Butler.goHome(this)
        }
    }

    /** MainActivity"测试唤醒流程"按钮调用：跳过听词直接走全流程 */
    fun testTrigger() {
        lastTriggerAt = System.currentTimeMillis()
        commSeen = false
        normalStreak = 0
        Butler.triggerSession(this)
    }
}
