# Validation evidence

## v0.5 baseline

Main commit: `e4b15828b32681834ef46729b53fe1cc67e26261`.
[Baseline workflow](https://github.com/phanni221b-gif/Adownloader/actions/runs/41206436645) passed: 12 host tests and 9 Android integration/UI tests. This is the preserved behavior baseline, not evidence that future work passes.

## v0.6 phase A snapshots

These commits are confined to the temporary `validation-v060` branch. Main remains the released baseline until the feature phases have passed.

- [Foundation validation](https://github.com/phanni221b-gif/Adownloader/actions/runs/41223516913), snapshot `0fda6a8387c2bd1533c0f49e542b0e3765138f51`: 17 host tests and 15 Android tests passed, including encrypted SQLite and segment checkpoint tests.
- [Durable queue and UI validation](https://github.com/phanni221b-gif/Adownloader/actions/runs/41224796625), snapshot `324d3ba87aed9242d7e6e21b0a8f371e26c9cc64`: build and Android integration jobs passed after UIDT/FGS and real UI actions were connected.
- [Actual process recovery](https://github.com/phanni221b-gif/Adownloader/actions/runs/41225692994), snapshot `fa63683002f61e010a88a9bac819f7c4b30ba52a`: ARM64 release build and Android integration passed. The external harness additionally reported six successful test invocations, creating and recovering a direct-file, HLS and DASH checkpoint across process termination. It verified the direct file SHA-256 and a positive HTTP Range offset, plus one request for the first committed HLS/DASH part.
- Snapshot `ba4f3fe64f792a4bf9f83e2fc3c986d42db0f70d` adds immediate-resume race handling, cleanup retry persistence, same-PID/new-process-epoch checks, a 1,000-part encrypted-plan test, two direct-transfer edge cases and an actual pause/cancel/retry harness. The local host suite has 19 passing tests and lint/test-APK compilation pass. Cloud lifecycle results are pending; do not count them as passed yet.

The emulator is Android 36 x86_64. The release build contains ARM64 Gecko. These results do not substitute for a physical Galaxy Fold8 installation, hours-long transfer, real service-side URL expiration or an actual installed Owl player. Later phases must add their own evidence rather than inherit these claims.
