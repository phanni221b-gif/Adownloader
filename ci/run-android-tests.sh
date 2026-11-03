#!/usr/bin/env bash
set +e
# Fail fast on the new real playback boundary, then retain the complete regression suite.
gradle -PtestAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=com.adownloader.app.TabsAndTransportIntegrationTest,com.adownloader.app.HlsHttpIntegrationTest#mseExtensionlessTsUsesJobCookiesScopedSegmentRefererAndLiveSheetTitle,com.adownloader.app.SuccessfulResponseIntegrationTest#originalRedirectHintReusesSuccessfulFinalResponseWithoutNativeReplay,com.adownloader.app.SuccessfulResponseIntegrationTest#oneTimeActualPlaybackResponseWorksWhenNativeAndContentFetchBothFail :app:connectedDebugAndroidTest --stacktrace
FOCUSED=$?
if [ "$FOCUSED" -eq 0 ]; then
gradle -PtestAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.notClass=com.adownloader.app.ProcessRecoveryTest :app:connectedDebugAndroidTest --stacktrace
RESULT=$?
else RESULT=$FOCUSED; fi
if [ "$RESULT" -eq 0 ]; then bash ci/run-process-recovery.sh; RESULT=$?; fi
adb pull /sdcard/Download/Adownloader-ui-proof ui-proof
adb pull /sdcard/Download/Adownloader-ui-proof/extension-performance.json extension-performance.json
adb logcat -d -s AndroidRuntime GeckoConsole Adownloader > ui-logcat.txt
exit "$RESULT"
