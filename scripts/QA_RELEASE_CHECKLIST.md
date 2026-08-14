# GeoStamp P23 QA Release Gate

- [ ] Debug build launches and capture works.
- [ ] Release build uses a production keystore, never the debug keystore.
- [ ] Capture works with network unavailable.
- [ ] Evidence remains in the local outbox after app restart.
- [ ] A failed registry upload does not delete the queued record.
- [ ] A successful registry upload moves the record exactly once.
- [ ] Reusing an Evidence ID with different image data is rejected.
- [ ] QR Evidence ID mismatch returns a failure/review result.
- [ ] Invalid SHA-256 format cannot produce a verified result.
- [ ] Invalid capture signature cannot produce a verified result.
- [ ] Signed payload mismatch cannot produce a verified result.
- [ ] Location-risk evidence cannot produce a clean verified result.
- [ ] Tampered registry record is not accepted for the requested ID.
- [ ] PDF does not report overall PASS unless registry + cryptographic validation pass.
- [ ] Evidence outbox and published records are excluded from Android backup/transfer.
- [ ] No production credentials/private keys exist in source/configuration.
- [ ] Camera rotation and app recreation do not lose the active capture session.
- [ ] Gallery metadata and stamped Site ID/Evidence ID remain identical.
