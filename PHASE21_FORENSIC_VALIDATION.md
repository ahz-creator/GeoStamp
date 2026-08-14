# Phase 21 — Forensic / Security Validation

This phase strengthens verification without changing the capture-signing architecture.

## Implemented

- Added `EvidenceForensicValidator`.
- Evidence ID is checked against the requested ID.
- Image SHA-256 is checked for a valid 64-hex format.
- Capture signatures are cryptographically verified from the embedded public key when the required material is present.
- The signed capture payload is compared against the record fields that are available.
- Cached thumbnail SHA-256 is verified when both the thumbnail and its hash are available.
- Verification UI now distinguishes `VERIFIED · REGISTERED`, `REGISTERED · REVIEW`, `SEALED · REGISTRY UNCONFIRMED`, `INTEGRITY FAILED`, and `REVIEW REQUIRED`.
- Registry lookup now rejects a response whose Evidence ID does not match the requested Evidence ID.
- Existing capture, registry, QR and PDF architecture remains intact.

## Important validation semantics

`VERIFIED · REGISTERED` is only shown when registry confirmation, visual evidence, and the local cryptographic checks are all available and valid.

A hash or signature being merely present is no longer treated as equivalent to a successful verification.

## Build note

The supplied project does not contain `gradle-wrapper.jar`, so a Gradle compilation could not be executed in this environment. Source-level integration checks were performed.
