#!/bin/bash
# band-qq 构建环境一键重建（MEMORY.md 配方 2026-09-09 验证版）
set -e
T=/home/z/tools
SDK=/home/z/android-sdk
mkdir -p $T $SDK

echo "=== 1/5 Temurin JDK 17 ==="
if [ ! -x $T/jdk-17.0.20.1+1/bin/javac ]; then
  curl -sSL -o $T/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf $T/jdk17.tar.gz -C $T && rm $T/jdk17.tar.gz
fi
export JAVA_HOME=$T/jdk-17.0.20.1+1
$JAVA_HOME/bin/javac -version

echo "=== 2/5 Gradle 8.13 ==="
if [ ! -x $T/gradle-8.13/bin/gradle ]; then
  curl -sSL -o $T/gradle.zip "https://services.gradle.org/distributions/gradle-8.13-bin.zip"
  unzip -q $T/gradle.zip -d $T && rm $T/gradle.zip
fi
$T/gradle-8.13/bin/gradle --version 2>&1 | grep Gradle || true

echo "=== 3/5 Android cmdline-tools ==="
if [ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]; then
  curl -sSL -o $T/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p $SDK/cmdline-tools
  unzip -q $T/cmdtools.zip -d $T/tmp-sdk
  mv $T/tmp-sdk/cmdline-tools $SDK/cmdline-tools/latest
  rm -rf $T/tmp-sdk $T/cmdtools.zip
fi

echo "=== 4/5 SDK packages ==="
yes | $SDK/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true
$SDK/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" > /dev/null 2>&1
echo installed: $(ls $SDK/platforms/) $(ls $SDK/build-tools/)

echo "=== 5/5 android-37 目录陷阱修复 ==="
cd $SDK/platforms
if [ -d android-37.0 ] && [ ! -d android-37 ]; then
  mv android-37.0 android-37
fi
sed -i 's|path="platforms;android-37.0"|path="platforms;android-37"|' android-37/package.xml
sed -i 's|AndroidVersion.ApiLevel=37.0|AndroidVersion.ApiLevel=37|' android-37/source.properties
grep -oE 'path="[^"]+"' android-37/package.xml
grep ApiLevel android-37/source.properties

echo "=== done. build with: ==="
echo "JAVA_HOME=$T/jdk-17.0.20.1+1 $T/gradle-8.13/bin/gradle assembleRelease"
