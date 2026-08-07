package com.axiominfratech.geostamp.verification

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
