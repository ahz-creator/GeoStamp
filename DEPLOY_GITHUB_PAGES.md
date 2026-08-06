# GeoStamp public verification portal — zero-cost deployment

1. Create a public GitHub repository named `geostamp-verify` under the `axiominfratech` account.
2. Copy the three files from `docs/` into the repository root, or keep them in `/docs`.
3. In GitHub: **Settings → Pages → Deploy from a branch**.
4. Select the branch and `/root` (or `/docs`, matching where the files were copied).
5. Confirm the final URL is `https://axiominfratech.github.io/geostamp-verify/`.
6. If the URL differs, update `PUBLIC_VERIFY_BASE_URL` in `VerificationEngine.kt` before building the Android app.

The portal is static. Photos selected for QR reading remain in the browser and are not uploaded.

## Current verification boundary

This version verifies that the QR has a supported GeoStamp payload and reports its recorded fields. It does **not** claim server-backed SHA-256 matching because no public registry exists yet. That capability will be added after the zero-cost admin/registry publishing workflow.
