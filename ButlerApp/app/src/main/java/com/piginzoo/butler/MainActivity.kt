package com.piginzoo.butler

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)

        // 打开App即自动启动唤醒服务（dock场景：开机/重启后点一下图标即可）
        if (WakeService.instance == null) {
            startForegroundService(Intent(this, WakeService::class.java))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startForegroundService(Intent(this, WakeService::class.java))
            Toast.makeText(this, "唤醒服务已请求启动", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, WakeService::class.java))
            refresh()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!Butler.a11yReady) {
                Toast.makeText(this, "请先到系统设置开启辅助功能", Toast.LENGTH_LONG).show()
                openAccessibilitySettings()
                return@setOnClickListener
            }
            WakeService.instance?.testTrigger()
                ?: Toast.makeText(this, "唤醒服务未启动", Toast.LENGTH_SHORT).show()
        }

        // 电池优化白名单用adb设置（dumpsys deviceidle whitelist），避免每次启动弹系统对话框
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Butler.listener = { runOnUiThread { statusText.text = it } }
    }

    override fun onPause() {
        Butler.listener = null
        super.onPause()
    }

    private fun refresh() {
        statusText.text = Butler.status
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {}
    }
}
