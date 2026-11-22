package com.piginzoo.butler

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * 全局共享状态：唤醒服务与辅助功能服务之间的桥梁
 */
object Butler {
    const val CHATGPT_PKG = "com.openai.chatgpt"

    /** ChatGPT语音会话进行中（从触发唤醒到挂断回桌面） */
    @Volatile var sessionActive = false

    /** 唤醒后等待辅助功能点击语音模式按钮 */
    @Volatile var pendingEnterVoice = false

    /** 语音会话里最近一次界面活动时间 */
    @Volatile var lastUiActivity = 0L

    /** 唤醒服务申请重建录音器（会话结束/挂断后） */
    @Volatile var micRestartRequested = false

    /** 界面状态文案，MainActivity展示用 */
    @Volatile var status: String = "未启动"
        set(value) { field = value; listener?.invoke(value) }
    var listener: ((String) -> Unit)? = null

    /** 辅助功能服务就绪标志 */
    @Volatile var a11yReady = false

    /** 会话期间的屏幕常亮锁（挂断即释放，60分钟保险丝防泄漏） */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    fun holdScreen(context: Context) {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "butler:session"
        ).apply { acquire(60 * 60 * 1000L) }
    }

    fun releaseScreen() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    fun triggerSession(context: Context) {
        if (sessionActive || pendingEnterVoice) return
        sessionActive = true
        pendingEnterVoice = true
        lastUiActivity = System.currentTimeMillis()
        status = "已唤醒，正在拉起ChatGPT…"
        holdScreen(context)   // 会话期间屏幕常亮
        // AI说话音量拉满：live语音走媒体/通话两条流，都调到最大
        try {
            val am = context.getSystemService(android.media.AudioManager::class.java)
            am.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC), 0
            )
            am.setStreamVolume(
                android.media.AudioManager.STREAM_VOICE_CALL,
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL), 0
            )
        } catch (_: Exception) {}
        // 1. 点亮屏幕（锁屏已禁用，turnScreenOn即可）
        context.startActivity(
            Intent(context, ScreenWakeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // 2. 拉起ChatGPT（getLaunchIntent失败则用显式component兜底）
        val launch = context.packageManager.getLaunchIntentForPackage(CHATGPT_PKG)
        android.util.Log.d("ButlerWake", "launch intent: $launch")
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launch)
        } else {
            android.util.Log.d("ButlerWake", "launch intent为null，改用显式component")
            try {
                context.startActivity(
                    Intent().setClassName(CHATGPT_PKG, "$CHATGPT_PKG.MainActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                android.util.Log.d("ButlerWake", "显式启动也失败: ${e.message}")
            }
        }
        // 3. 兜底：20秒内辅助功能没完成进入语音模式，就放弃本次会话
        Handler(Looper.getMainLooper()).postDelayed({
            if (pendingEnterVoice) {
                pendingEnterVoice = false
                sessionActive = false
                releaseScreen()
                status = "进入语音模式失败，已放弃本次唤醒"
                goHome(context)
            }
        }, 20_000)
    }

    fun goHome(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 辅助功能服务是否已在系统里启用 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val svc = "${context.packageName}/.ButlerAccessibilityService"
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(svc, true) || it.endsWith("ButlerAccessibilityService") }
    }
}
