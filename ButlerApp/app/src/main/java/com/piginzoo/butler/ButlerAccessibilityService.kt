package com.piginzoo.butler

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 辅助功能服务：对ChatGPT做最小量UI自动化。
 * 1) 唤醒后自动点蓝色语音按钮进入语音模式，并强制外放+拉高音量
 * 2) 10分钟无界面活动 → 自动挂断退出（超时兜底）
 * 3) 检测到用户自己挂断了（语音界面消失）→ 回桌面、复位状态、恢复唤醒监听
 * 挂断本身由用户自理（语音挂断或手点叉子），不做字幕告别语识别。
 */
class ButlerAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ButlerAccessibilityService? = null
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var enteredVoice = false
    private var enterAttempts = 0
    private var lastSpeakerEnforce = 0L
    private var voiceUiAbsentCount = 0
    private var sessionEnteredAt = 0L
    private val timeoutMs = 10 * 60 * 1000L
    private val hardCapMs = 60 * 60 * 1000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Butler.a11yReady = true
        Butler.status = "辅助功能就绪"
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Butler.a11yReady = false
        instance = null
        return super.onUnbind(intent)
    }

    private var lastEventLog = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val now = System.currentTimeMillis()
        if (now - lastEventLog > 4000) {  // 节流诊断
            lastEventLog = now
            android.util.Log.d("ButlerWake", "a11y收到事件: pkg=${e.packageName} session=${Butler.sessionActive} pending=${Butler.pendingEnterVoice} entered=$enteredVoice")
        }
        if (e.packageName != Butler.CHATGPT_PKG) return
        if (!Butler.sessionActive) return
        Butler.lastUiActivity = now

        val root = rootInActiveWindow ?: return

        if (Butler.pendingEnterVoice) {
            tryEnterVoice(root)
            return
        }
        if (enteredVoice) {
            enforceSpeaker()          // 周期性确保外放
            checkTimeout(root)        // 10分钟无活动 + 25分钟硬上限兜底
            // 会话结束检测交给WakeService的"麦克风归还"信号，UI判据不可靠（Compose无文字）
        }
    }

    override fun onInterrupt() {}

    // ---------- 进入语音模式 ----------

    private fun tryEnterVoice(root: AccessibilityNodeInfo) {
        // 统计可点击节点，页面没加载完（树为空）就继续等，不计入尝试次数
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { n -> if (n.isClickable) clickables.add(n) }
        if (clickables.isEmpty()) return  // 页面还在加载，等下一次事件

        // 第一次见到非空树：记录结构（不点击）
        if (enterAttempts == 0) {
            val sb = StringBuilder("可点击节点(${clickables.size}): ")
            for (n in clickables) {
                val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                sb.append("[d=").append(n.contentDescription)
                    .append(",t=").append(n.text)
                    .append(",id=").append(n.viewIdResourceName)
                    .append(",b=").append(r).append("] ")
            }
            android.util.Log.d("ButlerWake", sb.toString())
        }
        enterAttempts++

        // ChatGPT启动可能弹更新/新功能弹窗，先点掉（仅进入初期，防止误点）
        if (enterAttempts <= 2) {
            val dismissHints = listOf("以后再说", "稍后", "取消", "跳过", "not now", "maybe later", "dismiss", "got it", "no thanks")
            val d = findClickableByDesc(root, dismissHints)
            if (d != null) {
                android.util.Log.d("ButlerWake", "关闭启动弹窗: d=${d.contentDescription} t=${d.text}")
                d.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }

        val btn = findVoiceButton(root)
        if (btn != null) {
            val r = android.graphics.Rect(); btn.getBoundsInScreen(r)
            android.util.Log.d("ButlerWake", "点击语音按钮: d=${btn.contentDescription} t=${btn.text} id=${btn.viewIdResourceName} b=$r")
            val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            android.util.Log.d("ButlerWake", "点击结果: ok=$ok")
            if (ok) {
                Butler.pendingEnterVoice = false
                enteredVoice = true
                voiceUiAbsentCount = 0
                sessionEnteredAt = System.currentTimeMillis()
                Butler.lastUiActivity = System.currentTimeMillis()
                Butler.status = "已进入语音模式（外放已强制）"
                enforceSpeaker()
                // 3秒后再补一次：音频模式切换可能滞后
                uiHandler.postDelayed({ enforceSpeaker() }, 3000)
                return
            }
        }
        if (++enterAttempts == 3) {
            // 诊断：把当前窗口所有可点击节点打出来，便于调位置/desc匹配
            val sb = StringBuilder("可点击节点: ")
            walk(root) { n ->
                if (!n.isClickable) return@walk
                val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                sb.append("[d=").append(n.contentDescription)
                    .append(",t=").append(n.text)
                    .append(",id=").append(n.viewIdResourceName)
                    .append(",b=").append(r).append("] ")
            }
            android.util.Log.d("ButlerWake", sb.toString())
        }
        if (++enterAttempts > 10) {
            enterAttempts = 0
            Butler.pendingEnterVoice = false
            Butler.sessionActive = false
            Butler.status = "没找到语音按钮，本次放弃"
            Butler.goHome(this)
        }
    }

    /**
     * 强制外放：打开扬声器路由 + 音量拉到80%。
     * ChatGPT live用的是通信音频模式，speakerphone开关对它生效。
     */
    private fun enforceSpeaker() {
        val now = System.currentTimeMillis()
        if (now - lastSpeakerEnforce < 5000) return  // 节流
        lastSpeakerEnforce = now
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
            val maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (maxCall > 0) am.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                (maxCall * 0.8).toInt().coerceAtLeast(1), 0
            )
            val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxMusic > 0) am.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (maxMusic * 0.8).toInt().coerceAtLeast(1), 0
            )
        } catch (_: Exception) {}
    }

    // ---------- 超时兜底 ----------

    private fun checkTimeout(root: AccessibilityNodeInfo) {
        // 60分钟硬上限：防所有结束信号失效导致卡死（正常结束靠音频模式/麦克风信号，无声超时靠音量检测）
        val now = System.currentTimeMillis()
        if (sessionEnteredAt > 0 && now - sessionEnteredAt > hardCapMs) {
            Butler.status = "60分钟硬上限，强制退出"
            endSession(root)
        }
    }

    // ---------- 超时兜底时结束会话 ----------

    private fun endSession(root: AccessibilityNodeInfo?) {
        // 语音界面的挂断按钮无文字标识，按BACK退出语音模式（可能弹确认框，按两次）
        performGlobalAction(GLOBAL_ACTION_BACK)
        uiHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800)
        uiHandler.postDelayed({ finishSession() }, 1800)
    }

    /** 会话收尾：回桌面、复位状态、申请重建录音器 */
    private fun finishSession() {
        if (!Butler.sessionActive && !enteredVoice) return
        enteredVoice = false
        Butler.sessionActive = false
        Butler.releaseScreen()
        Butler.micRestartRequested = true
        performGlobalAction(GLOBAL_ACTION_HOME)
        Butler.status = "会话结束，回桌面恢复监听"
    }

    // ---------- 节点查找工具 ----------

    private fun walk(node: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        visit(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), visit)
        }
    }

    private fun findClickableByDesc(
        root: AccessibilityNodeInfo,
        hints: List<String>
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (found != null || !n.isClickable) return@walk
            val desc = n.contentDescription?.toString() ?: ""
            val text = n.text?.toString() ?: ""
            val hay = (desc + " " + text).lowercase()
            if (hints.any { hay.contains(it.lowercase()) }) found = n
        }
        return found
    }

    /**
     * 找语音模式按钮：
     * 1) desc/text含"语音模式/voice"的可点击节点
     * 2) resource-id含voice的可点击节点
     * 3) 兜底：下半屏右半区最大的可点击节点（蓝色大按钮位置）
     */
    private fun findVoiceButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val descHints = listOf("语音模式", "语音对话", "实时语音", "voice mode", "voice chat", "start voice")
        findClickableByDesc(root, descHints)?.let { return it }
        findVoiceById(root)?.let { return it }

        var best: AccessibilityNodeInfo? = null
        var bestCx = 0f
        walk(root) { n ->
            if (!n.isClickable) return@walk
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            // 蓝色语音按钮是约144x144的方块：右下区域 + 尺寸合理（过滤滚动条/输入框），取最靠右
            val w = r.width(); val h = r.height()
            if (cy > screenH * 0.3f && cx > screenW * 0.5f
                && w in 100..300 && h in 100..300
                && r.top > 0 && r.bottom <= screenH) {
                if (cx > bestCx) { bestCx = cx; best = n }
            }
        }
        return best
    }

    private fun findVoiceById(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (found != null || !n.isClickable) return@walk
            val vid = n.viewIdResourceName ?: ""
            if (vid.lowercase().contains("voice")) found = n
        }
        return found
    }
}
