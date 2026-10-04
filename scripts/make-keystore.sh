#!/usr/bin/env bash
# 生成 keystore.jks 并提取 aiot-toolkit 签名用 pem（Linux 版 rpk-pack.ps1 第 1-3 步）
# keystore.jks 放仓库根目录，Android release 配置 (../keystore.jks) 与 rpk 签名共用
set -euo pipefail
ROOT=/home/z/my-project/study-band-qq-assistant
BAND=$ROOT/band-qq
KS=$ROOT/keystore.jks
STOREPASS=bandqq123
ALIAS=bandqq

if [ ! -f "$KS" ]; then
  echo "== 生成调试证书 keystore.jks =="
  keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -dname "CN=BandQQ, OU=Dev, O=BandQQ, L=City, ST=State, C=CN" 2>&1 | tail -1
fi

echo "== 从 JKS 提取 private.pem / certificate.pem =="
for mode in release debug; do
  SIGN=$BAND/sign/$mode
  mkdir -p "$SIGN"
  P12=$SIGN/bandqq.p12
  PEM=$SIGN/bandqq.pem
  keytool -importkeystore -srckeystore "$KS" -destkeystore "$P12" \
    -srcstoretype jks -deststoretype pkcs12 \
    -storepass "$STOREPASS" -srcstorepass "$STOREPASS" -noprompt >/dev/null 2>&1
  openssl pkcs12 -in "$P12" -nodes -out "$PEM" -passin pass:"$STOREPASS" 2>/dev/null
  # 提取私钥（兼容 PKCS#8 头）
  awk '/-----BEGIN (RSA |EC )?PRIVATE KEY-----/,/-----END (RSA |EC )?PRIVATE KEY-----/' "$PEM" > "$SIGN/private.pem"
  awk '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/' "$PEM" > "$SIGN/certificate.pem"
  rm -f "$P12" "$PEM"
  echo "$mode: $(wc -c < "$SIGN/private.pem") bytes private.pem, $(wc -c < "$SIGN/certificate.pem") bytes certificate.pem"
done

echo "== 验证证书指纹 =="
keytool -list -v -keystore "$KS" -alias "$ALIAS" -storepass "$STOREPASS" 2>/dev/null | grep -E "SHA256:|SHA1:" | head -2
