package com.piginzoo.butler

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.view.WindowManager

/**
 * 息屏时点亮屏幕用的透明Activity。
 * 锁屏已禁用，turnScreenOn直接点亮；闪一下就finish。
 */
class ScreenWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        Handler().postDelayed({ finish() }, 400)
    }
}
