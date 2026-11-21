package com.piginzoo.butler

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 持久化日志：写 /sdcard/Android/data/com.piginzoo.butler/files/butler.log
 * logcat会被环境音识别输出刷掉，这个文件是排查的生命线。512KB自动轮转。
 */
object FileLog {
    private var dir: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS")

    fun init(d: File?) {
        if (dir == null && d != null) dir = d
    }

    fun log(msg: String) {
        val d = dir ?: return
        try {
            synchronized(this) {
                val file = File(d, "butler.log")
                if (file.exists() && file.length() > 512_000) {
                    val old = File(d, "butler.old.log")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText("${fmt.format(Date())} $msg\n")
            }
        } catch (_: Exception) {}
    }
}
