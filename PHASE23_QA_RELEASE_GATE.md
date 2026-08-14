# Phase 23 — QA / Release Gate

Implemented:

- Added `scripts/release_audit.py` for deterministic pre-release source/config checks.
- Added forensic validator unit tests covering ID consistency, SHA-256 format, signed-payload consistency and missing signature material.
- Added a release checklist covering offline capture, queue persistence, duplicate publication protection, QR mismatch handling, tamper detection and PDF trust status.
- P22/P23 are designed so the application can be built with a real release keystore supplied outside source control.

Run the source audit with:

`python scripts/release_audit.py`

The supplied project package still lacks `gradle-wrapper.jar`, so Gradle compilation cannot be executed until the wrapper JAR is restored in the project.
