from pathlib import Path

root = Path(__file__).resolve().parents[1]

# 1) Make the visual cache self-healing: recover the thumbnail from every local evidence source.
cache = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidenceVisualCache.kt'
cache.write_text(r'''package com.axiominfratech.geostamp.verification

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Durable local visual cache keyed by Evidence ID. */
object EvidenceVisualCache {
    private fun dir(context: Context) = File(context.filesDir, "evidence_visual_cache").also { it.mkdirs() }
    private fun safe(id: String) = id.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-')

    fun save(context: Context, evidenceId: String, thumbnailBase64: String, thumbnailSha256: String = ""): Boolean {
        if (evidenceId.isBlank() || thumbnailBase64.isBlank()) return false
        return runCatching {
            val record = JSONObject().apply {
                put("evidenceId", evidenceId)
                put("thumbnailBase64", thumbnailBase64)
                put("thumbnailMimeType", "image/jpeg")
                if (thumbnailSha256.isNotBlank()) put("thumbnailSha256", thumbnailSha256)
                put("cachedAt", System.currentTimeMillis())
            }
            File(dir(context), "${safe(evidenceId)}.json").writeText(record.toString())
            true
        }.getOrDefault(false)
    }

    fun load(context: Context, evidenceId: String): JSONObject? {
        val file = File(dir(context), "${safe(evidenceId)}.json")
        return if (!file.exists()) null else runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    /**
     * Recover the mandatory thumbnail even if a previous build forgot to populate the dedicated
     * cache. Sources are checked in order and any recovered visual is promoted into the cache.
     */
    fun loadOrRecover(context: Context, evidenceId: String): JSONObject? {
        load(context, evidenceId)?.let { if (hasVisual(it)) return it }

        val candidates = mutableListOf<File>()
        candidates += File(context.filesDir, "evidence_registry_published")
            .resolve("${safe(evidenceId)}.json")
        candidates += File(context.filesDir, "evidence_registry_outbox")
            .resolve("${safe(evidenceId)}.json")

        val metaDir = File(context.filesDir, "gallery_meta")
        metaDir.listFiles { f -> f.isFile && f.extension.equals("meta", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.let(candidates::addAll)

        for (file in candidates) {
            if (!file.exists()) continue
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            val id = json.optString("evidenceId", json.optString("verificationId")).trim()
            if (!id.equals(evidenceId, ignoreCase = true)) continue
            val thumb = firstVisual(json)
            if (thumb.isBlank()) continue
            val sha = json.optString("thumbnailSha256")
            save(context, evidenceId, thumb, sha)
            return JSONObject().apply {
                put("evidenceId", evidenceId)
                put("thumbnailBase64", thumb)
                put("thumbnailMimeType", "image/jpeg")
                if (sha.isNotBlank()) put("thumbnailSha256", sha)
                put("recoveredLocally", true)
            }
        }
        return null
    }

    private fun firstVisual(json: JSONObject): String = sequenceOf(
        json.optString("thumbnailBase64"),
        json.optString("thumbnailJpegBase64"),
        json.optString("thumb")
    ).firstOrNull { it.isNotBlank() }.orEmpty()

    private fun hasVisual(json: JSONObject): Boolean = firstVisual(json).isNotBlank()
}
''', encoding='utf-8')

# 2) Persist the visual immediately during capture, before network publication starts.
vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
needle = '''                val thumbnailSha256 = java.security.MessageDigest.getInstance("SHA-256")\n                    .digest(thumbnailBytes).joinToString("") { "%02x".format(it) }\n'''
replacement = needle + '''                if (!EvidenceVisualCache.save(app, evidenceId, slipThumbnailBase64, thumbnailSha256)) {\n                    _captureEvent.emit(CaptureEvent.Error("Mandatory visual evidence could not be persisted. Evidence was not registered."))\n                    return@launch\n                }\n'''
if 'Mandatory visual evidence could not be persisted' not in s:
    if needle not in s:
        raise SystemExit('Could not find thumbnail SHA block in MainViewModel.kt')
    s = s.replace(needle, replacement, 1)
vm.write_text(s, encoding='utf-8')

# 3) Verification always self-heals the record from cache/published/outbox/gallery metadata.
activity = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt'
s = activity.read_text(encoding='utf-8')
s = s.replace('val visual = EvidenceVisualCache.load(this, evidenceId)', 'val visual = EvidenceVisualCache.loadOrRecover(this, evidenceId)')
s = s.replace('val cachedVisual = visual ?: EvidenceVisualCache.load(this, evidenceId)', 'val cachedVisual = visual ?: EvidenceVisualCache.loadOrRecover(this, evidenceId)')

# Make the visible status truthful if the visual is genuinely missing.
status_old = '''        binding.tvResultStatus.text = when {\n            risk -> "REGISTERED · REVIEW REQUIRED"\n            registryBacked -> "VERIFIED · REGISTERED"\n            else -> "QR RECORD FOUND"\n        }'''
status_new = '''        val hasMandatoryVisual = firstNonBlank(\n            record.optString("thumbnailBase64"),\n            record.optString("thumbnailJpegBase64"),\n            record.optString("thumb")\n        ).isNotBlank()\n        binding.tvResultStatus.text = when {\n            risk -> "REGISTERED · REVIEW REQUIRED"\n            registryBacked && !hasMandatoryVisual -> "REGISTERED · VISUAL INCOMPLETE"\n            registryBacked -> "VERIFIED · REGISTERED"\n            else -> "QR RECORD FOUND"\n        }'''
if status_old in s:
    s = s.replace(status_old, status_new, 1)

summary_old = '''        binding.tvResultSummary.text = when {\n            risk -> "Registry record found; location-integrity signals require review."\n            registryBacked -> "Evidence ID confirmed in the GeoStamp Public Registry."\n            else -> "GeoStamp QR record decoded; public registration is not confirmed."\n        }'''
summary_new = '''        binding.tvResultSummary.text = when {\n            risk -> "Registry record found; location-integrity signals require review."\n            registryBacked && !hasMandatoryVisual -> "Registry record found, but the mandatory evidence visual is unavailable."\n            registryBacked -> "Evidence ID and mandatory visual evidence confirmed for this record."\n            else -> "GeoStamp QR record decoded; public registration is not confirmed."\n        }'''
if summary_old in s:
    s = s.replace(summary_old, summary_new, 1)

# Do not expose PDF as complete if visual is missing.
button_old = '''        binding.btnViewReport.visibility = View.VISIBLE\n        binding.btnViewReport.text = "SHARE REPORT · PDF"'''
button_new = '''        binding.btnViewReport.visibility = if (hasMandatoryVisual) View.VISIBLE else View.GONE\n        binding.btnViewReport.text = "SHARE REPORT · PDF"'''
if button_old in s:
    s = s.replace(button_old, button_new, 1)

activity.write_text(s, encoding='utf-8')

print('Applied definitive mandatory visual evidence recovery + truthful verification status.')
