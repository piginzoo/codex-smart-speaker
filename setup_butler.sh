#!/bin/zsh
# 管家App安装后一键配置（重装APK后必须跑一遍，然后重启一次手机让辅助功能重新绑定）
adb shell pm grant com.piginzoo.butler android.permission.RECORD_AUDIO
adb shell settings put secure enabled_accessibility_services com.piginzoo.butler/com.piginzoo.butler.ButlerAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys deviceidle whitelist +com.piginzoo.butler
# dock场景：30秒自动熄屏 + 低亮度
adb shell settings put system screen_off_timeout 30000
adb shell settings put system screen_brightness 20
echo "配置完成。三星系统需要重启一次才会绑定辅助功能服务：adb reboot"
