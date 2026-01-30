#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f "keystore.properties" ]]; then
  echo "Warning: keystore.properties not found. Create it from keystore.properties.example." >&2
fi

./gradlew clean bundleRelease

if compgen -G "app/build/outputs/bundle/release/*.aab" > /dev/null; then
  AAB_PATH="$(ls -1 app/build/outputs/bundle/release/*.aab | head -n 1)"
  echo "AAB generated: $AAB_PATH"
else
  echo "AAB not found in app/build/outputs/bundle/release/." >&2
  exit 1
fi
