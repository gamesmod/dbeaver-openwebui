#!/usr/bin/env bash
# Builds the plugin jar against an installed DBeaver CE and (optionally) installs it.
#
#   ./build.sh                     - build target/dbeaver.openwebui.ai_1.0.0.jar
#   ./build.sh --install           - build and copy into $DBEAVER_HOME/dropins
#   ./build.sh --install-bundles   - build, copy into plugins/ and register in bundles.info
#                                    (fallback if dropins are not picked up)
#
# DBEAVER_HOME defaults:
#   Linux:  /usr/share/dbeaver-ce  (deb/rpm)   or the unpacked tar.gz folder
#   macOS:  /Applications/DBeaver.app/Contents/Eclipse
set -euo pipefail

cd "$(dirname "$0")"

VERSION=1.0.0
BSN=dbeaver.openwebui.ai
JAR="target/${BSN}_${VERSION}.jar"

if [[ -z "${DBEAVER_HOME:-}" ]]; then
  for d in /usr/share/dbeaver-ce /opt/dbeaver /Applications/DBeaver.app/Contents/Eclipse "$HOME/dbeaver"; do
    [[ -d "$d/plugins" ]] && DBEAVER_HOME="$d" && break
  done
fi
if [[ -z "${DBEAVER_HOME:-}" || ! -d "$DBEAVER_HOME/plugins" ]]; then
  echo "DBeaver not found. Set DBEAVER_HOME to the folder that contains 'plugins'." >&2
  exit 1
fi
echo "DBeaver: $DBEAVER_HOME"

if ! ls "$DBEAVER_HOME"/plugins/org.jkiss.dbeaver.model.ai_*.jar >/dev/null 2>&1; then
  echo "org.jkiss.dbeaver.model.ai bundle not found - this DBeaver version has no AI module API used by the plugin." >&2
  exit 1
fi

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
# DBeaver ships its own JRE without javac; use a JDK 21+ from PATH/JAVA_HOME
if ! command -v "$JAVAC" >/dev/null; then
  echo "javac not found. Install JDK 21+ and/or set JAVA_HOME." >&2
  exit 1
fi

CP=$(find "$DBEAVER_HOME/plugins" -maxdepth 2 -name '*.jar' | tr '\n' ':')

rm -rf target
mkdir -p target/classes
"$JAVAC" --release 21 -encoding UTF-8 -proc:none -nowarn \
  -cp "$CP" -d target/classes $(find src -name '*.java')
# NLS message bundles
(cd src && find . -name '*.properties' | while read -r f; do
  mkdir -p "../target/classes/$(dirname "$f")"; cp "$f" "../target/classes/$f"
done)

jar --create --file "$JAR" --manifest META-INF/MANIFEST.MF -C target/classes . plugin.xml icons
echo "Built: $JAR"

case "${1:-}" in
  --install)
    mkdir -p "$DBEAVER_HOME/dropins"
    cp "$JAR" "$DBEAVER_HOME/dropins/"
    echo "Installed to $DBEAVER_HOME/dropins. Restart DBeaver (first start with -clean)."
    ;;
  --install-bundles)
    cp "$JAR" "$DBEAVER_HOME/plugins/"
    BI="$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
    LINE="${BSN},${VERSION},plugins/${BSN}_${VERSION}.jar,4,false"
    if ! grep -q "^${BSN}," "$BI"; then
      cp "$BI" "$BI.bak"
      [[ -n "$(tail -c1 "$BI")" ]] && echo >> "$BI"
      echo "$LINE" >> "$BI"
    fi
    echo "Installed to plugins/ and registered in bundles.info (backup: bundles.info.bak). Restart DBeaver with -clean."
    ;;
esac
