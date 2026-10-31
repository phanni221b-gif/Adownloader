#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 :app:connectedDebugAndroidTest --stacktrace
RESULT=$?
adb pull /sdcard/Download/Adownloader-ui-proof ui-proof
adb logcat -d -s AndroidRuntime GeckoConsole Adownloader > ui-logcat.txt
exit "$RESULT"
