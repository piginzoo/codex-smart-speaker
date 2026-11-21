package com.piginzoo.butler

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 系统TTS封装（三星SMT/Google TTS中文语音）。
 * speak()阻塞直到念完（或超时），speaking标志供麦克风循环避开自己的回声。
 */
object ButlerTts {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val initLatch = CountDownLatch(1)
    @Volatile var speaking = false
    @Volatile var muteUntilElapsed = 0L   // TTS结束后短暂丢弃音频，防喇叭回声进识别

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val r = tts!!.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts!!.setLanguage(Locale.CHINA)
                }
                FileLog.log("TTS初始化完成 engine=${tts!!.defaultEngine} lang=${tts!!.language}")
            } else {
                FileLog.log("TTS初始化失败 status=$status")
            }
            initLatch.countDown()
        }
    }

    /** 阻塞念一段话；失败/超时不抛异常 */
    fun speak(context: Context, text: String, blockMs: Long = 6000) {
        if (text.isBlank()) return
        init(context)
        initLatch.await(5, TimeUnit.SECONDS)
        val t = tts ?: return
        if (!ready) return
        try {
            speaking = true
            t.stop()
            val done = CountDownLatch(1)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.countDown() }
                override fun onError(utteranceId: String?) { done.countDown() }
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "butler")
            done.await(blockMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            FileLog.log("TTS speak异常: ${e.message}")
        } finally {
            speaking = false
            muteUntilElapsed = SystemClock.elapsedRealtime() + 800
        }
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }
}
