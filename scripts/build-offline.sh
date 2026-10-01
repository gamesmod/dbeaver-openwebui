#!/usr/bin/env bash
# Сборка плагина и update site без Maven: JDK 21+ и p2-publisher из поставки DBeaver.
#
#   DBEAVER_HOME=/opt/dbeaver-26.2 DBEAVER25_HOME=/opt/dbeaver-25.2.4 ./scripts/build-offline.sh
#
# DBEAVER_HOME   — DBeaver CE 26.2+: против него собирается основной бандл, его p2 публикует сайт.
# DBEAVER25_HOME — DBeaver CE 25.2.4/25.2.5: против него собирается совместимый бандл.
#                  Без него совместимый бандл не собирается (SKIP_COMPAT25=1 — осознанно пропустить).
#
# Результат в dist/:
#   dbeaver-openwebui-ai-site-<версия>.zip   — архив update site (Help → Install New Software → Add → Archive)
#   dbeaver.openwebui.ai_<версия>.jar         — бандл для DBeaver 26.2+
#   dbeaver.openwebui.ai.compat25_<версия>.jar — бандл для DBeaver 25.2.4–25.2.5
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${DBEAVER_HOME:?Укажите DBEAVER_HOME — DBeaver CE 26.2+ (каталог с папкой plugins)}"
[ -d "$DBEAVER_HOME/plugins" ] || { echo "Не найден $DBEAVER_HOME/plugins" >&2; exit 1; }
if [ -z "${DBEAVER25_HOME:-}" ] && [ "${SKIP_COMPAT25:-}" != "1" ]; then
  echo "Укажите DBEAVER25_HOME (DBeaver CE 25.2.4/25.2.5) или SKIP_COMPAT25=1" >&2; exit 1
fi

BASE_VERSION="$(sed -n 's/^Bundle-Version: \([0-9.]*\)\.qualifier.*/\1/p' "$ROOT/bundles/dbeaver.openwebui.ai/META-INF/MANIFEST.MF" | tr -d '\r')"
VERSION="${BASE_VERSION}.v$(date -u +%Y%m%d%H%M)"
WORK="$ROOT/target/offline"
DIST="$ROOT/dist"

JAVAC="${JAVAC:-$(command -v javac || true)}"
[ -n "$JAVAC" ] || { echo "Нужен JDK 21+ (javac в PATH или переменная JAVAC)" >&2; exit 1; }
JAR="${JAR:-$(dirname "$JAVAC")/jar}"
JAVA_RT="$DBEAVER_HOME/jre/bin/java"
[ -x "$JAVA_RT" ] || JAVA_RT="$(dirname "$JAVAC")/java"

rm -f "$DIST"/dbeaver.openwebui.ai*.jar "$DIST"/dbeaver-openwebui-ai-site-*.zip 2>/dev/null || true
rm -rf "$WORK" && mkdir -p "$WORK/src/plugins" "$WORK/src/features" "$WORK/site" "$DIST"

classpath() {  # classpath <DBeaver home>
  local cp=""
  while IFS= read -r -d '' f; do cp="$cp$f:"; done < <(find "$1/plugins" -name '*.jar' -print0)
  for d in "$1"/plugins/*/; do cp="$cp$d:"; done
  echo "$cp"
}

build_bundle() {  # build_bundle <имя бандла> <DBeaver home>
  local name="$1" home="$2" dir="$ROOT/bundles/$1" out="$WORK/$1"
  echo "==> $name: компиляция против $home"
  mkdir -p "$out/classes"
  "$JAVAC" -encoding UTF-8 --release 21 -proc:none -nowarn -cp "$(classpath "$home")" -d "$out/classes" \
    $(find "$dir/src" -name '*.java')
  (cd "$dir/src" && find . -name '*.properties' | while read -r f; do
    mkdir -p "$out/classes/$(dirname "$f")"; cp "$f" "$out/classes/$f"; done)
  cp -r "$dir/plugin.xml" "$dir/icons" "$out/classes/"
  if [ -d "$dir/OSGI-INF" ]; then cp -r "$dir/OSGI-INF" "$out/classes/"; fi
  local base; base="$(sed -n 's/^Bundle-Version: \([0-9.]*\)\.qualifier.*/\1/p' "$dir/META-INF/MANIFEST.MF" | tr -d '\r')"
  sed "s/$base.qualifier/$VERSION/" "$dir/META-INF/MANIFEST.MF" > "$out/MANIFEST.MF"
  (cd "$out/classes" && "$JAR" cfm "$WORK/src/plugins/${name}_$VERSION.jar" "$out/MANIFEST.MF" .)
  cp "$WORK/src/plugins/${name}_$VERSION.jar" "$DIST/"
}

build_feature() {  # build_feature <id фичи>
  local id="$1" out="$WORK/feature-$1"
  mkdir -p "$out"
  sed -e "s/version=\"[0-9.]*\.qualifier\"/version=\"$VERSION\"/" -e "s/version=\"0.0.0\"/version=\"$VERSION\"/g" \
    "$ROOT/features/$id/feature.xml" > "$out/feature.xml"
  (cd "$out" && "$JAR" cf "$WORK/src/features/${id}_$VERSION.jar" feature.xml)
  echo "==> фича $id $VERSION"
}

build_bundle dbeaver.openwebui.ai "$DBEAVER_HOME"
build_feature dbeaver.openwebui.ai.dbeaver26.feature
if [ -n "${DBEAVER25_HOME:-}" ]; then
  build_bundle dbeaver.openwebui.ai.compat25 "$DBEAVER25_HOME"
  build_feature dbeaver.openwebui.ai.dbeaver25.feature
fi
build_feature dbeaver.openwebui.ai.feature
build_feature io.dbtools.openwebui.feature
if [ -z "${DBEAVER25_HOME:-}" ]; then
  # Без совместимого бандла убираем ссылку на его фичу, иначе сайт будет ссылаться на несуществующую
  sed -i '/dbeaver.openwebui.ai.dbeaver25.feature/d' "$WORK/feature-dbeaver.openwebui.ai.feature/feature.xml"
  (cd "$WORK/feature-dbeaver.openwebui.ai.feature" && "$JAR" cf "$WORK/src/features/dbeaver.openwebui.ai.feature_$VERSION.jar" feature.xml)
fi

echo "==> Публикация p2-репозитория"
PLUGINS="$DBEAVER_HOME/plugins"
LAUNCHER="$(ls "$PLUGINS"/org.eclipse.equinox.launcher_*.jar | head -1)"
P2CFG="$WORK/p2config"
mkdir -p "$P2CFG/org.eclipse.equinox.simpleconfigurator"
cp "$DBEAVER_HOME/configuration/config.ini" "$P2CFG/"
# Пути в bundles.info относительны каталогу установки — делаем их абсолютными для временной конфигурации.
sed "s#,plugins/#,$PLUGINS/#" "$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" \
  > "$P2CFG/org.eclipse.equinox.simpleconfigurator/bundles.info"
SITE_URI="file:$WORK/site"
"$JAVA_RT" -jar "$LAUNCHER" -nosplash -configuration "$P2CFG" -install "$DBEAVER_HOME" \
  -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
  -metadataRepository "$SITE_URI" -artifactRepository "$SITE_URI" \
  -metadataRepositoryName "Open WebUI engine for DBeaver" -artifactRepositoryName "Open WebUI engine for DBeaver" \
  -source "$WORK/src" -publishArtifacts
"$JAVA_RT" -jar "$LAUNCHER" -nosplash -configuration "$P2CFG" -install "$DBEAVER_HOME" \
  -application org.eclipse.equinox.p2.publisher.CategoryPublisher \
  -metadataRepository "$SITE_URI" \
  -categoryDefinition "file:$ROOT/releng/site/category.xml" -categoryQualifier openwebui

SITE_ZIP="$DIST/dbeaver-openwebui-ai-site-$VERSION.zip"
(cd "$WORK/site" && "$JAR" cfM "$SITE_ZIP" .)

echo
echo "Готово ($VERSION):"
ls -1 "$DIST"
