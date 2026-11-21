package com.piginzoo.butler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * LLM对话客户端（智谱BigModel，glm-5.3-flash+关思考=秒级响应）。
 * 配置/key存手机本地文件 llm.json：{"url":"...","model":"...","key":"..."}
 * 这个文件在 /sdcard/Android/data/com.piginzoo.butler/files/ 下，绝不进源码仓库。
 */
object LlmClient {
    data class Config(val url: String, val model: String, val key: String)

    fun config(context: Context): Config? = try {
        val f = File(context.getExternalFilesDir(null), "llm.json")
        val o = JSONObject(f.readText())
        Config(o.getString("url"), o.getString("model"), o.getString("key"))
    } catch (e: Exception) {
        FileLog.log("llm.json读取失败: ${e.message}")
        null
    }

    fun hasKey(context: Context): Boolean = config(context) != null

    /** messages: JSONArray of {role, content}；返回模型输出的严格JSON对象，失败null */
    fun chat(context: Context, messages: JSONArray): JSONObject? {
        val c = config(context) ?: return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(c.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 20000
                setRequestProperty("Authorization", "Bearer ${c.key}")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val body = JSONObject()
                .put("model", c.model)
                .put("messages", messages)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("temperature", 0.3)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                FileLog.log("LLM HTTP $code")
                return null
            }
            val text = conn.inputStream.bufferedReader().readText()
            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
            JSONObject(stripFences(content))
        } catch (e: Exception) {
            FileLog.log("LLM调用失败: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 容错：剥掉模型偶尔包的 ```json 围栏 */
    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        return t
    }
}
