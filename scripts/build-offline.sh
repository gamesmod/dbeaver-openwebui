#!/usr/bin/env bash
# Сборка плагина и update site без Maven — только JDK из поставки DBeaver и его же p2-publisher.
# Подходит для закрытого контура, где Maven Central и p2-репозитории Eclipse недоступны.
#
#   DBEAVER_HOME=/opt/dbeaver ./scripts/build-offline.sh            # Linux
#   DBEAVER_HOME=/Applications/DBeaver.app/Contents/Eclipse ./scripts/build-offline.sh   # macOS
#   (Windows: Git Bash / WSL, DBEAVER_HOME="/c/Program Files/DBeaver")
#
# Результат в dist/:
#   io.dbtools.openwebui_<версия>.jar          — сам плагин
#   io.dbtools.openwebui-site-<версия>.zip     — архив update site для Help → Install New Software → Add → Archive
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${DBEAVER_HOME:?Укажите DBEAVER_HOME — каталог установки DBeaver (в нём лежит папка plugins)}"
PLUGINS="$DBEAVER_HOME/plugins"
[ -d "$PLUGINS" ] || { echo "Не найден $PLUGINS" >&2; exit 1; }

BASE_VERSION="$(sed -n 's/^Bundle-Version: \([0-9.]*\)\.qualifier.*/\1/p' "$ROOT/bundles/io.dbtools.openwebui/META-INF/MANIFEST.MF" | tr -d '\r')"
VERSION="${BASE_VERSION}.v$(date -u +%Y%m%d%H%M)"
BUNDLE="$ROOT/bundles/io.dbtools.openwebui"
FEATURE="$ROOT/features/io.dbtools.openwebui.feature"
WORK="$ROOT/target/offline"
DIST="$ROOT/dist"

# JDK: встроенный в DBeaver (jre) обычно без javac, поэтому предпочитаем системный JDK 21+.
JAVAC="${JAVAC:-$(command -v javac || true)}"
[ -n "$JAVAC" ] || { echo "Нужен JDK 21+ (javac в PATH или переменная JAVAC)" >&2; exit 1; }
JAR="${JAR:-$(dirname "$JAVAC")/jar}"
JAVA_RT="$DBEAVER_HOME/jre/bin/java"
[ -x "$JAVA_RT" ] || JAVA_RT="$(dirname "$JAVAC")/java"

rm -rf "$WORK" && mkdir -p "$WORK/classes" "$WORK/pkg" "$WORK/src/plugins" "$WORK/src/features" "$WORK/site" "$DIST"

echo "==> Classpath из $PLUGINS"
CP=""
while IFS= read -r -d '' f; do CP="$CP$f:"; done < <(find "$PLUGINS" -name '*.jar' -print0)
for d in "$PLUGINS"/*/; do CP="$CP$d:"; done

echo "==> Компиляция"
"$JAVAC" -encoding UTF-8 --release 21 -proc:none -nowarn -cp "$CP" -d "$WORK/classes" \
  $(find "$BUNDLE/src" -name '*.java')

echo "==> Упаковка плагина $VERSION"
cp -r "$WORK/classes/." "$WORK/pkg/"
cp -r "$BUNDLE/plugin.xml" "$BUNDLE/icons" "$WORK/pkg/"
sed "s/$BASE_VERSION.qualifier/$VERSION/" "$BUNDLE/META-INF/MANIFEST.MF" > "$WORK/MANIFEST.MF"
PLUGIN_JAR="$WORK/src/plugins/io.dbtools.openwebui_$VERSION.jar"
(cd "$WORK/pkg" && "$JAR" cfm "$PLUGIN_JAR" "$WORK/MANIFEST.MF" .)
cp "$PLUGIN_JAR" "$DIST/"

echo "==> Упаковка feature"
mkdir -p "$WORK/feature"
sed -e "s/$BASE_VERSION.qualifier/$VERSION/" -e "s/version=\"0.0.0\"/version=\"$VERSION\"/" \
  "$FEATURE/feature.xml" > "$WORK/feature/feature.xml"
(cd "$WORK/feature" && "$JAR" cf "$WORK/src/features/io.dbtools.openwebui.feature_$VERSION.jar" feature.xml)

echo "==> Публикация p2-репозитория"
LAUNCHER="$(ls "$PLUGINS"/org.eclipse.equinox.launcher_*.jar | head -1)"
P2CFG="$WORK/p2config"
mkdir -p "$P2CFG"
cp "$DBEAVER_HOME/configuration/config.ini" "$P2CFG/"
mkdir -p "$P2CFG/org.eclipse.equinox.simpleconfigurator"
# Пути в bundles.info относительны каталогу установки — делаем их абсолютными для временной конфигурации.
sed "s#,plugins/#,$PLUGINS/#" "$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info" \
  > "$P2CFG/org.eclipse.equinox.simpleconfigurator/bundles.info"
SITE_URI="file:$WORK/site"
"$JAVA_RT" -jar "$LAUNCHER" -nosplash -configuration "$P2CFG" -install "$DBEAVER_HOME" \
  -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
  -metadataRepository "$SITE_URI" -artifactRepository "$SITE_URI" \
  -metadataRepositoryName "Open WebUI for DBeaver" -artifactRepositoryName "Open WebUI for DBeaver" \
  -source "$WORK/src" -publishArtifacts
"$JAVA_RT" -jar "$LAUNCHER" -nosplash -configuration "$P2CFG" -install "$DBEAVER_HOME" \
  -application org.eclipse.equinox.p2.publisher.CategoryPublisher \
  -metadataRepository "$SITE_URI" \
  -categoryDefinition "file:$ROOT/releng/io.dbtools.openwebui.site/category.xml" -categoryQualifier openwebui

SITE_ZIP="$DIST/io.dbtools.openwebui-site-$VERSION.zip"
(cd "$WORK/site" && "$JAR" cfM "$SITE_ZIP" .)

echo
echo "Готово:"
echo "  плагин:      $DIST/io.dbtools.openwebui_$VERSION.jar"
echo "  update site: $SITE_ZIP"
