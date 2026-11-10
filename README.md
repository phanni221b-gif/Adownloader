# Adownloader

An Android 11+ video download browser with bundled Gecko, a Java application codebase and an ARM64 release for modern Galaxy Fold devices. Android System WebView is not used. The engine version has one source: [`geckoVersion` in gradle.properties](gradle.properties); Gradle also generates the version shown in App info and Device Diagnostics.

## v0.7.0 background transfer reliability

- **Browser network stack for downloads.** Manifests and segments are requested through Gecko's own `GeckoWebExecutor` — the same cookie store (HttpOnly and cookies refreshed during playback), TLS and HTTP/2 as playback — instead of replaying a captured header snapshot. A transfer no longer needs a live tab, so it keeps its access in the background and after a process restart. The captured-context `HttpURLConnection` path remains the fallback (for example for partitioned third-party cookies) and is kept for the rest of that transfer once it is the one that works. GeckoView computes the Referer of these requests itself (origin only), so a server that demands the exact page path is served through that fallback.
- **Expired signed segment URLs** during long transfers trigger one fresh playlist read; the transfer continues only if the new plan is the same media, quality and timeline, otherwise it stops for a refresh as before.
- **Automatic continuation.** A transfer stopped by Android (job stop, process death) or by a transient network/server error is queued again and resumes from its verified checkpoint. A budget of 4 attempts without progress prevents loops; a user pause always stays paused.
- **Faster HLS/DASH.** Up to 4 segments per track are fetched concurrently; each is still staged, synced and checkpointed individually.
- **Per-download notifications.** Parallel downloads no longer share one notification whose buttons could act on a different download.

## v0.6.1 stabilization

SQLite schema 2 preserves existing jobs and encrypted contexts. Download state now lives only in SQLite; successful v0.5 history import removes the legacy payload. Access failures distinguish AUTH from evidence-based EXPIRED_URL. Conservative CDN identity handling never changes the real request URL or removes resource/quality/JWT payload information.

HLS/DASH perform an early capacity estimate. After validated pending output is synchronized, a durable length/SHA-256 checkpoint permits immediate source cleanup and recovery without another download or mux. **Peak usage can still approach source plus output during mux**; unknown-length manifests provide a lower bound/bitrate estimate, not a space guarantee. See [stabilization details](docs/V0.6.1-STABILIZATION.md).

## v0.6 reliability

- SQLite job queue survives process termination. Real pause, resume, cancel and retry controls are available in the download list and notifications; 1–3 transfers run in parallel (Settings), each with its own notification controls.
- Per-installation Android Keystore AES-256-GCM protects source URLs, minimal request headers and durable segment plans. No bearer-token collection, browser cookie-jar export or plaintext secret fallback.
- Direct transfers verify the saved prefix and HTTP Range/If-Range validators. Changed or non-resumable sources restart safely. HLS/DASH reuse hash-verified committed segments and the saved maximum-quality plan.
- Android 14+ uses user-initiated data-transfer jobs; Android 11–13 uses an owned foreground service. Interrupted work is reconciled honestly and can be resumed. Completion follows actual track, duration and seek validation.
- Direct media streams into a pending MediaStore descriptor. Segments remain separate and a virtual source feeds sample-copy mux directly to the pending descriptor, avoiding aggregate files and a second private final copy.
- Expired URLs enter NEEDS_REFRESH. A newly detected compatible source can be linked after validating identity and transfer/segment compatibility.

## Browser and detection

Up to five retained Gecko sessions keep their own media candidates and navigation state. Desktop/mobile mode, find, encrypted history/bookmarks, long-press link actions and actual site/all browsing-data deletion are included. UI/theme/window changes retain sessions. Safe persisted history excludes forms, passwords and opaque page state.

New windows are blocked by default with block/open-once/site-permission controls. User-started redirect chains can cross login hosts until actual commit; unsolicited takeover and external-app schemes remain controlled. System filters such as Unicorn Pro/NordVPN are left in charge of network filtering; no internal filter-list service or VPN is added.

The built-in WebExtension uses Mozilla ensureBuiltIn/native messaging, response MIME and DOM/Performance observations. A bounded temporary request-ID cache forwards Cookie/Referer/Origin/User-Agent only after a response is recognized as media. Logical grouping preserves semantic query parameters and keeps the latest complete signed URL with its own headers. Shared bounded manifest probing avoids unnecessary preview/transfer duplication. File names prefer Content-Disposition, page title and URL name, with sanitization and collision handling.

## Original-quality behavior and limits

HLS chooses resolution, then FPS, then bitrate, with the selected default external audio group. Static DASH chooses the highest video and audio representations. Supported plans include HLS TS/fMP4, ranges and identity AES-128, and DASH SegmentTemplate/Timeline, SegmentList and full-file BaseURL. Direct MP4/WebM preserves source bytes; required container conversion uses sample-copy remux. No encoder is instantiated and no lower-quality fallback hides a failure.

DRM/SAMPLE-AES, live recording, HLS GAP and multiple-Period DASH are rejected explicitly. HLS discontinuity groups are joined by sample copy with shifted timestamps when every group has the same codec configuration; a resolution/codec change between groups is refused rather than stitched. The platform-only pipeline does not guarantee rolling-window recording or cross-period timestamp/codec normalization. Alternate audio/subtitle rendition metadata is recognized; embedded subtitle downloads and arbitrary language selection are not offered. Unsupported platform containers/codecs can fail safely. FFmpeg is used only to generate synthetic test assets, never included in the application.

Downloads are saved to Movies/Adownloader or Music/Adownloader. Cancellation removes private workspace and unpublished media; clearing completed history preserves published files. Startup orphan cleanup only targets app-owned unpublished rows in those two directories.

## Fold and privacy

WindowManager uses physical hinge/posture information for grids, browser panes and sheets. Narrow/expanded/landscape/multi-window/keyboard layouts keep system insets. Device Diagnostics lets the user inspect/share only version, Android/model, display/window/insets/posture and enum-only error categories. It excludes URLs, hostnames, cookies, exception text and serial identifiers.

No account, cloud backend, ads/analytics SDK, billing, vault or advanced internal player. Completion opens the user's default external player. The original supplied pink 3D icon is retained.

## Build and verification

JDK 17, Gradle wrapper and Android SDK 37.2/build-tools 36 are required. Run `node ci/test-media-extension.cjs`, `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`, then the Android integration/process-recovery harness described in [TESTING.md](TESTING.md). Test media is synthetic. [ARCHITECTURE.md](ARCHITECTURE.md) documents the schema, encryption, scheduler, resume and format decisions.

CI builds an unsigned ARM64 artifact. Release signing uses the existing locally protected RSA-4096 key via `ci/sign-release.ps1`; private keys/passwords are never committed or uploaded. Dependency updates are weekly reviewable PRs with no auto-merge. Engine upgrades require the complete unit/integration/UI/package checks.

Emulator evidence is not physical Galaxy Fold8 certification. Actual device installation, hours-long transfers, VPN/filter combinations and a locally installed Owl player require device-side checks. See the release's validation report for the exact tested commit, APK hash and signing-certificate fingerprint.
