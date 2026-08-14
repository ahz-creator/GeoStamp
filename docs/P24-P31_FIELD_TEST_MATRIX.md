# GeoStamp P24-P31 Field Test Matrix

Use this matrix on at least one low/mid-range and one flagship Android device.

| ID | Test | Expected |
|---|---|---|
| P24-01 | Capture with strong GPS + internet | Evidence sealed, queued, registry succeeds |
| P24-02 | Capture with no internet | Evidence remains locally available and marked pending |
| P24-03 | Kill app after capture | Evidence survives restart |
| P24-04 | Rotate during camera session | Preview and overlay remain aligned |
| P24-05 | Invalid QR | Verification returns FAIL, never VERIFIED |
| P24-06 | Modify registered JSON/image | Hash/signature gate returns FAIL |
| P24-07 | Mock location enabled | Location integrity warning/failure is surfaced |
| P25-01 | Missing signature | REVIEW, not PASS |
| P25-02 | Wrong evidence ID | FAIL |
| P25-03 | Wrong image hash | FAIL |
| P26-01 | Capture→store→register chain | Audit chain verifies |
| P26-02 | Modify one audit event | Chain verification fails |
| P29-01 | Server failure | Local queue retained; retry scheduled |
| P29-02 | Retry after recovery | Record publishes once; duplicate avoided |
| P30-01 | 100+ gallery items | Smooth scrolling, no OOM |
| P30-02 | Large photo capture sequence | No progressive memory crash |
| P31-01 | Android API 26+ | App launches and core flow works |
| P31-02 | Punch-hole/notch device | Controls avoid system bars |
| P31-03 | Tablet/large screen | Layout remains readable and usable |
