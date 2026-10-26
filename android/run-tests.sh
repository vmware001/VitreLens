#!/bin/bash
# 纯 JVM 测试：不依赖 Android 设备，验证元数据重组逻辑（最容易出错的地方）
set -e
cd "$(dirname "$0")/.."
JAVA_HOME="C:/Users/esetp/tools/jdk21/jdk-21.0.11+10"
mkdir -p android/build/test
"$JAVA_HOME/bin/javac.exe" -encoding UTF-8 -nowarn -d android/build/test \
  android/app/src/com/vitrelens/app/Meta.java android/tools/MetaTest.java
"$JAVA_HOME/bin/java.exe" -cp android/build/test com.vitrelens.app.MetaTest
