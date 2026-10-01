#!/usr/bin/env bash
# p2 director в указанной установке DBeaver.
#   ./scripts/p2.sh <DBeaver home> <аргументы director...>
set -euo pipefail
H="$1"; shift
"$( [ -x "$H/jre/bin/java" ] && echo "$H/jre/bin/java" || command -v java )" -jar "$(ls "$H"/plugins/org.eclipse.equinox.launcher_*.jar | head -1)" -nosplash \
  -application org.eclipse.equinox.p2.director "$@"
