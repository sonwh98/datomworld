#!/bin/bash
set -euo pipefail

AVD_NAME="${AVD_NAME:-Datomworld_Pixel_3_API_34}"
BOOT_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --boot-only)
      BOOT_ONLY=1
      shift
      ;;
    --avd)
      AVD_NAME="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 [--boot-only] [--avd <name>]" >&2
      exit 1
      ;;
  esac
done

if ! command -v emulator >/dev/null 2>&1; then
  echo "emulator not found on PATH" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found on PATH" >&2
  exit 1
fi

if ! command -v flutter >/dev/null 2>&1; then
  echo "flutter not found on PATH" >&2
  exit 1
fi

existing_emulator_serials() {
  adb devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1}'
}

before_serials="$(existing_emulator_serials | tr '\n' ' ')"
current_serial="$(existing_emulator_serials | head -n 1 || true)"

if [[ -z "${current_serial}" ]]; then
  echo "Starting emulator ${AVD_NAME}..."
  nohup emulator -avd "${AVD_NAME}" >/tmp/"${AVD_NAME}".log 2>&1 &

  echo "Waiting for emulator to appear in adb..."
  for _ in $(seq 1 120); do
    candidate="$(existing_emulator_serials | head -n 1 || true)"
    if [[ -n "${candidate}" && " ${before_serials} " != *" ${candidate} "* ]]; then
      current_serial="${candidate}"
      break
    fi
    sleep 2
  done
fi

if [[ -z "${current_serial}" ]]; then
  echo "Timed out waiting for emulator ${AVD_NAME} to register with adb." >&2
  exit 1
fi

echo "Waiting for ${current_serial} to finish booting..."
adb -s "${current_serial}" wait-for-device >/dev/null
for _ in $(seq 1 180); do
  boot_completed="$(adb -s "${current_serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [[ "${boot_completed}" == "1" ]]; then
    break
  fi
  sleep 2
done

boot_completed="$(adb -s "${current_serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
if [[ "${boot_completed}" != "1" ]]; then
  echo "Emulator ${current_serial} did not finish booting in time." >&2
  exit 1
fi

echo "Emulator ready: ${current_serial}"

if [[ "${BOOT_ONLY}" -eq 1 ]]; then
  exit 0
fi

exec flutter run -d "${current_serial}"
