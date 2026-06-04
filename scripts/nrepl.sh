#!/usr/bin/env bash
# Start an nREPL with the :dev alias so the LMDB --add-opens JVM flags apply.
# Connect your editor (Calva/CIDER) to the printed port.
set -euo pipefail
cd "$(dirname "$0")/.."
exec clojure -M:dev -m nrepl.cmdline \
  --middleware "[cider.nrepl/cider-middleware]"
