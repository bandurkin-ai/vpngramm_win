#!/bin/bash
# Перепаковывает установленные пакеты обратно в .deb (аналог dpkg-repack)
set -e
for p in "$@"; do
  d=$(mktemp -d); mkdir -p "$d/DEBIAN"
  dpkg-query -L "$p" | while read -r f; do
    if [ -d "$f" ] && [ ! -L "$f" ]; then mkdir -p "$d$(readlink -f "$f")"; fi
    if [ -L "$f" ] || [ -f "$f" ]; then
      dir=$(readlink -f "$(dirname "$f")"); mkdir -p "$d$dir"; cp -a "$f" "$d$dir/" 2>/dev/null || true
    fi
  done
  dpkg-query -s "$p" | awk '/^Status:/{next} /^Conffiles:/{c=1;next} c && /^ /{next} {c=0; print}' > "$d/DEBIAN/control"
  for s in preinst postinst prerm postrm conffiles templates config triggers shlibs symbols; do
    for f in /var/lib/dpkg/info/$p.$s /var/lib/dpkg/info/$p:amd64.$s; do [ -f "$f" ] && cp "$f" "$d/DEBIAN/$s"; done
  done
  dpkg-deb -b -Zgzip "$d" /out/ >/dev/null && echo "repacked $p"
  rm -rf "$d"
done
