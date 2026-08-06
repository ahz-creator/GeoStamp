from pathlib import Path

path = Path("app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt")
text = path.read_text(encoding="utf-8")

import_anchor = "import com.axiominfratech.geostamp.verification.RegistryPublisher\n"
import_line = "import com.axiominfratech.geostamp.verification.EvidenceSlipMetadata\n"
if import_line not in text:
    if import_anchor not in text:
        raise SystemExit("RegistryPublisher import anchor not found")
    text = text.replace(import_anchor, import_anchor + import_line, 1)

capture_anchor = """                val stampedFile = cameraManager.captureAndStamp(overlayData, outputDir)\n\n                // Enhancement happens once inside CameraManager before the overlay is drawn.\n"""
capture_replacement = """                val stampedFile = cameraManager.captureAndStamp(overlayData, outputDir)\n                val slipThumbnailBase64 = withContext(Dispatchers.IO) {\n                    EvidenceSlipMetadata.thumbnailBase64(stampedFile)\n                }\n                val slipSession = EvidenceSlipMetadata.sessionSnapshot(operatorSession, siteIdStr)\n\n                // Enhancement happens once inside CameraManager before the overlay is drawn.\n"""
if "val slipThumbnailBase64" not in text:
    if capture_anchor not in text:
        raise SystemExit("Capture anchor not found")
    text = text.replace(capture_anchor, capture_replacement, 1)

meta_anchor = """                        put(\"operatorSessionOperatorName\", operatorSession?.operatorName ?: \"\")\n                        put(\"distanceM\",   distM)\n"""
meta_replacement = """                        put(\"operatorSessionOperatorName\", operatorSession?.operatorName ?: \"\")\n                        put(\"thumbnailBase64\", slipThumbnailBase64)\n                        val slipKeys = slipSession.keys()\n                        while (slipKeys.hasNext()) {\n                            val slipKey = slipKeys.next()\n                            put(slipKey, slipSession.opt(slipKey))\n                        }\n                        put(\"distanceM\",   distM)\n"""
if 'put("thumbnailBase64", slipThumbnailBase64)' not in text:
    if meta_anchor not in text:
        raise SystemExit("Metadata anchor not found")
    text = text.replace(meta_anchor, meta_replacement, 1)

path.write_text(text, encoding="utf-8")
print("Applied GeoStamp evidence slip capture metadata patch")
