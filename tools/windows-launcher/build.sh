#!/bin/bash
# Сборка vpngramm.exe (лаунчер для Windows x64) на Linux: ./build.sh <куда положить exe>
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-vpngramm.exe}"
RSRC="$(go env GOPATH)/bin/rsrc"
[ -x "$RSRC" ] || go install github.com/akavel/rsrc@v0.10.2
"$RSRC" -arch amd64 -ico ../../desktop/icon/vpngramm.ico -manifest vpngramm.exe.manifest -o rsrc_windows_amd64.syso
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -H windowsgui" -o "$OUT" .
rm -f rsrc_windows_amd64.syso
