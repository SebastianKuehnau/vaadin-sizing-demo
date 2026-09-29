#!/usr/bin/env bash
# Rebuilds the app after a code change: builds the image, restarts the container and records the
# k6 script again against the new build (the script contains the hashed file names of the frontend
# bundle and the component IDs, so it must always match the build under test).
#
#   loadtest/rebuild.sh
#   APP_MEMORY=512m loadtest/rebuild.sh      # other container limits, see compose.yaml
set -euo pipefail

MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"
cd "$(dirname "$0")/.."

echo "1/3 Building the image and restarting the container"
docker compose up --build -d --remove-orphans

echo "2/3 Waiting for the app"
for _ in $(seq 1 120); do
    if curl -sf "http://127.0.0.1:$MANAGEMENT_PORT/actuator/health" >/dev/null; then
        break
    fi
    sleep 1
done
curl -sf "http://127.0.0.1:$MANAGEMENT_PORT/actuator/health" >/dev/null \
    || { echo "The app did not start, see: docker compose logs app" >&2; exit 1; }

echo "3/3 Recording the k6 script (runs EditPersonScenario in Chrome through the recording proxy)"
./mvnw -q -Ploadtest test-compile loadtest:record -Dk6.forceRecord=true

echo "Done. Measure with: loadtest/measure-session-size.sh"
