#!/usr/bin/env bash
# Скачивает и распаковывает DBeaver CE для Linux x86_64 из релизов github.com/dbeaver/dbeaver.
#   GH_TOKEN=... ./scripts/get-dbeaver.sh <версия|latest> <каталог назначения>
# Имя архива берётся из списка файлов релиза. Печатает фактическую версию.
set -euo pipefail
V="$1"; DEST="$2"
if [ "$V" = "latest" ] || [ -z "$V" ]; then
  V=$(gh release view --repo dbeaver/dbeaver --json tagName -q .tagName)
fi
if [ -d "$DEST/plugins" ]; then echo "$V"; exit 0; fi
ASSET=$(gh release view "$V" --repo dbeaver/dbeaver --json assets -q '.assets[].name' \
  | grep -E '^dbeaver-ce-.*linux.*(x86_64|amd64).*\.tar\.gz$' | head -1)
[ -n "$ASSET" ] || { echo "В релизе DBeaver $V нет архива для Linux x86_64" >&2; exit 1; }
TMP=$(mktemp -d)
gh release download "$V" --repo dbeaver/dbeaver --pattern "$ASSET" --output "$TMP/dbeaver.tgz" >&2
tar xzf "$TMP/dbeaver.tgz" -C "$TMP"
HOME_DIR=$(dirname "$(find "$TMP" -maxdepth 3 -type d -name plugins | head -1)")
mkdir -p "$(dirname "$DEST")"
mv "$HOME_DIR" "$DEST"
rm -rf "$TMP"
echo "$V"
