#!/usr/bin/env bash
# v2.28.1 NapCat 更新链路修复测试——10-05 23:27 日志定案四层根因回归：
#   ①WebUI 更新采纳机制（钉扎不再对抗用户更新）②launcher 原位重建（不再一票否决全量重装）
#   ③apt-get update 快速通道（被杀点根治）④L3 采纳版崩溃回退基线
# 用法: bash scripts/test-v2281-napcat-update.sh   （全部沙箱运行，不碰真实容器）
set -u
cd "$(dirname "$0")/.."
SRC="android-sync/astrbot-engine/src/main/assets/astrbot-startup.sh"
WORK="$(mktemp -d /tmp/bq-v2281-test.XXXXXX)"
SHIM="$WORK/shim"
mkdir -p "$SHIM"
PASS=0; FAIL=0

ok(){ PASS=$((PASS+1)); echo "  PASS: $1"; }
bad(){ FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
assert(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (期望[$3]实得[$2])"; fi; }
assert_contains(){ if printf '%s' "$2" | grep -qF -- "$3"; then ok "$1"; else bad "$1 (未包含: $3)"; fi; }
assert_not_contains(){ if printf '%s' "$2" | grep -qF -- "$3"; then bad "$1 (不应包含: $3)"; else ok "$1"; fi; }

# ---- 通用沙箱环境 ----
# dpkg shim: 已安装包白名单
cat > "$SHIM/dpkg" <<'SHIMEOF'
#!/bin/bash
if [ "$1" = "-s" ]; then
  case "$2" in
    linuxqq|libnss3|libnspr4|libasound2t64|libasound2)
      echo "Package: $2"; echo "Status: install ok installed"; exit 0;;
    *) exit 1;;
  esac
fi
exec /usr/bin/dpkg "$@"
SHIMEOF
# apt-get shim: 记录调用，永远成功
cat > "$SHIM/apt-get" <<'SHIMEOF'
#!/bin/bash
echo "apt-get $*" >> "${BQ_APT_LOG:-/dev/null}"
exit 0
SHIMEOF
for c in qq Xvfb screen xauth jq zip unzip; do
  printf '#!/bin/bash\nexit 0\n' > "$SHIM/$c"
done
# g++ 假编译器（-o 目标 ← 源拷贝，保持测试离线自洽）
cat > "$SHIM/g++" <<'SHIMEOF'
#!/bin/bash
out=""; src=""; prev=""
for a in "$@"; do
  if [ "$prev" = "-o" ]; then out="$a"
  else case "$a" in -*) ;; *) src="$a" ;; esac
  fi
  prev="$a"
done
[ -n "$out" ] && [ -n "$src" ] && cp "$src" "$out"
exit 0
SHIMEOF
chmod +x "$SHIM"/*

# 沙箱桩（source lib 后覆盖同名函数）
# gh_fetch 桩产出的 napcat.sh 含上游特征行（apt-get update / install_linuxqq）供补丁函数匹配
write_stubs() {
  cat <<'STUBEOF'
gh_fetch(){ local out="$1"; printf '#!/bin/bash\nlog(){ echo "LOG: $*"; }\nsudo apt-get update -y -qq\ninstall_linuxqq\necho NAPCAT_SH_RAN\n' > "$out"; return 0; }
pkill(){ return 0; }
stage(){ :; }
progress_echo(){ :; }
prepare_apt_downloads(){ return 0; }
apt(){ return 0; }
install_linuxqq(){ return 0; }
ensure_sudo_shim(){ return 0; }
ensure_napcat_zip(){ return 0; }
ensure_napcat_configs(){ return 0; }
napcat_reinstall_pinned(){ echo "REINSTALL_CALLED"; return 0; }
STUBEOF
}
STUBS="$(write_stubs)"

# 提取函数库（主分发器之前）
awk '/^if \[ "\$1" = "--step" \]/{exit}{print}' "$SRC" > "$WORK/lib.sh"

new_sandbox(){
  SB="$WORK/sb$1"; rm -rf "$SB"; mkdir -p "$SB/home/napcat/config" "$SB/tmp" "$SB/aptlog"
  export BQ_APT_LOG="$SB/aptlog/calls.log"; : > "$BQ_APT_LOG"
}

# 场景执行器：在全新 bash 里 source 函数库 + 桩 + 场景代码（纯换行拼接，禁用行首分号）
run_scn(){
  local sb="$1" code="$2"
  SB="${SB:-$WORK/scratch}"; mkdir -p "$SB/home" "$SB/tmp"
  HOME="$SB/home" TMPDIR="$SB/tmp" PATH="$SHIM:$PATH" \
  bash -c "source '$WORK/lib.sh'
$STUBS
$code" 2>&1
}

echo "== 1. 源语法 =="
if bash -n "$SRC" 2>&1; then ok "bash -n 源脚本"; else bad "bash -n 源脚本"; fi
if bash -n "$WORK/lib.sh" 2>&1; then ok "bash -n 函数库提取"; else bad "bash -n 函数库提取"; fi

echo "== 2. ver_gt 版本比较 =="
r=$(run_scn x 'ver_gt 4.18.30 4.18.28 && echo GT || echo NG'); assert "4.18.30>4.18.28" "$r" "GT"
r=$(run_scn x 'ver_gt 4.18.29 4.18.28 && echo GT || echo NG'); assert "4.18.29>4.18.28" "$r" "GT"
r=$(run_scn x 'ver_gt 4.18.28 4.18.28 && echo GT || echo NG'); assert "4.18.28>4.18.28 为假" "$r" "NG"
r=$(run_scn x 'ver_gt 4.17.9 4.18.1 && echo GT || echo NG');  assert "4.17.9>4.18.1 为假" "$r" "NG"
r=$(run_scn x 'ver_gt 4.9.0 4.10.0 && echo GT || echo NG');   assert "4.9.0>4.10.0 为假(段内数值比较)" "$r" "NG"
r=$(run_scn x 'ver_gt 5.0.0 4.99.99 && echo GT || echo NG');  assert "5.0.0>4.99.99" "$r" "GT"

echo "== 3. get_napcat_version =="
new_sandbox 3
printf '{"name":"napcat","version":"4.18.30"}\n' > "$SB/home/napcat/package.json"
r=$(run_scn 3 'get_napcat_version'); assert "package.json 读取" "$r" "4.18.30"
printf 'broken json {{\n"version": "4.18.31",\n' > "$SB/home/napcat/package.json"
r=$(run_scn 3 'get_napcat_version'); assert "坏 JSON grep 兜底" "$r" "4.18.31"
rm -f "$SB/home/napcat/package.json"
r=$(run_scn 3 'get_napcat_version'); assert "缺失 package.json → 空" "$r" ""

echo "== 4. WebUI 更新采纳机制（钉扎不再对抗用户更新）=="
# v2.28.2：基线已提至 4.18.30，WebUI 更新场景同步改为 4.18.31（保持“高于基线”语义）
new_sandbox 4
printf 'const v="4.18.31";\n' > "$SB/home/napcat/napcat.mjs"
printf '{"version":"4.18.31"}\n' > "$SB/home/napcat/package.json"
r=$(run_scn 4 'napcat_enforce_pinned; echo "RC=$?"; [ "$(cat $HOME/napcat/.pinned_version 2>/dev/null)" = "4.18.31" ] && echo MARK_OK || echo MARK_MISSING')
assert_contains "输出采纳消息" "$r" "采纳为新基线"
assert_contains ".pinned_version 落盘" "$r" "MARK_OK"
assert_contains "返回成功" "$r" "RC=0"
assert_not_contains "未触发重装" "$r" "REINSTALL_CALLED"
r2=$(run_scn 4 'napcat_enforce_pinned; echo "RC=$?"')
assert_not_contains "二次运行幂等（不再重复采纳）" "$r2" "采纳为新基线"
assert_contains "二次运行直接通过字面量校验" "$r2" "RC=0"

echo "== 5. 显式 env 钉扎时不采纳，仍走降级重装 =="
new_sandbox 5
printf 'const v="4.18.27";\n' > "$SB/home/napcat/napcat.mjs"
printf '{"version":"4.18.27"}\n' > "$SB/home/napcat/package.json"
r=$(run_scn 5 'NAPCAT_PIN_EXPLICIT=1; napcat_enforce_pinned; echo "RC=$?"')
assert_contains "触发重装" "$r" "REINSTALL_CALLED"
assert_contains "消息含真实检测版本" "$r" "检测到 NapCat v4.18.27"
assert_contains "消息含钉扎版" "$r" "非钉扎版 v4.18.30"
assert_not_contains "显式钉扎不采纳" "$r" "采纳为新基线"

echo "== 6. 15 分钟退避 =="
new_sandbox 6
printf 'const v="4.18.27";\n' > "$SB/home/napcat/napcat.mjs"
printf '{"version":"4.18.27"}\n' > "$SB/home/napcat/package.json"
r=$(run_scn 6 'date +%s > $HOME/napcat/.pin_retry; napcat_enforce_pinned; echo "RC=$?"')
assert_contains "退避期内跳过重装" "$r" "本轮跳过"
assert_not_contains "退避期内不重装" "$r" "REINSTALL_CALLED"

echo "== 7. 采纳标记跨会话同步下载 URL =="
new_sandbox 7
printf 'const v="4.18.30";\n' > "$SB/home/napcat/napcat.mjs"
printf '{"version":"4.18.30"}\n' > "$SB/home/napcat/package.json"
echo "4.18.30" > "$SB/home/napcat/.pinned_version"
r=$(run_scn 7 'napcat_enforce_pinned; echo "URL_OK=${NAPCAT_SHELL_URL#*download/v}"; echo "RC=$?"')
assert_contains "URL 同步到采纳版" "$r" "URL_OK=4.18.30/NapCat.Shell.zip"
assert_contains "校验通过" "$r" "RC=0"

echo "== 8. ensure_napcat_launcher 原位重建 =="
new_sandbox 8
printf 'int bandqq_test_symbol;\n' > "$SB/home/launcher.cpp"
printf 'const v="4.18.30";\n' > "$SB/home/napcat/napcat.mjs"
r=$(run_scn 8 'ensure_napcat_launcher; echo "RC=$?"')
assert_contains "重建消息" "$r" "原位重建"
assert_contains "返回成功" "$r" "RC=0"
[ -f "$SB/home/launcher.sh" ] && ok "launcher.sh 已生成" || bad "launcher.sh 已生成"
grep -q "LD_PRELOAD=./libnapcat_launcher.so qq --no-sandbox" "$SB/home/launcher.sh" && ok "launcher.sh 内容与上游一致" || bad "launcher.sh 内容与上游一致"
[ -s "$SB/home/libnapcat_launcher.so" ] && ok "libnapcat_launcher.so 已产出" || bad "libnapcat_launcher.so 已产出"
[ -f "$SB/home/napcat/launcher.sh" ] && ok "napcat 目录重启垫片已生成" || bad "napcat 目录重启垫片已生成"
bash -n "$SB/home/launcher.sh" && ok "launcher.sh 语法正确" || bad "launcher.sh 语法正确"
r2=$(run_scn 8 'ensure_napcat_launcher; echo "RC=$?"')
assert_not_contains "二次调用幂等" "$r2" "原位重建"
assert_contains "二次调用返回成功" "$r2" "RC=0"

echo "== 9. ensure_napcat_launcher 无框架时拒绝（交安装链）=="
new_sandbox 9
r=$(run_scn 9 'ensure_napcat_launcher; echo "RC=$?"')
assert_contains "无 napcat.mjs 返回失败" "$r" "RC=1"

echo "== 10. install_napcat 修复优先（不再整删完好安装）=="
# v2.28.2：安装场景同步改为 4.18.31（高于新基线 4.18.30，保持“采纳”语义）
new_sandbox 10
printf 'const v="4.18.31";\n' > "$SB/home/napcat/napcat.mjs"
printf '{"version":"4.18.31"}\n' > "$SB/home/napcat/package.json"
echo "SENTINEL" > "$SB/home/napcat/config/keepme"
printf 'int bandqq_test_symbol;\n' > "$SB/home/launcher.cpp"
r=$(run_scn 10 'install_napcat; echo "RC=$?"')
assert_contains "打印缺失项（可观测）" "$r" "缺失项"
assert_contains "走原位修复" "$r" "已原位修复"
assert_contains "采纳 WebUI 更新版本" "$r" "采纳为新基线"
assert_contains "整体成功" "$r" "RC=0"
[ "$(cat "$SB/home/napcat/config/keepme" 2>/dev/null)" = "SENTINEL" ] && ok "配置/框架未被 rm -rf（哨兵完好）" || bad "配置/框架未被 rm -rf（哨兵完好）"
[ -f "$SB/home/launcher.sh" ] && ok "launcher 已补齐" || bad "launcher 已补齐"

echo "== 11. install_napcat 框架缺失时仍走全量重装 =="
new_sandbox 11
set +e
r=$(run_scn 11 'install_napcat; echo "RC=$?"' ); rc11=$?
set -e
assert_contains "宣告全量重装" "$r" "走全量重装"
assert_contains "上游安装脚本被调用并执行" "$r" "NAPCAT_SH_RAN"
assert_contains "上游快速通道补丁生效（桩内 apt-get update 被守卫替换）" "$r" "依赖已齐备，跳过 apt-get update"
assert_contains "沙箱内 launcher 缺失 → 最终校验拦截" "$r" "NapCat 安装不完整"
assert "exit 1 向上传播（校验拦截生效）" "$rc11" "1"

echo "== 12. ensure_napcat_build_deps 快速通道 =="
new_sandbox 12
r=$(run_scn 12 'ensure_napcat_build_deps; echo "RC=$?"')
assert_contains "依赖齐备跳过 apt" "$r" "跳过 apt-get update 快速通道"
assert_contains "返回成功" "$r" "RC=0"
[ -s "$BQ_APT_LOG" ] && bad "依赖齐备时不应调用 apt-get" || ok "依赖齐备时零 apt 操作"
mv "$SHIM/xauth" "$SHIM/xauth.hidden"   # 容器真实缺 xauth，隐藏 shim 即为真实缺失场景
r=$(run_scn 12 'ensure_napcat_build_deps; echo "RC=$?"')
mv "$SHIM/xauth.hidden" "$SHIM/xauth"
assert_contains "缺 xauth 时直接安装（不刷源）" "$r" "直接安装"
assert_contains "缺依赖路径返回成功" "$r" "RC=0"
grep -q "apt-get install" "$BQ_APT_LOG" && ok "apt-get install 被调用" || bad "apt-get install 被调用"
grep -q "xauth" "$BQ_APT_LOG" && ok "缺的正是 xauth" || bad "缺的正是 xauth"
if grep -q "apt-get update" "$BQ_APT_LOG"; then bad "不应先刷新源"; else ok "未先刷新源"; fi

echo "== 13. patch_napcat_installer 快速通道补丁（真实上游脚本）=="
UP="$WORK/napcat-install.sh"
if [ ! -s "$UP" ]; then curl -fsSL --max-time 60 "https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/refs/heads/main/install.sh" -o "$UP" 2>/dev/null; fi
if [ -s "$UP" ]; then
  cp "$UP" "$WORK/patched.sh"
  r=$(run_scn x "patch_napcat_installer '$WORK/patched.sh'; echo RC=\$?")
  assert_contains "补丁执行成功" "$r" "RC=0"
  if bash -n "$WORK/patched.sh" 2>/dev/null; then ok "补丁后脚本语法正确"; else bad "补丁后脚本语法正确"; fi
  n1=$(grep -c "BandQQ 快速通道" "$WORK/patched.sh")
  assert "补丁标记唯一" "$n1" "1"
  n2=$(grep -cF "sudo apt-get update -y -qq" "$WORK/patched.sh")
  assert "apt-get update 保留在 else 分支（仅一处）" "$n2" "1"
  run_scn x "patch_napcat_installer '$WORK/patched.sh' >/dev/null" >/dev/null
  n3=$(grep -c "BandQQ 快速通道" "$WORK/patched.sh")
  assert "二次运行幂等" "$n3" "1"
  if grep -q "依赖已齐备，跳过 apt-get update" "$WORK/patched.sh"; then ok "守卫语义落位"; else bad "守卫语义落位"; fi
else
  echo "  SKIP: 无法下载上游 install.sh（离线环境），跳过补丁测试"
fi

echo "== 14. 静态断言（L3 回退/看门狗头部修复/标记清单）=="
grep -q 'rm -f "$HOME/napcat/.pinned_version" "$HOME/NapCat.Shell.zip"' "$SRC" && ok "L3 采纳版回退：清采纳标记+删离线包" || bad "L3 采纳版回退：清采纳标记+删离线包"
grep -q 'NAPCAT_SHELL_VERSION_DEFAULT}（如需升级请重新在 WebUI 更新）' "$SRC" && ok "L3 回退消息" || bad "L3 回退消息"
sed -n '/^start_napcat(){/,/^  napcat_enforce_pinned/p' "$SRC" | grep -q "ensure_napcat_launcher" && ok "start_napcat 头部先原位修复" || bad "start_napcat 头部先原位修复"
n=$(grep -c '\.pin_retry \.pinned_version' "$SRC"); assert "重装标记清单含 .pinned_version（摘+还两处）" "$n" "2"
sed -n '/^install_napcat(){/,/^  if ! napcat_enforce_pinned/p' "$SRC" | grep -q "ensure_napcat_build_deps" && ok "install_napcat 全量路径接快速通道" || bad "install_napcat 全量路径接快速通道"
sed -n '/^napcat_reinstall_pinned(){/,/^}/p' "$SRC" | grep -q "ensure_napcat_build_deps" && ok "napcat_reinstall_pinned 接快速通道" || bad "napcat_reinstall_pinned 接快速通道"
grep -q 'check_napcat_ready 2>&1 | sed' "$SRC" && ok "就绪检查缺失项可见" || bad "就绪检查缺失项可见"
grep -q 'NAPCAT_SHELL_VERSION_DEFAULT="4.18.30"' "$SRC" && ok "基线版本常量" || bad "基线版本常量"

echo
echo "======================================"
echo "PASS=$PASS FAIL=$FAIL"
rm -rf "$WORK"
[ "$FAIL" -eq 0 ] && echo "ALL TESTS PASSED" || exit 1
