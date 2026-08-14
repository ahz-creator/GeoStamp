# GeoStamp Phase 15 — Forensic UI Fix

## Scope of this pass

1. Reworked the permanent saved-photo overlay into a forensic-minimal layout.
2. Removed battery/network/weather/compass telemetry from the permanent photo stamp.
3. Made the QR area explicitly say `SCAN TO VERIFY`.
4. Changed capture-time photo status from `REGISTERED` to `CAPTURE SEALED` / `LOCATION REVIEW` / `LOCATION RISK`.
5. Persisted the exact site/operator identity used by the stamped image into gallery metadata to prevent visible Site ID vs gallery `UNASSIGNED` mismatches.
6. Gallery now distinguishes `REGISTERED` (public registry record exists) from `SEALED` (local evidence integrity record exists).
7. Improved the gallery unassigned wording from `GPS Verification Failed` to `Location not matched · review required`.
8. Site statistics now exclude `UNASSIGNED` and placeholder `–` values.

## Important implementation note

The permanent photo overlay intentionally does not claim public registration at capture time. Registry publication is a later state. The QR still points to the permanent public verification certificate URL.

## Build status

Source-level sanity checks passed for balanced Kotlin delimiters. Full Gradle/Android build could not be executed in this environment because the uploaded project does not contain `gradle-wrapper.jar` and no system Gradle installation is available.

Recommended next step in Android Studio/CI:

- Sync Gradle
- Assemble Debug
- Capture one photo at 1080×1920
- Confirm the new overlay at 20%, 25%, and 30% height
- Confirm gallery shows the same Site ID as the photo
- Publish/verify one evidence ID and confirm the gallery changes from SEALED to REGISTERED
