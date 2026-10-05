#!/bin/bash

ASTRBOT_APP_VERSION="{{VERSION}}"

# v2.26.0：引擎管道保存——fd9 = 原始 stdout。看门狗 nohup 上下文里重新拉起的
# napcat-console tap 若写当前 stdout 只会落进 watchdog.log，引擎日志/App 日志面板
# 从自愈重启时刻起就看不到 NapCat 动态。fd9 在主脚本启动时捕获引擎读取管道，
# 子进程（nohup 不动 fd≥3）层层继承，tap 恒写 >&9 → 引擎日志全程可见。
# 守卫：仅在 fd9 尚未打开时捕获（看门狗重新执行本脚本时 fd9 已继承，不能覆盖）。
if { true >&9; } 2>/dev/null; then :; else exec 9>&1; fi

# 自定义 Git Clone 命令（为空时使用默认逻辑）
CUSTOM_GIT_CLONE=""

# 重装插件依赖标记（1表示需要重装，执行后自动清除）
REINSTALL_PLUGINS_FLAG=0

# GitHub 代理选择：
# auto 表示按列表自动测试；direct 表示直连；其他值视为代理 URL。
ASTRBOT_GITHUB_PROXY="${ASTRBOT_GITHUB_PROXY:-auto}"
ASTRBOT_FORCE_REINSTALL_STEP="${ASTRBOT_FORCE_REINSTALL_STEP:-}"

export UV_LINK_MODE=copy
export UV_DEFAULT_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
export UV_PYTHON_INSTALL_MIRROR="https://ghfast.top/https://github.com/astral-sh/python-build-standalone/releases/download"

if [ -z "$TMPDIR" ]; then
  echo "错误：未检测到 TMPDIR，请在挂载共享目录时传入 TMPDIR"
  exit 1
fi

if [ ! -d "$TMPDIR" ]; then
  echo "错误：临时目录 $TMPDIR 不存在，请确认挂载已经完成"
  exit 1
fi


progress_echo(){
  echo -e "\033[31m- $@\033[0m"
  echo "$@" > "$TMPDIR/progress_des"
}

# v2.20.0：结构化阶段标记。EngineManager 解析 [STAGE:百分比:描述] 驱动 APK 的
# 启动进度卡（用户反馈：普通用户看不懂裸 log，不知道流程到哪了）；
# 同时以普通行进日志，人可直接读。描述用大白话+预估耗时。
stage(){
  local pct="$1"; shift
  echo "[STAGE:$pct:$*]"
  progress_echo "$*"
}

# v2.20.0：上游脚本的本地化变量（L_NOT_INSTALLED 等）在本分支从未定义，
# UI 曾出现 "Napcat ，..." 之类的残缺文案（用户 10-04 日志实证）。显式定义。
L_NOT_INSTALLED="未安装"
L_INSTALLING="安装中"
L_INSTALLED="已安装"

# v2.20.0：sudo 透传垫片。proot 容器内恒为 root，而上游 NapCat 安装脚本硬性检查
# sudo 命令存在（用户 10-04 日志实证："sudo不存在, 请手动安装" → NapCat 安装
# 失败 → 引擎 exit=1）。不装真 sudo（省一次 apt 联网与数十 MB 依赖），垫片
# sudo xxx == 直接执行 xxx，覆盖上游脚本的全部 sudo 用法（proot 内无需提权）。
ensure_sudo_shim(){
  if ! command -v sudo >/dev/null 2>&1; then
    if printf '#!/bin/sh\nexec "$@"\n' > /usr/local/bin/sudo 2>/dev/null && chmod 755 /usr/local/bin/sudo 2>/dev/null; then
      echo "[AstrBot Android] 已创建 sudo 透传垫片（proot 恒 root，sudo xxx 直接执行 xxx）"
    else
      echo "[AstrBot Android] 警告: sudo 垫片创建失败（上游安装脚本可能拒绝执行）"
    fi
  fi
}

prepare_reinstall_step(){
  case "$1" in
    uv)
      progress_echo "uv 重装准备中"
      rm -f "$HOME/.local/bin/uv" "$HOME/.local/bin/uvx"
      ;;
    napcat)
      progress_echo "NapCat 重装准备中"
      if [ -d "$HOME/napcat/config" ]; then
        rm -rf "$HOME/napcat_config_backup"
        cp -r "$HOME/napcat/config" "$HOME/napcat_config_backup"
      fi
      pkill -f 'qq --no-sandbox' 2>/dev/null || true
      pkill -f 'NapCat' 2>/dev/null || true
      pkill -f '/root/launcher_.*\.sh' 2>/dev/null || true
      pkill -f '/root/launcher\.sh' 2>/dev/null || true
      pkill -f 'napcat_instances/.*/launcher' 2>/dev/null || true
      rm -rf "$HOME/napcat" "$HOME/napcat.sh" "$HOME/launcher.sh" "$HOME/launcher.cpp" "$HOME/libnapcat_launcher.so"
      ;;
    astrbot)
      progress_echo "AstrBot 重装准备中"
      killall uv 2>/dev/null || true
      rm -rf "$HOME/AstrBot_data_reinstall_backup"
      if [ -d "$HOME/AstrBot/data" ]; then
        cp -r "$HOME/AstrBot/data" "$HOME/AstrBot_data_reinstall_backup"
      fi
      rm -rf "$HOME/AstrBot" "$HOME/AstrBot_tmp"
      ;;
  esac
}

maybe_prepare_reinstall(){
  if [ "$ASTRBOT_FORCE_REINSTALL_STEP" = "$1" ]; then
    prepare_reinstall_step "$1"
  fi
}

bump_progress(){
  current=0
  if [ -f "$TMPDIR/progress" ]; then
    current=$(cat "$TMPDIR/progress" 2>/dev/null || echo 0)
  fi
  next=$((current + 1))
  printf "$next" > "$TMPDIR/progress"
}

# v2.19.0：容器 DNS 根修。实证（用户 10-04 日志 + rootfs 资产取证）：发行包
# /etc/resolv.conf 是普通文件且烙着构建机 systemd-resolved 桩 —— nameserver 127.0.0.53
# （Azure VM 残留），proot 内 127.0.0.53 无监听 → glibc 解析必死；v2.18.1 的
# 「已有 nameserver 即跳过」幂等检查恰好被这个毒桩骗过，修复从未生效。
# 改法：每次启动无条件重写为公共解析器（幂等，成本可忽略）+ getent 解析自检。
ensure_container_dns(){
  local rc=/etc/resolv.conf
  local old=""
  [ -f "$rc" ] && old=$(grep -m1 '^nameserver' "$rc" 2>/dev/null)
  {
    echo "nameserver 223.5.5.5"
    echo "nameserver 119.29.29.29"
    echo "nameserver 114.114.114.114"
    echo "nameserver 8.8.8.8"
    echo "options timeout:2 attempts:3 rotate"
  } > "$rc" 2>/dev/null || { echo "[AstrBot Android] 警告: 无法写入 $rc（rootfs 只读?）"; return 1; }
  if [ -n "$old" ]; then
    echo "[AstrBot Android] resolv.conf 已重写（原: $old → 公共解析器x4）"
  fi
  # 极简 rootfs 可能缺 nsswitch.conf（glibc 缺省含 dns，显式补上更稳）
  if [ ! -f /etc/nsswitch.conf ]; then
    printf 'hosts: files dns\nnetworks: files\n' > /etc/nsswitch.conf 2>/dev/null || true
  fi
  # 解析自检：getent 与 apt 同一条 glibc 解析路径，成败即 apt 前置判定
  if command -v getent >/dev/null 2>&1; then
    if getent hosts mirrors.tuna.tsinghua.edu.cn >/dev/null 2>&1; then
      echo "[AstrBot Android] DNS 自检通过（getent 解析正常）"
    else
      echo "[AstrBot Android] DNS 自检失败（域名解析不可用；若持续失败请检查系统网络/VPN/私人DNS）"
    fi
  fi
  return 0
}

# v2.28.0：设备身份稳定化（用户 10-05 封号反馈的对抗基础项：疑似虚拟设备风控）。
# 两个特征源：
# ① /etc/machine-id 缺失/为空时，Ubuntu 工具链（systemd-machine-id-setup/dbus/
#   machine-id 消费方）会各自随机生成 → rootfs 每次重装后设备身份漂移，QQ NT 侧
#   设备指纹随之变化，腾讯侧视为「新电脑频繁登录」→ 风控分累积。首次生成一次后
#   永久固定（rootfs 持久保存；自愈 L1~L3 均不触碰，L4 深度重置也只清 ~/.config/QQ）。
# ② /etc/hostname 缺失时固定为常规 Linux 桌面名（空/异常主机名本身就是一个
#   容器特征）；/etc/hosts 补 127.0.1.1 桌面惯例条目（部分程序启动时反解主机名）。
ensure_stable_identity(){
  # machine-id：仅缺失/空时生成（两个 uuid 拼接取 32 hex；存在则绝不动）
  if [ ! -s /etc/machine-id ]; then
    local a b
    a=$(tr -d '-' < /proc/sys/kernel/random/uuid 2>/dev/null)
    b=$(tr -d '-' < /proc/sys/kernel/random/uuid 2>/dev/null)
    if [ -n "$a" ] && [ -n "$b" ]; then
      printf '%s%s\n' "$a" "$b" | cut -c1-32 > /etc/machine-id 2>/dev/null || true
      if [ -s /etc/machine-id ]; then
        echo "[AstrBot Android] 设备身份已初始化并永久固定（/etc/machine-id；重装引擎不变，腾讯侧始终视为同一台电脑）"
      fi
    fi
  fi
  chmod 444 /etc/machine-id 2>/dev/null || true
  # hostname：仅缺失时固定（存在则保留用户的取名）
  if [ ! -s /etc/hostname ]; then
    echo "bandqq-desktop" > /etc/hostname 2>/dev/null || true
    echo "[AstrBot Android] 主机名已固定为 bandqq-desktop（常规桌面命名，避免空主机名暴露容器特征）"
  fi
  if [ -f /etc/hosts ] && ! grep -q '127\.0\.1\.1' /etc/hosts 2>/dev/null; then
    echo "127.0.1.1 $(cat /etc/hostname 2>/dev/null || echo bandqq-desktop)" >> /etc/hosts 2>/dev/null || true
  fi
  return 0
}

# v2.19.0：ubuntu-ports 多源竞速。apt 无内置多源并行，此处用 bash /dev/tcp 在容器内
# 并发探测（DNS+TCP:80 一体，与 apt 同一条解析路径），全部后台并发、首个打通者按
# 国内优先级胜出，总耗时 ≤10s；curl 尚未安装也能跑（不依赖任何外部工具）。
APT_MIRROR_CANDIDATES=(
  "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
  "http://mirrors.ustc.edu.cn/ubuntu-ports"
  "http://mirrors.aliyun.com/ubuntu-ports"
  "http://mirrors.cloud.tencent.com/ubuntu-ports"
  "http://mirrors.huaweicloud.com/ubuntu-ports"
  "http://ports.ubuntu.com/ubuntu-ports"
)
APT_MIRROR_KNOWN=(
  "http://ports.ubuntu.com/ubuntu-ports"
  "https://ports.ubuntu.com/ubuntu-ports"
  "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
  "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
  "http://mirrors.ustc.edu.cn/ubuntu-ports"
  "https://mirrors.ustc.edu.cn/ubuntu-ports"
  "http://mirrors.aliyun.com/ubuntu-ports"
  "https://mirrors.aliyun.com/ubuntu-ports"
  "http://mirrors.cloud.tencent.com/ubuntu-ports"
  "https://mirrors.cloud.tencent.com/ubuntu-ports"
  "http://mirrors.huaweicloud.com/ubuntu-ports"
  "https://mirrors.huaweicloud.com/ubuntu-ports"
)
set_apt_mirror(){
  local want="$1" file before after changed=0
  for file in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [ -f "$file" ] || continue
    before=$(cat "$file" 2>/dev/null) || continue
    after="$before"
    for known in "${APT_MIRROR_KNOWN[@]}"; do
      [ "$known" = "$want" ] && continue
      after="${after//$known/$want}"
    done
    if [ "$after" != "$before" ]; then
      printf '%s' "$after" > "$file" 2>/dev/null || continue
      changed=1
    fi
  done
  if [ "$changed" -eq 1 ]; then echo "软件源已切换: $want"; else echo "软件源保持: $want"; fi
  return 0
}
pick_apt_mirror(){
  # 同一会话只竞速一次（CHOSEN_APT_MIRROR 缓存）
  if [ -n "${CHOSEN_APT_MIRROR:-}" ]; then echo "复用已选镜像: $CHOSEN_APT_MIRROR"; return 0; fi
  local race_dir="$TMPDIR/apt-mirror-race"
  rm -rf "$race_dir"; mkdir -p "$race_dir"
  echo "[AstrBot Android] ubuntu-ports 多源竞速（${#APT_MIRROR_CANDIDATES[@]} 源并行探测，≤10s）..."
  local url h
  for url in "${APT_MIRROR_CANDIDATES[@]}"; do
    h="${url#http://}"; h="${h%%/*}"
    (
      if timeout 10 bash -c "exec 3<>/dev/tcp/$h/80" 2>/dev/null; then
        echo "$url" > "$race_dir/$(echo "$h" | tr '.' '_').ok"
      fi
    ) &
  done
  wait
  for url in "${APT_MIRROR_CANDIDATES[@]}"; do
    h="${url#http://}"; h="${h%%/*}"
    if [ -f "$race_dir/$(echo "$h" | tr '.' '_').ok" ]; then
      CHOSEN_APT_MIRROR="$url"
      echo "[AstrBot Android] 竞速胜出镜像: $url"
      set_apt_mirror "$url"
      return 0
    fi
  done
  echo "[AstrBot Android] 警告: 所有镜像 TCP 探测均失败（容器网络不通或 DNS 不可用），保持默认源"
  return 1
}

# v2.18.1：ubuntu-ports 官方源在国内弱网下可用性差；update 失败后切清华镜像重试一次。
# 与 prepare_apt_downloads（清华源 http→https 升级）互补：那个管协议，这个管官方源不可达。
switch_apt_mirror_tuna(){
  local file changed=0
  for file in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [ -f "$file" ] || continue
    if grep -qE 'https?://ports\.ubuntu\.com' "$file"; then
      sed -i -e 's#http://ports\.ubuntu\.com#https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports#g' \
             -e 's#https://ports\.ubuntu\.com#https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports#g' "$file"
      changed=1
    fi
  done
  if [ "$changed" -eq 1 ]; then
    echo "已将 ubuntu-ports 官方源切换为清华镜像"
    return 0
  fi
  return 1
}

install_sudo_curl_git(){
  # v2.18.1：必装清单去掉 sudo —— proot 容器内恒为 root，装 sudo 纯属浪费且是
  # 自动补装链第一环就失败的高频点（包名清单越长，源不可达时死得越早）。
  # v2.20.0：sudo 以垫片形式补回（见 ensure_sudo_shim），上游脚本不再被卡。
  stage 10 "检查基础命令（git/curl）"
  ensure_sudo_shim
  missing=()
  for cmd in git curl; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      missing+=("$cmd")
    fi
  done

  if [ ${#missing[@]} -eq 0 ]; then
    progress_echo "基础命令已安装"
    return 0
  fi

  stage 12 "安装基础命令（清华镜像，约 20MB，约 1 分钟）"

  progress_echo "基础命令缺失: ${missing[*]}, 开始安装..."

  export DEBIAN_FRONTEND=noninteractive
  # v2.19.0：apt 通信硬化提前落盘（超时/重试/IPv4），所有 apt 调用统一受益
  mkdir -p /etc/apt/apt.conf.d
  printf 'Acquire::ForceIPv4 "true";\nAcquire::Retries "3";\nAcquire::http::Timeout "15";\nAcquire::https::Timeout "15";\n' > /etc/apt/apt.conf.d/99bandqq-net 2>/dev/null || true
  apt_opts="-o Acquire::ForceIPv4=true -o Acquire::Retries=3 -o Acquire::http::Timeout=15 -o Acquire::https::Timeout=15"

  # v2.19.0：先多源竞速选镜像再 update（≤10s）；失败再遍历其余可达源逐个重试
  pick_apt_mirror || true
  local ok=0 url h
  if apt-get $apt_opts update; then
    ok=1
  else
    echo "apt-get update 失败（当前源），遍历其余可达镜像重试..."
    for url in "${APT_MIRROR_CANDIDATES[@]}"; do
      [ "$url" = "${CHOSEN_APT_MIRROR:-}" ] && continue
      h="${url#http://}"; h="${h%%/*}"
      [ -f "$TMPDIR/apt-mirror-race/$(echo "$h" | tr '.' '_').ok" ] || continue
      echo "切换镜像重试: $url"
      set_apt_mirror "$url"
      CHOSEN_APT_MIRROR="$url"
      if apt-get $apt_opts update; then ok=1; break; fi
    done
  fi
  if [ "$ok" -ne 1 ]; then
    echo "警告: apt-get update 在所有可达镜像上均失败（DNS/网络异常），继续尝试安装（依赖旧索引）..."
  fi

  if ! apt-get $apt_opts install -y git curl; then
    echo "基础命令安装失败"
    return 1
  fi

  for cmd in git curl; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      echo "基础命令安装后仍缺少: $cmd"
      return 1
    fi
  done

  progress_echo "基础命令安装完成"
}

# ---------- v2.20.0 GitHub 下载多源竞速 + 断流自杀 ----------
# 用户 10-04 日志实证：代理竞速胜出者 ghfast.top 在正式下载 napcat.sh 时 0 字节
# 挂死 35 秒以上（TCP 已连但无数据，--connect-timeout 管不到响应阶段），curl 无
# 低速自杀参数，用户只能手动停止引擎（还引发 read interrupted 噪音）。

GH_PROXY_ARR=()
GH_RACE_DIR=""
GH_RACE_DONE=""

# 按竞速结果生成下载候选序列（全局数组 GHCANDS）：
# 胜出代理优先 → 其余竞速存活代理（按列表优先序）→ 直连兑底
gh_build_candidates(){
  GHCANDS=()
  [ -n "${target_proxy:-}" ] && GHCANDS+=("$target_proxy")
  if [ -n "$GH_RACE_DIR" ] && [ -d "$GH_RACE_DIR" ]; then
    local i p
    for i in $(seq 1 ${#GH_PROXY_ARR[@]}); do
      [ -f "$GH_RACE_DIR/$i.ok" ] || continue
      p="${GH_PROXY_ARR[$((i-1))]}"
      [ "$p" = "${target_proxy:-}" ] || GHCANDS+=("$p")
    done
    [ -f "$GH_RACE_DIR/direct.ok" ] && GHCANDS+=("__DIRECT__")
  fi
  # 最后统一加直连兜底（去重：直连已在存活清单时不再重复尝试）
  local has_direct=0 c
  for c in "${GHCANDS[@]}"; do [ "$c" = "__DIRECT__" ] && has_direct=1; done
  [ "$has_direct" = "0" ] && GHCANDS+=("__DIRECT__")
}

# GitHub 下载统一入口。用法: gh_fetch <输出文件> <完整URL> [透传给 curl 的附加参数...]
# 1) 每次尝试带 --speed-time 20 --speed-limit 512（20 秒均速不足 512B/s 即断，
#    杜绝 0 字节挂死）与 --max-time 900 总超时（调用方可用附加参数覆盖）
# 2) 失败自动遍历其余候选源
# 3) 全部失败重跑一次竞速再试一轮（共两轮）
gh_fetch(){
  local out="$1" url="$2"; shift 2
  network_test || true
  local round p full
  for round in 1 2; do
    gh_build_candidates
    for p in "${GHCANDS[@]}"; do
      full="$url"; [ "$p" != "__DIRECT__" ] && full="${p}/${url}"
      echo "下载源: ${p#__DIRECT__} → ${url##*/}"
      if curl -fL --connect-timeout 10 --speed-time 20 --speed-limit 512 --max-time 900 "$@" "$full" -o "$out"; then
        return 0
      fi
      rm -f "$out" 2>/dev/null
      echo "该源下载失败，自动切换下一个源重试..."
    done
    if [ "$round" = "1" ]; then
      echo "[AstrBot Android] 所有下载源均失败，重新竞速后再试一轮..."
      GH_RACE_DONE=""
      target_proxy=""
      network_test || true
    fi
  done
  echo "[AstrBot Android] 下载失败（全部候选源 × 2 轮）: $url"
  return 1
}

network_test() {
    # v2.20.0：同会话缓存——首个安装步骤竞速一次，后续步骤复用胜出者与存活清单；
    # gh_fetch 全部候选源失败时会清 GH_RACE_DONE 强制重竞速一轮
    if [ -n "$GH_RACE_DONE" ]; then
        echo "复用已选 Github 代理: ${target_proxy:-直连}"
        return 0
    fi
    local timeout=10
    local found=0
    target_proxy=""
    echo "开始网络测试: Github..."

    if [ "$ASTRBOT_GITHUB_PROXY" = "direct" ]; then
        echo "已选择 Github 直连"
        target_proxy=""
        return 0
    fi

    if [ -n "$ASTRBOT_GITHUB_PROXY" ] && [ "$ASTRBOT_GITHUB_PROXY" != "auto" ]; then
        target_proxy="$ASTRBOT_GITHUB_PROXY"
        echo "已选择 Github 代理: $target_proxy"
        return 0
    fi

    proxy_arr=("https://ghfast.top" "https://gh-proxy.com" "https://ghproxy.net" "https://ghproxy.cc" "https://gh.dpik.top" "https://gh.monlor.com" "https://gh.chjina.com" "https://github.boki.moe" "https://gh.jasonzeng.dev" "https://gh.geekertao.top" "https://gh.nxnow.top" "https://down.npee.cn")
    GH_PROXY_ARR=("${proxy_arr[@]}")
    check_url="https://raw.githubusercontent.com/astral-sh/uv/main/README.md"

    # v2.19.0：多任务并行竞速（原串行逐个测 12 个代理最坏 240s；现全部并发发出，
    # 每个 --max-time 12s，首个 200 按列表优先序胜出，总耗时 ≤15s）
    local race_dir="$TMPDIR/gh-proxy-race"
    rm -rf "$race_dir"; mkdir -p "$race_dir"
    GH_RACE_DIR="$race_dir"
    local i=0 proxy code
    for proxy in "${proxy_arr[@]}"; do
        i=$((i+1))
        (
            code=$(curl -fL --connect-timeout 8 --max-time 12 -o /dev/null -s -w "%{http_code}" "${proxy}/${check_url}" 2>/dev/null)
            [ "$code" = "200" ] && echo "$proxy" > "$race_dir/$i.ok"
        ) &
    done
    # 直连 GitHub 同步并发探测（全部代理都挂时兑底判定）
    (
        code=$(curl -fL --connect-timeout 8 --max-time 12 -o /dev/null -s -w "%{http_code}" "${check_url}" 2>/dev/null)
        [ "$code" = "200" ] && echo direct > "$race_dir/direct.ok"
    ) &
    wait
    i=0
    for proxy in "${proxy_arr[@]}"; do
        i=$((i+1))
        if [ -f "$race_dir/$i.ok" ]; then
            found=1
            target_proxy="$proxy"
            echo "将使用Github代理: $target_proxy（多源竞速）"
            break
        fi
    done

    if [ ${found} -eq 0 ]; then
        if [ -f "$race_dir/direct.ok" ]; then
            echo "直连Github成功，将不使用代理"
            target_proxy=""
        else
            echo "警告: 无法找到可用的Github代理且直连不可达。将继续尝试安装，但可能会失败。"
        fi
    fi
    GH_RACE_DONE=1
}

install_uv(){
  INSTALL_DIR="$HOME/.local/bin"
  if [ ! -x "$INSTALL_DIR/uv" ]; then
    stage 20 "安装 uv（Python 包管理器，约 20MB，多源竞速下载）"
    network_test
    APP_NAME="uv"
    APP_VERSION="0.9.9"
    ARCHIVE_FILE="uv-aarch64-unknown-linux-gnu.tar.gz"

    # 检查必要命令
    for cmd in tar mkdir cp chmod mktemp rm curl; do
      if ! command -v $cmd >/dev/null 2>&1; then
        echo "错误：缺少必要命令 $cmd，无法安装 $APP_NAME"
        exit 1
      fi
    done

    # 创建安装目录和临时目录
    mkdir -p $INSTALL_DIR
    TMP_DIR=$(mktemp -d 2>/dev/null || mktemp -t 'uvtmp.XXXXXX')
    if [ -z "$TMP_DIR" ]; then
      echo "创建临时目录失败"
      exit 1
    fi
    mkdir -p "$TMP_DIR"
    TMP_ARCHIVE="$TMP_DIR/$ARCHIVE_FILE"

    # 下载并解压（失败直接退出，不使用return）
    # v2.20.0：改走 gh_fetch（多源竞速 + 断流自杀 + 全候选源重试），
    # 原 curl 无超时无重试，代理抖动时 0 字节挂死
    echo "正在下载 $APP_NAME $APP_VERSION..."
    if ! gh_fetch "$TMP_ARCHIVE" "https://github.com/astral-sh/uv/releases/download/${APP_VERSION}/${ARCHIVE_FILE}"; then
      echo "下载失败"
      rm -rf $TMP_DIR
      exit 1
    fi
    echo "正在解压 $APP_NAME..."
    if ! tar -C "$TMP_DIR" -xf "$TMP_ARCHIVE" --strip-components 1; then
      echo "解压失败"
      rm -rf $TMP_DIR
      exit 1
    fi

    # 安装并授权
    cp $TMP_DIR/uv $TMP_DIR/uvx $INSTALL_DIR/
    chmod +x $INSTALL_DIR/uv $INSTALL_DIR/uvx

    # 自动配置 PATH（写入 Ubuntu root 的 bashrc）
    if ! grep -q "$INSTALL_DIR" $HOME/.bashrc; then
      echo "export PATH=$INSTALL_DIR:\$PATH" >> $HOME/.bashrc
      source $HOME/.bashrc
      echo "已自动配置 $APP_NAME 路径到环境变量"
    fi

    # 清理临时文件
    rm -rf $TMP_DIR
  else
    progress_echo "uv $L_INSTALLED"
  fi
}

linuxqq_ready(){
  command -v qq >/dev/null 2>&1 &&
    dpkg-query -W -f='${Status}\n' linuxqq 2>/dev/null | grep -qx 'install ok installed'
}

prepare_apt_downloads(){
  local file changed=0
  export DEBIAN_FRONTEND=noninteractive
  mkdir -p /etc/apt/apt.conf.d
  printf 'Acquire::ForceIPv4 "true";\nAcquire::Retries "3";\n' > /etc/apt/apt.conf.d/99astrbot-force-ipv4
  for file in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [ -f "$file" ] || continue
    if grep -q 'http://mirrors\.tuna\.tsinghua\.edu\.cn' "$file"; then
      sed -i 's#http://mirrors\.tuna\.tsinghua\.edu\.cn#https://mirrors.tuna.tsinghua.edu.cn#g' "$file"
      changed=1
    fi
  done
  if [ "$changed" -eq 1 ]; then
    echo "已将 Ubuntu 清华软件源切换为 HTTPS，正在刷新索引..."
    apt-get -o Acquire::ForceIPv4=true update
  fi
}

validate_linuxqq_deb(){
  local file="$1" arch package
  [ -s "$file" ] || return 1
  dpkg-deb --info "$file" >/dev/null 2>&1 || return 1
  dpkg-deb --contents "$file" >/dev/null 2>&1 || return 1
  arch=$(dpkg-deb -f "$file" Architecture 2>/dev/null)
  package=$(dpkg-deb -f "$file" Package 2>/dev/null)
  case "$arch" in arm64|aarch64) ;; *) return 1 ;; esac
  [ "$package" = "linuxqq" ]
}

use_local_linuxqq_deb(){
  local dest="$1" candidate
  for candidate in "${ASTRBOT_LINUXQQ_FILE:-}" /sdcard/Download/*.deb /storage/emulated/0/Download/*.deb; do
    [ -n "$candidate" ] && [ -f "$candidate" ] || continue
    validate_linuxqq_deb "$candidate" || continue
    echo "发现本地 LinuxQQ 安装包: $candidate"
    cp -f "$candidate" "$dest"
    return $?
  done
  return 1
}

get_linuxqq_signed_url(){
  local bare_url="$1"
  local api_url="https://im.qq.com/http2rpc/gotrpc/noauth/trpc.qqntv2.urlsign.UrlSign/GetSign"
  local response_file="$TMPDIR/linuxqq-sign.json"
  local normalized_file="$TMPDIR/linuxqq-sign-normalized.json"
  local payload
  LINUXQQ_SIGNED_URL=""
  payload=$(printf '{"url":"%s"}' "$bare_url")
  echo "正在向 LinuxQQ 官网申请临时下载签名..."
  if ! curl -fL --connect-timeout 15 --max-time 30 \
      -A 'Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 Chrome/124 Safari/537.36' \
      -e 'https://im.qq.com/' \
      -H 'Accept: application/json, text/plain, */*' \
      -H 'Content-Type: application/json' \
      -H 'x-oidb: {"uint32_command":"0x9b8e","uint32_service_type":1}' \
      --data "$payload" "$api_url" -o "$response_file"; then
    echo "获取 LinuxQQ 临时下载签名失败"
    return 1
  fi
  sed 's#\\/#/#g; s#\\u0026#\&#g; s#\\u003d#=#g' "$response_file" > "$normalized_file"
  LINUXQQ_SIGNED_URL=$(grep -Eo '"url"[[:space:]]*:[[:space:]]*"[^"]+"' "$normalized_file" |
    head -n 1 | sed -E 's/^"url"[[:space:]]*:[[:space:]]*"//; s/"$//')
case "$LINUXQQ_SIGNED_URL" in
https://*.deb|https://*.deb\?*) return 0 ;;
    *)
      echo "LinuxQQ 签名接口未返回有效下载地址"
      cat "$response_file" 2>/dev/null || true
      LINUXQQ_SIGNED_URL=""
      return 1
      ;;
  esac
}

install_linuxqq(){
  if linuxqq_ready; then
    echo "LinuxQQ 已安装"
    return 0
  fi

  local config_url="${ASTRBOT_LINUXQQ_CONFIG_URL:-https://cdn-go.cn/qq-web/im.qq.com_new/latest/rainbow/linuxConfig.js}"
  local config_file="$TMPDIR/linuxqq-config.js"
  local normalized_config="$TMPDIR/linuxqq-config-normalized.js"
  local qq_deb="$HOME/QQ.deb"
  local qq_deb_part="${qq_deb}.part"
  local qq_url="${ASTRBOT_LINUXQQ_URL:-}"
  local package_arch package_name sound_package download_url

echo "[AstrBot Android] LinuxQQ 修复流程 v9"
  stage 35 "下载 LinuxQQ（约 200MB，约 1~3 分钟，中断自动重试）"
  progress_echo "LinuxQQ 安装中"
  rm -f "$config_file" "$normalized_config" "$qq_deb_part"

  if [ -z "$qq_url" ]; then
    echo "正在读取 LinuxQQ 官方发布配置..."
    if ! curl -fL --connect-timeout 15 --max-time 60 "$config_url" -o "$config_file"; then
      echo "获取 LinuxQQ 官方发布配置失败: $config_url"
      return 1
    fi
    sed 's#\\/#/#g' "$config_file" > "$normalized_config"
    qq_url=$(grep -Eo "(https?:)?//[^\"'[:space:]]+" "$normalized_config" |
      grep -Ei '(arm64|aarch64)[^[:space:]]*\.deb([?#][^[:space:]]*)?' |
      head -n 1)
    if [ -z "$qq_url" ]; then
      echo "官方发布配置中未找到 ARM64 LinuxQQ deb 下载地址"
      echo "可临时通过 ASTRBOT_LINUXQQ_URL 指定可信的 ARM64 deb 地址后重试"
      return 1
    fi
    case "$qq_url" in //*) qq_url="https:$qq_url" ;; esac
  fi

  if validate_linuxqq_deb "$qq_deb"; then
    echo "复用上次已下载并校验通过的 LinuxQQ 安装包"
  else
    if [ -f "$qq_deb" ]; then echo "发现不完整的 LinuxQQ 缓存，已清理并重新下载"; fi
    rm -f "$qq_deb"
echo "正在下载 LinuxQQ ARM64 安装包..."
# v2.25.0：断点续传 + 3 次重试 + max-time 600→1800。用户 10-04 第六份日志定案：
# 官网直链 403 → 签名链下载成功但 ~200KB/s，197MB 需 12~17 分钟，600 秒上限必超时
# （日志 21:13:48 起下到 21:23:24 = 576s 整，正是 max-time 到期），下次重试又从 0 开始。
# 修法：.part 保留 + curl -C - 续传，中断 10 秒后自动续（最多 3 次）；慢速但稳定的
# 链路不再被判死（speed-time 20→30），30 分钟上限覆盖 100KB/s 级慢速网络。
dl_linuxqq_deb(){
  # $1=下载地址 $2=.part 目标；返回 0=下完（是否完整由 validate_linuxqq_deb 判定）
  local url="$1" out="$2" i
  for i in 1 2 3; do
    if curl -fL --connect-timeout 20 --speed-time 30 --speed-limit 512 --max-time 1800 -C - \
        -A 'Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 Chrome/124 Safari/537.36' \
        -e 'https://im.qq.com/' "$url" -o "$out"; then
      return 0
    fi
    [ "$i" = "3" ] && break
    echo "LinuxQQ 下载中断（第 $i 次），10 秒后从断点续传（已下载部分保留）..."
    sleep 10
  done
  return 1
}
download_url="$qq_url"
if ! dl_linuxqq_deb "$download_url" "$qq_deb_part"; then
      rm -f "$qq_deb_part"
  echo "LinuxQQ 官网直链下载失败，尝试申请兼容签名..."
  if get_linuxqq_signed_url "$qq_url" && [ "$LINUXQQ_SIGNED_URL" != "$qq_url" ] &&
      dl_linuxqq_deb "$LINUXQQ_SIGNED_URL" "$qq_deb_part"; then
    :
  else
    rm -f "$qq_deb_part"
    if ! use_local_linuxqq_deb "$qq_deb_part"; then
      echo "LinuxQQ 官网下载安装包失败"
      return 1
    fi
  fi
fi
    if ! validate_linuxqq_deb "$qq_deb_part"; then
      echo "LinuxQQ 下载文件不完整或校验失败"
      rm -f "$qq_deb_part"
      return 1
    fi
    if ! mv -f "$qq_deb_part" "$qq_deb"; then
      echo "保存 LinuxQQ 安装包失败"
      rm -f "$qq_deb_part"
      return 1
    fi
  fi
  if ! validate_linuxqq_deb "$qq_deb"; then
    echo "LinuxQQ 安装包完整性校验失败"
    rm -f "$qq_deb"
    return 1
  fi

  package_arch=$(dpkg-deb -f "$qq_deb" Architecture 2>/dev/null)
  package_name=$(dpkg-deb -f "$qq_deb" Package 2>/dev/null)
  case "$package_arch" in arm64|aarch64) ;; *)
    echo "LinuxQQ 安装包架构不匹配: ${package_arch:-未知} (需要 arm64)"
    rm -f "$qq_deb"
    return 1
  esac
  if [ "$package_name" != "linuxqq" ]; then
    echo "LinuxQQ 安装包名称异常: ${package_name:-未知}"
    rm -f "$qq_deb"
    return 1
  fi

  if apt-cache show libasound2t64 >/dev/null 2>&1; then
    sound_package=libasound2t64
  else
    sound_package=libasound2
  fi
  if ! apt-get install -y libnss3 libgbm1 "$sound_package"; then
    echo "LinuxQQ 运行依赖安装失败"
    return 1
  fi
  if ! apt-get install -y "$qq_deb"; then
    echo "LinuxQQ deb 安装失败"
    return 1
  fi
  if ! linuxqq_ready; then
    echo "LinuxQQ 安装后的命令/包状态验收失败"
    return 1
  fi

  rm -f "$config_file" "$normalized_config" "$qq_deb"
  progress_echo "LinuxQQ 安装完成"
}

patch_napcat_installer(){
  local installer="$1"
  # 上游历史脚本会让 curl 在 HTTP 404 时继续，并固定使用 Ubuntu 24.04 已淘汰的 libasound2。
  sed -i -E 's/curl[[:space:]]+-k[[:space:]]+-L/curl -fL/g; s/curl[[:space:]]+-kL/curl -fL/g' "$installer"
  if apt-cache show libasound2t64 >/dev/null 2>&1; then
    sed -i -E 's/(^|[^[:alnum:]_])libasound2([^[:alnum:]_]|$)/\1libasound2t64\2/g' "$installer"
  fi
  # 本脚本已用官网签名地址安装并验收 LinuxQQ；上游主流程仍会无条件调用旧版 install_linuxqq。
  # 只替换主流程中的独立调用行，保留函数定义，避免再次下载已失效的固定版本。
  sed -i -E 's/^[[:space:]]*install_linuxqq[[:space:]]*$/log "LinuxQQ 已由 AstrBot Android 安装，跳过上游重复安装"/' "$installer"
  if grep -qE '^[[:space:]]*install_linuxqq[[:space:]]*$' "$installer"; then
    echo "修补 NapCat 上游 LinuxQQ 重复安装步骤失败"
    return 1
  fi
  # v2.28.1：上游 install_dependency 无条件 sudo apt-get update（proot 下最慢最易被杀的
  # 一步，10-05 23:29 实证 Killed 于该步）——依赖齐备时跳过。python3 幂等改写（rootfs 自带
  # 3.12）；无 python3 或上游脚本变更时优雅降级保持上游行为。
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$installer" <<'BANDQQ_PY_EOF' || return 1
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8", errors="surrogateescape").read()
if "BandQQ 快速通道" in s:
    sys.exit(0)
old = "sudo apt-get update -y -qq"
if old not in s:
    sys.stderr.write("BandQQ 依赖快速通道补丁未匹配（上游脚本可能已变更），跳过（保持上游行为）\n")
    sys.exit(0)
guard = ("if command -v g++ >/dev/null 2>&1 && command -v jq >/dev/null 2>&1 && command -v zip >/dev/null 2>&1 "
         "&& command -v unzip >/dev/null 2>&1 && command -v Xvfb >/dev/null 2>&1 && command -v screen >/dev/null 2>&1 "
         "&& command -v xauth >/dev/null 2>&1 && command -v ps >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then "
         "log \"依赖已齐备，跳过 apt-get update（BandQQ 快速通道）\"; else sudo apt-get update -y -qq; fi")
s = s.replace(old, guard)
open(p, "w", encoding="utf-8", errors="surrogateescape").write(s)
sys.exit(0)
BANDQQ_PY_EOF
  fi
}

# v2.21.0：预下载 NapCat 压缩包喂给上游脚本。用户 10-04 引擎日志（会话4）实证：
# 上游脚本内部的 NapCat 下载不受我方 gh_fetch 管控——它自测代理选中 ghfast.top，
# 4.5 分钟爬至 1.3% 后 curl(18) Transferred a partial file → exit=1。上游
# download_napcat 检测脚本同目录 NapCat.Shell.zip 存在即跳过内部下载（官方支持
# 路径，日志原话"或者手动下载压缩包并放在脚本同目录下"），故此处用 gh_fetch
# （多源竞速+断流自杀）预取 + unzip -t 自验；失败不阻断，上游自下载路径保留兜底。
ensure_napcat_zip(){
  local zip_file="$HOME/NapCat.Shell.zip"
  if [ -s "$zip_file" ] && unzip -t "$zip_file" >/dev/null 2>&1; then
    echo "检测到有效 NapCat 离线包，跳过预下载"
    return 0
  fi
  rm -f "$zip_file"
  echo "预下载 NapCat v${NAPCAT_SHELL_VERSION} 离线包（多源竞速，20 秒无数据自动换源）..."
  if gh_fetch "$zip_file" "$NAPCAT_SHELL_URL"; then
    if unzip -t "$zip_file" >/dev/null 2>&1; then
      echo "NapCat 离线包预下载完成（上游脚本将跳过其内部下载）"
      return 0
    fi
    echo "预下载包校验失败，删除后交由上游脚本自行下载"
    rm -f "$zip_file"
  else
    echo "预下载未成功（保留上游脚本自下载兜底路径）"
    rm -f "$zip_file"
  fi
  return 0
}

# ---------- v2.22.0 NapCat 启动链 ----------
# 用户 10-04 第三份引擎日志定案：v2.21 全链生效后唯一断点 = NapCat 只装不启——
# 脚本与 APK 均无启动代码，3001/3000 永不监听（"手动探测：本机 NapCat 未就绪"实证）。

NAPCAT_WEBUI_PORT="${NAPCAT_WEBUI_PORT:-5099}"
NAPCAT_WEBUI_TOKEN="${NAPCAT_WEBUI_TOKEN:-bandqq-napcat}"
NAPCAT_DISPLAY="${NAPCAT_DISPLAY:-20}"
# v2.26.0：NapCat 版本钉扎。原 releases/latest 下载源会静默漂移——10-04 20:30 上游
# 发布 v4.18.29 后，容器内安装/重装立即拉到新版，随即出现 [UtilityProcess] Worker
# SIGSEGV×3 崩溃循环（用户 10-05 第七份日志；同症状上游 issue #1626 实证为 bypass
# 反检测原生钩子在部分容器/proot 环境段错误，官方规避 NAPCAT_DISABLE_BYPASS=1）。
# 钉扎本设备实测可用版本；如需升级用环境变量 NAPCAT_SHELL_URL / NAPCAT_SHELL_VERSION 覆盖。
# v2.28.1：区分「显式环境变量钉扎」与「默认基线钉扎」——用户在 NapCat WebUI 主动更新的
# 更高版本会被采纳为新基线（否则钉扎永远对抗用户更新：10-05 23:27 实测 WebUI 更新
# v4.18.30 被整链吞掉：自更新动 launcher → 误判未安装 → 全量 rm -rf → 重装链被杀）；
# 显式环境变量钉扎时采纳机制停用（运维指定版本即以指定为准）。采纳版连崩自动回退基线。
NAPCAT_SHELL_VERSION_DEFAULT="4.18.28"
NAPCAT_SHELL_URL_DEFAULT="https://github.com/NapNeko/NapCatQQ/releases/download/v${NAPCAT_SHELL_VERSION_DEFAULT}/NapCat.Shell.zip"
if [ -n "${NAPCAT_SHELL_VERSION:-}" ]; then
  NAPCAT_PIN_EXPLICIT=1
else
  NAPCAT_PIN_EXPLICIT=0
  NAPCAT_SHELL_VERSION="$NAPCAT_SHELL_VERSION_DEFAULT"
fi
NAPCAT_SHELL_URL="${NAPCAT_SHELL_URL:-https://github.com/NapNeko/NapCatQQ/releases/download/v${NAPCAT_SHELL_VERSION}/NapCat.Shell.zip}"

# v2.28.1：读取已安装 NapCat 版本（package.json 为权威来源——WebUI 自更新与离线包都带）。
# python3 优先（rootfs 自带 3.12），grep 兜底。输出空串=无法识别（不采纳，走原降级重装路径）。
get_napcat_version(){
  local v=""
  if [ -f "$HOME/napcat/package.json" ]; then
    if command -v python3 >/dev/null 2>&1; then
      v=$(python3 -c 'import json,sys
try:
    print(json.load(open(sys.argv[1], encoding="utf-8")).get("version", "") or "")
except Exception:
    pass' "$HOME/napcat/package.json" 2>/dev/null || true)
    fi
    if [ -z "$v" ]; then
      v=$(grep -m1 -oE '"version"[[:space:]]*:[[:space:]]*"[0-9][0-9A-Za-z.+-]*"' "$HOME/napcat/package.json" 2>/dev/null | grep -oE '[0-9][0-9A-Za-z.+-]*' || true)
    fi
  fi
  printf '%s' "$v"
}

# v2.28.1：三段数字版本比较。ver_gt a b → a>b 返回 0（纯 bash，非数字段按 0 处理）
ver_gt(){
  local av="$1" bv="$2" i x y
  local -a a b
  local IFS=.
  set -- $av; a=("$@")
  set -- $bv; b=("$@")
  unset IFS
  for i in 0 1 2; do
    x=${a[i]:-0}; y=${b[i]:-0}
    case "$x" in ''|*[!0-9]*) x=0;; esac
    case "$y" in ''|*[!0-9]*) y=0;; esac
    [ "$x" -gt "$y" ] && return 0
    [ "$x" -lt "$y" ] && return 1
  done
  return 1
}

# v2.28.1：生效钉扎版本 = 显式环境变量 > 已采纳标记（WebUI 更新）> 默认基线
napcat_effective_version(){
  if [ "${NAPCAT_PIN_EXPLICIT:-0}" = "1" ]; then printf '%s' "$NAPCAT_SHELL_VERSION"; return; fi
  local adopted
  adopted=$(cat "$HOME/napcat/.pinned_version" 2>/dev/null || true)
  case "$adopted" in 4.[0-9]*.[0-9]*) printf '%s' "$adopted";; *) printf '%s' "$NAPCAT_SHELL_VERSION";; esac
}

# ---------- v2.27.0 版本钉扎强制执行 ----------
# v2.26.0 的钉扎只管「新下载」——10-04 20:30 漂移窗口内已装上 4.18.29 的容器
# 从未被降级（install_napcat 的 check_napcat_ready 通过即跳过重装；自愈 L3 要
# 连跨三轮 2 分钟巡检才轮到，而第八份日志实证看门狗承诺的重启 5 分钟内从未发生）。
# 用户设备现状（第八份日志）：NapCat 4.18.29 Worker SIGSEGV×3 崩溃循环，端口永不监听。
# 探测规则：版本常量以字面量形式烙在 napcat.mjs（如 "4.18.28"）；文件里找不到
# 钉扎版本号 = 装的是漂移版 → 立即重装钉扎版（napcat_reinstall_pinned 配置保留，
# 离线包优先不需要联网）。不做 semver 解析，避免 minify 代码里其它版本串误匹配。
napcat_pinned_ok(){
  local pin="${1:-$(napcat_effective_version)}"
  [ -f "$HOME/napcat/napcat.mjs" ] && grep -qF "\"${pin}\"" "$HOME/napcat/napcat.mjs" 2>/dev/null
}

napcat_enforce_pinned(){
  # 未安装（无 napcat.mjs）不归本函数管——交给安装链
  [ -f "$HOME/napcat/napcat.mjs" ] || return 0
  local pin; pin=$(napcat_effective_version)
  # v2.28.1：采纳标记跨会话生效——把下载 URL 同步到生效钉扎版（URL 是本进程全局量）
  if [ "${NAPCAT_PIN_EXPLICIT:-0}" != "1" ] && [ "$pin" != "$NAPCAT_SHELL_VERSION" ]; then
    NAPCAT_SHELL_VERSION="$pin"
    NAPCAT_SHELL_URL="https://github.com/NapNeko/NapCatQQ/releases/download/v${pin}/NapCat.Shell.zip"
  fi
  napcat_pinned_ok "$pin" && return 0
  # v2.28.1：WebUI 更新采纳——用户主动更新的更高版本直接采纳为新基线（旧逻辑一律强制
  # 降回钉扎版 = 用户永远更新不了）。显式环境变量钉扎时不采纳；采纳后仍受崩溃自愈保护。
  local installed; installed=$(get_napcat_version)
  if [ "${NAPCAT_PIN_EXPLICIT:-0}" != "1" ] && [ -n "$installed" ] && ver_gt "$installed" "$pin"; then
    echo "$installed" > "$HOME/napcat/.pinned_version"
    NAPCAT_SHELL_VERSION="$installed"
    NAPCAT_SHELL_URL="https://github.com/NapNeko/NapCatQQ/releases/download/v${installed}/NapCat.Shell.zip"
    echo "[AstrBot Android] 检测到 NapCat 已更新到 v${installed}（WebUI 更新生效），采纳为新基线（原基线 v${pin}）；若新版本连续崩溃将自动回退"
    return 0
  fi
  # 退避：重装尝试间隔 ≥15 分钟（离线包缺失且无网络时，看门狗不至每 2 分钟白耗一轮）
  local now last
  now=$(date +%s); last=$(cat "$HOME/napcat/.pin_retry" 2>/dev/null || echo 0)
  case "$last" in ''|*[!0-9]*) last=0;; esac
  if [ $((now - last)) -lt 900 ]; then
    echo "[AstrBot Android] NapCat v${installed:-未知版本} 与钉扎版 v${pin} 不符（15 分钟内已尝试过重装，本轮跳过）"
    return 1
  fi
  echo "$now" > "$HOME/napcat/.pin_retry"
  echo "[AstrBot Android] 检测到 NapCat v${installed:-未知版本}（非钉扎版 v${pin}，漂移版在本环境有 Worker SIGSEGV 崩溃循环）——立即重装钉扎版（配置保留）..."
  if napcat_reinstall_pinned; then
    if napcat_pinned_ok "$pin"; then
      echo "[AstrBot Android] 钉扎版 v${pin} 重装完成，崩溃源已移除"
    else
      echo "[AstrBot Android] 警告：重装后 napcat.mjs 仍非钉扎版（离线包缺失时上游拉到最新版），自愈阶梯兜底"
    fi
    return 0
  fi
  echo "[AstrBot Android] 钉扎版重装未成功（可能无网络），保留现有版本继续；自愈阶梯兜底（L2 禁 bypass 绕开崩溃源）"
  return 1
}

# ---------- v2.28.1 launcher 原位重建 + 构建依赖快速通道 ----------
# NapCat WebUI 自更新会动安装文件——10-05 23:28 实证自更新后 $HOME/launcher.sh 消失，
# 看门狗重启链全灭（bash: launcher.sh: No such file or directory），下次引擎启动又被
# 旧逻辑误判为「未安装」→ rm -rf 整个框架（把用户刚更新好的版本一并删掉）。
ensure_napcat_launcher(){
  # 重启垫片：外部组件（含 WebUI 自更新器重启）可能以 napcat 目录为工作目录调 launcher.sh
  if [ -f "$HOME/launcher.sh" ] && [ ! -f "$HOME/napcat/launcher.sh" ]; then
    printf '#!/bin/bash\nexec bash "$HOME/launcher.sh" "$@"\n' > "$HOME/napcat/launcher.sh" 2>/dev/null \
      && chmod +x "$HOME/napcat/launcher.sh" 2>/dev/null || true
  fi
  [ -f "$HOME/launcher.sh" ] && [ -f "$HOME/libnapcat_launcher.so" ] && return 0
  # 框架不在 → 无从谈起，交给安装链
  [ -f "$HOME/napcat/napcat.mjs" ] || return 1
  command -v qq >/dev/null 2>&1 || return 1
  echo "[AstrBot Android] NapCat launcher 文件缺失（WebUI 自更新后遗症），原位重建（不动框架与配置）..."
  cd "$HOME" || return 1
  if [ ! -f "$HOME/launcher.cpp" ]; then
    gh_fetch "$HOME/launcher.cpp" "https://raw.githubusercontent.com/NapNeko/napcat-linux-launcher/refs/heads/main/launcher.cpp" --max-time 60 \
      || { echo "launcher.cpp 下载失败"; return 1; }
  fi
  if [ ! -f "$HOME/libnapcat_launcher.so" ]; then
    command -v g++ >/dev/null 2>&1 || { echo "g++ 不可用，无法编译 launcher"; return 1; }
    g++ -shared -fPIC "$HOME/launcher.cpp" -o "$HOME/libnapcat_launcher.so" -ldl \
      || { echo "libnapcat_launcher.so 编译失败"; return 1; }
  fi
  if [ ! -f "$HOME/launcher.sh" ]; then
    cat > "$HOME/launcher.sh" <<'BANDQQ_LAUNCHER_EOF'
#!/bin/bash
Xvfb :1 -screen 0 1x1x8 +extension GLX +render > /dev/null 2>&1 &
export DISPLAY=:1
trap "" SIGPIPE
LD_PRELOAD=./libnapcat_launcher.so qq --no-sandbox
BANDQQ_LAUNCHER_EOF
    chmod +x "$HOME/launcher.sh"
  fi
  if [ ! -f "$HOME/napcat/launcher.sh" ]; then
    printf '#!/bin/bash\nexec bash "$HOME/launcher.sh" "$@"\n' > "$HOME/napcat/launcher.sh"
    chmod +x "$HOME/napcat/launcher.sh"
  fi
  echo "[AstrBot Android] NapCat launcher 重建完成"
  return 0
}

# v2.28.1：构建依赖快速通道。上游 install_dependency 无条件 sudo apt-get update -y -qq——
# proot 下最慢最易被杀的一步（10-05 23:29 实证 Killed 于该步，重装链全断，引擎 exit=137）。
# 依赖已在时零 apt 操作；缺失时先不刷源直接装（秒级），失败才回退刷新源。
ensure_napcat_build_deps(){
  local need=() pair pkg cmd
  for pair in zip:zip unzip:unzip jq:jq curl:curl xvfb:Xvfb screen:screen xauth:xauth procps:ps g++:g++; do
    pkg="${pair%%:*}"; cmd="${pair##*:}"
    command -v "$cmd" >/dev/null 2>&1 || need+=("$pkg")
  done
  if [ ${#need[@]} -eq 0 ]; then
    echo "[AstrBot Android] NapCat 构建依赖已齐备（跳过 apt-get update 快速通道）"
    return 0
  fi
  echo "[AstrBot Android] 缺少构建依赖: ${need[*]}——直接安装（不刷新源）..."
  if ! apt-get install -y --no-install-recommends "${need[@]}"; then
    echo "直接安装失败，刷新软件源后重试..."
    apt-get update || return 1
    apt-get install -y --no-install-recommends "${need[@]}" || return 1
  fi
  return 0
}

# onebot11.json：本机服务端（HTTP :3000 / WS :3001，BandQQ 直连）+ 反向 WS 客户端（AstrBot :6199）。
# v2.20/2.21 旧默认只有反向客户端且两个 server 数组为空——即使 NapCat 启动，App 也连不上。
# v2.23.0：账号配置根修——NapCat 登录账号后加载的是按账号的 onebot11_<uin>.json
# （用户实测：通用模板写齐了 3001/3000 但 App 依旧连不上，NapCat 已登录过账号时
# 根本不读模板）。此处扫描全部账号配置，旧空 server 形状自动升级为规范配置。
# 返回值：0=本次有写入/升级（调用方应重启 NapCat 生效），1=无变化。
# v2.25.0：websocketClients 占位符化——AstrBot 机器人开关关闭时展开为 []。
# 用户 10-04 第六份日志：仅 NapCat 模式下 NapCat 反向 WS (ws://localhost:6199/ws)
# 每 5 秒重连报错刷屏（AstrBot 关闭无人监听 :6199），日志面板被错误刷满。
NAPCAT_OB11_CLIENT_ON='
    [
      {
        "name": "AstrBot",
        "enable": true,
        "url": "ws://localhost:__OB_WS_PORT__/ws",
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "reconnectInterval": 5000,
        "token": "kasdkfljsadhlskdjhasdlkfshdlafksjdhf",
        "debug": false,
        "heartInterval": 30000
      }
    ]'

NAPCAT_OB11_BODY='
{
  "network": {
    "httpServers": [
      {
        "name": "bandqq-http",
        "enable": true,
        "host": "127.0.0.1",
        "port": 3000,
        "enableCors": false,
        "enableWebsocket": false,
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "token": "",
        "debug": false
      }
    ],
    "httpClients": [],
    "websocketServers": [
      {
        "name": "bandqq-ws",
        "enable": true,
        "host": "127.0.0.1",
        "port": 3001,
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "token": "",
        "enableForcePushEvent": true,
        "debug": false,
        "heartInterval": 30000
      }
    ],
    "websocketClients": __OB_CLIENTS__
  },
  "musicSignUrl": "",
  "enableLocalFile2Url": false,
  "parseMultMsg": false
}'

upgrade_ob11_file(){
  # $1=目标文件；server 数组为空（旧默认形状）→ 用规范配置覆盖；返回 0=有升级
  # v2.25.0：AstrBot 关闭时 websocketClients 非空也升级（停 :6199 反向 WS 重连刷屏）
  local f="$1"
  [ -f "$f" ] || return 1
  if grep -qE '"httpServers":\s*\[\]' "$f" && grep -qE '"websocketServers":\s*\[\]' "$f"; then
    write_ob11_config "$f"
    echo "已升级账号配置 $(basename "$f")（OneBot HTTP :3000 / WS :3001）"
    return 0
  fi
  if [ "${ASTRBOT_ENABLE:-1}" != "1" ] && ! grep -qE '"websocketClients":\s*\[\s*\]' "$f"; then
    write_ob11_config "$f"
    echo "已按「仅 NapCat 模式」更新 $(basename "$f")（停用 AstrBot 桥 :${ASTRBOT_ONEBOT_WS_PORT:-6199} 反向连接）"
    return 0
  fi
  return 1
}

write_ob11_config(){
  # 展开端口/客户端占位符后写入（NAPCAT_OB11_BODY 为单引号字面量，不展开变量）
  # v2.25.0：__OB_CLIENTS__ 按 ASTRBOT_ENABLE 展开——开=完整 AstrBot 反向 WS 客户端，关=[]
  # 顺序关键：先展开 __OB_CLIENTS__（其内部还嵌着 __OB_WS_PORT__），再展开端口
  local clients
  if [ "${ASTRBOT_ENABLE:-1}" = "1" ]; then
    clients="$NAPCAT_OB11_CLIENT_ON"
  else
    clients='[]'
  fi
  printf '%s\n' "$NAPCAT_OB11_BODY" \
    | sed -e "s|__OB_CLIENTS__|$(printf '%s' "$clients" | sed 's/[&/\\]/\\&/g; $!s/$/\\n/' | tr -d '\n')|" \
          -e "s/__OB_WS_PORT__/${ASTRBOT_ONEBOT_WS_PORT:-6199}/g" \
    > "$1"
}

# ---------- v2.25.0 反检测自动开启 ----------
# 用户需求："自动把所有反检测开启，或者提示用户手动开启"。
# NapCat 4.18.x WebUI「反检测开关配置」对应 napcat.json 的 bypass 块
# （hook/window/module/process/container/js 全 boolean）+ o3HookMode。
# v2.28.0 源码考据（NapCat master bypass.tsx）：六项语义 = hook:hook特征隐藏 /
# window:窗口伪造 / module:加载模块隐藏 / process:进程反检测 / container:容器反检测 /
# js:JS反检测；实现 = 闭源原生模块 napi2native.linux.arm64.node 的
# enableAllBypasses(options)，NAPCAT_DISABLE_BYPASS=1 时整体跳过。
# 关键坑（上游 issue #1805 / NapCat-Docker #134）：WebUI 开启后重启会被重置，
# 配置层写入是唯一可靠路径。读取规则：登录后 per-UIN napcat_<uin>.json 优先，
# 全局 napcat.json 只在无 per-UIN 文件时兜底 → 两边都必须写，缺一不可。
# 工具链（v2.27.0）：JSON 合并首选 python3（rootfs 自带 3.12，node 三连退在真机
# 全军覆没）；本 JS 仅作 python3 缺失时的 node 兜底。幂等：内容一致不写
# （防看门狗死循环重启）。

# ---------- v2.28.0 反检测禁用标记分级复检（封号案例根治） ----------
# 日志取证定案（用户 10-05 上传日志 + 发消息即封）：21:44 配置层全开 → NapCat
# 4.18.28 启动带 bypass → Worker SIGSEGV×3 主进程退出（本环境全开形态必崩，
# 钉扎版也一样）→ 21:45 自愈 NAPCAT_DISABLE_BYPASS=1 才稳定 → 21:46:44 发送
# 1 条群消息 → 21:46:45 即被踢下线（"当前登录已失效"）→ 封禁。即：封号发生时
# bypass 实际处于运行时禁用态（配置层"全开"是假象），且全开形态在本环境从未
# 真正跑起来过——docker/泡泡版基线本就是 bypass 关闭（上游默认全 false）。
# 修法：旧全禁标记一次性分级复检（.bypass_retry_done_v228 防循环）——迁移为
# nohook 形态（仅禁实证崩溃源 hook/write-writev 钩子，其余 5 项恢复开启），
# 绝不自动重试已实证必崩的全开形态；若 nohook 仍崩，自愈阶梯自动回到全禁。
napcat_antidetect_upgrade(){
  local mark="$HOME/napcat/.bypass_disabled" retry="$HOME/napcat/.bypass_retry_done_v228"
  [ -f "$mark" ] || return 0
  [ -f "$retry" ] && return 0
  rm -f "$mark"
  : > "$HOME/napcat/.bypass_nohook"
  : > "$retry"
  echo "[AstrBot Android] 反检测分级复检：旧全禁标记（漂移崩溃时代产物）迁移为 nohook 形态——仅禁用 hook 钩子（实证崩溃源），window/module/process/container/js 五项恢复开启；若仍崩将自动回到全禁（仅复检一次，不循环）"
  return 0
}
NAPCAT_ANTI_DETECT_JS='
const fs = require("fs");
const f = process.argv[2] || process.argv[1];
// v2.28.0：argv[3]==="0" 表示 nohook 形态（hook 单项关闭，其余五项开启；崩溃分级规避）
const hookOff = process.argv[3] === "0";
let c = {};
try { c = JSON.parse(fs.readFileSync(f, "utf8")); } catch (e) { c = {}; }
if (typeof c !== "object" || c === null || Array.isArray(c)) c = {};
const want = { hook: !hookOff, window: true, module: true, process: true, container: true, js: true };
const before = JSON.stringify({ b: c.bypass || null, o: c.o3HookMode, l: c.fileLog });
c.bypass = Object.assign({}, c.bypass, want);
c.o3HookMode = 1;
if (c.fileLog !== true) { c.fileLog = true; }
if (typeof c.fileLogLevel !== "string" || c.fileLogLevel === "off") { c.fileLogLevel = "info"; }
const after = JSON.stringify({ b: c.bypass, o: c.o3HookMode, l: c.fileLog });
if (before === after) { console.log("skip"); process.exit(3); }
fs.writeFileSync(f, JSON.stringify(c, null, 2) + "\n");
console.log("write");
'

# v2.26.0：node 运行时回退链。proot 容器内没有独立 node（AstrBot 装 uv/python，
# QQ deb 只带 Electron 内嵌 node）——v2.25.0 的 node 合并在真机永远落到 WebUI 手动
# 兜底（用户 10-05 第七份日志“反检测自动开启失败（node 不可用）”）。QQ 的 Electron
# 支持 ELECTRON_RUN_AS_NODE=1 退化为纯 Node 运行时（无 GUI 依赖，proot 可用），
# 是容器内唯一稳定的 JS 引擎来源；独立 node/nodejs 存在时优先。
napcat_node_run(){
  # v2.27.0：每次执行限时 25 秒。QQ Electron RUN_AS_NODE 路径在真机环境疑似会
  # 挂起/残留子进程占住管道（第八份日志 18:48-18:53 自愈沉默的头号嫌疑），
  # 限时后超时返回 124 按失败计，看门狗/启动链不再被拖死。
  local runner=""
  if command -v node >/dev/null 2>&1; then runner=node
  elif command -v nodejs >/dev/null 2>&1; then runner=nodejs
  elif [ -x /opt/QQ/qq ]; then runner=qqelectron
  fi
  case "$runner" in
    node|nodejs)
      if command -v timeout >/dev/null 2>&1; then timeout 25 "$runner" "$@"; else "$runner" "$@"; fi
      return $? ;;
    qqelectron)
      if command -v timeout >/dev/null 2>&1; then ELECTRON_RUN_AS_NODE=1 timeout 25 /opt/QQ/qq "$@"; else ELECTRON_RUN_AS_NODE=1 /opt/QQ/qq "$@"; fi
      return $? ;;
  esac
  return 127
}

# v2.27.0：反检测写入免 JS 引擎化。第八份日志（10-05）定案两件事：
# ① rootfs 自带 /usr/bin/python3.12（json 模块），而 node 三连退在真机全军覆没
#   （QQ Electron RUN_AS_NODE 路径也失败）——v2.26.0 的「反检测自动开启失败
#   （无可用 node 运行时）」从此消失；
# ② watchdog 自愈内层调用会先跑 ensure_napcat_antidetect（在 start_napcat 的
#   自愈判定之前），JS/子进程挂起会冻结整条自愈链 → 所有执行路径限时。
# 语义与原 JS 完全对齐：bypass 六项全 true + o3HookMode=1 + fileLog=true +
# fileLogLevel 兜底 info；幂等（无变化 exit 3 不写，防看门狗死循环重启）。
# v2.28.0：$2="0" 表示 nohook 形态——hook 单项写 false（write/writev 钩子是实证
# 崩溃源，issue #1626 崩溃点「prepare write and writev hooks」），其余五项仍全开：
# 反检测裸奔 = 风控封号，能保一项是一项。缺省 $2 非 "0" = 六项全开。
napcat_antidetect_patch_one(){
  # $1=目标 json [$2="0"=hook 关闭]；exit 0=已写入 3=无变化 其他=失败
  local f="$1" hook_off="${2:-}"
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$f" "$hook_off" <<'BANDQQ_AD_PY' 2>/dev/null
import json, sys
f = sys.argv[1]
hook_off = len(sys.argv) > 2 and sys.argv[2] == "0"
try:
    with open(f, encoding="utf-8") as fh:
        c = json.load(fh)
    if not isinstance(c, dict):
        c = {}
except Exception:
    c = {}
b = c.get("bypass") if isinstance(c.get("bypass"), dict) else {}
before = (b, c.get("o3HookMode"), c.get("fileLog"))
b = dict(b)
b.update({"hook": not hook_off, "window": True, "module": True, "process": True, "container": True, "js": True})
c["bypass"] = b
c["o3HookMode"] = 1
c["fileLog"] = True
if not (isinstance(c.get("fileLogLevel"), str) and c["fileLogLevel"] != "off"):
    c["fileLogLevel"] = "info"
after = (c["bypass"], c["o3HookMode"], c["fileLog"])
if before == after:
    print("skip")
    sys.exit(3)
with open(f, "w", encoding="utf-8") as fh:
    json.dump(c, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print("write")
BANDQQ_AD_PY
    return $?
  fi
  # node 兜底（JS 落临时文件调用；argv[2]||argv[1] 双兼容；argv[3]=hook 开关）
  local jsfile="$HOME/napcat/.antidetect.js" out rc
  printf '%s' "$NAPCAT_ANTI_DETECT_JS" > "$jsfile"
  out=$(napcat_node_run "$jsfile" "$f" "$hook_off" 2>/dev/null)
  rc=$?
  [ "$rc" = "0" ] && [ "$out" = "write" ] && return 0
  return "$rc"
}

ensure_napcat_antidetect(){
  # v2.26.0：崩溃自愈已禁用 bypass 时跳过写入（NAPCAT_DISABLE_BYPASS=1 下配置被
  # 短路，写入会造成“反检测已全开”假象）
  if [ -f "$HOME/napcat/.bypass_disabled" ]; then
    echo "[AstrBot Android] 反检测状态：关闭（bypass 钩子崩溃自愈禁用；NAPCAT_DISABLE_BYPASS=1 短路配置，跳过写入）"
    return 1
  fi
  # v2.28.0：nohook 分级形态——hook 单项关闭（write/writev 钩子实证崩溃源），
  # 其余五项保持开启；写入的配置形态与运行时形态严格一致，杜绝"假已开"
  local hook_on=1
  if [ -f "$HOME/napcat/.bypass_nohook" ]; then
    hook_on=0
    echo "[AstrBot Android] 反检测状态：部分（hook 单项崩溃规避，window/module/process/container/js 五项保持开启）"
  else
    echo "[AstrBot Android] 反检测状态：全开（hook/window/module/process/container/js 六项 + o3HookMode）"
  fi
  local f written=0 skipped=0 failed=0 rc
  local files=()
  files+=("$HOME/napcat/config/napcat.json")
  for f in "$HOME"/napcat/config/napcat_*.json; do
    [ -f "$f" ] && files+=("$f")
  done
  for f in "${files[@]}"; do
    # v2.27.0：stdout（write/skip）必须捕获，否则漏进引擎日志刷屏
    out=$(napcat_antidetect_patch_one "$f" "$hook_on" 2>/dev/null)
    rc=$?
    if [ "$rc" = "0" ]; then
      echo "反检测已开启：$(basename "$f")（$([ "$hook_on" = "1" ] && echo 六项 || echo 五项/hook 规避) + o3HookMode）"
      written=$((written+1))
    elif [ "$rc" = "3" ]; then
      skipped=$((skipped+1))
    else
      failed=$((failed+1))
    fi
  done
  if [ "$written" -gt 0 ]; then
    echo "[AstrBot Android] 反检测配置已更新（重启 NapCat 生效，看门狗/启动链会自动重启）"
    return 0
  fi
  if [ "$failed" -gt 0 ]; then
    echo "[AstrBot Android] 反检测自动开启失败（python3/node 均不可用），请手动开启：打开 NapCat WebUI :$NAPCAT_WEBUI_PORT → 反检测开关配置 → 全部开关打开并保存"
    return 1
  fi
  return 1
}

ensure_napcat_configs(){
  mkdir -p "$HOME/napcat/config"
  local ob="$HOME/napcat/config/onebot11.json"
  local changed=0
  if [ ! -f "$ob" ]; then
    echo "写入 onebot11.json（OneBot HTTP :3000 / WS :3001 + AstrBot 桥 :6199）"
    write_ob11_config "$ob"
    changed=1
  else
    upgrade_ob11_file "$ob" && changed=1
  fi
  # v2.23.0：全部已存在账号配置升级（登录过的账号只读 onebot11_<uin>.json）
  local f
  for f in "$HOME"/napcat/config/onebot11_*.json; do
    [ -f "$f" ] || continue
    upgrade_ob11_file "$f" && changed=1
  done
  # webui.json：内容一致跳写（v2.23.0：此前每次强制重写导致调用方无法判断配置变化）
  local wj="$HOME/napcat/config/webui.json"
  local wj_new
  wj_new=$(printf '{\n  "port": %s,\n  "token": "%s",\n  "loginRate": 3\n}' "$NAPCAT_WEBUI_PORT" "$NAPCAT_WEBUI_TOKEN")
  if [ ! -s "$wj" ] || [ "$(cat "$wj" 2>/dev/null)" != "$wj_new" ]; then
    printf '%s\n' "$wj_new" > "$wj"
    echo "webui.json 已写入（WebUI :$NAPCAT_WEBUI_PORT，Token 可在 App 复制）"
    changed=1
  fi
  # v2.25.0：反检测自动全开（napcat.json + 全部 per-uin；幂等跳写不算变更）
  ensure_napcat_antidetect && changed=1
  [ "$changed" = "1" ] && return 0 || return 1
}

# ---------- v2.25.0 二次启动崩溃根治（用户 10-04 第六份日志定案） ----------
# 现象：NapCat 首次启动/登录一切正常，重启后 [UtilityProcess] Worker进程退出码 11
# （SIGSEGV）连续 3 次 → 主进程退出 → :3001/:3000 永不监听 →「启动了一次就使用不了」。
# 根因链：引擎停止链 pkill -9 强杀 QQ → Chromium/NT 数据目录脏（GPU/Shader 缓存、
# Singleton 残锁、Crashpad 计数）→ 二次启动快速登录路径读脏数据段错误。
# 修法三件套：①停止改优雅（TERM 先行给落盘机会）②启动前清理脏状态（保登录态）
# ③看门狗检测崩溃特征自动清理拉起。

# 优雅+彻底地结束残留 NapCat/QQ/Xvfb（TERM → 轮询等待 → KILL 收尾）
napcat_kill_stale(){
  # v2.26.0：补 '/opt/QQ/qq'——崩溃后残留的子进程（renderer/gpu，cmdline 带 --type=，
  # 未必含 --no-sandbox 字样）旧模式杀不到，会一直干扰存活判定
  local pats=('/opt/QQ/qq' 'qq --no-sandbox' 'Xvfb :20' 'bandqq-napcat-tap-marker' 'bash launcher.sh' 'launcher_.*\.sh')
  local p i killed=0
  for p in "${pats[@]}"; do
    pgrep -f "$p" >/dev/null 2>&1 && { pkill -TERM -f "$p" 2>/dev/null || true; killed=1; }
  done
  [ "$killed" = "1" ] || return 0
  # 最多等 8 秒让 Chromium 优雅退出落盘（不能只 sleep 2——脏数据就是强杀来的）
  for i in $(seq 1 16); do
    pgrep -f 'qq --no-sandbox' >/dev/null 2>&1 || break
    sleep 0.5
  done
  for p in "${pats[@]}"; do
    pkill -KILL -f "$p" 2>/dev/null || true
  done
  sleep 1
  return 0
}

# 清理强杀留下的脏状态（只删缓存/残锁/Crash 计数，绝不碰登录态与聊天数据）
napcat_clean_dirty_state(){
  local qdir="$HOME/.config/QQ"
  [ -d "$qdir" ] || return 0
  # Chromium 残锁（进程已死但锁文件在 → 新实例起不来/异常）
  rm -f "$qdir/SingletonLock" "$qdir/SingletonCookie" "$qdir/SingletonSocket" 2>/dev/null || true
  # GPU/着色器缓存（强杀后最易损坏，重建零成本）
  rm -rf "$qdir/GPUCache" "$qdir/GrShaderCache" "$qdir/ShaderCache" \
         "$qdir/GraphiteDawnCache" "$qdir/DawnGraphiteCache" "$qdir/DawnWebGPUCache" \
         "$qdir/Code Cache" 2>/dev/null || true
  # Crashpad 待处理转储与计数字段（脏计数会干扰启动判断）
  rm -rf "$qdir/Crashpad/reports" "$qdir/Crashpad/metrics" "$qdir/Crashpad/settings.dat" 2>/dev/null || true
  return 0
}

# ---------- v2.26.0 崩溃自愈阶梯（用户 10-05 第七份日志定案） ----------
# 第 7 份日志实证三件事：①清理缓存后的全新启动仍 Worker SIGSEGV×3（v2.25.0 的
# 缓存级清理不够——上游 issue #1626 证明同类崩溃由 bypass 反检测原生钩子在
# 容器/proot 环境触发，官方规避 = NAPCAT_DISABLE_BYPASS=1）；②看门狗宣布"5 分钟
# 内重启"却永不发生——主进程退出后残留子进程仍命中 pgrep，start_napcat 误判
# "已在运行，跳过重复启动"；③反检测写入因容器内无 node 永远失败。
# 阶梯：每次检测到崩溃特征计数 +1（端口真正监听即清零）：
#   L1 清缓存残锁原样重启（v2.25.0 行为）→ L2 NAPCAT_DISABLE_BYPASS=1（标记
#   持久化）→ L3 重装钉扎版 NapCat（配置保留）→ L4 深度重置 QQ 数据目录（需重新扫码）
qq_main_alive(){
  # 只有"无 --type= 的主进程"算存活；崩溃后残留的 renderer/gpu 子进程（cmdline
  # 带 --type=）不算——旧 pgrep 判定把它们误当运行中的 NapCat
  local pid
  for pid in $(pgrep -f 'qq --no-sandbox' 2>/dev/null); do
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -qv -- '--type=' && return 0
  done
  return 1
}

napcat_ports_open(){
  (exec 3<>"/dev/tcp/127.0.0.1/3001") 2>/dev/null && return 0
  (exec 3<>"/dev/tcp/127.0.0.1/3000") 2>/dev/null && return 0
  return 1
}

napcat_heal_count(){
  local cnt
  cnt=$(cat "$HOME/napcat/.crash_count" 2>/dev/null || echo 0)
  case "$cnt" in ''|*[!0-9]*) cnt=0;; esac
  echo "$cnt"
}

napcat_mark_healthy(){
  if [ -f "$HOME/napcat/.crash_count" ]; then
    rm -f "$HOME/napcat/.crash_count"
    echo "[AstrBot Android] NapCat 端口已监听，自愈计数清零"
  fi
}

# 自愈 L3：重装 NapCat（钉扎 NAPCAT_SHELL_VERSION，配置目录备份恢复；不动 LinuxQQ）
napcat_reinstall_pinned(){
  echo "[AstrBot Android] 重装 NapCat v${NAPCAT_SHELL_VERSION}（配置保留）..."
  # 自愈标记文件随 napcat 目录一起被删，先摘到 TMPDIR 用后放回
  # v2.28.0：补 .bypass_nohook（分级 bypass 标记）与 .bypass_retry_done_v228（复检防循环标记）
  local m
  for m in .crash_count .bypass_disabled .bypass_nohook .bypass_retry_done_v228 .need_clean .pin_retry .pinned_version; do
    [ -f "$HOME/napcat/$m" ] && cp "$HOME/napcat/$m" "$TMPDIR/bandqq-marker$m"
  done
  if [ -d "$HOME/napcat/config" ]; then
    cp -r "$HOME/napcat/config" "$HOME/napcat_config_backup"
  fi
  rm -rf "$HOME/napcat" "$HOME/napcat.sh" "$HOME/launcher.sh" "$HOME/launcher.cpp" "$HOME/libnapcat_launcher.so"
  cd "$HOME" || return 1
  if ! gh_fetch "$HOME/napcat.sh" "https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/refs/heads/main/install.sh" --max-time 120; then
    echo "下载 napcat.sh 失败"
    return 1
  fi
  chmod +x napcat.sh || return 1
  if ! patch_napcat_installer napcat.sh; then echo "修补 napcat.sh 失败"; return 1; fi
  ensure_sudo_shim
  ensure_napcat_build_deps
  ensure_napcat_zip
  if ! bash napcat.sh; then
    echo "NapCat 上游安装脚本执行失败"
    return 1
  fi
  pkill -f 'qq --no-sandbox' 2>/dev/null || true
  pkill -f 'NapCat' 2>/dev/null || true
  if [ -d "$HOME/napcat_config_backup" ]; then
    mkdir -p "$HOME/napcat/config"
    cp -r "$HOME/napcat_config_backup"/* "$HOME/napcat/config/"
    rm -rf "$HOME/napcat_config_backup"
  fi
  mkdir -p "$HOME/napcat"
  for m in .crash_count .bypass_disabled .bypass_nohook .bypass_retry_done_v228 .need_clean .pin_retry .pinned_version; do
    [ -f "$TMPDIR/bandqq-marker$m" ] && mv "$TMPDIR/bandqq-marker$m" "$HOME/napcat/$m"
  done
  configure_napcat_token_ttl
  ensure_napcat_configs
  return 0
}

# 后台启动 NapCat（Xvfb 虚拟显示 + launcher.sh），幂等；QQ 登录后 :3001/:3000 自动可用
# v2.23.0：①配置升级先行（已运行时若 onebot11 配置有变更，自动重启生效）
# ②launcher 改子 shell 内 cd（原主 shell cd $HOME 污染工作目录——用户 10-04 第四份日志
# 定案：launch_astrbot 先 cd AstrBot 再调 start_napcat，cwd 被改成 /root，
# uv run main.py 报 "Failed to spawn: No such file or directory" → AstrBot 启动失败 exit=1）
start_napcat(){
  if ! check_napcat_ready >/dev/null 2>&1; then
    # v2.28.1：先原位修复再放弃（WebUI 自更新会动 launcher 文件——10-05 23:28 实证
    # 看门狗重启链因 launcher.sh 缺失全灭，用户被迫手停引擎）
    ensure_napcat_launcher || true
    if ! check_napcat_ready >/dev/null 2>&1; then
      echo "[AstrBot Android] NapCat 未安装完整，跳过启动"
      return 1
    fi
    echo "[AstrBot Android] NapCat 缺失文件已自动修复，继续启动"
  fi
  # v2.27.0：版本钉扎强制执行——漂移版容器在下次启动即被降级重装（不再依赖
  # 自愈阶梯三轮升级；用户设备 4.18.29 崩溃循环的根治入口）
  napcat_enforce_pinned || true
  # v2.28.0：旧禁用标记一次性自动复检（封号根治——4.18.29 时代被永久禁用的
  # 反检测在钉扎版本上重新开启，详见函数头注释）
  napcat_antidetect_upgrade || true
  # 配置先行：无论是否已运行都确保 onebot11/webui/反检测 配置为规范形状
  local cfg_changed=0
  ensure_napcat_configs && cfg_changed=1
  # v2.26.0：崩溃特征判定优先于"已在运行"——Worker SIGSEGV 后主进程退出但残留
  # 子进程仍命中 pgrep，旧逻辑误判"运行中"导致看门狗重启永不发生（第 7 份日志实证）
  local heal=0
  if [ -f "$HOME/napcat/.need_clean" ] \
     || { [ -f "$HOME/napcat/napcat-console.log" ] \
          && tail -n 300 "$HOME/napcat/napcat-console.log" 2>/dev/null | grep -q "主进程退出"; }; then
    heal=$(( $(napcat_heal_count) + 1 ))
    echo "$heal" > "$HOME/napcat/.crash_count"
    echo "[AstrBot Android] 检测到 NapCat 崩溃（第 $heal 次），执行自愈第 $heal 级（登录态尽量保留）"
  fi
  if qq_main_alive; then
    if [ "$heal" -gt 0 ]; then
      echo "[AstrBot Android] 终止残留进程并清理崩溃现场..."
      napcat_kill_stale
      napcat_clean_dirty_state
    elif [ "$cfg_changed" = "1" ]; then
      echo "[AstrBot Android] NapCat 配置已升级，优雅重启使配置生效"
      napcat_kill_stale
      napcat_clean_dirty_state
    else
      if napcat_ports_open; then napcat_mark_healthy; fi
      echo "[AstrBot Android] NapCat 已在运行，跳过重复启动"
      return 0
    fi
  else
    if [ "$heal" = "0" ]; then
      # 无崩溃特征也便宜地清一次残锁/陈旧缓存（幂等，代价≈0）
      napcat_clean_dirty_state
    else
      echo "[AstrBot Android] 清理崩溃现场（残留进程/残锁/缓存）..."
      napcat_kill_stale
      napcat_clean_dirty_state
    fi
  fi
  rm -f "$HOME/napcat/.need_clean"
  # v2.28.0：分级 bypass 自愈阶梯。旧 L2 一步禁全部 bypass → 反检测裸奔=风控
  # 封号（用户 10-05 封号案例）。新阶梯：L2 先只禁 hook（write/writev 钩子，
  # issue #1626 崩溃点「prepare write and writev hooks」实证崩溃源，其余五项
  # 保持开启；nohook 仍崩才升级完全禁用。能保一项是一项。
  if [ "$heal" -ge 2 ]; then
    if [ ! -f "$HOME/napcat/.bypass_disabled" ] && [ ! -f "$HOME/napcat/.bypass_nohook" ]; then
      : > "$HOME/napcat/.bypass_nohook"
      echo "[AstrBot Android] 自愈第 2 级：仅禁用 hook 钩子（write/writev 崩溃源规避），其余 5 项反检测保持开启（window/module/process/container/js）"
    elif [ -f "$HOME/napcat/.bypass_nohook" ] && [ ! -f "$HOME/napcat/.bypass_disabled" ]; then
      : > "$HOME/napcat/.bypass_disabled"
      echo "[AstrBot Android] 自愈升级：nohook 形态仍崩溃 → 完全禁用 bypass（NAPCAT_DISABLE_BYPASS=1；恢复：删除 /root/napcat/.bypass_disabled 后重启引擎）"
    fi
    if [ -f "$HOME/napcat/.bypass_disabled" ]; then
      export NAPCAT_DISABLE_BYPASS=1
    fi
  elif [ -f "$HOME/napcat/.bypass_disabled" ]; then
    export NAPCAT_DISABLE_BYPASS=1
  fi
  if [ "$heal" -ge 3 ]; then
    # v2.28.1：采纳版崩溃回退——WebUI 更新的新版本若连崩到 L3，重装回已知可用基线
    # （采纳标记清除 + 离线包删除，确保 ensure_napcat_zip 重新下载基线版而非采纳版）
    if [ "${NAPCAT_PIN_EXPLICIT:-0}" != "1" ] && [ -f "$HOME/napcat/.pinned_version" ]; then
      echo "[AstrBot Android] 采纳版本 v$(cat "$HOME/napcat/.pinned_version" 2>/dev/null) 连续崩溃，回退已知可用基线 v${NAPCAT_SHELL_VERSION_DEFAULT}（如需升级请重新在 WebUI 更新）"
      rm -f "$HOME/napcat/.pinned_version" "$HOME/NapCat.Shell.zip"
      NAPCAT_SHELL_VERSION="$NAPCAT_SHELL_VERSION_DEFAULT"
      NAPCAT_SHELL_URL="$NAPCAT_SHELL_URL_DEFAULT"
    fi
    echo "[AstrBot Android] 自愈第 3 级：重装 NapCat v${NAPCAT_SHELL_VERSION}（钉扎版本，配置保留）"
    napcat_reinstall_pinned || echo "[AstrBot Android] NapCat 重装未成功，继续尝试启动现有文件"
  fi
  if [ "$heal" -ge 4 ]; then
    echo "[AstrBot Android] 自愈第 4 级：深度重置 QQ 数据目录（多次崩溃后登录态可能已损坏；下次启动需重新扫码登录）"
    rm -rf "$HOME/.config/QQ"
    napcat_clean_dirty_state
  fi
  stage 90 "启动 NapCat（首次需扫码登录 QQ，Token 在 App「密码与登录」卡复制）"
  progress_echo "NapCat 启动中"
  pkill -f "Xvfb :$NAPCAT_DISPLAY" 2>/dev/null || true
  rm -f "/tmp/.X$NAPCAT_DISPLAY-lock" "/tmp/.X11-unix/X$NAPCAT_DISPLAY" 2>/dev/null || true
  mkdir -p /tmp/.X11-unix
  chmod 1777 /tmp/.X11-unix 2>/dev/null || true
  Xvfb ":$NAPCAT_DISPLAY" -screen 0 720x720x16 +extension GLX +render > "$HOME/napcat/xvfb.log" 2>&1 &
  local xpid=$! i
  for i in $(seq 1 50); do
    [ -S "/tmp/.X11-unix/X$NAPCAT_DISPLAY" ] && break
    kill -0 "$xpid" 2>/dev/null || break
    sleep 0.1
  done
  if [ ! -S "/tmp/.X11-unix/X$NAPCAT_DISPLAY" ]; then
    echo "[AstrBot Android] Xvfb 启动失败（NapCat 无法启动；详见 /root/napcat/xvfb.log）"
    return 1
  fi
  export DISPLAY=":$NAPCAT_DISPLAY"
  nohup bash -c "cd '$HOME' && exec bash launcher.sh" > "$HOME/napcat/napcat-console.log" 2>&1 &
  rm -f "$HOME/napcat/.need_clean"
  echo "[AstrBot Android] NapCat 已后台拉起（控制台: /root/napcat/napcat-console.log）"
  # v2.24.0：控制台 tail 进引擎日志（用户反馈 log 里看不到 NapCat 的日志）
  napcat_console_tap
  echo "[AstrBot Android] QQ 登录完成后 OneBot :3001/:3000 自动可用；未登录时打开 WebUI :$NAPCAT_WEBUI_PORT 扫码"
  return 0
}

# NapCat 看门狗：进程消失自动重启（容器存活期间常驻；日志在 /root/napcat/watchdog.log）
# v2.24.0：巡检升级为「保活 + 配置升级」双职责。旧逻辑只查进程存活——用户扫码登录后
# NapCat 新生成的 onebot11_<uin>.json 默认无 :3001/:3000 服务端，进程活着端口永不监听，
# 状态卡永远"启动中"且手环发不出消息（用户 10-04 第五份日志链路定案）。
# 每轮无条件走 --step napcat-start：进程死→拉起；进程活但配置被 ensure_napcat_configs
# 升级→自动重启生效；进程活且配置一致→内部"跳过重复启动"no-op。
# v2.25.0：新增崩溃自愈——按字节偏移增量扫描控制台，新出现的"主进程退出"
# （Worker SIGSEGV×3 终局）→ 写 .need_clean 标记 → 本轮 napcat-start 自动清脏拉起。
# 偏移增量扫描保证不重复触发（旧日志里的崩溃痕迹不会被反复消费）；控制台被轮转/重建
# （字节数变小）时自动重置偏移。
start_napcat_watchdog(){
  nohup bash -c '
    last=0
    while true; do
      sleep 120
      log="$HOME/napcat/napcat-console.log"
      cur=$(wc -c < "$log" 2>/dev/null || echo 0)
      case "$cur" in ""|*[!0-9]*) cur=0;; esac
      if [ "$last" -gt 0 ] && [ "$cur" -gt "$last" ]; then
        if tail -c +$((last + 1)) "$log" 2>/dev/null | grep -q "主进程退出"; then
          echo "[$(date "+%m-%d %H:%M:%S")] 检测到 NapCat 内部崩溃（Worker 异常退出），标记自愈清理后拉起" >> "$HOME/napcat/watchdog.log"
          touch "$HOME/napcat/.need_clean"
        fi
      fi
      last=$cur
      # v2.27.0：内层调用整体限时 8 分钟——版本钉扎强制执行可能下载离线包，
      # 子进程挂起不再冻结巡检循环；超时/失败下轮重试
      if command -v timeout >/dev/null 2>&1; then
        timeout 480 bash /root/astrbot-startup.sh --step napcat-start >> "$HOME/napcat/watchdog.log" 2>&1 \
          || echo "[$(date "+%m-%d %H:%M:%S")] napcat-start 巡检超时/失败（限时 480s），下轮重试" >> "$HOME/napcat/watchdog.log"
      else
        bash /root/astrbot-startup.sh --step napcat-start >> "$HOME/napcat/watchdog.log" 2>&1
      fi
      # v2.26.0：端口真正监听才算恢复——自愈计数清零，下次崩溃重新从第 1 级起步
      if (exec 3<>"/dev/tcp/127.0.0.1/3001") 2>/dev/null || (exec 3<>"/dev/tcp/127.0.0.1/3000") 2>/dev/null; then
        if [ -f "$HOME/napcat/.crash_count" ]; then
          rm -f "$HOME/napcat/.crash_count"
          echo "[$(date "+%m-%d %H:%M:%S")] NapCat 端口已监听，自愈计数清零" >> "$HOME/napcat/watchdog.log"
          { echo "[NAPCAT-WD] $(date "+%H:%M:%S") NapCat 已恢复：端口监听，自愈计数清零"; } >&9 2>/dev/null || true
        fi
      else
        # v2.27.0：端口未监听时给引擎管道一行巡检摘要——第八份日志实证：自愈
        # 动作全进 watchdog.log，App 日志面板从崩溃那刻起再无任何动静，用户完全
        # 无从感知自愈是否在推进（可观测性修复）
        { echo "[NAPCAT-WD] $(date "+%H:%M:%S") 巡检：端口未监听（未登录或自愈中；自愈级别 $(cat "$HOME/napcat/.crash_count" 2>/dev/null || echo 0)），详情见 /root/napcat/watchdog.log"; } >&9 2>/dev/null || true
      fi
    done
  ' > /dev/null 2>&1 &
  echo "[AstrBot Android] NapCat 看门狗已启动（2 分钟巡检：进程保活 + 崩溃自愈阶梯 + 版本钉扎强制执行 + 登录后配置自动升级重启）"
}

# v2.24.0：NapCat 控制台 tail（用户反馈「log 没有 napcat 的 log」）。
# start_napcat 把 NapCat 输出重定向进容器内 napcat-console.log，引擎日志从此看不到
# QQ 登录/WS 监听/扫码提示等任何 NapCat 动态。此 tap 把控制台逐行剥离 ANSI 转义、
# 截断 160 字符后加 [NAPCAT] 前缀写回引擎 stdout → 引擎日志/落盘文件/App 日志面板
# 全链可见。生命周期：引擎停止链 pkill -f napcat / 下次 start_napcat 重入 / 引擎进程
# 退出后管道断裂（SIGPIPE）三者任一即收尾；tail -F 跨 NapCat 重启跟随截断重长的同一文件。
napcat_console_tap(){
  pkill -f "bandqq-napcat-tap-marker" 2>/dev/null || true
  nohup bash -c '
    # bandqq-napcat-tap-marker
    log="$HOME/napcat/napcat-console.log"
    for i in $(seq 1 40); do [ -f "$log" ] && break; sleep 0.5; done
    [ -f "$log" ] || exit 0
    tail -n +1 -F "$log" 2>/dev/null | while IFS= read -r line; do
      clean=$(printf "%s" "$line" | tr -d "\000" | sed -e "s/\x1b\[[0-9;]*[A-Za-z]//g" -e "s/\r//g" | cut -c1-160)
      [ -n "$clean" ] && echo "[NAPCAT] $clean" >&9
    done
  ' &
}

# v2.24.0：等待 NapCat 端口真正监听（修「NapCat 启动完成还在显示启动」的窗口期）。
# start_napcat 的"已启动"只表示进程拉起，QQ 引导完成前 3001/3000 不会监听（真机实测
# 2~3 分钟），期间 App 检测按钮只会得到"未就绪"。此函数探测到端口即输出 stage 100
# 终态标记（仅 NapCat 模式前台调用）；bg=1 时后台运行只打日志（AstrBot 模式，避免与
# AstrBot 启动进度标记互相覆盖）。
wait_napcat_ports(){
  local mode="${1:-fg}" i ok="" heals=0
  (
    for i in $(seq 1 60); do
      if (exec 3<>"/dev/tcp/127.0.0.1/3001") 2>/dev/null; then ok=1; break; fi
      if (exec 3<>"/dev/tcp/127.0.0.1/3000") 2>/dev/null; then ok=1; break; fi
      # v2.27.0：立即自愈——第八份日志实证「看门狗约 2 分钟内重启」是空头支票
      # （内层巡检链被 JS/子进程挂起冻结，用户等了 5 分钟毫无动静）。检测到
      # 「主进程退出」特征直接同步调 napcat-start（自愈阶梯按 .crash_count
      # 自动升级 L1→L2→L3），不再等待看门狗；每个等待期最多 3 轮防死循环。
      if [ "$heals" -lt 3 ] \
         && tail -n 100 "$HOME/napcat/napcat-console.log" 2>/dev/null | grep -q "主进程退出"; then
        heals=$((heals+1))
        echo "[AstrBot Android] 检测到 NapCat 崩溃（主进程退出）——立即执行自愈重启（第 $heals 轮；阶梯级别按崩溃计数自动升级）"
        { bash "${BASH_SOURCE[0]:-/root/astrbot-startup.sh}" --step napcat-start >> "$HOME/napcat/watchdog.log" 2>&1; } || true
        echo "[AstrBot Android] 自愈第 $heals 轮已执行（详见 /root/napcat/watchdog.log；若已进入禁 bypass 态，反检测将保持关闭直至手动恢复）"
        sleep 10
        continue
      fi
      [ "$((i % 6))" = "0" ] && echo "[AstrBot Android] NapCat 启动中…（已等待 $((i * 5)) 秒，QQ 首次引导较慢）"
      sleep 5
    done
    if [ -n "$ok" ]; then
      napcat_mark_healthy
      if [ "$mode" = "fg" ]; then
        stage 100 "NapCat 已就绪（:3001/:3000 可连，手环消息链路可用）"
      else
        echo "[AstrBot Android] NapCat 已就绪（:3001/:3000 可连）"
      fi
    else
      echo "[AstrBot Android] NapCat 仍在启动（QQ 未登录时端口不会开启；可开 WebUI :$NAPCAT_WEBUI_PORT 扫码，App 会持续自动检测）"
    fi
  ) &
  local pid=$!
  [ "$mode" = "fg" ] && wait "$pid" 2>/dev/null
  return 0
}

install_napcat(){
  # 检查是否完整安装。旧版本可能留下 launcher.sh，但 LinuxQQ 或依赖包安装失败。
  if ! check_napcat_ready >/dev/null 2>&1; then
    # v2.28.1：缺失项进日志（旧逻辑 >/dev/null 君掉失败原因，10-05 23:29 案例无从诊断）
    echo "[AstrBot Android] NapCat 就绪检查未通过，缺失项："
    check_napcat_ready 2>&1 | sed 's/^/  /'
    local repaired=0
    # v2.28.1：原位修复优先——框架(napcat.mjs)与 LinuxQQ 完好时只补缺失件，
    # 绝不 rm -rf 整个安装（旧逻辑把 WebUI 自更新后仅缺 launcher 的完好安装整删）
    if [ -f "$HOME/napcat/napcat.mjs" ] && dpkg -s linuxqq 2>/dev/null | grep -q "Status: install ok installed"; then
      if ensure_napcat_launcher && check_napcat_ready >/dev/null 2>&1; then
        echo "[AstrBot Android] NapCat 已原位修复（保留现有版本与全部配置，跳过重装）"
        repaired=1
      else
        echo "[AstrBot Android] 原位修复未成功，转入全量重装"
      fi
    else
      echo "[AstrBot Android] NapCat 框架或 LinuxQQ 缺失，走全量重装"
    fi
    if [ "$repaired" != "1" ]; then
    stage 45 "安装 NapCat（QQ↔OneBot 协议桥，含依赖，约 2~5 分钟）"
    progress_echo "Napcat $L_NOT_INSTALLED，$L_INSTALLING..."

    if ! prepare_apt_downloads; then
      echo "Ubuntu 软件源刷新失败，请检查上方 apt 输出"
      exit 1
    fi
    
    if ! apt --fix-broken install -y; then
      echo "apt 修复依赖失败，继续执行安装并在结束时做完整校验"
    fi

    # 先修复最容易失效的 LinuxQQ 下载。失败时保留现有 NapCat 文件与配置，便于直接重试。
    if ! install_linuxqq; then
      echo "LinuxQQ 安装失败，NapCat 安装已中止"
      exit 1
    fi

    # 备份配置目录（如果存在）
    if [ -d "$HOME/napcat/config" ]; then
      echo "备份 NapCat 配置目录..."
      cp -r "$HOME/napcat/config" "$HOME/napcat_config_backup"
    fi
    
    rm -rf "$HOME/napcat" "$HOME/napcat.sh" "$HOME/launcher.sh" "$HOME/launcher.cpp" "$HOME/libnapcat_launcher.so"
    cd $HOME
    echo "Napcat $L_NOT_INSTALLED，$L_INSTALLING..."
    # v2.19.0：napcat.sh 走 network_test 竞速代理（原直连 raw.githubusercontent.com
    # 在被墙网络必死；此时代码链已保证 curl 可用）。
    # v2.20.0：改走 gh_fetch（断流自杀 + 全候选源重试）——用户 10-04 日志实证：
    # 胜出代理 0 字节挂死 35s+，原 curl 参数只能等到 max-time，用户被迫手停引擎。
    if ! gh_fetch "$HOME/napcat.sh" "https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/refs/heads/main/install.sh" --max-time 120; then
      echo "下载 napcat.sh 失败"
      exit 1
    fi
    if ! chmod +x napcat.sh; then
      echo "设置 napcat.sh 执行权限失败"
      exit 1
    fi
    if ! patch_napcat_installer napcat.sh; then echo "修补 napcat.sh 失败"; exit 1; fi
    # v2.20.0：上游脚本硬性检查 sudo（10-04 日志实证 "sudo不存在" → exit 1），
    # proot 恒 root，垫片透传即可（幂等，前面装过这里秒过）
    ensure_sudo_shim
    # v2.28.1：构建依赖快速通道——依赖已在时上游的 apt-get update 被补丁跳过（防被杀）
    ensure_napcat_build_deps
    # v2.21.0：预取 NapCat 离线包，让上游脚本跳过其不受控的内部下载
    ensure_napcat_zip
    if ! bash napcat.sh; then
      echo "NapCat 上游安装脚本执行失败"
      exit 1
    fi

    # 环境管理的 NapCat 步骤只做安装，不做登录启动。
    # 有些上游安装脚本会在安装结束后顺手启动 QQ/NapCat，这里统一收掉，
    # 后续从主页账号卡片手动启动。
    pkill -f 'qq --no-sandbox' 2>/dev/null || true
    pkill -f 'NapCat' 2>/dev/null || true
    pkill -f '/root/launcher_.*\.sh' 2>/dev/null || true
    pkill -f '/root/launcher\.sh' 2>/dev/null || true
    pkill -f 'napcat_instances/.*/launcher' 2>/dev/null || true
    
    # 恢复配置目录
    if [ -d "$HOME/napcat_config_backup" ]; then
      echo "恢复 NapCat 配置目录..."
      mkdir -p "$HOME/napcat/config"
      cp -r "$HOME/napcat_config_backup"/* "$HOME/napcat/config/"
      rm -rf "$HOME/napcat_config_backup"
    fi
    fi  # v2.28.1：repaired 守卫结束（原位修复成功时跳过整个全量重装块）
    
  # v2.22.0：配置写入/修补统一走 ensure_napcat_configs（幂等，装与未装路径都执行）
fi
  # v2.27.0：版本钉扎强制执行——上游安装器在离线包缺失时会拉最新版（漂移版本
  # 在本环境崩溃循环），装完立即校验并对非钉扎版重装
  if ! napcat_enforce_pinned; then
    echo "[AstrBot Android] NapCat 版本与钉扎版不符（下次启动自动重试重装）"
  fi
  configure_napcat_token_ttl
  ensure_napcat_configs
  if ! check_napcat_ready; then
    echo "NapCat 安装不完整，请查看上方 apt/dpkg/curl 错误后重试"
    exit 1
  fi
  stage 55 "NapCat 安装完成"
  progress_echo "Napcat $L_INSTALLED"
}

configure_napcat_token_ttl(){
  if [ -f "$HOME/napcat/napcat.mjs" ]; then
    sed -i -E "s#static MAX_CREDENTIAL_VALID_SECONDS = [0-9]+#static MAX_CREDENTIAL_VALID_SECONDS = 604800#g" "$HOME/napcat/napcat.mjs"
    sed -i -E 's#Rp\.set\(`revoked:\$\{r\}`, !0, [0-9]+\)#Rp.set(`revoked:${r}`, !0, 604800)#g' "$HOME/napcat/napcat.mjs"
  fi
}

check_napcat_ready(){
  local missing=0

  if ! command -v qq >/dev/null 2>&1; then
    echo "[AstrBot Android] missing NapCat dependency: qq"
    missing=1
  fi

  if ! command -v Xvfb >/dev/null 2>&1; then
    echo "[AstrBot Android] missing NapCat dependency: Xvfb"
    missing=1
  fi

  if ! dpkg -s linuxqq 2>/dev/null | grep -q "Status: install ok installed"; then
    echo "[AstrBot Android] missing or broken NapCat dependency: linuxqq"
    missing=1
  fi

  if ! dpkg -s libnss3 2>/dev/null | grep -q "Status: install ok installed"; then
    echo "[AstrBot Android] missing or broken NapCat dependency: libnss3"
    missing=1
  fi

  if ! dpkg -s libnspr4 2>/dev/null | grep -q "Status: install ok installed"; then
    echo "[AstrBot Android] missing or broken NapCat dependency: libnspr4"
    missing=1
  fi

  if ! { dpkg -s libasound2t64 2>/dev/null || dpkg -s libasound2 2>/dev/null; } | grep -q "Status: install ok installed"; then
    echo "[AstrBot Android] missing or broken NapCat dependency: libasound2/libasound2t64"
    missing=1
  fi

  if [ ! -f "$HOME/launcher.sh" ]; then
    echo "[AstrBot Android] missing NapCat launcher: $HOME/launcher.sh"
    missing=1
  fi

  if [ ! -f "$HOME/libnapcat_launcher.so" ]; then
    echo "[AstrBot Android] missing NapCat launcher library: $HOME/libnapcat_launcher.so"
    missing=1
  fi

  if [ ! -d "$HOME/napcat" ]; then
    echo "[AstrBot Android] missing NapCat directory: $HOME/napcat"
    missing=1
  fi

  if [ "$missing" -ne 0 ]; then
    return 1
  fi

  return 0
}

check_astrbot_ready(){
  local missing=0

  if ! command -v curl >/dev/null 2>&1; then
    echo "[AstrBot Android] missing dependency: curl"
    missing=1
  fi

  if ! command -v git >/dev/null 2>&1; then
    echo "[AstrBot Android] missing dependency: git"
    missing=1
  fi

  if [ ! -x "$HOME/.local/bin/uv" ]; then
    echo "[AstrBot Android] missing dependency: uv"
    missing=1
  fi

  if [ ! -d "$HOME/AstrBot" ]; then
    echo "[AstrBot Android] missing runtime: AstrBot"
    missing=1
  fi

  if [ ! -d "$HOME/AstrBot/.venv" ]; then
    echo "[AstrBot Android] missing runtime: AstrBot .venv"
    missing=1
  elif ! cd "$HOME/AstrBot" || ! "$HOME/.local/bin/uv" run --no-sync python -c "import aiohttp" >/dev/null 2>&1; then
    echo "[AstrBot Android] missing dependency: aiohttp"
    missing=1
  fi

  # v2.17.0：诊断职责单一化（只报缺失清单）。__ASTRBOT_MANUAL_ENV_REQUIRED__ 标记
  # 移至 launch_astrbot 自动补装仍失败后输出，避免首次启动自愈前误导用户。
  if [ "$missing" -ne 0 ]; then
    return 1
  fi

  return 0
}

install_astrbot(){
  local INSTALL_DIR="$HOME/AstrBot"
  local CLONE_TEMP_DIR="$HOME/AstrBot_tmp"
  local BACKUP_DIR="/sdcard/Download/AstrBotBubble"

  rm -rf "$CLONE_TEMP_DIR"

  killall uv 2>/dev/null

  if [ -d "$INSTALL_DIR" ] && { [ ! -f "$INSTALL_DIR/pyproject.toml" ] || [ ! -f "$INSTALL_DIR/main.py" ]; }; then
    echo "AstrBot 安装目录不完整，准备重新安装..."
    rm -rf "$HOME/AstrBot_data_reinstall_backup"
    if [ -d "$INSTALL_DIR/data" ]; then
      cp -r "$INSTALL_DIR/data" "$HOME/AstrBot_data_reinstall_backup"
    fi
    rm -rf "$INSTALL_DIR"
  fi

  # 检查是否已安装
  if [ ! -d "$INSTALL_DIR" ]; then
    cd $HOME
    stage 60 "下载 AstrBot 本体（拉取最新正式版，约 30MB）"
    progress_echo "AstrBot $L_NOT_INSTALLED，$L_INSTALLING..."

    # 克隆仓库（失败直接退出）
    echo "正在获取 AstrBot 最新版本..."

    # 判断是否使用自定义 git clone 命令
    if [ -n "$CUSTOM_GIT_CLONE" ]; then
      echo "使用自定义 Git Clone 命令..."
      echo "执行: $CUSTOM_GIT_CLONE"
      # 执行自定义命令，假设克隆到当前目录，然后重命名为临时目录
      if ! eval "$CUSTOM_GIT_CLONE"; then
        echo "自定义 Git Clone 命令执行失败"
        exit 1
      fi
      # 查找克隆后的目录（通常是 AstrBot）
      if [ -d "AstrBot" ]; then
        mv "AstrBot" "$CLONE_TEMP_DIR"
      else
        echo "错误: 自定义 git clone 后未找到 AstrBot 目录"
        exit 1
      fi
    else
      network_test || true
      gh_build_candidates
      # v2.20.0：竞速胜出代理优先，失败自动遍历其余存活代理与直连（原只试单一胜出者，
      # 代理抖动时 ls-remote/clone 直接失败退出）
      local cand base cloned=0
      for cand in "${GHCANDS[@]}"; do
        base=""; [ "$cand" != "__DIRECT__" ] && base="$cand"
        # 使用默认逻辑：获取最新的正式版 tag，跳过 beta/alpha/rc/dev/pre 等预发布版本
        LATEST_TAG=$(git ls-remote --tags --sort='-v:refname' ${base:+${base}/}https://github.com/AstrBotDevs/AstrBot.git 2>/dev/null | awk -F'/' '{print $3}' | sed 's/\^{}//g' | grep -E '^v?[0-9]+(\.[0-9]+){1,2}$' | head -n 1)

        if [ -z "$LATEST_TAG" ]; then
          echo "警告: 无法获取最新 tag（源: ${base:-直连}），使用 master 分支"
          CLONE_BRANCH="master"
        else
          echo "最新正式版: $LATEST_TAG（源: ${base:-直连}）"
          CLONE_BRANCH="$LATEST_TAG"
        fi

        # 克隆到临时目录
        echo "正在克隆 AstrBot 仓库，分支/标签: $CLONE_BRANCH..."
        if git clone --depth=1 --branch "$CLONE_BRANCH" ${base:+${base}/}https://github.com/AstrBotDevs/AstrBot.git "$CLONE_TEMP_DIR"; then
          cloned=1
          break
        fi
        rm -rf "$CLONE_TEMP_DIR"  # 清理失败的临时目录
        echo "该源克隆失败，自动切换下一个源重试..."
      done
      if [ "$cloned" != "1" ]; then
        echo "克隆 AstrBot 仓库失败（全部候选源）"
        rm -rf "$CLONE_TEMP_DIR"
        exit 1
      fi
    fi

    # 原子性重命名
    mv "$CLONE_TEMP_DIR" "$INSTALL_DIR"

  else
    progress_echo "AstrBot $L_INSTALLED"
  fi

  progress_echo "AstrBot 初始化中"
  cd "$INSTALL_DIR"

  if [ ! -d "$INSTALL_DIR/data" ]; then

    echo "检测到 data 目录不存在，初始化数据目录..."
    mkdir "$INSTALL_DIR/data"

    if [ -d "$HOME/AstrBot_data_reinstall_backup" ]; then
      echo "恢复重装前 AstrBot 数据..."
      rm -rf "$INSTALL_DIR/data"
      mv "$HOME/AstrBot_data_reinstall_backup" "$INSTALL_DIR/data"
      REINSTALL_PLUGINS_FLAG=1
    else
    
    # 检查并恢复最新备份
    if [ -d "$BACKUP_DIR" ]; then
      echo "扫描备份目录: $BACKUP_DIR"
      LATEST_BACKUP=$(ls -t "$BACKUP_DIR"/AstrBotBubble-backup-*.tar.gz 2>/dev/null | head -n 1)
      
      if [ -n "$LATEST_BACKUP" ]; then
        echo "找到备份文件: $LATEST_BACKUP"
        echo "恢复 AstrBot 数据备份..."
        
        # 解压备份到 data 目录
        if tar -xzf "$LATEST_BACKUP" -C "$INSTALL_DIR"; then
          echo "备份恢复成功"
          echo "AstrBot 数据已从备份恢复"
          REINSTALL_PLUGINS_FLAG=1  # 备份恢复成功，需要重装插件依赖

        else
          echo "备份恢复失败，使用默认配置"
          cp "$HOME/cmd_config.json" "$INSTALL_DIR/data"
          chmod +w "$INSTALL_DIR/data/cmd_config.json"
        fi
      else
        echo "未找到备份文件，使用默认配置"
        cp "$HOME/cmd_config.json" "$INSTALL_DIR/data"
        chmod +w "$INSTALL_DIR/data/cmd_config.json"
        echo "拷贝 cmd_config.json 默认配置文件"
      fi
    else
      echo "备份目录不存在，使用默认配置"
      cp "$HOME/cmd_config.json" "$INSTALL_DIR/data"
      chmod +w "$INSTALL_DIR/data/cmd_config.json"
      echo "拷贝 cmd_config.json 默认配置文件"
    fi
    fi
    
    rm -rf "$INSTALL_DIR/.venv"

  fi

  if [ ! -d "$INSTALL_DIR/.venv" ] || ! $HOME/.local/bin/uv run --no-sync python -c "import aiohttp" >/dev/null 2>&1; then

    # 使用 uv sync 同步依赖
    stage 75 "安装 AstrBot Python 依赖（首次约 3~8 分钟，走清华 PyPI 镜像）"
    echo "同步 AstrBot 依赖..."
    if ! $HOME/.local/bin/uv sync; then
      # v2.19.0：Python 构建镜像单点兜底——默认 ghfast.top 挂掉时换代理重试一次
      echo "依赖同步失败，切换 Python 构建镜像后重试一次..."
      export UV_PYTHON_INSTALL_MIRROR="https://gh-proxy.com/https://github.com/astral-sh/python-build-standalone/releases/download"
      if ! $HOME/.local/bin/uv sync; then
        echo "依赖同步失败"
        exit 1
      fi
    fi

    REINSTALL_PLUGINS_FLAG=1  # .venv 不存在，需要重装插件依赖
  fi

  # 检查是否需要重装插件依赖（根据标记）
  if [ "$REINSTALL_PLUGINS_FLAG" -eq 1 ]; then

    echo "检测到重装插件依赖标记，开始重装..."
    # 清除标记（将脚本中的标记重置为0）
    sed -i 's/^REINSTALL_PLUGINS_FLAG=1$/REINSTALL_PLUGINS_FLAG=0/' /root/astrbot-startup.sh

    # 扫描所有插件的 requirements.txt 并安装到 venv
    echo "扫描插件依赖..."
    if [ -d "$INSTALL_DIR/data/plugins" ]; then
      for plugin_dir in "$INSTALL_DIR/data/plugins"/*; do
        if [ -d "$plugin_dir" ] && [ -f "$plugin_dir/requirements.txt" ]; then
          echo "发现插件依赖: $plugin_dir/requirements.txt"
          if [ -f "$HOME/.local/bin/uv" ]; then
            cd "$INSTALL_DIR"
            echo "安装插件依赖: $(basename "$plugin_dir")..."
            $HOME/.local/bin/uv pip install -r "$plugin_dir/requirements.txt" 2>/dev/null || echo "警告: 插件依赖安装失败，将在启动时重试"
          fi
        fi
      done
    fi
  fi

  progress_echo "AstrBot 安装完成"
}

launch_astrbot(){
  local INSTALL_DIR="$HOME/AstrBot"

  # v2.22.0：「启动 AstrBot 机器人」开关（App 内 Switch → EngineManager 环境变量 ASTRBOT_ENABLE）。
  # 关闭时仅装/启 NapCat（手环 QQ 直连所需），不下载/不启动 AstrBot；容器以看门狗循环常驻。
  if [ "${ASTRBOT_ENABLE:-1}" != "1" ]; then
    stage 5 "仅 NapCat 模式（AstrBot 机器人开关已关闭）"
    if ! check_napcat_ready >/dev/null 2>&1; then
      install_sudo_curl_git || { echo "自动补装失败：基础命令安装异常（检查网络/存储）"; return 1; }
      install_napcat || { echo "自动补装失败：NapCat 安装异常"; return 1; }
    fi
    ensure_napcat_configs
    start_napcat || echo "[AstrBot Android] NapCat 启动失败（可重启引擎重试）"
    start_napcat_watchdog
    # v2.24.0：等待端口真正监听（修「启动完成还显示启动中」的窗口期）——
    # QQ 引导完成前 3001/3000 不监听（真机实测 2~3 分钟），等到即出 stage 100 终态
    stage 92 "NapCat 启动中（QQ 引导完成后 :3001/:3000 生效；AstrBot 已按开关关闭）"
    wait_napcat_ports fg
    progress_echo "NapCat 运行中（仅 NapCat 模式）"
    while true; do sleep 3600; done
  fi

  if ! check_astrbot_ready; then
    # v2.17.0 启动自愈：BandQQ 无上游「Environment Manager」UI，环境不完整时
    # 直接在启动流程内按依赖顺序补装（幂等，已装步骤秒过）：
    # 基础命令(curl/git) → uv → NapCat(LinuxQQ) → AstrBot(clone+uv sync)。
    # 首次需联网下载数百 MB（约 5~20 分钟，取决于网络）；progress 实时进引擎日志。
    stage 5 "检测到首次运行，自动安装环境（全程约 10~25 分钟，取决于网络）"
    install_sudo_curl_git || { echo "自动补装失败：基础命令安装异常（检查网络/存储）"; return 1; }
    install_uv || { echo "自动补装失败：uv 安装异常"; return 1; }
    install_napcat || { echo "自动补装失败：NapCat 安装异常"; return 1; }
    install_astrbot || { echo "自动补装失败：AstrBot 安装异常"; return 1; }
    if ! check_astrbot_ready; then
      echo "__ASTRBOT_MANUAL_ENV_REQUIRED__"
      echo "自动补装后环境仍不完整，请查看上方日志定位网络/存储问题后重启引擎重试"
      return 1
    fi
    progress_echo "环境自愈完成"
  fi

  if [ ! -f "$HOME/.local/bin/uv" ]; then
    echo "uv 未找到"
    exit 1
  fi

  # v2.22.0：AstrBot 启动前先后台拉起 NapCat（此前只装不启——用户 10-04 日志定案断点），
  # 与 AstrBot 启动并行；登录态在 QQ 侧保持，重启引擎后 NapCat 自动恢复会话
  start_napcat || echo "[AstrBot Android] NapCat 启动失败（不影响 AstrBot 本体；可重启引擎重试）"
  start_napcat_watchdog
  # v2.24.0：后台等待 NapCat 端口（只打日志不占 stage，避免与 AstrBot 启动进度互覆盖）
  wait_napcat_ports bg

  # 使用 uv run --no-sync main.py 启动（跳过依赖同步）
  stage 92 "启动 AstrBot 服务（就绪后手机通知栏与手环会同步状态）"
  progress_echo "AstrBot 启动中"

  # v2.23.0：cd 移到 uv run 前——start_napcat/自愈分支会改变工作目录
  # （用户 10-04 第四份日志：uv run 在 /root 下报 Failed to spawn main.py → 启动必败）
  cd "$INSTALL_DIR" || { echo "AstrBot 目录缺失（$INSTALL_DIR），请重启引擎重试自动补装"; exit 1; }
  if [ ! -f "main.py" ]; then
    echo "main.py 缺失（$INSTALL_DIR 不完整），请重启引擎重试自动补装"
    exit 1
  fi
  if ! $HOME/.local/bin/uv run --no-sync main.py; then
    echo "AstrBot 启动失败"
    exit 1
  fi

}

run_step(){
  # v2.18.1：所有路径（start 与 --step）先做 DNS 自举，再进入安装/启动链
  ensure_container_dns || true
  # v2.28.0：设备身份稳定化（machine-id/hostname 固定；防"每次都是新电脑"进风控特征）
  ensure_stable_identity || true
  case "$1" in
    start)
      launch_astrbot
      ;;
    base)
      maybe_prepare_reinstall base
      install_sudo_curl_git
      ;;
    uv)
      maybe_prepare_reinstall uv
      install_sudo_curl_git
      install_uv
      ;;
    napcat)
      maybe_prepare_reinstall napcat
      install_sudo_curl_git
      install_napcat
      ;;
    napcat-start)
      # v2.22.0：看门狗/手动路径——只启不装
      ensure_napcat_configs
      start_napcat
      ;;
    astrbot)
      maybe_prepare_reinstall astrbot
      install_sudo_curl_git
      install_uv
      install_astrbot
      ;;
    all|"")
      install_sudo_curl_git
      bump_progress
      bump_progress
      install_uv
      bump_progress
      install_napcat
      bump_progress
      bump_progress
      bump_progress
      install_astrbot
      ;;
    *)
      echo "未知步骤: $1"
      echo "可用步骤: base uv napcat astrbot ports all"
      exit 1
      ;;
  esac
}

if [ "$1" = "--step" ]; then
  run_step "$2"
else
  run_step start
fi
