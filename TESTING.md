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
