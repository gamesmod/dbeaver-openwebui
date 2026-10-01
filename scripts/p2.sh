#!/usr/bin/env bash
# p2 director в указанной установке DBeaver.
#   ./scripts/p2.sh <DBeaver home> <аргументы director...>
set -euo pipefail
H="$1"; shift
"$H/jre/bin/java" -jar "$(ls "$H"/plugins/org.eclipse.equinox.launcher_*.jar | head -1)" -nosplash \
  -application org.eclipse.equinox.p2.director "$@"
