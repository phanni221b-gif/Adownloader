# Validation evidence

## v0.5 baseline

Main commit: `e4b15828b32681834ef46729b53fe1cc67e26261`.
[Baseline workflow](https://github.com/phanni221b-gif/Adownloader/actions/runs/41206436645) passed: 12 host tests and 9 Android integration/UI tests. This is the preserved behavior baseline, not evidence that future work passes.

## v0.6 phase A snapshots

These commits are confined to the temporary `validation-v060` branch. Main remains the released baseline until the feature phases have passed.

- [Foundation validation](https://github.com/phanni221b-gif/Adownloader/actions/runs/41223516913), snapshot `0fda6a8387c2bd1533c0f49e542b0e3765138f51`: 17 host tests and 15 Android tests passed, including encrypted SQLite and segment checkpoint tests.
- [Durable queue and UI validation](https://github.com/phanni221b-gif/Adownloader/actions/runs/41224796625), snapshot `324d3ba87aed9242d7e6e21b0a8f371e26c9cc64`: build and Android integration jobs passed after UIDT/FGS and real UI actions were connected.
- [Actual process recovery](https://github.com/phanni221b-gif/Adownloader/actions/runs/41225692994), snapshot `fa63683002f61e010a88a9bac819f7c4b30ba52a`: ARM64 release build and Android integration passed. The external harness additionally reported six successful test invocations, creating and recovering a direct-file, HLS and DASH checkpoint across process termination. It verified the direct file SHA-256 and a positive HTTP Range offset, plus one request for the first committed HLS/DASH part.
- Snapshot `ba4f3fe64f792a4bf9f83e2fc3c986d42db0f70d` adds immediate-resume race handling, cleanup retry persistence, same-PID/new-process-epoch checks, a 1,000-part encrypted-plan test, two direct-transfer edge cases and an actual pause/cancel/retry harness. The 19 host tests, 17 normal Android tests and seven external process/lifecycle invocations passed in [workflow 41226320898](https://github.com/phanni221b-gif/Adownloader/actions/runs/41226320898).

The emulator is Android 36 x86_64. The release build contains ARM64 Gecko. These results do not substitute for a physical Galaxy Fold8 installation, hours-long transfer, real service-side URL expiration or an actual installed Owl player. Later phases must add their own evidence rather than inherit these claims.

## Phase A acceptance

[Workflow 41229382675](https://github.com/phanni221b-gif/Adownloader/actions/runs/41229382675), verified snapshot `b78229fb9b07d9317ed702103592ea13749a08a8`: ARM64 release, 19 host tests, 17 Android integration/UI tests and eight external recovery/control invocations all passed. This includes actual UI pause, resume and cancel; authenticated chunk tamper rejection; randomized long signed URLs for 1,000 segments; and reuse of an actual committed DASH media segment rather than just initialization data.

The new control test exposed a real list-update starvation bug: resetting a 250 ms debounce on every progress event could indefinitely postpone rendering. Updates now coalesce at a bounded interval without postponing an already scheduled refresh. UI tests identify the intended job row and wait for stable sheet geometry before touching confirmation buttons. Earlier snapshots 6869dfb, 7558feb, 91fdbef and bc0b061 did not pass this added UI test; they are not acceptance evidence.

## Phase B acceptance

[Workflow 41233892829](https://github.com/phanni221b-gif/Adownloader/actions/runs/41233892829), snapshot `cb155a0538920b9d719a319035787b279ca3e5d7`: build, lint, 38 host tests, 19 Android integration/UI tests and nine external recovery/control invocations passed. Coverage includes authenticated extensionless media, response MIME authority, conservative token grouping, one-request preview/download probing, UTF-8 Content-Disposition and filename collisions. The external refresh invocation exercises same-content direct Range resume, changed-content restart, HLS and DASH signed-token replacement with verified committed-part reuse. This is emulator evidence, not a physical Fold8 claim.

## Phase C acceptance

[Workflow 41259025797](https://github.com/phanni221b-gif/Adownloader/actions/runs/41259025797), snapshot `8a28d24214a985a7268efe6bb69bb7547496a645`: ARM64 release, lint, 43 host tests, 25 Android integration/UI tests and nine external recovery/control invocations passed. The browser tests verify a delayed (11-second) three-host HTTP login redirect followed by blocked unsolicited takeover, actual find-in-page, five retained sessions with isolated media candidates, active-only recovery preserving the other sessions, actual cookie and localStorage removal, encrypted legacy preference migration without form/opaque-state retention, real Gecko history restoration and back navigation, and the desktop menu changing the HTTP User-Agent without replacing the session.

The earlier phase C snapshots a192814 and 6684e2e failed the new delayed redirect test. Frozen Gecko 159 engine source shows a synthetic initial about:blank start/location/stop sequence, which must not commit or cancel the explicitly requested document's trust. The final policy binds that pending intent to its expected URL and actual page-start lifecycle; no timed cross-site allowance was restored. The intermediate f9f9895 snapshot passed before the final UI/recovery additions were validated above.

## Phase D coverage

Host stress writes an actual 2 GiB + 2 MiB deterministic HTTP payload to disk, pauses at an offset above Integer.MAX_VALUE, reopens the file, resumes with validated Range and verifies the final original SHA-256. This is separate from sparse long-offset and 1,000-part random-read tests. Segment tests exercise a payload above the former 64 MiB cap and retry after a truncated encrypted response.

Android coverage adds descriptor ownership/long offsets, direct mux to an unpublished MediaStore FD, pending-orphan deletion while preserving referenced/published rows, and 1,000 timestamped fMP4 HLS media segments plus initialization with duration/track/seek validation and no aggregate file. Native fold testing injects real WindowLayoutInfo events and checks actual item/sheet bounds against asymmetric vertical and tabletop hinges. It combines 200 candidates, 100 persisted history entries, 500 valid app-owned library files and five retained Gecko sessions; scrolling must remain responsive and StrictMode terminates a Java main-thread network violation. Existing UI tests cover narrow, expanded portrait, landscape, IME and both themes. No physical Fold8, real VPN/filter interoperability, hour-long recording or external Owl installation is implied.

The D1 snapshot failed a real browser-state timing race: Gecko's periodic native history update need not arrive before restoration. Safe URL/title-only fallback history is now updated at committed navigation and reconciled with a registered HistoryDelegate when its native state matches the current document and back/forward flags. The native test requires two entries within two seconds, then verifies actual restored back navigation. D2 failed because Android rejected a generic Files insertion into Movies; typed Video/Audio collection insertion fixed it. D3 exposed terminal-frame ambiguity in Android's TS extractor under a one-frame-per-segment synthetic stress input; the long stress fixture now uses explicit-timestamp fMP4, while ordinary TS VOD remains covered separately. These failed snapshots are not acceptance evidence.

## Phase D acceptance

[Workflow 41263851939](https://github.com/phanni221b-gif/Adownloader/actions/runs/41263851939), snapshot `ef3f96ea59a6c7a6e622238ff06881a6bbd01f62`: build, lint, 50 host tests, 30 Android integration/UI tests and the external process-recovery/control harness passed. This includes actual >2 GiB HTTP Range/hash stress, 1,000 fMP4 HLS segments, real pending-descriptor output and cleanup, the 200-candidate/100-history/500-library/five-session native fold stress, and all earlier authenticated detection/browser regressions.

The final stress assertion exposed an unused empty `.media` placeholder on the nonpersistent test path. It is now created only for a representation that actually needs a direct input file; durable checkpoint names remain unchanged. The final orphan test also avoids deleting an already removed URI, which Android correctly no longer considers app-owned. The prior D4–D6 snapshots were not acceptance runs. All captures are emulator evidence, not Fold8 hardware certification.

## Phase E / v0.6.0 acceptance

[Workflow 41298112814](https://github.com/phanni221b-gif/Adownloader/actions/runs/41298112814), snapshot `ca212e06d3e02afe2714ad19307fb2c74ced0f1c`: 50 host tests, 30 Android integration/UI tests, nine external recovery/control invocations, lint, ARM64 release and engine/ABI/launcher package checks passed. The final app is version 0.6.0 / code 10. Phase E changes only settings grouping, restrained section/download typography and redundant dividers; it does not modify the accepted engine. Emulator UI captures cover light/dark, phone/Fold sizes, landscape, sheets and keyboard.


## v0.6.1 stabilization acceptance

[Workflow 41303247976](https://github.com/phanni221b-gif/Adownloader/actions/runs/41303247976), snapshot `08566a030b65a5c5e8c0689ae130a9e36f693e13`: build, 62 host tests, lint, ARM64 release, media-extension JS/package checks, 36 Android integration/UI tests and all 11 external recovery/control invocations passed. The XML reports zero failures/errors/skips. Existing 50 host / 30 native / nine external checks remain, with the foreground-queue test observing SQLite instead of retired duplicate preferences and retaining the two-completion assertion plus a new no-legacy-write assertion.

The new native tests cover real encrypted v1-to-v2 upgrade, failed-upgrade rollback/downgrade rejection, durable final-output cleanup/reopen/corruption, insufficient capacity, typed ENOSPC and SQLite-only state. The external harness expires source URLs between finalization and actual force-stop recovery and asserts no extra media requests. Added host cases cover synthetic 20 GiB capacity and conservative CDN/JWT/refresh identity. The first validation snapshot failed only because a legacy foreground test still read SharedPreferences; the final test reads authoritative job IDs and still requires both actual jobs to finish after Activity closure. Its timeout and success requirement were not relaxed.

Peak segment+output storage during platform mux remains a disclosed limitation; the final checkpoint adds full-hash I/O. No physical Fold8 was available; native evidence is Android 36 x86_64. Same-key ARM64 package/signature checks are recorded with the published release.
