package com.piginzoo.butler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESTART = "com.piginzoo.butler.RESTART"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, ACTION_RESTART -> {
                FileLog.log("BootReceiver: action=${intent.action}，启动服务")
                context.startForegroundService(Intent(context, WakeService::class.java))
                AlarmStore.rescheduleAll(context)   // 系统重启会清AlarmManager注册，开机重挂
            }
        }
    }
}
