#!/data/data/com.termux/files/usr/bin/bash
# snowluma-termux.sh —— 在 Termux 上一键部署 SnowLuma（proot Ubuntu + Linux arm64 QQ）
# 用途：让 QQ 协议端跑在手机本机，同步器 App 直连 127.0.0.1:3001/3000，无需电脑。
# 原理：Termux → proot-dial Ubuntu → Xvfb 虚拟显示 → SnowLuma 官方 linux-arm64 发行包 → 桌面 Linux QQ。
# 仅下载官方发布物，不修改不再分发 SnowLuma 二进制（遵守其 EULA）。
set -e

YELLOW='\033[1;33m'; GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
say() { echo -e "${GREEN}[SnowLuma]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
die() { echo -e "${RED}[x]${NC} $1"; exit 1; }

command -v proot-dialect >/dev/null 2>&1 && PROOT_BIN=proot-dialect || PROOT_BIN=proot-distro
say "1/5 安装 Termux 基础依赖"
pkg update -y >/dev/null 2>&1 || true
pkg install -y proot-distro curl tsu pulseaudio 2>/dev/null || pkg install -y proot-distro curl
[ "$(uname -m)" = "aarch64" ] || die "仅支持 arm64 设备（当前 $(uname -m)）"

say "2/5 安装 Ubuntu 容器（约 25MB）"
proot-distro install ubuntu 2>/dev/null || warn "ubuntu 已存在，跳过安装"

say "3/5 容器内安装运行依赖（Node 22 / Xvfb / 字体 / QQ 运行库）"
proot-distro login ubuntu -- bash -s <<'INSIDE'
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates xvfb x11-utils libnss3 libatk1.0-0 libatk-bridge2.0-0 \
  libgtk-3-0 libasound2 fonts-noto-cjk libx11-xcb1 libgbm1 libxss1 >/dev/null
# Node 22（SnowLuma 要求 >= 22.13）
if ! command -v node >/dev/null 2>&1 || [ "$(node -v | cut -dv -f2 | cut -d. -f1)" -lt 22 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
  apt-get install -y -qq nodejs >/dev/null
fi
mkdir -p /opt/snowluma && cd /opt/snowluma
say "4/5 下载 SnowLuma 官方 linux-arm64 发行包"
# 取最新 release 资产名（lite 完整包），失败时提示手动下载
ASSET_URL=$(curl -fsSL https://api.github.com/repos/SnowLuma/SnowLuma/releases/latest \
  | grep browser_download_url | grep -i 'linux-arm64' | head -1 | cut -d'"' -f4 || true)
if [ -z "$ASSET_URL" ]; then
  echo "[!] 未能自动获取下载地址，请到 https://github.com/SnowLuma/SnowLuma/releases 手动下载"
  echo "    linux-arm64 完整包并解压到 /opt/snowluma 后重跑本步骤。"
  exit 0
fi
curl -fL "$ASSET_URL" -o snowluma.tar.gz
tar xzf snowluma.tar.gz --strip-components=1 2>/dev/null || tar xzf snowluma.tar.gz
rm -f snowluma.tar.gz
chmod +x launcher.sh 2>/dev/null || true
cat > /opt/snowluma/start-headless.sh <<'LAUNCH'
#!/bin/bash
# 无头启动：Xvfb 虚拟显示 + DBus + SnowLuma launcher
export DISPLAY=:99
Xvfb :99 -screen 0 1280x720x24 &
sleep 1
eval "$(dbus-launch --sh-syntax)" 2>/dev/null || true
cd /opt/snowluma
exec ./launcher.sh
LAUNCH
chmod +x /opt/snowluma/start-headless.sh
say "5/5 完成！"
echo "---------------------------------------------"
echo " 后续使用（每次两步）："
echo "   1) proot-distro login ubuntu"
echo "   2) /opt/snowluma/start-headless.sh"
echo " 然后 Termux 里浏览器/同步器 App 打开 http://127.0.0.1:5099"
echo " （初始密码看启动日志），扫码登录 Linux QQ。"
echo " OneBot 连接：WS  ws://127.0.0.1:3001 / HTTP 127.0.0.1:3000"
echo "---------------------------------------------"
INSIDE

say "全部完成。上面已输出使用步骤；保活请配合同步器 App 的「后台保活向导」给 Termux 关电池优化+锁定任务卡片。"
