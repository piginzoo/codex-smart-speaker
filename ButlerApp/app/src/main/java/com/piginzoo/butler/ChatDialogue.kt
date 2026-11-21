package com.piginzoo.butler

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 小管家对话状态机（GLM语音聊天）：触发词命中后进入，我说一句你回一句。
 * 能力由GLM输出的action驱动：
 *   chat    普通闲聊（reply直接念）
 *   set     设置闹钟（datetime+label已确认，本地AlarmManager执行）
 *   list    查询闹钟（系统提示词注入清单，reply里念）
 *   cancel  取消闹钟
 *   open_chatgpt  语音切换到Codex/ChatGPT live
 *   exit    退出小管家回待机
 * 30秒没听到说话自动退出。
 */
object ChatDialogue {
    @Volatile var currentLabel: String = "闹钟"
    private val utterances = LinkedBlockingQueue<String>(10)
    private val messages = JSONArray()

    fun onUtterance(text: String) {
        utterances.clear()   // 只要最新一句
        utterances.offer(text)
    }

    /** 在mic-loop线程以外的新线程调用（阻塞式）。返回true表示用户要求切换到ChatGPT */
    fun start(service: WakeService, firstUtterance: String): Boolean {
        val ctx = service
        Butler.chatDialogueActive = true
        var switchToCodex = false
        FileLog.log("小管家对话开始: \"$firstUtterance\"")
        try {
            if (!LlmClient.hasKey(ctx)) {
                ButlerTts.speak(ctx, "小管家还没配置大脑密钥，这事我办不了")
                return false
            }
            messages.let { while (it.length() > 0) it.remove(0) }
            val sys = buildSystemPrompt(ctx)
            messages.put(JSONObject().put("role", "system").put("content", sys))

            var utterance = firstUtterance
            var turn = 0
            var pendingSet: Triple<Long, String, JSONObject>? = null   // clarify暂存：等用户"对"后本地落库
            while (turn < 15) {
                messages.put(JSONObject().put("role", "user").put("content", utterance))
                val resp = LlmClient.chat(ctx, messages)
                if (resp == null) {
                    ButlerTts.speak(ctx, "脑子暂时不在线，网络可能断了，稍后再试")
                    FileLog.log("小管家: LLM调用失败退出")
                    return false
                }
                messages.put(JSONObject().put("role", "assistant").put("content", resp.toString()))
                val reply = resp.optString("reply", "")
                val action = resp.optString("action", "chat")
                val datetime = resp.optString("datetime", "null")
                val label = resp.optString("label", "")
                FileLog.log("小管家: action=$action datetime=$datetime label=$label reply=$reply")

                if (reply.isNotBlank()) ButlerTts.speak(ctx, reply)

                when (action) {
                    "set" -> {
                        // GLM跳过确认直接set时兜底：直接落库
                        val ms = parseDatetime(datetime)
                        if (ms != null && ms > System.currentTimeMillis()) {
                            val a = AlarmStore.add(ctx, ms, label.ifBlank { "闹钟" })
                            currentLabel = a.label
                            val nice = SimpleDateFormat("M月d日HH点mm分", Locale.CHINA).format(Date(a.triggerAt))
                            ButlerTts.speak(ctx, "设好了，${nice}，${a.label}")
                            FileLog.log("闹钟已设 at=${a.triggerAt} label=${a.label}")
                            return false
                        } else {
                            ButlerTts.speak(ctx, "这个时间我没算明白，你再说一遍？")
                        }
                    }
                    "clarify" -> {
                        // GLM确认话术的JSON自带精确时间：代码级暂存，用户说"对"后本地直接落库（零二次调用）
                        val ms = parseDatetime(datetime)
                        if (ms != null && ms > System.currentTimeMillis()) {
                            pendingSet = Triple(ms, label.ifBlank { "闹钟" }, resp)
                        }
                    }
                    "cancel" -> {
                        val ms = parseDatetime(datetime)
                        val all = AlarmStore.all(ctx)
                        val target = all.find { label.isNotBlank() && it.label == label }
                            ?: (if (ms != null) all.filter { kotlin.math.abs(it.triggerAt - ms) < 60_000L }.maxByOrNull { it.triggerAt } else null)
                            ?: all.firstOrNull()
                        if (target != null) {
                            AlarmStore.remove(ctx, target.id)
                            ButlerTts.speak(ctx, "取消了，${target.label}")
                        } else {
                            ButlerTts.speak(ctx, "没找到要取消的闹钟")
                        }
                        return false
                    }
                    "list" -> return false    // reply里已经念了清单
                    "open_chatgpt" -> {
                        switchToCodex = true
                        return true
                    }
                    "exit" -> return false
                    // chat / clarify / none：reply已念，继续听下一句
                }

                val next = utterances.poll(30, TimeUnit.SECONDS)
                if (next == null) {
                    ButlerTts.speak(ctx, "那先这样，需要再喊我")
                    return false
                }
                utterance = next
                turn++
            }
            ButlerTts.speak(ctx, "聊太久了，先歇歇")
            return false
        } catch (e: Exception) {
            FileLog.log("小管家对话异常: ${e.message}")
            return false
        } finally {
            Butler.chatDialogueActive = false
            FileLog.log("小管家对话结束 switchToCodex=$switchToCodex")
        }
    }

    private fun buildSystemPrompt(ctx: WakeService): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).format(Date())
        val alarms = AlarmStore.all(ctx).joinToString("") {
            SimpleDateFormat("M月d日HH:mm", Locale.CHINA).format(Date(it.triggerAt)) + " ${it.label}\n"
        }.ifBlank { "（无）" }
        return "你是\"小管家\"，一台音箱上的中文语音助手，用户在跟你连续语音对话。\n" +
            "当前时间：$now\n" +
            "现有闹钟：\n$alarms\n" +
            "你只能输出一个JSON对象，格式：\n" +
            "{\"reply\":\"要念给用户听的中文\",\"action\":\"chat|set|cancel|list|open_chatgpt|exit\",\"datetime\":\"yyyy-MM-dd HH:mm:ss 或 null\",\"label\":\"闹钟文字内容或null\"}\n" +
            "规则：\n" +
            "1)用户表达设闹钟/提醒意图：先用reply复述你理解的时间与内容请求确认，action=clarify；用户确认后action=set，datetime为绝对时间，label为闹钟内容。\n" +
            "2)相对时间(十分钟后/半小时后/明早七点/两天以后下午两点半)基于当前时间换算成绝对datetime。\n" +
            "3)查询闹钟：action=list，reply里逐条念出现有闹钟。\n" +
            "4)取消闹钟：action=cancel，datetime或label尽量给出。\n" +
            "5)用户要叫ChatGPT/切到Codex/打开ChatGPT：action=open_chatgpt，reply简短确认。\n" +
            "6)用户说拜拜/退出/没事了：action=exit，reply简短道别。\n" +
            "7)其余一律action=chat，reply正常聊天回复（保持口语化、简短，这是音箱对话）。\n" +
            "8)除JSON外不要输出任何文字。"
    }

    private fun parseDatetime(s: String): Long? = try {
        if (s == "null" || s.isBlank()) null
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(s.trim())?.time
    } catch (e: Exception) { null }
}
