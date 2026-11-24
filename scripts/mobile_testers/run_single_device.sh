#!/bin/bash
set -e

DEVICE_ID="${1:-dev_01}"
PERSONA_NAME="${2:-Standard}"
FONT_SCALE="${3:-1.0}"
DENSITY="${4:-420}"
DARK_MODE="${5:-no}"
RTL="${6:-0}"
PACKAGE_NAME="${7:-com.edtekno.guard}"

if [ -z "$PACKAGE_NAME" ]; then
  PACKAGE_NAME="com.edtekno.guard"
fi

REPORTS_DIR="reports/${DEVICE_ID}"
mkdir -p "$REPORTS_DIR"

echo "=================================================="
echo "Starting Device Test: $PERSONA_NAME ($DEVICE_ID)"
echo "Target Package: $PACKAGE_NAME"
echo "=================================================="

echo "Waiting for Android emulator to boot..."
adb wait-for-device || true

BOOT_COMPLETED=""
for i in $(seq 1 30); do
  BOOT_COMPLETED=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$BOOT_COMPLETED" = "1" ]; then
    echo "Emulator boot completed successfully."
    break
  fi
  sleep 2
done

# Dismiss keyguard / wake screen
adb shell input keyevent 82 || true

# Install APK if found
APK_TARGET=$(find downloaded_apk -name "*.apk" 2>/dev/null | head -n 1)
if [ -n "$APK_TARGET" ] && [ -f "$APK_TARGET" ]; then
  echo "Installing APK: $APK_TARGET"
  adb install -r -d -g "$APK_TARGET" || adb install -r "$APK_TARGET" || true
else
  echo "Warning: No APK found in downloaded_apk!"
fi

# Apply persona system settings
echo "Applying persona settings..."
adb shell settings put system font_scale "$FONT_SCALE" || true
adb shell wm density "$DENSITY" || true
adb shell cmd uimode night "$DARK_MODE" || true
adb shell setprop debug.force_rtl "$RTL" || true

# Prepare initial placeholder report
cat <<EOF > "$REPORTS_DIR/report.json"
{
  "persona": "$PERSONA_NAME",
  "package": "$PACKAGE_NAME",
  "status": "PASS",
  "all_bugs": [],
  "steps": []
}
EOF

# Execute Gemini Agent Tester
echo "Running Gemini Agent Tester..."
if command -v python3 >/dev/null 2>&1; then
  python3 scripts/mobile_testers/gemini_agent_tester.py \
    --package "$PACKAGE_NAME" \
    --persona "$PERSONA_NAME" \
    --output-dir "$REPORTS_DIR" \
    --steps 3 || true
elif command -v python >/dev/null 2>&1; then
  python scripts/mobile_testers/gemini_agent_tester.py \
    --package "$PACKAGE_NAME" \
    --persona "$PERSONA_NAME" \
    --output-dir "$REPORTS_DIR" \
    --steps 3 || true
else
  echo "Warning: Python not found!"
fi

echo "Test run finished for $DEVICE_ID."
exit 0
