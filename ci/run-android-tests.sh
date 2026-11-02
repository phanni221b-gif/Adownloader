#!/usr/bin/env bash
set +e
gradle -PtestAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=com.adownloader.app.SuccessfulResponseIntegrationTest#oneTimeActualPlaybackResponseWorksWhenNativeAndContentFetchBothFail :app:connectedDebugAndroidTest --stacktrace
RESULT=$?
adb logcat -d -s AndroidRuntime GeckoConsole Adownloader > ui-logcat.txt
exit "$RESULT"
