#!/usr/bin/env bash
# Офлайн-пакет для Windows из собранного update site:
#   ./scripts/make-offline-package.sh <каталог с site zip и jar> <каталог результата>
# Результат: <out>/dbeaver-openwebui-ai-offline-<версия>/ и <out>/dbeaver-openwebui-ai-offline-<версия>.zip
set -euo pipefail
SRC="$1"; OUT="$2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SITE=$(ls "$SRC"/dbeaver-openwebui-ai-site-*.zip | head -1)
FULL=$(basename "$SITE" .zip); FULL=${FULL#dbeaver-openwebui-ai-site-}   # 2.2.1.v202610021500
VER=${FULL%.v*}                                                           # 2.2.1
P="dbeaver-openwebui-ai-offline-$VER"
rm -rf "${OUT:?}/$P" "$OUT/$P.zip"
mkdir -p "$OUT/$P/plugins"
cp "$SITE" "$OUT/$P/dbeaver-openwebui-ai-site-$VER.zip"
cp "$SRC"/dbeaver.openwebui.ai*_"$FULL".jar "$OUT/$P/plugins/"
cp "$ROOT/releng/offline/install.cmd" "$OUT/$P/"
sed "s/@FULLVERSION@/$FULL/g; s/@VERSION@/$VER/g" "$ROOT/releng/offline/УСТАНОВКА.txt" > "$OUT/$P/УСТАНОВКА.txt"
if command -v zip >/dev/null; then (cd "$OUT" && zip -qr "$P.zip" "$P"); else (cd "$OUT" && 7z a "$P.zip" "$P" >/dev/null); fi
echo "$OUT/$P"
