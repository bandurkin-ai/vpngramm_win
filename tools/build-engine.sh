#!/bin/bash
# Сборка движка vpncore (sing-box) для приложения и для тестов. Нужен только Go (NDK не нужен: CGO выключен).
#  app/src/main/jniLibs/arm64-v8a/libvpncore.so — исполняемый файл для Android (лежит как .so, чтобы Android
#  распаковал его в каталог библиотек приложения, откуда разрешён запуск);
#  engine/build/vpncore-linux — та же программа для Linux: проверка конфигураций в тестах и сквозной стенд.
set -e
cd "$(dirname "$0")/../engine"
TAGS=with_gvisor,with_quic,with_utls
LD="-s -w -buildid="
mkdir -p build ../app/src/main/jniLibs/arm64-v8a
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -tags $TAGS -trimpath -ldflags "$LD" -o ../app/src/main/jniLibs/arm64-v8a/libvpncore.so .
CGO_ENABLED=0 go build -tags $TAGS -trimpath -ldflags "$LD" -o build/vpncore-linux .
ls -la ../app/src/main/jniLibs/arm64-v8a/libvpncore.so build/vpncore-linux
