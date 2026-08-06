# GeoStamp Phase 13 — Public Registry Bridge

## What this phase adds

- Android creates a public-safe JSON outbox record for every new capture.
- GeoStamp Admin can import that JSON and publish it to `GeoStamp-Config/evidence/`.
- GeoStamp Portal can look up a Verification ID case-insensitively.
- A demo record is included: `GST-DEMO-0001`.

## Android changed files

- `verification/EvidenceRegistryOutbox.kt` (new)
- `ui/MainViewModel.kt` (changed)

The outbox is local only. It is intentionally not an automatic cloud upload because a public Android app must not contain a GitHub token.

## Deploy

1. Replace the Android files and rebuild.
2. Upload the contents of `GeoStamp-Admin` to the Admin repository.
3. Upload the contents of `GeoStamp-Portal` to the Portal repository.
4. Upload `GeoStamp-Config/evidence/` to the Config repository.
5. Test Portal lookup using `GST-DEMO-0001`.
6. For a real capture, export/copy its JSON from the app outbox during development, import it in Admin, then publish.

## Security boundary

Do not publish private identity, email, IMEI, SIM serial, full device fingerprint, consent receipts, private notes, or original images in the public Config repository.
