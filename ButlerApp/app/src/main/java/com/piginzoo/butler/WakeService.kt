package com.piginzoo.butler

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * 唤醒服务：前台服务24小时离线监听唤醒词（小管家 / Codex / GPT）。
 * 听到 → 亮屏 → 拉起ChatGPT → 通知辅助功能进语音模式。
 *
 * 识别方案（方案B）：只用一个中文模型做全量转写（听到什么写什么），
 * 再用变体表匹配唤醒词。不用英文语法模式——语法模式是"强制选择题"，
 * 会把环境音硬凑成唤醒词导致误触发，已彻底删除。
 *
 * 变体表：优先读外部文件 wake_variants.txt（adb可推可改，无需重装），
 * 文件缺失/为空时用内置默认表。每15秒热加载一次。
 *
 * 关键设计：语音会话期间彻底释放麦克风（ChatGPT的WebRTC需要独占，
 * 三星机器上先到先得，抢麦会导致live连接饿死）。
 * 挂断检测由独立的看守线程负责：音频模式从"通话模式"回落=用户已挂断。
 * 会话结束后重建录音器恢复监听。
 */
class WakeService : Service() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val VARIANTS_FILE = "wake_variants.txt"

        /** 内置默认变体表：文件缺失或为空时的兜底（真人声实测后可持续往文件里加） */
        val DEFAULT_VARIANTS = listOf(
            // 小管家（实测转写：小管家/小管加/小户人家）
            "小管家", "小管加", "小馆家", "小管嫁", "小官家", "晓管家", "小管佳", "小户人家",
            // Codex（英文词，中文模型按音转写）
            "口德克斯", "科德克斯", "扣德克斯", "寇德克斯", "克达克斯", "科达克斯",
            "扣达克斯", "口代克斯", "咳德克斯", "黑德克斯", "codex",
            // GPT（字母读音，中文模型按音转写）
            "几皮提", "吉皮提", "记皮提", "集皮提", "激皮提", "jipiti", "gpt", "chatgpt"
        )

        @Volatile var instance: WakeService? = null
    }

    private lateinit var workThread: HandlerThread
    private lateinit var handler: Handler
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    @Volatile private var running = false
    private var lastTriggerAt = 0L
    private var lastLoggedText = ""
    @Volatile private var lastBeatAt = 0L   // 心跳：mic循环每次迭代刷新，看门狗据此判断假死

    /** 生效中的变体表（归一化后的小写无空格形式） */
    @Volatile private var variants: List<String> = normalizeAll(DEFAULT_VARIANTS)

    fun isHealthy(): Boolean =
        running && (SystemClock.elapsedRealtime() - lastBeatAt) < 30_000

    // 挂断看守：通话模式见过后回落到正常=会话结束
    private var commSeen = false
    private var normalStreak = 0
    private var lastModeLog = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lastBeatAt = SystemClock.elapsedRealtime()
        FileLog.init(getExternalFilesDir(null))
        FileLog.log("WakeService onCreate pid=${android.os.Process.myPid()}")
        startAsForeground()
        workThread = HandlerThread("wake-engine").also { it.start() }
        handler = Handler(workThread.looper)
        handler.post { initModel() }
        handler.post(hangupWatcher)      // 独立看守线程：不占麦克风
        handler.post(variantReloader)    // 变体表热加载
        ButlerWatchdogReceiver.scheduleNext(this)  // 看门狗巡视启动
        Butler.status = "唤醒服务运行中"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉App：1秒后自动拉活服务
        FileLog.log("onTaskRemoved：安排1秒后重启")
        try {
            val am = getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                this, 1002,
                Intent(this, BootReceiver::class.java).setAction(BootReceiver.ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000, pi
            )
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        Butler.releaseScreen()
        FileLog.log("WakeService onDestroy")
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
            .setContentText("正在监听：小管家 / Codex / GPT")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    /** 加载Vosk中文模型，路径：/sdcard/Android/data/com.piginzoo.butler/files/vosk-model-small-cn-0.22 */
    private fun initModel() {
        loadVariants()
        val modelPath = File(getExternalFilesDir(null), "vosk-model-small-cn-0.22")
        if (!File(modelPath, "am/final.mdl").exists() && !File(modelPath, "final.mdl").exists()) {
            Butler.status = "模型缺失：请先用adb推送模型到 ${modelPath.absolutePath}"
            return
        }
        try {
            model = Model(modelPath.absolutePath)
            running = true
            FileLog.log("模型加载完成：cn全量转写 + 变体表${variants.size}个")
            Butler.status = "监听中（小管家 / Codex / GPT）"
            thread(name = "mic-loop") { listenLoop() }
        } catch (e: Exception) {
            FileLog.log("模型加载失败：${e.message}")
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

    /** 变体表热加载：每15秒读一次外部文件，改表无需重装App */
    private val variantReloader = object : Runnable {
        override fun run() {
            loadVariants()
            handler.postDelayed(this, 15_000)
        }
    }

    private fun normalizeAll(list: List<String>): List<String> =
        list.map { normalize(it) }.filter { it.length >= 2 }.distinct()

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("\\s+"), "")

    private fun loadVariants() {
        try {
            val f = File(getExternalFilesDir(null), VARIANTS_FILE)
            val lines = if (f.exists()) f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            else emptyList()
            val merged = if (lines.isEmpty()) normalizeAll(DEFAULT_VARIANTS) else normalizeAll(lines)
            if (merged != variants) {
                variants = merged
                FileLog.log("变体表更新：${variants.size}个 ${variants.take(12)}")
            }
        } catch (e: Exception) {
            FileLog.log("变体表加载失败：${e.message}")
        }
    }

    private fun listenLoop() {
        val buf = ShortArray(3200) // 200ms
        while (running) {
            lastBeatAt = SystemClock.elapsedRealtime()
            // 会话刚结束：歇1秒换新录音器，避免音频路由残留
            if (Butler.micRestartRequested) {
                Butler.micRestartRequested = false
                FileLog.log("mic-loop: 会话结束，重建录音器")
                SystemClock.sleep(1000)
                continue
            }
            // 语音会话期间彻底让出麦克风（ChatGPT的WebRTC需要独占，三星机器上先到先得，抢麦会导致live连接饿死）
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
            FileLog.log("mic-loop: 本轮监听开始")
            // 本轮监听（会话一旦开始立即退出本轮）
            while (running) {
                lastBeatAt = SystemClock.elapsedRealtime()
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
                // partial超长保险丝：环境音持续说话时partial会一直涨，太长就重置
                if (!hasFinal && text != null && text.length > 30) {
                    FileLog.log("partial超长(${text.length}字)，重置识别器")
                    try { rec.reset() } catch (_: Exception) {}
                }
                if (!text.isNullOrEmpty() && text != lastLoggedText) {
                    lastLoggedText = text
                    android.util.Log.d("ButlerWake", "识别: $text")
                    FileLog.log("cn识别: $text")
                }
                if (!text.isNullOrEmpty() && containsWakeWord(text)) {
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
        // VOICE_RECOGNITION源：自带自动增益(AGC)+降噪，远场唤醒专用；MIC是原始裸麦，远/小声基本听不见
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, 32000)
        ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    } catch (e: Exception) { null }

    private fun extractText(rec: Recognizer, hasFinal: Boolean): String? = try {
        val json = if (hasFinal) rec.result else rec.partialResult
        JSONObject(json).optString(if (hasFinal) "text" else "partial")
    } catch (e: Exception) { null }

    /** 唤醒词匹配：全量转写命中变体表任一条即触发（对partial和final都生效，响应快） */
    private fun containsWakeWord(text: String): Boolean {
        if (Butler.sessionActive || Butler.pendingEnterVoice) return false
        val t = normalize(text)
        if (t.isEmpty()) return false
        return variants.any { t.contains(it) }
    }

    private fun maybeTrigger() {
        android.util.Log.d("ButlerWake", "maybeTrigger: sessionActive=${Butler.sessionActive} pending=${Butler.pendingEnterVoice}")
        FileLog.log("maybeTrigger: session=${Butler.sessionActive} pending=${Butler.pendingEnterVoice}")
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
        FileLog.log("exitSession: $reason")
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
