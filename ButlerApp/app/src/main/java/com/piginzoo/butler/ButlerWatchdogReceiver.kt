package com.piginzoo.butler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 看门狗：每10分钟被闹钟叫醒一次，检查唤醒服务是否健康。
 * 不健康（进程被杀/识别循环假死）就拉起来，然后安排下一次巡视。
 */
class ButlerWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = WakeService.instance
        val healthy = svc != null && svc.isHealthy()
        if (!healthy) {
            FileLog.log("watchdog: 服务不健康(instance=${svc != null})，拉起")
            try {
                context.startForegroundService(Intent(context, WakeService::class.java))
            } catch (e: Exception) {
                FileLog.log("watchdog: 拉起失败 ${e.message}")
            }
        }
        scheduleNext(context)
    }

    companion object {
        const val ACTION = "com.piginzoo.butler.WATCHDOG"
        fun scheduleNext(context: Context, delayMs: Long = 10 * 60 * 1000L) {
            try {
                val am = context.getSystemService(AlarmManager::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, 1001,
                    Intent(context, ButlerWatchdogReceiver::class.java).setAction(ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pi
                )
            } catch (_: Exception) {}
        }
    }
}
