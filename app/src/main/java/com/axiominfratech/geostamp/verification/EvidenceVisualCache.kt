package com.axiominfratech.geostamp.verification

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
