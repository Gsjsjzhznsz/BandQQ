#!/usr/bin/env bash
# 手环端 rpk 打包脚本（Linux/macOS 版，对应 rpk-pack.ps1）
# 用法: ./scripts/build-rpk.sh [输出目录]
# 依赖: JDK keytool、openssl、band-qq/node_modules 中的 aiot-toolkit（npm install）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BAND="$ROOT/band-qq"
OUT_DIR="${1:-$ROOT/dist}"
KS="$ROOT/keystore.jks"
STOREPASS="bandqq123"
ALIAS="bandqq"

[ -d "$BAND" ] || { echo "未找到手环端目录: $BAND"; exit 1; }
mkdir -p "$OUT_DIR"

# 1. 生成共享调试证书（如不存在；与 Android release 同一 keystore）
if [ ! -f "$KS" ]; then
  echo "生成调试证书 $KS ..."
  keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -dname "CN=BandQQ, OU=Dev, O=BandQQ, L=City, ST=State, C=CN" >/dev/null 2>&1
fi

# 2. 从 JKS 提取 aiot-toolkit 签名用 private.pem / certificate.pem
for mode in release debug; do
  SIGN="$BAND/sign/$mode"; mkdir -p "$SIGN"
  keytool -importkeystore -srckeystore "$KS" -destkeystore "$SIGN/bandqq.p12" \
    -srcstoretype jks -deststoretype pkcs12 \
    -storepass "$STOREPASS" -srcstorepass "$STOREPASS" -noprompt >/dev/null 2>&1
  openssl pkcs12 -in "$SIGN/bandqq.p12" -nodes -out "$SIGN/bandqq.pem" -passin pass:"$STOREPASS" 2>/dev/null
  awk '/-----BEGIN (RSA |EC )?PRIVATE KEY-----/,/-----END (RSA |EC )?PRIVATE KEY-----/' "$SIGN/bandqq.pem" > "$SIGN/private.pem"
  awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' "$SIGN/bandqq.pem" > "$SIGN/certificate.pem"
  rm -f "$SIGN/bandqq.p12" "$SIGN/bandqq.pem"
done

# 3. aiot-toolkit 打包签名（release 读取 sign/release）
cd "$BAND"
[ -x node_modules/.bin/aiot ] || { echo "未找到 aiot-toolkit，请先: cd band-qq && npm install"; exit 1; }
node_modules/.bin/aiot release

RPK=$(find "$BAND/dist" "$ROOT/.temp_band-qq/dist" -name "*.rpk" 2>/dev/null | head -1)
if [ -n "$RPK" ]; then
  cp -f "$RPK" "$OUT_DIR/"
  echo "产物: $OUT_DIR/$(basename "$RPK")"
else
  echo "未找到 rpk 产物，请检查上方 aiot 输出"; exit 1
fi
echo "证书: $KS (storepass=$STOREPASS alias=$ALIAS，与 Android release 共用)"
