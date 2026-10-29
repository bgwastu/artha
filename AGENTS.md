# Agent instructions

- Ship small usable changes promptly.
- Run only local checks relevant to the change (`./gradlew -p artha testDebugUnitTest assembleDebug`). Use JDK 21 for Gradle; newer JDKs can fail the build.
- Android device checks use real ADB devices. Never use emulators.
- Preserve existing user data and append-only Room ledger invariants.
- Keep documentation and tooling minimal.
