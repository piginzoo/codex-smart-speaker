package com.piginzoo.butler

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 闹钟到点：如果在live会话中则先退会话（闹钟永远优先），然后拉全屏闹钟页。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        val label = intent.getStringExtra("label") ?: "闹钟"
        FileLog.log("闹钟到点 id=$id label=$label sessionActive=${Butler.sessionActive}")
        Butler.holdScreen(context)   // 响铃期间强制亮屏（Activity的KEEP_SCREEN_ON接管前由它兜底）

        if (Butler.sessionActive) {
            Butler.sessionActive = false
            Butler.releaseScreen()
            Butler.micRestartRequested = true
            val s = ButlerAccessibilityService.instance
            if (s != null) {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Handler(Looper.getMainLooper()).postDelayed({
                    ButlerAccessibilityService.instance?.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_HOME
                    )
                }, 800)
            }
        }
        ChatDialogue.currentLabel = label
        // Activity作跳板：亮屏后由它（前台态）启动响铃服务，绕开熄屏后台启动限制
        try {
            context.startActivity(
                Intent(context, AlarmActivity::class.java)
                    .putExtra("id", id).putExtra("label", label)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { FileLog.log("闹钟页启动失败: ${e.message}") }
    }
}
