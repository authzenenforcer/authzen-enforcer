#!/usr/bin/env bash
# Runs Maven with the given arguments, and again when it failed to download something.
#
# Maven Central sometimes answers 404 for a file it has. Maven treats a 404 as a missing file
# and remembers it, so the next attempt adds -U to ask Central again. Central also answers 429
# when it limits the rate of requests. Maven does not remember such a failure, so the next
# attempt needs no -U, which would only send more requests; it waits longer instead, to give the
# limit time to pass. Any other failure, such as a failing test, ends the script with Maven's
# status at once.
set -uo pipefail

attempts=3
log=$(mktemp)
trap 'rm -f "$log"' EXIT

update=()
for attempt in $(seq 1 "$attempts"); do
  mvn ${update[@]+"${update[@]}"} "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [[ "$status" -eq 0 ]]; then
    exit 0
  fi
  # Only Maven's own error lines, so that output of a test cannot cause a retry
  errors=$(grep -E '^\[ERROR\] .*(Could not (find|transfer) artifact|Non-resolvable (import|parent) POM|Could not resolve dependencies|PluginResolutionException)' "$log")
  if [[ -z "$errors" ]]; then
    exit "$status"
  fi
  if [[ "$attempt" -lt "$attempts" ]]; then
    update=()
    if grep -qE 'Could not find artifact|status code: 404' <<< "$errors"; then
      update=(-U)
    fi
    if grep -qE 'status code: 429|Too Many Requests' <<< "$errors"; then
      wait=$((attempt * 120))
      echo "::warning::Maven Central limited the rate of requests; trying again in $wait seconds"
    else
      wait=$((attempt * 30))
      echo "::warning::Maven could not download a file; trying again in $wait seconds"
    fi
    sleep "$wait"
  fi
done
exit "$status"
