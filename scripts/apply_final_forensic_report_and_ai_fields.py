from pathlib import Path

root = Path(__file__).resolve().parents[1]

# 1) Persist AI output into the evidence metadata/registry record.
vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
needle = '                        put("thumbnailBase64", slipThumbnailBase64)\n'
insert = '''                        put("thumbnailBase64", slipThumbnailBase64)\n                        put("thumbnailMimeType", "image/jpeg")\n                        put("thumbnailSha256", thumbnailSha256)\n                        put("visualEvidenceRequired", true)\n                        put("aiVisualSummary", aiVisual.summary)\n                        put("aiVisualPurpose", aiVisual.purpose)\n                        put("aiVisualSummaryStatus", aiVisual.status)\n                        put("aiVisualSummaryProvider", aiVisual.provider)\n'''
if needle in s and 'put("aiVisualSummary", aiVisual.summary)' not in s:
    s = s.replace(needle, insert, 1)
vm.write_text(s, encoding='utf-8')

# 2) Refine the one-page PDF: correct AI field name + public verification QR + generated/report ID.
pdf = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = pdf.read_text(encoding='utf-8')

# Add ZXing imports if not already present.
if 'com.google.zxing.BarcodeFormat' not in s:
    s = s.replace('import androidx.core.content.FileProvider\n', 'import androidx.core.content.FileProvider\nimport com.google.zxing.BarcodeFormat\nimport com.google.zxing.qrcode.QRCodeWriter\n')

s = s.replace('val purpose = r.optString("aiLikelyPurpose").trim()', 'val purpose = first(r.optString("aiVisualPurpose"), r.optString("aiLikelyPurpose"), "").takeIf { it != "Unavailable" }.orEmpty().trim()')

# Make AI section status-aware and non-authentication.
old_ai = '''        if (ai.isNotBlank() || purpose.isNotBlank()) {\n            section(c,"AI VISUAL SUMMARY",M,bandY,green)\n            val summary = listOfNotNull(ai.takeIf{it.isNotBlank()}, purpose.takeIf{it.isNotBlank()}?.let{"Likely documentation purpose: $it"}).joinToString("  ")\n            val lines = wrap(summary, 82)\n            lines.take(2).forEachIndexed { i,v -> text(c,v,M,bandY+16+i*10,7.2f,Color.rgb(55,65,75),false) }\n            text(c,"Descriptive assistance only · not part of authentication result",W-M,bandY+16,6.1f,Color.GRAY,false,Paint.Align.RIGHT)\n            bandY += 42f\n        }\n'''
new_ai = '''        if (ai.isNotBlank() || purpose.isNotBlank()) {\n            section(c,"AI VISUAL SUMMARY",M,bandY,green)\n            val summary = listOfNotNull(\n                ai.takeIf { it.isNotBlank() },\n                purpose.takeIf { it.isNotBlank() }?.let { "Likely documentation purpose: $it" }\n            ).joinToString("  ")\n            wrap(summary, 82).take(2).forEachIndexed { i,v ->\n                text(c,v,M,bandY+16+i*10,7.2f,Color.rgb(55,65,75),false)\n            }\n            text(c,"AI description only · excluded from PASS/FAIL authentication",W-M,bandY+16,6.1f,Color.GRAY,false,Paint.Align.RIGHT)\n            bandY += 42f\n        }\n'''
if old_ai in s:
    s = s.replace(old_ai, new_ai)

# Replace bottom finding/footer with structured public verification zone.
old_footer = '''        val noteY=findingY+57f\n        text(c,"GeoStamp authenticates the digital evidence record and recorded capture metadata; it does not independently establish the truth of objects or events depicted.",M,noteY,5.8f,Color.GRAY,false)\n        text(c,"GeoStamp · Axiom Infratech",M,H-20f,6.2f,Color.GRAY,true)\n        text(c,"PAGE 1 OF 1",W-M,H-20f,6.2f,Color.GRAY,true,Paint.Align.RIGHT)\n'''
new_footer = '''        val noteY=findingY+57f\n        text(c,"GeoStamp authenticates the digital evidence record and recorded capture metadata; it does not independently establish the truth of objects or events depicted.",M,noteY,5.8f,Color.GRAY,false)\n\n        // Public verification: machine-readable independent lookup reference.\n        val verifyUrl = "https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}"\n        val qrTop = noteY + 12f\n        val qrSize = 58f\n        drawQr(c, verifyUrl, M, qrTop, qrSize)\n        text(c,"PUBLIC VERIFICATION",M+qrSize+10f,qrTop+12f,6.2f,green,true)\n        text(c,id(r),M+qrSize+10f,qrTop+25f,7.2f,Color.rgb(30,42,55),true)\n        text(c,"Scan QR or verify by Evidence ID in the GeoStamp public registry.",M+qrSize+10f,qrTop+38f,6.0f,Color.GRAY,false)\n        text(c,"Report generated ${time(System.currentTimeMillis())}",M+qrSize+10f,qrTop+51f,5.8f,Color.GRAY,false)\n\n        text(c,"GeoStamp · Axiom Infratech",M,H-20f,6.2f,Color.GRAY,true)\n        text(c,"PAGE 1 OF 1",W-M,H-20f,6.2f,Color.GRAY,true,Paint.Align.RIGHT)\n'''
if old_footer in s:
    s = s.replace(old_footer, new_footer)

# Add QR helper before decodeThumbnail.
marker = '    private fun decodeThumbnail(r: JSONObject): Bitmap? {'
helper = '''    private fun drawQr(c: Canvas, value: String, x: Float, y: Float, size: Float) {\n        runCatching {\n            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 160, 160)\n            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)\n            for (xx in 0 until matrix.width) {\n                for (yy in 0 until matrix.height) {\n                    bitmap.setPixel(xx, yy, if (matrix[xx, yy]) Color.BLACK else Color.WHITE)\n                }\n            }\n            c.drawBitmap(bitmap, null, RectF(x, y, x + size, y + size), Paint(Paint.ANTI_ALIAS_FLAG))\n            bitmap.recycle()\n        }\n    }\n\n'''
if marker in s and 'private fun drawQr(' not in s:
    s = s.replace(marker, helper + marker)

pdf.write_text(s, encoding='utf-8')
print('Applied final one-page forensic report structure + AI metadata persistence + public verification QR.')
