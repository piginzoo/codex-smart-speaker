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
import kotlin.math.sqrt

/**
 * 唤醒服务：前台服务，双模式语音终端，单一中文耳朵架构。
 *
 * 待机：一个中文全量ASR识别器持续识别，文字匹配两套唤醒词变体（配置文件热加载）：
 *   - "小管家"变体   → GLM语音聊天模式（ChatDialogue：聊天+设/查/取消闹钟）
 *   - "Hey Codex"变体（中文转写）→ 亮屏拉起ChatGPT live
 * 为什么不用英文语法识别器：语法模式会把一切声音强行归类到词表（"小智"→codex误触发
 * 三连），已彻底开除（踩坑#17）。英文"Codex"由中文ASR转写（嘿/科/德/克…）变体匹配。
 *
 * 聊天模式：同一识别器继续收语句 → glm-5.3-flash → TTS回话，可语音切换到ChatGPT。
 * 麦克风独占协议：ChatGPT live会话期间彻底释放AudioRecord（三星先到先得）。
 * 挂断检测：独立看守线程查音频模式回落（含10秒资历防开场抖动误判）。
 * 死亡螺旋保险丝：partial超8秒强制重置（踩坑#16：长partial解码冻结）。
 */
class WakeService : Service() {

    companion object {
        const val SAMPLE_RATE = 16000
        @Volatile var instance: WakeService? = null
    }

    private lateinit var workThread: HandlerThread
    private lateinit var handler: Handler
    private var cnModel: Model? = null
    private var cnRecognizer: Recognizer? = null
    @Volatile private var running = false
    private var lastTriggerAt = 0L
    private var lastCnLogged = ""
    private var cnPartialSince = 0L
    @Volatile private var lastBeatAt = 0L

    private var chatTriggers: List<String> = listOf("小管家")           // 唤醒GLM聊天
    private var codexVariants: List<String> = listOf("科德克斯")         // 唤醒ChatGPT
    @Volatile private var codexTriggerSeen = false
    @Volatile private var codexTriggerSeenAt = 0L

    fun isHealthy(): Boolean =
        running && (SystemClock.elapsedRealtime() - lastBeatAt) < 30_000

    /** 调试用：外部触发配置重载 */
    fun loadConfigsPublic() { loadConfigs() }

    // 挂断看守
    private var commSeen = false
    private var commStartedAt = 0L
    private var normalStreak = 0
    private var stuckCommCount = 0
    private var lastModeLog = 0L
    private var lastCfgCheck = 0L

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
        handler.post(hangupWatcher)
        ButlerWatchdogReceiver.scheduleNext(this)
        Butler.status = "双模式待机：Hey Codex→ChatGPT，小管家→GLM聊天"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
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
            try { cnRecognizer?.close() } catch (_: Exception) {}
            try { cnModel?.close() } catch (_: Exception) {}
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
            .setContentText("待机中：「Hey Codex」→ChatGPT，「小管家」→语音聊天")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun initModel() {
        val cnPath = File(getExternalFilesDir(null), "vosk-model-small-cn-0.22")
        if (!File(cnPath, "am/final.mdl").exists()) {
            Butler.status = "中文模型缺失：${cnPath.absolutePath}"
            return
        }
        try {
            cnModel = Model(cnPath.absolutePath)
            loadConfigs()
            running = true
            FileLog.log("模型加载完成：cn✓ 聊天触发词${chatTriggers.size}个 Codex变体${codexVariants.size}个")
            Butler.status = "模型加载完成，双模式待机"
            thread(name = "mic-loop") { listenLoop() }
        } catch (e: Exception) {
            FileLog.log("模型加载失败：${e.message}")
            Butler.status = "模型加载失败：${e.message}"
        }
    }

    /** 配置文件热加载（30秒一次），改词表不用重装App */
    private fun loadConfigs() {
        val base = getExternalFilesDir(null)
        chatTriggers = try {
            val f = File(base, "chat_triggers.txt")
            if (!f.exists()) f.writeText("小管家\n小管加\n小官家")
            f.readLines().map { it.trim().replace(" ", "") }.filter { it.isNotEmpty() }
        } catch (e: Exception) { listOf("小管家") }
        codexVariants = try {
            val f = File(base, "codex_variants.txt")
            if (!f.exists()) f.writeText("科德克斯\n扣德克斯\n考德克斯\n科代克斯\n嘿科德\n科迪斯\n口德克")
            f.readLines().map { it.trim().replace(" ", "") }.filter { it.isNotEmpty() }
        } catch (e: Exception) { listOf("科德克斯") }
    }

    private fun newCnRecognizer(): Recognizer? {
        val m = cnModel ?: return null
        return try { Recognizer(m, SAMPLE_RATE.toFloat()) } catch (e: Exception) { null }
    }

    private fun audioManager() = getSystemService(AudioManager::class.java)

    /** 挂断看守线程：音频模式回落=用户已挂断（10秒资历防开场抖动）；
     *  兼职：30秒热加载一次配置文件；无会话时音频模式卡死归位 */
    private val hangupWatcher = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val now = System.currentTimeMillis()
                if (now - lastCfgCheck > 30_000) {
                    lastCfgCheck = now
                    loadConfigs()   // 词表热加载
                }
                if (Butler.sessionActive) {
                    val mode = audioManager().mode
                    if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                        if (!commSeen) commStartedAt = now
                        commSeen = true
                        normalStreak = 0
                    } else if (commSeen && now - commStartedAt > 10_000) {
                        if (++normalStreak >= 10) {
                            exitSession("音频模式回落(用户已挂断)")
                            normalStreak = 0
                            commSeen = false
                        }
                    }
                    if (now - lastModeLog > 5000) {
                        lastModeLog = now
                        android.util.Log.d("ButlerWake", "watcher: mode=$mode commSeen=$commSeen")
                    }
                } else {
                    commSeen = false
                    normalStreak = 0
                    // 自愈：无会话但音频模式卡在通话模式（ChatGPT App启动残留）→录音流死寂（聋病根）
                    val m = audioManager().mode
                    if (m == AudioManager.MODE_IN_COMMUNICATION) {
                        if (++stuckCommCount >= 5) {
                            try {
                                audioManager().mode = AudioManager.MODE_NORMAL
                                FileLog.log("音频模式卡死通话态，已强制归位NORMAL")
                            } catch (e: Exception) { FileLog.log("归位失败: ${e.message}") }
                            stuckCommCount = 0
                        }
                    } else stuckCommCount = 0
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, 1000)
        }
    }

    private fun listenLoop() {
        val buf = ShortArray(3200) // 200ms
        while (running) {
            lastBeatAt = SystemClock.elapsedRealtime()
            if (Butler.micRestartRequested) {
                Butler.micRestartRequested = false
                FileLog.log("mic-loop: 会话结束，重建录音器")
                SystemClock.sleep(1000)
                continue
            }
            // ChatGPT live会话期间彻底让出麦克风
            if (Butler.sessionActive) {
                SystemClock.sleep(1500)
                continue
            }
            if (Butler.micFree) {
                SystemClock.sleep(1500)
                continue
            }
            val recorder = openRecorder()
            if (recorder == null) { SystemClock.sleep(3000); continue }
            try { recorder.startRecording() } catch (e: Exception) {
                recorder.release(); SystemClock.sleep(3000); continue
            }
            var cnRecognizer = newCnRecognizer()
            var failedReads = 0
            var utteranceMaxRms = 0.0
            FileLog.log("mic-loop: 本轮监听开始")
            while (running) {
                lastBeatAt = SystemClock.elapsedRealtime()
                if (Butler.sessionActive || Butler.micRestartRequested || Butler.micFree) break
                val n = try { recorder.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) {
                    if (++failedReads > 15) break
                    SystemClock.sleep(200); continue
                }
                failedReads = 0
                // TTS回声静默期：自己念的话+结束后800ms内不喂识别器（防回声进对话）
                if (ButlerTts.speaking || SystemClock.elapsedRealtime() < ButlerTts.muteUntilElapsed) continue

                var cnRec = cnRecognizer
                if (cnRec == null) { cnRec = newCnRecognizer(); cnRecognizer = cnRec }
                if (cnRec == null) { SystemClock.sleep(1000); continue }
                val cnFinal = try { cnRec.acceptWaveForm(buf, n) } catch (e: Exception) { false }
                val cnText = extractText(cnRec, cnFinal)
                if (cnText.isNullOrEmpty()) continue

                if (Butler.chatDialogueActive) {
                    // 对话模式：静音端点=一句话说完，交给GLM；能量门过滤环境音/回声
                    var sum = 0.0
                    for (s in buf) sum += s.toDouble() * s
                    val rms = sqrt(sum / buf.size)
                    if (rms > 250) utteranceMaxRms = maxOf(utteranceMaxRms, rms)
                    if (cnFinal && !cnText.isNullOrBlank()) {
                        if (utteranceMaxRms > 250) ChatDialogue.onUtterance(cnText)
                        else FileLog.log("低能量utterance丢弃: \"$cnText\"")
                        utteranceMaxRms = 0.0
                    }
                    continue
                }

                // 死亡螺旋保险丝：partial挂起超8秒强制重置（长语音解码冻结，踩坑#16）
                if (cnPartialSince == 0L) cnPartialSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - cnPartialSince > 8000) {
                    try { cnRec.reset() } catch (_: Exception) {}
                    cnPartialSince = 0
                    FileLog.log("cn partial超8秒，强制重置")
                    continue
                }

                // 诊断日志：待机时记录中文识别真实输出（变体表按实测调）
                if (cnText != lastCnLogged) {
                    lastCnLogged = cnText
                    FileLog.log("cn识别: $cnText")
                }

                val clean = cnText.replace(" ", "")
                if (matchList(clean, chatTriggers)) {
                    cnPartialSince = 0
                    Butler.chatDialogueActive = true
                    FileLog.log("触发小管家: \"$cnText\"")
                    thread(name = "chat-dlg") {
                        val wantCodex = ChatDialogue.start(this@WakeService, cnText)
                        loadConfigs()
                        if (wantCodex) {
                            lastTriggerAt = System.currentTimeMillis()
                            commSeen = false; normalStreak = 0
                            Butler.triggerSession(this@WakeService)
                        }
                    }
                    continue
                }
                if (matchList(clean, codexVariants) && !codexTriggerSeen) {
                    codexTriggerSeen = true
                    codexTriggerSeenAt = System.currentTimeMillis()
                }
                if (cnFinal && codexTriggerSeen) {
                    codexTriggerSeen = false
                    maybeTrigger()
                }
            }
            // 收拾本轮：彻底释放
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            try { cnRecognizer?.close() } catch (_: Exception) {}
            cnRecognizer = null
            cnPartialSince = 0
            if (running) SystemClock.sleep(800)
        }
    }

    /** 匹配：文本含任一变体（去空格比对） */
    private fun matchList(text: String, list: List<String>): Boolean =
        list.any { text.contains(it.replace(" ", "")) }

    private fun openRecorder(): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
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

    private fun maybeTrigger() {
        android.util.Log.d("ButlerWake", "maybeTrigger: sessionActive=${Butler.sessionActive} pending=${Butler.pendingEnterVoice}")
        FileLog.log("maybeTrigger: session=${Butler.sessionActive} pending=${Butler.pendingEnterVoice}")
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 10_000) return
        lastTriggerAt = now
        commSeen = false
        normalStreak = 0
        Butler.status = "听到Hey Codex！"
        Butler.triggerSession(this)
    }

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

    fun testTrigger() {
        lastTriggerAt = System.currentTimeMillis()
        commSeen = false
        normalStreak = 0
        Butler.triggerSession(this)
    }
}
