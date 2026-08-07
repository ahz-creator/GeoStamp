from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = p.read_text(encoding='utf-8')
old = '''    private fun decodeThumbnail(r:JSONObject):Bitmap? { val raw=first(r.optString("thumbnailBase64"),r.optString("thumbnailJpegBase64")).substringAfter("base64,",""); if(raw.isBlank())return null; return runCatching{val b=Base64.decode(raw,Base64.DEFAULT);BitmapFactory.decodeByteArray(b,0,b.size)}.getOrNull() }'''
new = '''    private fun decodeThumbnail(r: JSONObject): Bitmap? {
        val source = first(
            r.optString("thumbnailBase64"),
            r.optString("thumbnailJpegBase64"),
            r.optString("thumb")
        ).trim()
        if (source.isBlank() || source == "Unavailable") return null
        val raw = if (source.contains("base64,")) source.substringAfter("base64,") else source
        if (raw.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }'''
if old not in s:
    # tolerate formatting changes by replacing the one-line function by boundaries
    start = s.find('    private fun decodeThumbnail(')
    end = s.find('\n    private fun id(', start)
    if start < 0 or end < 0:
        raise SystemExit('decodeThumbnail function not found')
    s = s[:start] + new + s[end:]
else:
    s = s.replace(old, new)
p.write_text(s, encoding='utf-8')
print('Fixed EvidencePdfExporter thumbnail decoder for plain Base64 and data URI forms.')
