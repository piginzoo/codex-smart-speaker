package com.piginzoo.butler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** 调试通道：adb注入文字直接进小管家对话（绕过语音），exported=false仅shell可用 */
class DebugUtteranceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        FileLog.log("调试注入: \"$text\"")
        WakeService.instance?.let { svc ->
            if (!Butler.chatDialogueActive) {
                Butler.chatDialogueActive = true
                thread(name = "chat-dlg") {
                    val wantCodex = ChatDialogue.start(svc, text)
                    svc.loadConfigsPublic()
                    if (wantCodex) Butler.triggerSession(svc)
                }
            } else {
                ChatDialogue.onUtterance(text)
            }
        }
    }
}
