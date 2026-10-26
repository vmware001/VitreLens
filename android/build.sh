#!/bin/bash
set -e
cd "$(dirname "$0")"
SDK="D:/data/code/liquidglass/android/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="C:/Users/esetp/tools/jdk21/jdk-21.0.11+10"
JAVAC="$JAVA_HOME/bin/javac.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

rm -rf build
mkdir -p build/gen build/classes build/dex

# ── 每次构建自动递增版本号；构建失败则回滚，避免白白吃掉一个版本号
MANIFEST=app/AndroidManifest.xml
cp "$MANIFEST" build/manifest.bak
restore_manifest() {
  if [ -f build/manifest.bak ]; then
    cp build/manifest.bak "$MANIFEST"
    echo "!! 构建失败，版本号已回滚"
  fi
}
trap 'rc=$?; if [ $rc -ne 0 ]; then restore_manifest; fi' EXIT

PYBIN0="C:/Users/esetp/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe"
"$PYBIN0" - <<'BUMP'
import re, io
p = 'app/AndroidManifest.xml'
s = io.open(p, encoding='utf-8').read()
code = int(re.search(r'android:versionCode="(\d+)"', s).group(1)) + 1
name = '1.%d' % (code - 1)
s = re.sub(r'android:versionCode="\d+"', 'android:versionCode="%d"' % code, s, count=1)
s = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % name, s, count=1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('== 版本号 -> versionCode %d / versionName %s ==' % (code, name))
BUMP

echo "== 0/6 导出色器（唯一真源：js/glass.js）=="
PYBIN="$PYBIN0"
"$PYBIN" tools/extract_shaders.py

echo "== 单元测试：元数据重组 =="
mkdir -p build/test
"$JAVA_HOME/bin/javac.exe" -encoding UTF-8 -nowarn -d build/test \
  app/src/com/vitrelens/app/Meta.java tools/MetaTest.java
"$JAVA_HOME/bin/java.exe" -cp build/test com.vitrelens.app.MetaTest "$PWD/../reference" \
  > build/test.log 2>&1 || { tail -30 build/test.log; echo "!! 单元测试失败"; exit 1; }
tail -3 build/test.log

echo "== 1/6 aapt2 compile =="
"$BT/aapt2.exe" compile --dir app/res -o build/res.zip

echo "== 2/6 aapt2 link =="
"$BT/aapt2.exe" link -I "$PLATFORM" --manifest app/AndroidManifest.xml \
  -R build/res.zip --java build/gen -o build/base.apk \
  --min-sdk-version 29 --target-sdk-version 34 --auto-add-overlay

echo "== 3/6 javac =="
find app/src build/gen -name "*.java" > build/sources.txt
set +e
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:none \
  -classpath "$PLATFORM" -d build/classes @build/sources.txt > build/javac.log 2>&1
RC=$?
set -e
grep -v "^注:" build/javac.log || true
if [ $RC -ne 0 ]; then
  echo "!! javac 失败，退出码 $RC"
  exit 1
fi
if ! find build/classes -name "MainActivity*.class" | grep -q .; then
  echo "!! 未生成 MainActivity.class"
  exit 1
fi

echo "== 4/6 d8 =="
find build/classes -name "*.class" > build/classes.txt
"$BT/d8.bat" --lib "$PLATFORM" --min-api 29 --output build/dex @build/classes.txt

echo "== 5/6 打包 dex =="
python - <<'PY'
import zipfile
src='build/base.apk'; dst='build/withdex.apk'
zin=zipfile.ZipFile(src)
zout=zipfile.ZipFile(dst,'w',zipfile.ZIP_DEFLATED)
for item in zin.infolist():
    zout.writestr(item, zin.read(item.filename))
zout.write('build/dex/classes.dex','classes.dex')
zout.close(); zin.close()
print('dex added: %d bytes' % __import__('os').path.getsize('build/dex/classes.dex'))
PY

"$BT/zipalign.exe" -f 4 build/withdex.apk build/aligned.apk

echo "== 6/6 签名 =="
if [ ! -f debug.keystore ]; then
  "$KEYTOOL" -genkeypair -v -keystore debug.keystore -alias vitrelens \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass vitrelens -keypass vitrelens \
    -dname "CN=VitreLens, OU=App, O=VitreLens, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner.bat" sign --ks debug.keystore --ks-pass pass:vitrelens \
  --key-pass pass:vitrelens --out VitreLens.apk build/aligned.apk

"$BT/apksigner.bat" verify VitreLens.apk && echo "SIGNED OK"
"$BT/aapt2.exe" dump badging VitreLens.apk | head -6
ls -la VitreLens.apk
