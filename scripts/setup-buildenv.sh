#!/bin/bash
# band-qq 构建环境一键重建（v3 配方 · 2026-10-04 · AGP 9.0.1 + Gradle 9.1.0）
#
# v3：AGP 9.0.1 起原生支持 platforms;android-37.0（compileSdk=37+compileSdkMinor=0），
#     旧版的 platform 元数据手术（api-level/Platform.Version/目录改名）全部作废。
set -e
T=/home/z/tools
SDK=/home/z/android-sdk
mkdir -p $T $SDK

echo "=== 1/4 Temurin JDK 17 ==="
if [ ! -x $T/jdk-17*/bin/javac ]; then
  curl -sSL -o $T/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf $T/jdk17.tar.gz -C $T && rm $T/jdk17.tar.gz
fi
export JAVA_HOME=$(echo $T/jdk-17*)
$JAVA_HOME/bin/javac -version

echo "=== 2/4 Gradle 9.1.0（AGP 9.0.1 最低要求）==="
if [ ! -x $T/gradle-9.1.0/bin/gradle ]; then
  curl -sSL -o $T/gradle.zip "https://services.gradle.org/distributions/gradle-9.1.0-bin.zip"
  unzip -q $T/gradle.zip -d $T && rm $T/gradle.zip
fi

echo "=== 3/4 Android cmdline-tools + SDK 组件 ==="
if [ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]; then
  curl -sSL -o $T/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  mkdir -p $SDK/cmdline-tools
  rm -rf $T/tmp-sdk && unzip -q $T/cmdtools.zip -d $T/tmp-sdk
  mv $T/tmp-sdk/cmdline-tools $SDK/cmdline-tools/latest
  rm -rf $T/tmp-sdk $T/cmdtools.zip
fi
SDKM=$SDK/cmdline-tools/latest/bin/sdkmanager
yes | $SDKM --sdk_root=$SDK --licenses > /dev/null 2>&1 || true
$SDKM --sdk_root=$SDK --install "platforms;android-37.0" "build-tools;37.0.0" "build-tools;35.0.0" "platform-tools" > /dev/null 2>&1
ls $SDK/platforms/ $SDK/build-tools/

echo "=== 4/4 完成 ==="
echo "构建：cd android-sync && JAVA_HOME=$JAVA_HOME PATH=\$JAVA_HOME/bin:$T/gradle-9.1.0/bin:\$PATH GRADLE_USER_HOME=$T/gradle-home ANDROID_HOME=$SDK gradle --no-daemon test assembleBundledDebug assembleCompanionDebug"
