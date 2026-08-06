GeoStamp Phase 14.1 Hotfix

Problem:
Long-pressing Settings did not reliably open the Public Registry Queue.

Fix:
A normal tap on Settings now opens:
1. Stamp Settings
2. Public Registry Queue (count)

Long-press remains supported as a shortcut.

Replace only:
app/src/main/java/com/axiominfratech/geostamp/ui/CameraFragment.kt
