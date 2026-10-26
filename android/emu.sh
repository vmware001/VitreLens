#!/bin/bash
# 模拟器 adb 包装：沙箱里 adb 服务不会跨命令存活，所以每次都先 connect 再执行
# MSYS_NO_PATHCONV：关掉 Git Bash 的路径转换，否则 /sdcard/... 会被改写成 Windows 路径
export MSYS_NO_PATHCONV=1
ADB="/d/data/code/liquidglass/android/sdk/platform-tools/adb.exe"
SERIAL="127.0.0.1:16384"
"$ADB" connect "$SERIAL" >/dev/null 2>&1
exec "$ADB" -s "$SERIAL" "$@"
