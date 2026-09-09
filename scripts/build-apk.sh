#!/usr/bin/env bash
# Android 同步器 APK 构建（debug + release + 单元测试）
set -uo pipefail
ROOT=/home/z/my-project/study-band-qq-assistant
PROJ=$ROOT/android-sync
ENV=/home/z/my-project/env

export ANDROID_HOME=$ENV/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=$ENV/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$ENV/gradle-8.13/bin:$PATH
export GRADLE_USER_HOME=$ENV/gradle-home

# local.properties（Gradle 需要）
if [ ! -f "$PROJ/local.properties" ]; then
  echo "sdk.dir=$ANDROID_HOME" > "$PROJ/local.properties"
  echo "已写入 local.properties"
fi

cd "$PROJ"
echo "== Gradle $(gradle --version 2>/dev/null | grep Gradle | head -1) / Java $(java -version 2>&1 | head -1) =="

# 先跑单元测试，再出包
gradle --no-daemon test assembleDebug assembleRelease 2>&1 | tail -25
RC=${PIPESTATUS[0]}
echo "GRADLE_EXIT=$RC"

echo "== 产物 =="
find app/build/outputs -name "*.apk" -exec ls -la {} \; 2>/dev/null
echo "== 测试报告 =="
find app/build/test-results -name "*.xml" 2>/dev/null | while read -r f; do
  python3 -c "
import xml.etree.ElementTree as ET,sys
r=ET.parse('$f').getroot()
print(f\"{r.get('name','?').split('.')[-1]}: tests={r.get('tests')} failures={r.get('failures')} errors={r.get('errors')} skipped={r.get('skipped')}\")" 2>/dev/null
done
exit $RC
