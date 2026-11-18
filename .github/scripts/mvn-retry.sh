#!/usr/bin/env bash
# Runs Maven with the given arguments, and again when it failed to download something.
#
# Maven Central sometimes answers 404 for a file it has. Maven treats a 404 as a missing file
# and remembers it, so the next attempts add -U to ask Central again. Any other failure, such
# as a failing test, ends the script with Maven's status at once.
set -uo pipefail

attempts=3
log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$attempts"); do
  update=()
  if [ "$attempt" -gt 1 ]; then
    update=(-U)
  fi
  mvn ${update[@]+"${update[@]}"} "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  # Only Maven's own error lines, so that output of a test cannot cause a retry
  if ! grep -qE '^\[ERROR\] .*(Could not (find|transfer) artifact|Non-resolvable (import|parent) POM|Could not resolve dependencies|PluginResolutionException)' "$log"; then
    exit "$status"
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    echo "::warning::Maven could not download a file; trying again in $((attempt * 30)) seconds"
    sleep $((attempt * 30))
  fi
done
exit "$status"
