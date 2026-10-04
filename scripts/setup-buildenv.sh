#!/bin/bash
# band-qq 构建环境一键重建（v2 配方 · 2026-10-04 本地 e2e 验证版）
#
# v2 变更（v1 的 platforms;android-37.0 + 老版 11076708 sdkmanager 组合已失效）：
#   ① 老格式目录 repository2-1.xml 已不含 android-37.* 包，老 sdkmanager 装 37 静默失败
#   ② Google 官方 platform-37.0 zip 元数据自带笔误：<api-level>37.0 / Platform.Version=17，
#      AGP 8.13.2 按 hash 串 android-37 查找必报 Failed to find target —— 必须四处补丁
#      （v1 只补了 package.xml path + source.properties ApiLevel 两处，api-level 是根因）
set -e
T=/home/z/tools
SDK=/home/z/android-sdk
mkdir -p $T $SDK

echo "=== 1/6 Temurin JDK 17 ==="
if [ ! -x $T/jdk-17*/bin/javac ]; then
  curl -sSL -o $T/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf $T/jdk17.tar.gz -C $T && rm $T/jdk17.tar.gz
fi
export JAVA_HOME=$(echo $T/jdk-17*)
$JAVA_HOME/bin/javac -version

echo "=== 2/6 Gradle 8.13 ==="
if [ ! -x $T/gradle-8.13/bin/gradle ]; then
  curl -sSL -o $T/gradle.zip "https://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip"
  unzip -q $T/gradle.zip -d $T && rm $T/gradle.zip
fi
$T/gradle-8.13/bin/gradle --version 2>&1 | grep Gradle || true

echo "=== 3/6 Android cmdline-tools（latest，能解析含 37.* 的新目录）==="
if [ ! -x $SDK/cmdline-tools/latest/bin/sdkmanager ]; then
  curl -sSL -o $T/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  mkdir -p $SDK/cmdline-tools
  rm -rf $T/tmp-sdk && unzip -q $T/cmdtools.zip -d $T/tmp-sdk
  mv $T/tmp-sdk/cmdline-tools $SDK/cmdline-tools/latest
  rm -rf $T/tmp-sdk $T/cmdtools.zip
fi
SDKM=$SDK/cmdline-tools/latest/bin/sdkmanager

echo "=== 4/6 SDK 组件（platform-tools / build-tools 37.0.0）==="
yes | $SDKM --sdk_root=$SDK --licenses > /dev/null 2>&1 || true
$SDKM --sdk_root=$SDK --install "platform-tools" "build-tools;37.0.0" > /dev/null 2>&1
echo installed: $(ls $SDK/build-tools/)

echo "=== 5/6 平台 android-37（临时 root 装包→落位→四处补丁）==="
rm -rf /tmp/sdktmp
yes | $SDKM --sdk_root=/tmp/sdktmp --licenses > /dev/null 2>&1 || true
$SDKM --sdk_root=/tmp/sdktmp --install "platforms;android-37.0" > /dev/null 2>&1
mkdir -p $SDK/platforms
rm -rf $SDK/platforms/android-37
mv /tmp/sdktmp/platforms/android-37.0 $SDK/platforms/android-37
# 补丁一：package.xml 注册表 path（37.0→37）
sed -i 's|platforms;android-37\.0|platforms;android-37|g' $SDK/platforms/android-37/package.xml
# 补丁二（根因）：package.xml <api-level>37.0</api-level> → 37，AGP hash 串由此而来
sed -i 's|<api-level>37\.0</api-level>|<api-level>37</api-level>|' $SDK/platforms/android-37/package.xml
# 补丁三/四：source.properties ApiLevel 37.0→37 + Google zip 笔误 Platform.Version=17→37
sed -i 's|AndroidVersion.ApiLevel=37\.0|AndroidVersion.ApiLevel=37|; s|Platform\.Version=17|Platform.Version=37|' $SDK/platforms/android-37/source.properties
grep -o '<api-level>[^<]*</api-level>' $SDK/platforms/android-37/package.xml
grep -E 'ApiLevel|Platform.Version' $SDK/platforms/android-37/source.properties
[ -f $SDK/platforms/android-37/android.jar ] && echo "android.jar ok"

echo "=== 6/6 验证 ==="
echo "JAVA_HOME=$JAVA_HOME"
echo "构建：JAVA_HOME=$JAVA_HOME PATH=\$JAVA_HOME/bin:$T/gradle-8.13/bin:\$PATH GRADLE_USER_HOME=$T/gradle-home ANDROID_HOME=$SDK gradle --no-daemon test assembleBundledDebug assembleCompanionDebug"
