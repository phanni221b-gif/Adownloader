#!/usr/bin/env bash
set -euo pipefail
mkdir -p process-recovery-results
python3 -m py_compile ci/recovery-fixture-server.py
python3 ci/recovery-fixture-server.py > process-recovery-results/fixture.log 2>&1 &
FIXTURE_PID=$!
trap 'kill "$FIXTURE_PID" 2>/dev/null || true; adb reverse --remove tcp:9009 >/dev/null 2>&1 || true' EXIT
python3 - <<'PY'
import urllib.request,time
for attempt in range(100):
 try:
  urllib.request.urlopen('http://127.0.0.1:9009/stats',timeout=1).close();break
 except OSError:time.sleep(.1)
else:raise SystemExit('Recovery fixture did not start')
PY
adb reverse tcp:9009 tcp:9009
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
capture_failure() {
  local method="$1"
  adb shell 'kill -3 $(pidof com.adownloader.app)' || true
  sleep 2
  adb logcat -d > "process-recovery-results/$method-logcat.txt"
  adb shell dumpsys activity lastanr > "process-recovery-results/$method-anr.txt"
  adb shell screencap -p /sdcard/Download/recovery-failure.png
  adb pull /sdcard/Download/recovery-failure.png "process-recovery-results/$method.png"
  timeout 10s adb shell uiautomator dump /sdcard/Download/recovery-failure.xml || true
  adb pull /sdcard/Download/recovery-failure.xml "process-recovery-results/$method.xml" || true
}
run_case() {
  local method="$1"
  echo "RECOVERY_CASE $method"
  if ! timeout 150s adb shell am instrument -w -e externalRecovery true -e class "com.adownloader.app.ProcessRecoveryTest#$method" com.adownloader.app.test/androidx.test.runner.AndroidJUnitRunner | tee "process-recovery-results/$method.txt"; then
    capture_failure "$method"
    return 1
  fi
  if ! grep -q 'OK (1 test)' "process-recovery-results/$method.txt" || grep -qE 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "process-recovery-results/$method.txt"; then capture_failure "$method"; return 1; fi
}
for kind in Direct Hls Dash; do
  run_case "prepare$kind"
  adb shell am force-stop com.adownloader.app
  run_case "recover$kind"
done

run_case pauseCancelRetry

run_case uiPauseResumeCancel

run_case refreshSources
