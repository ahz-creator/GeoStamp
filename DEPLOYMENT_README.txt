GeoStamp Automatic Public Registry Patch

ANDROID — modified/new files only
NEW:
- app/src/main/java/com/axiominfratech/geostamp/verification/RegistryPublisher.kt

MODIFIED:
- app/src/main/java/com/axiominfratech/geostamp/verification/EvidenceRegistryOutbox.kt
- app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt

PORTAL — modified file only
- GeoStamp-Portal/app.js

FREE BACKEND — new files
- GeoStamp-Registry-Backend/Code.gs
- GeoStamp-Registry-Backend/appsscript.json

CONFIG — new file
- GeoStamp-Config/registry.json

WHAT IT DOES
1. Capture creates one Evidence ID.
2. Public-safe JSON is queued locally.
3. Android silently POSTs it to the free Google Apps Script registry.
4. The record is stored as <evidence-id>.json in Google Drive.
5. The app moves successful records from pending to its local published archive.
6. Desktop ID lookup and QR verification query the same registry endpoint.
7. Older GitHub evidence JSON remains supported as a fallback.

ONE-TIME DEPLOYMENT
1. Open script.google.com and create a new project.
2. Replace Code.gs with the supplied file.
3. Project Settings > enable Show appsscript.json, then replace the manifest.
4. Deploy > New deployment > Web app:
   Execute as: Me
   Who has access: Anyone
5. Copy the /exec URL.
6. Put that URL into GeoStamp-Config/registry.json endpoint.
7. Upload registry.json to the root of ahz-creator/GeoStamp-Config.
8. Upload GeoStamp-Portal/app.js.
9. Replace the three Android files and rebuild.

SECURITY NOTE
No GitHub token is stored in the app. Only public-safe evidence fields are published.
The endpoint is public by design for public evidence verification. A stronger signed-request
validation layer can be added next without changing the Evidence ID flow.
