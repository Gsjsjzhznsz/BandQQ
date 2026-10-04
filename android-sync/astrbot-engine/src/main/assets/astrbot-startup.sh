#!/bin/bash

ASTRBOT_APP_VERSION="{{VERSION}}"

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
    rm -f "$qq_deb" "$qq_deb_part"
echo "正在下载 LinuxQQ ARM64 安装包..."
download_url="$qq_url"
if ! curl -fL --connect-timeout 20 --speed-time 20 --speed-limit 512 --max-time 600 \
        -A 'Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 Chrome/124 Safari/537.36' \
        -e 'https://im.qq.com/' "$download_url" -o "$qq_deb_part"; then
      rm -f "$qq_deb_part"
  echo "LinuxQQ 官网直链下载失败，尝试申请兼容签名..."
  if get_linuxqq_signed_url "$qq_url" && [ "$LINUXQQ_SIGNED_URL" != "$qq_url" ] &&
      curl -fL --connect-timeout 20 --speed-time 20 --speed-limit 512 --max-time 600 \
        -A 'Mozilla/5.0 (X11; Linux aarch64) AppleWebKit/537.36 Chrome/124 Safari/537.36' \
        -e 'https://im.qq.com/' "$LINUXQQ_SIGNED_URL" -o "$qq_deb_part"; then
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
  echo "预下载 NapCat 离线包（多源竞速，20 秒无数据自动换源）..."
  if gh_fetch "$zip_file" "https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip"; then
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

install_napcat(){
  # 检查是否完整安装。旧版本可能留下 launcher.sh，但 LinuxQQ 或依赖包安装失败。
  if ! check_napcat_ready >/dev/null 2>&1; then
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
    
  # 只在配置文件不存在时写入默认配置
  if [ ! -f "$HOME/napcat/config/onebot11.json" ]; then
    echo "写入 onebot11.json 默认配置文件"
    cat > "$HOME/napcat/config/onebot11.json" <<EOF
{
  "network": {
    "httpServers": [],
    "httpClients": [],
    "websocketServers": [],
    "websocketClients": [
      {
        "name": "WsClient",
        "enable": true,
        "url": "ws://localhost:${ASTRBOT_ONEBOT_WS_PORT:-6199}/ws",
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "reconnectInterval": 5000,
        "token": "kasdkfljsadhlskdjhasdlkfshdlafksjdhf",
        "debug": false,
        "heartInterval": 30000
      }
    ]
  },
  "musicSignUrl": "",
  "enableLocalFile2Url": false,
  "parseMultMsg": false
}
EOF
  fi
fi
  configure_napcat_token_ttl
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

  cd "$INSTALL_DIR"
  if [ ! -f "$HOME/.local/bin/uv" ]; then
    echo "uv 未找到"
    exit 1
  fi

  # 使用 uv run --no-sync main.py 启动（跳过依赖同步）
  stage 92 "启动 AstrBot 服务（就绪后手机通知栏与手环会同步状态）"
  progress_echo "AstrBot 启动中"

  if ! $HOME/.local/bin/uv run --no-sync main.py; then
    echo "AstrBot 启动失败"
    exit 1
  fi

}

run_step(){
  # v2.18.1：所有路径（start 与 --step）先做 DNS 自举，再进入安装/启动链
  ensure_container_dns || true
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
