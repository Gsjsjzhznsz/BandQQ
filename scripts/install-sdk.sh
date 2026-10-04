#!/usr/bin/env bash
# 安装 Android SDK 组件并接受许可证
set -uo pipefail
ENV=/home/z/my-project/env
SDK=$ENV/android-sdk
export ANDROID_HOME=$SDK
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
SDKM=$SDK/cmdline-tools/latest/bin/sdkmanager

echo "== sdkmanager 版本 =="
$SDKM --version 2>/dev/null || { echo "sdkmanager 运行失败"; exit 1; }

echo "== 预写许可证 =="
mkdir -p "$SDK/licenses"
echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$SDK/licenses/android-sdk-license"
echo "84831b9409646a918e30573bab4c9c91346d8abd" > "$SDK/licenses/android-sdk-preview-license"

echo "== 安装 platform-tools / android-37 / build-tools =="
yes | $SDKM --sdk_root=$SDK --licenses >/dev/null 2>&1 || true
$SDKM --sdk_root=$SDK "platform-tools" "platforms;android-37" "build-tools;36.0.0" "build-tools;35.0.0" 2>&1 | tail -4

echo "== 已安装组件 =="
ls "$SDK/platforms" "$SDK/build-tools" 2>/dev/null
