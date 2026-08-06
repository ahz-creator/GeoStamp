# GeoStamp Zero-Cost Architecture

## Product modes
- Personal workspace: local-only projects, labels and evidence.
- Organization workspace: signed configuration downloaded from a static GitHub endpoint.
- Telecom remains a template, not a hard-coded product boundary.

## No-cost operation
- Full-resolution photos remain on the user device.
- Hashes, compact signed metadata and QR payloads are generated locally.
- Basic verification runs locally in the Android app or browser.
- Organization/site configuration is published as small signed JSON through GitHub Pages or Releases.
- No permanent photo cloud, paid database or server-side image processing is required for v1.

## Integrity policy
- Suspected mock location never blocks capture.
- The evidence record stores the coordinates supplied to GeoStamp and marks location integrity as warning/fail.
- GeoStamp does not claim to know the user's true physical location without independent evidence.
- A subtle integrity marker plus signed metadata allows verification tools to expose the warning.

## Planned free admin tool
- Add organization and logo.
- Add project and locations.
- Import CSV.
- Generate signed config bundle.
- Publish bundle to GitHub.

## Planned verification
- QR scan.
- Verification ID entry.
- Local image upload and hash comparison.
- Ad-supported detailed PDF report in Android.
