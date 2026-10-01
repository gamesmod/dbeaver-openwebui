#!/usr/bin/env bash
# Проверка обновления через тот же InstallOperation, что и мастер Help → Install New Software:
# в копию DBeaver ставится старая версия, затем выбираются все фичи категории нового сайта.
#   ./scripts/upgrade-test.sh <DBeaver home> <старый site.zip> <корневая IU старой версии> <новый site.zip> <ожидаемый бандл>
set -euo pipefail
# Вывод дублируется в файл: при ошибке хвост попадает в аннотацию GitHub Actions
LOG="$(mktemp)"
exec > >(tee "$LOG") 2>&1
trap 'rc=$?; if [ $rc -ne 0 ] && [ -n "${GITHUB_ACTIONS:-}" ]; then sleep 1; echo "::error title=upgrade-test failed::$(tail -60 "$LOG" | sed -e "s/%/%25/g" | sed -e ":a;N;\$!ba;s/\n/%0A/g")"; fi' EXIT
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_HOME="$1"; OLD_SITE="$2"; OLD_IU="$3"; NEW_SITE="$4"; EXPECT_BUNDLE="$5"
H="$(mktemp -d)/dbeaver"
cp -r "$SRC_HOME" "$H"
BI="$H/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"

if [ "$OLD_SITE" != "none" ]; then
  echo "==> Старая версия: $OLD_IU из $(basename "$OLD_SITE")"
  "$ROOT/scripts/p2.sh" "$H" -repository "jar:file:$OLD_SITE!/" -installIU "$OLD_IU" >/dev/null
  grep -E 'openwebui' "$BI" | sed 's/^/  /'
else
  echo "==> Чистая установка"
fi

echo "==> Тестовое приложение InstallOperation"
T="$(mktemp -d)"
JAVAC="$(command -v javac)"
CP=$(find "$H/plugins" -name '*.jar' | tr '\n' ':')
"$JAVAC" --release 21 -proc:none -nowarn -cp "$CP" -d "$T/classes" $(find "$ROOT/tests/p2test/src" -name '*.java')
cp "$ROOT/tests/p2test/plugin.xml" "$T/classes/"
jar cfm "$H/plugins/dbeaver.openwebui.p2test_1.0.0.jar" "$ROOT/tests/p2test/META-INF/MANIFEST.MF" -C "$T/classes" .
echo "dbeaver.openwebui.p2test,1.0.0,plugins/dbeaver.openwebui.p2test_1.0.0.jar,4,false" >> "$BI"

echo "==> Установка новой версии: все фичи категории (как при отметке категории в мастере)"
IUS="dbeaver.openwebui.ai.feature.feature.group,io.dbtools.openwebui.feature.feature.group"
set +e
"$( [ -x "$H/jre/bin/java" ] && echo "$H/jre/bin/java" || command -v java )" -jar "$(ls "$H"/plugins/org.eclipse.equinox.launcher_*.jar | head -1)" -nosplash -consoleLog \
  -application dbeaver.openwebui.p2test.app "jar:file:$NEW_SITE!/" "$IUS" 2>&1 | tee "$T/out.txt" | grep -v -E '^\s*$|SLF4J'
RC=${PIPESTATUS[0]}
set -e
[ "$RC" = "0" ] || { echo "InstallOperation завершился с кодом $RC" >&2; exit 1; }

echo "==> bundles.info после обновления"
grep -E 'openwebui' "$BI" | grep -v p2test | sed 's/^/  /'
NEW_LINES=$(grep -c "^$EXPECT_BUNDLE," "$BI" || true)
[ "$NEW_LINES" = "1" ] || { echo "Ожидалась ровно одна версия $EXPECT_BUNDLE, найдено: $NEW_LINES" >&2; exit 1; }
if grep -q '^io.dbtools.openwebui,' "$BI"; then echo "Старый плагин io.dbtools.openwebui не удалён" >&2; exit 1; fi
OTHER=dbeaver.openwebui.ai.compat25
[ "$EXPECT_BUNDLE" = "$OTHER" ] && OTHER=dbeaver.openwebui.ai
if grep -q "^$OTHER," "$BI"; then echo "На этой версии DBeaver не должен ставиться $OTHER" >&2; exit 1; fi
if [ "$OLD_SITE" != "none" ]; then
  grep -q 'PLAN - ' "$T/out.txt" || { echo "В плане нет удаления старой версии" >&2; exit 1; }
fi
echo "OK"
