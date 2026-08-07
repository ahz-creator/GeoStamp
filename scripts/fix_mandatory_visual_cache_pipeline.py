from pathlib import Path

root = Path(__file__).resolve().parents[1]

cache = root/'app/src/main/java/com/axiominfratech/geostamp/verification/EvidenceVisualCache.kt'
cache.write_text(r'''package com.axiominfratech.geostamp.verification

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Durable local visual cache keyed by Evidence ID.
 *
 * The public registry remains the source of truth for verification. This cache only guarantees
 * that the capture device never loses the mandatory thumbnail while a backend deployment catches
 * up or a registry response omits the visual payload.
 */
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
}
''', encoding='utf-8')

vm = root/'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
if 'import com.axiominfratech.geostamp.verification.EvidenceVisualCache' not in s:
    anchor = 'import com.axiominfratech.geostamp.verification.EvidenceSlipMetadata'
    s = s.replace(anchor, anchor+'\nimport com.axiominfratech.geostamp.verification.EvidenceVisualCache')

old = '''                val slipThumbnailBase64 = withContext(Dispatchers.IO) {
                    EvidenceSlipMetadata.thumbnailBase64(stampedFile)
                }
                val slipSession = EvidenceSlipMetadata.sessionSnapshot(operatorSession, siteIdStr)'''
new = '''                val slipThumbnailBase64 = withContext(Dispatchers.IO) {
                    EvidenceSlipMetadata.thumbnailBase64(stampedFile)
                }
                if (slipThumbnailBase64.isBlank()) {
                    throw IllegalStateException("Mandatory evidence thumbnail generation failed; evidence was not registered.")
                }
                EvidenceVisualCache.save(app, evidenceId, slipThumbnailBase64)
                val slipSession = EvidenceSlipMetadata.sessionSnapshot(operatorSession, siteIdStr)'''
if old not in s:
    print('WARN: thumbnail generation anchor not found in MainViewModel.kt')
else:
    s = s.replace(old,new)
vm.write_text(s,encoding='utf-8')

act = root/'app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt'
s = act.read_text(encoding='utf-8')
if 'import com.axiominfratech.geostamp.verification.EvidenceVisualCache' not in s:
    anchor='import com.axiominfratech.geostamp.verification.EvidenceRegistryOutbox'
    s=s.replace(anchor,anchor+'\nimport com.axiominfratech.geostamp.verification.EvidenceVisualCache')

old='''    private fun mergeLocalVisualFields(remote: JSONObject, evidenceId: String): JSONObject {
        val local = EvidenceRegistryOutbox.publishedRecord(this, evidenceId) ?: return remote
        val merged = JSONObject(remote.toString())'''
new='''    private fun mergeLocalVisualFields(remote: JSONObject, evidenceId: String): JSONObject {
        val published = EvidenceRegistryOutbox.publishedRecord(this, evidenceId)
        val visual = EvidenceVisualCache.load(this, evidenceId)
        val merged = JSONObject(remote.toString())
        val local = published ?: visual ?: return merged'''
if old not in s:
    print('WARN: mergeLocalVisualFields anchor not found')
else:
    s=s.replace(old,new)

# After normal local merge, explicitly restore visual cache even when published local record exists but lacks thumbnail.
needle='''        keys.forEach { key ->
            val remoteMissing = !merged.has(key) || merged.isNull(key) || merged.optString(key).isBlank()
            if (remoteMissing && local.has(key) && !local.isNull(key)) merged.put(key, local.opt(key))
        }
        return merged'''
replacement='''        keys.forEach { key ->
            val remoteMissing = !merged.has(key) || merged.isNull(key) || merged.optString(key).isBlank()
            if (remoteMissing && local.has(key) && !local.isNull(key)) merged.put(key, local.opt(key))
        }
        if (merged.optString("thumbnailBase64").isBlank()) {
            val cachedVisual = visual ?: EvidenceVisualCache.load(this, evidenceId)
            val cachedThumb = cachedVisual?.optString("thumbnailBase64").orEmpty()
            if (cachedThumb.isNotBlank()) {
                merged.put("thumbnailBase64", cachedThumb)
                merged.put("thumbnailMimeType", "image/jpeg")
                if (cachedVisual?.has("thumbnailSha256") == true) merged.put("thumbnailSha256", cachedVisual.optString("thumbnailSha256"))
            }
        }
        return merged'''
if needle not in s:
    print('WARN: local merge tail anchor not found')
else:
    s=s.replace(needle,replacement)

# Do not call a record fully verified on the phone when the mandatory visual is absent.
needle2='''        binding.tvResultStatus.text = when {
            risk -> "REGISTERED · REVIEW REQUIRED"
            registryBacked -> "VERIFIED · REGISTERED"
            else -> "QR RECORD FOUND"
        }'''
replacement2='''        val hasVisual = firstNonBlank(
            record.optString("thumbnailBase64"), record.optString("thumbnailJpegBase64"), record.optString("thumb")
        ).isNotBlank()
        binding.tvResultStatus.text = when {
            risk -> "REGISTERED · REVIEW REQUIRED"
            registryBacked && hasVisual -> "VERIFIED · REGISTERED"
            registryBacked -> "REGISTERED · VISUAL INCOMPLETE"
            else -> "QR RECORD FOUND"
        }'''
if needle2 in s:
    s=s.replace(needle2,replacement2)
act.write_text(s,encoding='utf-8')

print('Applied mandatory visual pipeline fix: hard fail on thumbnail generation + durable Evidence ID visual cache + verification fallback.')
