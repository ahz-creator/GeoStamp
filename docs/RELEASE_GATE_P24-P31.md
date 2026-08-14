# GeoStamp P24-P31 Release Gate

A build may only be called production-ready when all critical rows are PASS on physical devices.

- [ ] Capture → Seal → Store → Register → Verify → PDF
- [ ] Offline capture survives app restart
- [ ] Tampered image/metadata is detected
- [ ] Invalid signature is never reported as verified
- [ ] Audit chain verifies and detects mutation
- [ ] Duplicate registration is prevented
- [ ] Retry/backoff does not delete evidence
- [ ] Gallery handles production-scale evidence volume
- [ ] Camera/overlay alignment passes on target devices
- [ ] GPS spoof/mock-location test passes expected policy
- [ ] Release build passes R8/shrinkResources
- [ ] No test/sample evidence remains
- [ ] No production secrets are hardcoded
- [ ] Privacy/data handling has been reviewed
