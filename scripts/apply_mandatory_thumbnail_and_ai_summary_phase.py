from pathlib import Path

root = Path(__file__).resolve().parents[1]

# 1) Make thumbnail generation deterministic and small enough for registry transport/storage.
thumb = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidenceSlipMetadata.kt'
s = thumb.read_text(encoding='utf-8')
start = s.index('    fun thumbnailBase64(')
end = s.index('\n\n    /** Snapshot taken immediately', start)
replacement = r'''    fun thumbnailBase64(
        imageFile: File,
        maxWidth: Int = 320,
        maxHeight: Int = 240,
        targetBase64Chars: Int = 28000
    ): String = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Evidence image could not be decoded" }

        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("Evidence thumbnail decode failed")

        var working = decoded
        val initialScale = minOf(maxWidth.toFloat() / decoded.width, maxHeight.toFloat() / decoded.height, 1f)
        if (initialScale < 1f) {
            working = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * initialScale).toInt().coerceAtLeast(1),
                (decoded.height * initialScale).toInt().coerceAtLeast(1),
                true
            )
        }

        var quality = 62
        var encoded = ""
        repeat(8) {
            val out = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, quality, out)
            encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (encoded.length <= targetBase64Chars) return@repeat
            quality = (quality - 7).coerceAtLeast(35)
            if (quality <= 42 && encoded.length > targetBase64Chars) {
                val nextW = (working.width * 0.86f).toInt().coerceAtLeast(180)
                val nextH = (working.height * 0.86f).toInt().coerceAtLeast(120)
                val next = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (working !== decoded) working.recycle()
                working = next
            }
        }
        if (working !== decoded) working.recycle()
        decoded.recycle()
        require(encoded.isNotBlank()) { "Evidence thumbnail generation failed" }
        encoded
    }.getOrElse { "" }
'''
s = s[:start] + replacement + s[end:]
thumb.write_text(s, encoding='utf-8')

# 2) Add public-safe mandatory visual metadata to registry outbox.
outbox = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidenceRegistryOutbox.kt'
s = outbox.read_text(encoding='utf-8')
needle = '            copyIfPresent(fullMetadata, this, "thumbnailJpegBase64")\n'
insert = needle + '''            put("visualEvidenceRequired", true)\n            copyIfPresent(fullMetadata, this, "thumbnailMimeType")\n            copyIfPresent(fullMetadata, this, "thumbnailSha256")\n            copyIfPresent(fullMetadata, this, "aiVisualSummary")\n            copyIfPresent(fullMetadata, this, "aiVisualPurpose")\n            copyIfPresent(fullMetadata, this, "aiVisualSummaryStatus")\n            copyIfPresent(fullMetadata, this, "aiVisualSummaryProvider")\n'''
if 'visualEvidenceRequired' not in s:
    s = s.replace(needle, insert)
outbox.write_text(s, encoding='utf-8')

# 3) Add a backend-safe AI visual summary client. No secret/API key is stored in the APK.
ai = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/AiVisualSummaryClient.kt'
ai.write_text(r'''package com.axiominfratech.geostamp.verification

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional visual-description service used only to describe what the evidence image appears to show.
 * It is NOT part of cryptographic authentication and must never influence PASS/FAIL integrity results.
 * Endpoint is remotely configured; credentials remain server-side.
 */
object AiVisualSummaryClient {
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/ahz-creator/GeoStamp-Config/main/ai.json"

    data class Summary(
        val summary: String,
        val purpose: String,
        val status: String,
        val provider: String
    )

    fun analyze(
        context: Context,
        thumbnailBase64: String,
        operator: String,
        siteId: String,
        capturedAt: Long
    ): Summary {
        if (thumbnailBase64.isBlank()) return Summary("", "", "NO_VISUAL", "")
        return runCatching {
            val cfgConn = URL(CONFIG_URL).openConnection() as HttpURLConnection
            cfgConn.connectTimeout = 5000
            cfgConn.readTimeout = 5000
            val cfgText = cfgConn.inputStream.bufferedReader().use { it.readText() }
            cfgConn.disconnect()
            val cfg = JSONObject(cfgText)
            if (!cfg.optBoolean("enabled", false)) return Summary("", "", "DISABLED", "")
            val endpoint = cfg.optString("endpoint").trim()
            if (!endpoint.startsWith("https://")) return Summary("", "", "NOT_CONFIGURED", "")

            val request = JSONObject().apply {
                put("thumbnailBase64", thumbnailBase64)
                put("operator", operator)
                put("siteId", siteId)
                put("capturedAt", capturedAt)
                put("instruction", "Describe only visible, non-sensitive scene facts in 1-2 short sentences. Then state the likely field-documentation purpose in one short phrase. Do not identify people. Do not infer wrongdoing, ownership, safety compliance, or facts not visible in the image.")
            }
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(request.toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return Summary("", "", "FAILED", cfg.optString("provider"))
            val json = JSONObject(body)
            Summary(
                summary = json.optString("summary").trim().take(420),
                purpose = json.optString("purpose").trim().take(180),
                status = if (json.optString("summary").isBlank()) "NO_RESULT" else "GENERATED",
                provider = json.optString("provider", cfg.optString("provider"))
            )
        }.getOrElse { Summary("", "", "FAILED", "") }
    }
}
''', encoding='utf-8')

# 4) Wire mandatory thumbnail + AI fields into capture metadata.
vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
if 'AiVisualSummaryClient' not in s:
    s = s.replace('import com.axiominfratech.geostamp.verification.EvidenceSlipMetadata', 'import com.axiominfratech.geostamp.verification.EvidenceSlipMetadata\nimport com.axiominfratech.geostamp.verification.AiVisualSummaryClient')

old = '''                val slipThumbnailBase64 = withContext(Dispatchers.IO) {
                    EvidenceSlipMetadata.thumbnailBase64(stampedFile)
                }
                val slipSession = EvidenceSlipMetadata.sessionSnapshot(operatorSession, siteIdStr)
'''
new = '''                val slipThumbnailBase64 = withContext(Dispatchers.IO) {
                    EvidenceSlipMetadata.thumbnailBase64(stampedFile)
                }
                if (slipThumbnailBase64.isBlank()) {
                    _captureEvent.emit(CaptureEvent.Error("Visual evidence thumbnail could not be generated. Evidence was not registered."))
                    return@launch
                }
                val thumbnailBytes = android.util.Base64.decode(slipThumbnailBase64, android.util.Base64.DEFAULT)
                val thumbnailSha256 = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(thumbnailBytes).joinToString("") { "%02x".format(it) }
                val aiVisual = withContext(Dispatchers.IO) {
                    AiVisualSummaryClient.analyze(
                        app,
                        slipThumbnailBase64,
                        operatorStr,
                        siteIdStr,
                        location.timestampMs
                    )
                }
                val slipSession = EvidenceSlipMetadata.sessionSnapshot(operatorSession, siteIdStr)
'''
s = s.replace(old, new)

needle = '                        put("thumbnailBase64", slipThumbnailBase64)\n'
if 'thumbnailSha256' not in s:
    s = s.replace(needle, needle + '''                        put("thumbnailMimeType", "image/jpeg")\n                        put("thumbnailSha256", thumbnailSha256)\n                        put("visualEvidenceRequired", true)\n                        put("aiVisualSummary", aiVisual.summary)\n                        put("aiVisualPurpose", aiVisual.purpose)\n                        put("aiVisualSummaryStatus", aiVisual.status)\n                        put("aiVisualSummaryProvider", aiVisual.provider)\n''')
vm.write_text(s, encoding='utf-8')

# 5) AI config template for the separate config repo is emitted locally for easy copy/commit there.
template = root / 'ai.config.template.json'
template.write_text(r'''{
  "enabled": false,
  "endpoint": "",
  "provider": "server-side-vision-model",
  "schemaVersion": 1
}
''', encoding='utf-8')

print('Applied: mandatory registry thumbnail + visual hash + safe AI summary integration architecture.')
print('AI remains disabled until GeoStamp-Config/ai.json points to a secure server-side endpoint.')
