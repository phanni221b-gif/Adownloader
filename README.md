# Adownloader

Java-only Android app shell with bundled GeckoView 159.0.20261022150525. Android System WebView is not used. Android 11+; ARM64 release for Galaxy Fold devices. No account, ads SDK, billing, vault, cloud backend, VPN, filter-list service or DRM bypass.

## Components

- `MainActivity`: address/navigation shell, media chooser, downloads history, system/keyboard/cutout insets and external player intents.
- `BrowserSessionManager`: Gecko runtime/session ownership, navigation and explicit crash recovery, persisted back/forward session state. One browsing tab in this release.
- `PopupController`: default-deny new windows and external-app schemes, open once or grant the initiating site. Unsolicited cross-host navigations are gated outside an intentional navigation/redirect chain. Grants can be reset in Settings.
- `MediaExtensionManager` + bundled `media_sniffer`: Mozilla `ensureBuiltIn` and background native messaging. `webRequest` Content-Type observation plus DOM/Performance fallback. No page-injected native Java bridge.
- `MediaDetector`: bounded request candidate list, enriched response context and per-navigation clearing.
- `DownloadService`: queued foreground jobs, progress/completion notifications, private bounded job history and pending MediaStore publication. Browser closure does not stop downloads. Android can stop long dataSync services; interrupted jobs require restarting.
- `DownloadEngine`, `ManifestParser`, `HttpSource`, `PostProcessor`: browser-independent fetching, static VOD planning, retries and size/range checks, Android MediaExtractor/MediaMuxer sample copy, then track/duration/seek sample validation. No media encoder is created.

## Original-quality behavior

HLS chooses resolution (pixel area), then frame rate, then average/peak bandwidth. A selected external default audio group is also downloaded. Static DASH chooses the maximum original video representation and maximum bitrate audio representation; both are muxed. HLS TS, fMP4, byte ranges and identity AES-128 are supported. DASH SegmentTemplate (Number/Time, padding, inherited attributes, timelines including bounded negative repeats), SegmentList and full-file BaseURL are supported. Direct MP4/WebM files are preserved bit for bit; other supported containers are repacked without encoding.

Encrypted sample/DRM tracks, SAMPLE-AES, dynamic/live manifests, HLS gaps/discontinuities, multi-Period DASH and unsupported platform muxer codecs fail with an error. There is no quality downgrade fallback. Individual buffered segments are limited to 64 MiB and manifests to 8 MiB. Whole-file DASH BaseURL and direct downloads stream to disk. Android container/codec support remains a practical limit; exotic streams may be rejected.

The extension retains only Referer/Origin/User-Agent, plus cookies on actual media-classified requests. It never exports a browser cookie jar or captures Authorization headers. Cookies remain in memory, are attached only to the original media origin and are dropped on cross-origin redirects. Required cookies for extensionless non-media-classified requests may be unavailable; those authenticated sites may fail. Download URLs/headers are not saved in history or logged.

## Build and validation

JDK 17, Gradle 9.6.0, Android platform 37.2 (required by Gecko AAR), build tools 36.0.0. Own app sources are Java; Gecko dependencies may contain Kotlin runtime libraries.

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

The tracked launcher artwork is the supplied pink 3D icon, with an adaptive icon wrapper. No generated replacement artwork is used. `ci/ic_launcher_xxxhdpi.webp.b64` is retained as its source backup.

CI also runs Android 36 x86_64 integration tests (`-PtestAbi=x86_64` is for the emulator only): bit-exact MP4 copy, HLS TS remux, DASH video/audio mux, seek/track validation and actual Gecko native-message detection. The release job always uses the default ARM64 ABI. CI checks native Gecko inclusion and the single MainActivity launcher. This is not a Samsung Fold physical-device test.

## Fixed signing and publishing

The private RSA-4096 key is never in this repository, logs, artifacts or Releases. It is retained separately with a Windows-DPAPI protected password. The public certificate and SHA-256 fingerprint accompany each release. Signing is intentionally local; GitHub has no signing secret configured by this change. CI only uploads explicitly unsigned build artifacts and never publishes them as installable Releases.

```powershell
./ci/sign-release.ps1 -UnsignedApk app/build/outputs/apk/release/app-release-unsigned.apk -OutputApk Adownloader-v0.5.0-arm64.apk -AndroidSdk <SDK-path> -KeyDirectory <private-key-directory>
```

The script reuses the existing key, refuses to regenerate it, aligns native libraries to 16 KiB, signs with APK v2/v3 and verifies the result. Android 11+ does not require v1 signing. Keep the keystore and a recoverable password backup for all future updates. DPAPI password files can only be decrypted under the same Windows account/machine; export a password backup to a password manager before migrating PCs. A new key cannot update an old installation signed by another key.

Publish only the verified signed APK, checksum, public certificate, verification report and release notes. Never publish key material.

Mozilla integration reference: https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/web-extensions.html
Android media APIs: https://developer.android.com/reference/android/media/MediaMuxer

## v0.5 UI layer

Four XML-backed screens share one retained GeckoView. MainActivity owns only shell/lifecycle wiring; ui/ controllers own media sheets, downloads/library, settings and UI telemetry. Switching sections never creates a browser session. Light/dark configuration changes rebind the shell while reparenting the same GeckoView. Actual system/cutout/keyboard insets are applied once at the shell.

DownloadService only exports UI progress and safe host/type/MediaStore metadata into the existing private bounded history; its completion intent also respects the default external player. DownloadEngine, HttpSource, ManifestParser, PostProcessor, BrowserSessionManager, MediaExtensionManager, MediaDetector and extension scripts are unchanged from v0.4. PopupController retains its policy with a bottom-sheet presentation and repeat-request debounce. No browser cookies or request URLs are persisted in download history.

UI automation additionally verifies sheets, address navigation, browser-session identity across sections/themes/window resizing, actual completed-file library and external ACTION_VIEW, popup block/open-once/site grants, and relaunch persistence. The CI integration artifact includes phone/Fold-size screenshots in light and dark themes. These are emulator sizes, not a physical Galaxy Fold8 certification. Unknown byte totals/speeds/ETA are deliberately omitted rather than invented; current engine callbacks expose phase and percent. Audio/video container and quality selection are unchanged.
