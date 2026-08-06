# GeoStamp Phase 14 — Real Capture Registry Queue

## Android

Every new capture already creates a public-safe JSON record. This phase makes those real records usable:

- Long-press the **Settings** button on the camera screen.
- Select a queued Verification ID.
- Tap **OPEN IN ADMIN**.
- GeoStamp Admin opens with the real record pre-filled.
- Enter the fine-grained GitHub token and tap **Publish Evidence Record**.
- The public Portal can then find that real Verification ID.

Long-press a queue item to remove it after successful publishing.

## Updated files

### Android
- `verification/EvidenceRegistryOutbox.kt`
- `ui/CameraFragment.kt`

### GitHub
- `GeoStamp-Admin/app.js`
- `GeoStamp-Portal/app.js` (same Phase 13 registry-safe version, included for alignment)

## Important

This remains a zero-cost manual publication bridge. Android does not contain a GitHub token. Automatic publication requires a proper authenticated backend, which should be added only after this full real-capture flow is proven.
