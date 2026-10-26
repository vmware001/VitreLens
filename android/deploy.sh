#!/bin/bash
# 等手机接上来 → 装 APK → 清日志 → 拉起 App → 抓关键日志
# 用法：./deploy.sh
set +e
cd "$(dirname "$0")"
ADB="./sdk/platform-tools/adb.exe"

echo "等待设备（手机先开 开发者选项 → USB 调试，插上后在手机上点「允许」）…"
"$ADB" wait-for-device
"$ADB" devices -l

MODEL=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
SDK=$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
echo "设备：$MODEL (API $SDK)"

echo "== 安装 =="
"$ADB" install -r VitreLens.apk

echo "== 清日志并启动 =="
"$ADB" logcat -c
"$ADB" shell am force-stop com.vitrelens.app
"$ADB" shell am start -n com.vitrelens.app/.MainActivity
sleep 4

echo "== 关键日志 =="
"$ADB" logcat -d -v brief 2>/dev/null | grep -iE \
  "vitrelens|AndroidRuntime|FATAL|GLES|libEGL|OpenGLRenderer|MediaCodec|ACodec|CCodec|MediaMuxer|shader" \
  | tail -60
