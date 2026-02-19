#!/bin/bash
set -e

# Configuration
# Find the first connected Android device (excluding 'List of devices attached' header)
DEVICE_ID=$(adb devices | grep -w "device" | head -n 1 | cut -f1)

if [ -z "$DEVICE_ID" ]; then
  echo "Error: No connected Android device found."
  echo "Please start an emulator or connect a device."
  exit 1
fi

ENTRY_POINT="lib/cljd-out/datomworld/main.dart"
PACKAGE_NAME="com.example.cljd_datomworld"

echo "=== Redeploying to $DEVICE_ID ==="

# 1. Compile ClojureDart
echo "1. Compiling ClojureDart..."
mise exec -- clj -M:cljd compile

# 2. Build APK
echo "2. Building APK (Target: $ENTRY_POINT)..."
mise exec -- flutter build apk -t "$ENTRY_POINT"

# 3. Install to Emulator
echo "3. Installing to device..."
mise exec -- flutter install -d "$DEVICE_ID"

# 4. Launch App
echo "4. Launching application..."
adb -s "$DEVICE_ID" shell am start -n "$PACKAGE_NAME/.MainActivity"

echo "=== Redeploy Complete ==="
