# Phase 22 — Production / Security Hardening

Implemented:

- Release signing no longer falls back to the debug keystore.
- Optional release signing is controlled by Gradle project properties:
  `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
- Release builds keep minification and now also enable resource shrinking.
- Evidence registry queue writes are atomic to reduce corruption after a kill/power loss.
- Duplicate Evidence IDs are rejected unless the existing queued record has the same image hash.
- Evidence registry outbox and published evidence are excluded from Android cloud backup/device transfer.
- PDF overall-pass logic now requires the same cryptographic validation used by the verification screen; missing signatures no longer count as a pass.
- Evidence confidence scoring now requires a structurally valid 64-character SHA-256 rather than an arbitrary 32+ character string.
- Added a source-only release audit script for credentials, signing configuration, backup rules and release hygiene.

No capture signing keys or registry credentials were added to the source.
