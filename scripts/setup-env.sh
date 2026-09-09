#!/usr/bin/env bash
# band-qq-assistant 容器构建环境搭建
# 输出: /home/z/my-project/env/{android-sdk, gradle-8.13, cmdline-tools}
set -uo pipefail

ENV=/home/z/my-project/env
SDK=$ENV/android-sdk
mkdir -p "$ENV" "$SDK"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# ---------- 1. Android cmdline-tools ----------
(
  cd "$ENV"
  if [ ! -f .cmdline-tools.done ]; then
    log "下载 Android cmdline-tools..."
    curl -fsSL -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
      && unzip -q cmdtools.zip \
      && mkdir -p "$SDK/cmdline-tools" \
      && mv cmdline-tools "$SDK/cmdline-tools/latest" 2>/dev/null || mv cmdline-tools "$SDK/cmdline-tools/latest"
    rm -f cmdtools.zip
    touch .cmdline-tools.done
    log "cmdline-tools 完成"
  else
    log "cmdline-tools 已就绪，跳过"
  fi
) >"$ENV/log-cmdtools.txt" 2>&1 &

# ---------- 2. Gradle 8.13 ----------
(
  cd "$ENV"
  if [ ! -f .gradle.done ]; then
    log "下载 Gradle 8.13..."
    curl -fsSL -o gradle.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip \
      && unzip -q gradle.zip \
      && rm -f gradle.zip \
      && touch .gradle.done
    log "Gradle 完成"
  else
    log "Gradle 已就绪，跳过"
  fi
) >"$ENV/log-gradle.txt" 2>&1 &

# ---------- 3. aiot-toolkit (小米 Vela 快应用工具链) ----------
(
  cd /home/z/my-project/study-band-qq-assistant/band-qq
  if [ ! -d node_modules/aiot-toolkit ]; then
    log "npm install aiot-toolkit..."
    npm install --no-audit --no-fund 2>&1 | tail -5
  fi
  log "aiot-toolkit: $(node -e "console.log(require('./node_modules/aiot-toolkit/package.json').version)" 2>/dev/null || echo 未知)"
) >"$ENV/log-npm.txt" 2>&1 &

wait
echo "===== cmdtools ====="; tail -3 "$ENV/log-cmdtools.txt"
echo "===== gradle =====";   tail -3 "$ENV/log-gradle.txt"
echo "===== npm =====";       tail -5 "$ENV/log-npm.txt"
ls "$SDK/cmdline-tools/latest/bin" 2>/dev/null | head -3
ls "$ENV" | head
