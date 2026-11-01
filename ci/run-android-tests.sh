#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.notClass=com.adownloader.app.ProcessRecoveryTest :app:connectedDebugAndroidTest --stacktrace
RESULT=$?
if [ "$RESULT" -eq 0 ]; then bash ci/run-process-recovery.sh; RESULT=$?; fi
adb pull /sdcard/Download/Adownloader-ui-proof ui-proof
adb logcat -d -s AndroidRuntime GeckoConsole Adownloader > ui-logcat.txt
exit "$RESULT"
