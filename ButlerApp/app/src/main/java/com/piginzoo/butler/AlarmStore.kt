package com.piginzoo.butler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 闹钟仓库：闹钟本体在手机系统AlarmManager里（中继/断网都不影响已设闹钟响铃），
 * 这里维护一份JSON清单用于查询/取消，并负责调度精确闹钟。
 */
object AlarmStore {
    data class Alarm(val id: Long, val triggerAt: Long, val label: String)

    private fun prefs(context: Context) = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(context: Context): List<Alarm> = try {
        val arr = JSONArray(prefs(context).getString("list", "[]")!!)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Alarm(o.getLong("id"), o.getLong("at"), o.getString("label"))
        }.sortedBy { it.triggerAt }
    } catch (e: Exception) { emptyList() }

    private fun save(context: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("at", it.triggerAt).put("label", it.label)) }
        prefs(context).edit().putString("list", arr.toString()).apply()
    }

    fun add(context: Context, triggerAt: Long, label: String): Alarm {
        val a = Alarm(System.currentTimeMillis(), triggerAt, label)
        save(context, all(context) + a)
        schedule(context, a)
        FileLog.log("闹钟添加 id=${a.id} at=$triggerAt label=$label")
        return a
    }

    fun remove(context: Context, id: Long) {
        cancelSchedule(context, id)
        save(context, all(context).filter { it.id != id })
        FileLog.log("闹钟移除 id=$id")
    }

    fun snooze(context: Context, id: Long, minutes: Int) {
        val a = all(context).find { it.id == id } ?: return
        val s = a.copy(triggerAt = System.currentTimeMillis() + minutes * 60_000L)
        save(context, all(context).filter { it.id != id } + s)
        schedule(context, s)
        FileLog.log("闹钟贪睡 id=$id +${minutes}分钟")
    }

    fun schedule(context: Context, a: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, a.id.toInt(),
            Intent(context, AlarmReceiver::class.java).putExtra("id", a.id).putExtra("label", a.label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.triggerAt, pi)
    }

    fun cancelSchedule(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, id.toInt(), Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    /** 开机后重挂所有闹钟（系统重启会清掉AlarmManager注册） */
    fun rescheduleAll(context: Context) {
        all(context).forEach { schedule(context, it) }
        if (all(context).isNotEmpty()) FileLog.log("开机重挂闹钟${all(context).size}个")
    }
}
