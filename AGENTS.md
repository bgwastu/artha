# Agent instructions

- Work directly on `main`. Keep commits signed and history linear; no branches or PRs.
- Ship small usable changes promptly.
- Run only local checks relevant to the change (`./gradlew -p artha testDebugUnitTest assembleDebug`).
- Android device checks use real ADB devices. Never use emulators.
- Preserve existing user data and append-only Room ledger invariants.
- Keep documentation and tooling minimal.
