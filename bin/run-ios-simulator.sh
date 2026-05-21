#!/bin/bash
set -euo pipefail

SIMULATOR_ID="apple_ios_simulator"
BOOT_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --boot-only)
      BOOT_ONLY=1
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--boot-only]" >&2
      exit 1
      ;;
  esac
done

if ! command -v flutter >/dev/null 2>&1; then
  echo "flutter not found on PATH" >&2
  exit 1
fi

# Check if simulator is already running
is_running() {
  xcrun simctl list devices | grep -E "Booted" > /dev/null
}

if ! is_running; then
  echo "Starting iOS Simulator..."
  flutter emulators --launch "${SIMULATOR_ID}"
  
  echo "Waiting for simulator to boot..."
  # xcrun simctl bootstatus will wait until the device is booted
  # We need the actual UUID of the booted simulator to use simctl bootstatus
  # but 'flutter emulators --launch' is usually sufficient.
  # A simple loop checking 'Booted' status is more robust.
  for _ in $(seq 1 60); do
    if is_running; then
      break
    fi
    sleep 2
  done
fi

if ! is_running; then
  echo "Timed out waiting for iOS Simulator to boot." >&2
  exit 1
fi

echo "iOS Simulator is ready."

if [[ "${BOOT_ONLY}" -eq 1 ]]; then
  exit 0
fi

exec flutter run -d ios
