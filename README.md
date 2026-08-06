# GeoStamp - Field Documentation Camera App

## Overview
Professional Android camera app for telecom field engineers in Pakistan.
Captures photos with GPS-verified location overlays and auto-matches tower site IDs.

## Features
- **Camera Only** — no gallery access, live camera capture only
- **GPS Anti-Spoofing** — detects mock locations, VPNs, and location forging apps
- **Site ID Matching** — matches coordinates within ±10m to Pakistan operator tower database
- **Multi-Operator Support** — PTCL, Jazz, Zong, Telenor, Ufone, SCO
- **Stamp Options** — configurable overlays (coordinates, address, timestamp, site ID, operator logo)

## Architecture
```
GeoStamp/
├── camera/          CameraManager — CameraX, front/back, no gallery
├── location/        LocationEngine — GPS + anti-spoof validation
├── database/        SiteDatabase — Room DB with all PK operator towers
├── security/        AntiSpoofManager — mock location & integrity checks
├── overlay/         StampRenderer — Canvas-based image stamping
└── ui/              MainActivity, CameraFragment, SettingsFragment
```

## Security Features
1. **Mock Location Detection** — checks `isMockLocationProvider()` / `isMock` flag
2. **Location Provider Validation** — only trusts GPS hardware provider
3. **Accuracy Gate** — rejects fixes with accuracy > 30m
4. **Speed Sanity Check** — rejects teleport-speed jumps
5. **Play Integrity API** — device attestation to prevent rooted/emulator abuse
6. **No Gallery** — `ACTION_IMAGE_CAPTURE` with `FileProvider`, intent filtered

## Site Matching Logic
- Haversine distance check: `±10 meters` radius
- Operator filter applied before match
- Falls back to "NO MATCH – {coords}" if nothing found
- Matched result: `[OPERATOR] Site: {SITE_ID} | Sector: {SECTOR}`

## Build
```bash
./gradlew assembleRelease
```
Requires Android Studio Hedgehog or newer, minSdk 26, targetSdk 34.
