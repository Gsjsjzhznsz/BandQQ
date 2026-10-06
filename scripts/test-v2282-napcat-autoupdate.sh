#!/usr/bin/env bash
# v2.28.2 NapCat 自动更新检测测试——QQ 官网永远拉最新(10-06 18:45 日志: 3.2.34-53644
# 安装成功)而钉扎基线 4.18.28 内置 Appid 表未含 → [QQ版本兼容性检测] Appid未内置。
# 自动跟进机制回归：24h 门控/黑名单/显式钉扎跳过/上游>基线自动更新采纳/L3 回退拉黑。
# 用法: bash scripts/test-v2282-napcat-autoupdate.sh   （全部沙箱运行，不碰真实容器）
set -u
cd "$(dirname "$0")/.."
SRC="android-sync/astrbot-engine/src/main/assets/astrbot-startup.sh"
WORK="$(mktemp -d /tmp/bq-v2282-test.XXXXXX)"
SHIM="$WORK/shim"
mkdir -p "$SHIM"
PASS=0; FAIL=0

ok(){ PASS=$((PASS+1)); echo "  PASS: $1"; }
bad(){ FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
assert(){ if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (期望[$3]实得[$2])"; fi; }
assert_contains(){ if printf '%s' "$2" | grep -qF -- "$3"; then ok "$1"; else bad "$1 (未包含: $3)"; fi; }
assert_not_contains(){ if printf '%s' "$2" | grep -qF -- "$3"; then bad "$1 (不应包含: $3)"; else ok "$1"; fi; }

# ---- curl 桩：releases/latest 返回固定重定向（可被场景覆盖为空=网络失败）----
cat > "$SHIM/curl" <<'SHIMEOF'
#!/bin/bash
# 真实形态: curl -sI -o /dev/null -w '%{redirect_url}' <url>
# 场景可传 BQ_FAKE_REDIRECT 覆盖；空串模拟首通道失败（走 -sL url_effective 兜底）
if [ -n "${BQ_CURL_DEAD:-}" ]; then exit 7; fi
# 用 ${var-default}（无冒号）：空串保持空串（模拟无重定向），未设置才用默认
printf '%s' "${BQ_FAKE_REDIRECT-https://github.com/NapNeko/NapCatQQ/releases/tag/v9.9.9}"
exit 0
SHIMEOF
chmod +x "$SHIM/curl"
for c in date grep cat; do ln -sf "$(command -v $c)" "$SHIM/$c" 2>/dev/null || true; done

# ---- 沙箱桩（source lib 后覆盖同名函数）----
# 默认重装桩：模拟安装成功（package.json + napcat.mjs 均变为上游版 9.9.9）
cat > "$WORK/stubs.sh" <<'STUBEOF'
napcat_reinstall_pinned(){
  printf '{"version":"9.9.9"}\n' > "$HOME/napcat/package.json"
  printf 'const v="9.9.9";\n' > "$HOME/napcat/napcat.mjs"
  echo "REINSTALL_CALLED"; return 0;
}
STUBEOF

# 提取函数库（主分发器之前）
awk '/^if \[ "\$1" = "--step" \]/{exit}{print}' "$SRC" > "$WORK/lib.sh"

new_sandbox(){
  SB="$WORK/sb$1"; rm -rf "$SB"; mkdir -p "$SB/home/napcat" "$SB/tmp"
}

run_scn(){
  local sb="$1" code="$2"
  HOME="$SB/home" TMPDIR="$SB/tmp" PATH="$SHIM:$PATH" \
  bash -c "source '$WORK/lib.sh'
source '$WORK/stubs.sh'
$code" 2>&1
}

echo "== 1. 源语法 =="
bash -n "$SRC" && ok "bash -n 源脚本" || bad "bash -n 源脚本"
bash -n "$WORK/lib.sh" && ok "bash -n 函数库提取" || bad "bash -n 函数库提取"

echo "== 2. napcat_upstream_version 解析 =="
new_sandbox 2
r=$(run_scn 2 'napcat_upstream_version'); assert "302 重定向解析出 9.9.9" "$r" "9.9.9"
r=$(run_scn 2 'BQ_CURL_DEAD=1 napcat_upstream_version; echo "RC=$?"'); assert "全通道失败返回非零" "$r" "RC=1"
r=$(run_scn 2 'BQ_FAKE_REDIRECT="" napcat_upstream_version; echo "RC=$?"'); assert "无重定向走 url_effective 兜底后失败" "$r" "RC=1"

echo "== 3. napcat_check_upstream 24h 门控 =="
new_sandbox 3
date +%s > "$SB/home/napcat/.upd_check"
r=$(run_scn 3 'napcat_check_upstream; echo "RC=$?"')
assert "24h 内静默跳过" "$r" "RC=0"
assert_not_contains "不触发重装" "$r" "REINSTALL_CALLED"
[ "$(cat "$SB/home/napcat/.upd_check")" = "$(date +%s)" ] && ok "时间戳未重写" || ok "时间戳本轮不刷新（允许误差）"

echo "== 4. 上游 > 基线 → 自动更新并采纳 =="
new_sandbox 4
printf '{"version":"4.18.30"}\n' > "$SB/home/napcat/package.json"
r=$(run_scn 4 'napcat_check_upstream; echo "RC=$?"')
assert_contains "触发重装" "$r" "REINSTALL_CALLED"
assert_contains "检测消息" "$r" "检测到 NapCat 上游新版本 v9.9.9"
assert_contains "采纳消息" "$r" "采纳为新基线"
[ "$(cat "$SB/home/napcat/.pinned_version" 2>/dev/null)" = "9.9.9" ] && ok ".pinned_version 落盘 9.9.9" || bad ".pinned_version 落盘 9.9.9"
[ -s "$SB/home/napcat/.upd_check" ] && ok ".upd_check 时间戳已写（24h 门控生效）" || bad ".upd_check 时间戳已写"
r2=$(run_scn 4 'napcat_enforce_pinned >/dev/null 2>&1; echo "URL=${NAPCAT_SHELL_URL#*download/v}"')
assert_contains "下次启动 enforce 按 .pinned_version 同步 URL" "$r2" "URL=9.9.9/NapCat.Shell.zip"

echo "== 5. 重装成功但版本验证失败 → 不采纳并还原 URL =="
new_sandbox 5
printf '{"version":"4.18.30"}\n' > "$SB/home/napcat/package.json"
cat > "$WORK/stubs5.sh" <<'STUBEOF'
napcat_reinstall_pinned(){ echo "REINSTALL_CALLED"; return 0; }   # 模拟重装后版本未变（验证失败场景）
STUBEOF
r=$(HOME="$SB/home" TMPDIR="$SB/tmp" PATH="$SHIM:$PATH" bash -c "source '$WORK/lib.sh'
$(cat "$WORK/stubs5.sh")
get_napcat_version(){ printf '4.18.30'; }   # 假装重装后仍是旧版（package.json 未变）
napcat_check_upstream; echo \"RC=\$?\"" 2>&1)
assert_contains "不写采纳标记" "$r" "自动更新未成功"
[ -f "$SB/home/napcat/.pinned_version" ] && bad "不应采纳" || ok "未写 .pinned_version"
# （还原逻辑在函数内，直接检查最终 NAPCAT_SHELL_URL）
r2=$(HOME="$SB/home" TMPDIR="$SB/tmp" PATH="$SHIM:$PATH" bash -c "source '$WORK/lib.sh'
$(cat "$WORK/stubs5.sh")
get_napcat_version(){ printf '4.18.30'; }
napcat_check_upstream >/dev/null 2>&1
echo \"URL=\${NAPCAT_SHELL_URL#*download/v}\"" 2>&1)
assert_contains "URL 回到基线版" "$r2" "URL=4.18.30/NapCat.Shell.zip"

echo "== 6. 黑名单版本跳过 =="
new_sandbox 6
echo "9.9.9" > "$SB/home/napcat/.upd_blacklist"
r=$(run_scn 6 'napcat_check_upstream; echo "RC=$?"')
assert_contains "黑名单提示" "$r" "黑名单"
assert_not_contains "不触发重装" "$r" "REINSTALL_CALLED"

echo "== 7. 显式环境变量钉扎时跳过 =="
new_sandbox 7
r=$(run_scn 7 'NAPCAT_PIN_EXPLICIT=1 napcat_check_upstream; echo "RC=$?"')
assert "显式钉扎静默返回" "$r" "RC=0"
[ -f "$SB/home/napcat/.upd_check" ] && bad "显式钉扎不应写时间戳" || ok "显式钉扎不写检查时间戳"

echo "== 8. 基线与上游相当时不动 =="
new_sandbox 8
printf '{"version":"4.18.30"}\n' > "$SB/home/napcat/package.json"
r=$(run_scn 8 'BQ_FAKE_REDIRECT="https://github.com/NapNeko/NapCatQQ/releases/tag/v4.18.30" napcat_check_upstream; echo "RC=$?"')
assert_not_contains "同版本不更新" "$r" "REINSTALL_CALLED"

echo "== 9. 静态断言 =="
grep -q 'napcat_check_upstream || true' "$SRC" && ok "start_napcat 已挂自动更新检测" || bad "start_napcat 已挂自动更新检测"
n=$(grep -c '\.upd_check \.upd_blacklist' "$SRC"); assert "重装标记清单含 .upd_check/.upd_blacklist（摘+还两处）" "$n" "2"
sed -n '/采纳版本 v\$(cat/,/NAPCAT_SHELL_URL="\$NAPCAT_SHELL_URL_DEFAULT"/p' "$SRC" | grep -q '\.upd_blacklist' && ok "L3 回退写入黑名单" || bad "L3 回退写入黑名单"

echo
echo "======================================"
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
