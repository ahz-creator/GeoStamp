package com.axiominfratech.geostamp.verification

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import com.axiominfratech.geostamp.forensics.EvidenceAuditTrail

/** Public-safe local registry queue. */
object EvidenceRegistryOutbox {

    private const val ADMIN_URL = "https://ahz-creator.github.io/GeoStamp-Admin/"

    data class PendingRecord(
        val file: File,
        val verificationId: String,
        val capturedAt: Long,
        val primaryValue: String,
        val secondaryValue: String,
        val locationRisk: Boolean
    )

    fun enqueue(context: Context, fullMetadata: JSONObject): File {
        val evidenceId = fullMetadata.optString(
            "evidenceId",
            fullMetadata.optString("verificationId")
        ).trim()
        require(evidenceId.isNotBlank()) { "Evidence ID is required" }

        val publicRecord = JSONObject().apply {
            put("schemaVersion", 2)
            put("evidenceId", evidenceId)
            put("verificationId", evidenceId)
            put("registryStatus", "PUBLIC_RECORD")
            put("evidenceStatus", fullMetadata.optString("evidenceStatus", "REGISTERED"))
            put("capturedAt", fullMetadata.optLong("timestamp", System.currentTimeMillis()))
            put("workspaceMode", fullMetadata.optString("workspaceMode"))
            put("primaryValue", fullMetadata.optString("primaryValue"))
            put("secondaryValue", fullMetadata.optString("secondaryValue"))
            put("latitude", fullMetadata.optDouble("lat"))
            put("longitude", fullMetadata.optDouble("lon"))
            put("accuracyM", fullMetadata.optDouble("accuracy"))
            put("locationRisk", fullMetadata.optBoolean("locationIntegrityRisk", false))
            put("imageSha256", fullMetadata.optString("imageSha256"))
            put("maskedGeoStampDeviceIdentity", fullMetadata.optString("maskedGeoStampDeviceIdentity"))
            put("deviceManufacturer", fullMetadata.optString("deviceManufacturer"))
            put("deviceBrand", fullMetadata.optString("deviceBrand"))
            put("deviceHardwareModel", fullMetadata.optString("deviceHardwareModel"))
            put("capturePublicKey", fullMetadata.optString("capturePublicKey"))
            put("captureKeyFingerprint", fullMetadata.optString("captureKeyFingerprint"))
            put("captureKeyHardwareBacked", fullMetadata.optBoolean("captureKeyHardwareBacked", false))
            put("captureKeySecurityLevel", fullMetadata.optString("captureKeySecurityLevel"))
            put("captureSignature", fullMetadata.optString("captureSignature"))
            put("captureSignatureAlgorithm", fullMetadata.optString("captureSignatureAlgorithm"))
            put("captureSignedPayload", fullMetadata.optString("captureSignedPayload"))
            put("markerVersion", fullMetadata.optInt("markerVersion", 1))

            // Compact mobile slip fields. These are public-safe, compressed values.
            copyIfPresent(fullMetadata, this, "thumbnailBase64")
            copyIfPresent(fullMetadata, this, "thumbnailJpegBase64")
            put("visualEvidenceRequired", true)
            copyIfPresent(fullMetadata, this, "thumbnailMimeType")
            copyIfPresent(fullMetadata, this, "thumbnailSha256")
            copyIfPresent(fullMetadata, this, "aiVisualSummary")
            copyIfPresent(fullMetadata, this, "aiVisualPurpose")
            copyIfPresent(fullMetadata, this, "aiVisualSummaryStatus")
            copyIfPresent(fullMetadata, this, "aiVisualSummaryProvider")
            copyIfPresent(fullMetadata, this, "operatorSessionId")
            copyIfPresent(fullMetadata, this, "operatorSessionStartedAt")
            copyIfPresent(fullMetadata, this, "operatorSessionLastActivityAt")
            copyIfPresent(fullMetadata, this, "operatorSessionInactivityMinutes")
            copyIfPresent(fullMetadata, this, "sitePhotosBefore")
            copyIfPresent(fullMetadata, this, "sitePhotosAfter")
            copyIfPresent(fullMetadata, this, "operatorSessionPhotosBefore")
            copyIfPresent(fullMetadata, this, "operatorSessionPhotosAfter")
            copyIfPresent(fullMetadata, this, "siteSessionPhotoTotal")
            copyIfPresent(fullMetadata, this, "operatorSessionPhotoTotal")
            copyIfPresent(fullMetadata, this, "operatorSessionSitesVisited")
            copyIfPresent(fullMetadata, this, "operatorSessionSitesVisitedBefore")
            copyIfPresent(fullMetadata, this, "operatorSessionClockOutAt")
            copyIfPresent(fullMetadata, this, "operatorSessionClockOutReason")
            copyIfPresent(fullMetadata, this, "sessionFinalized")
            copyIfPresent(fullMetadata, this, "siteDistanceM")
            copyIfPresent(fullMetadata, this, "siteRadiusM")

            put("createdAt", System.currentTimeMillis())
            put("syncAttemptCount", 0)
            put("nextRetryAt", 0L)
        }

        val dir = queueDir(context)
        val safeName = safeFileName(evidenceId)
        require(safeName.isNotBlank()) { "Evidence ID cannot be converted to a safe filename" }
        val target = File(dir, "$safeName.json")
        if (target.exists()) {
            val existing = runCatching { JSONObject(target.readText()) }.getOrNull()
            val existingHash = existing?.optString("imageSha256").orEmpty()
            val newHash = publicRecord.optString("imageSha256")
            if (existingHash.isNotBlank() && newHash.isNotBlank() && existingHash.equals(newHash, true)) {
                return target
            }
            throw IllegalStateException("Evidence ID already exists in the local registry queue: $evidenceId")
        }

        // Atomic write: never leave a half-written registry record if the app is
        // killed or the device loses power during capture/finalization.
        val temp = File(dir, ".${safeName}.${System.nanoTime()}.tmp")
        temp.writeText(publicRecord.toString(2), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("Unable to commit evidence record to the local registry queue")
        }
        runCatching {
            EvidenceAuditTrail.append(context, EvidenceAuditTrail.Event(
                evidenceId = evidenceId,
                type = EvidenceAuditTrail.EventType.STORED,
                timestamp = System.currentTimeMillis(),
                details = JSONObject().put("queueFile", target.name)
            ))
        }
        return target
    }

    private fun copyIfPresent(source: JSONObject, target: JSONObject, key: String) {
        if (source.has(key)) target.put(key, source.opt(key))
    }

    fun pending(context: Context): List<PendingRecord> =
        queueDir(context)
            .listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    PendingRecord(
                        file = file,
                        verificationId = json.optString("verificationId", file.nameWithoutExtension),
                        capturedAt = json.optLong("capturedAt", file.lastModified()),
                        primaryValue = json.optString("primaryValue"),
                        secondaryValue = json.optString("secondaryValue"),
                        locationRisk = json.optBoolean("locationRisk", false)
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.capturedAt }

    fun adminImportUrl(record: PendingRecord): String {
        val json = record.file.readText()
        val encoded = Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$ADMIN_URL?e=${URLEncoder.encode(encoded, "UTF-8")}"
    }

    suspend fun publish(context: Context, record: PendingRecord): RegistryPublisher.PublishResult {
        val result = RegistryPublisher.publish(context, record.file)
        if (result.success) markPublished(context, record, result.registryUrl)
        return result
    }

    fun recordRetry(file: File, attempt: Int, nextRetryAt: Long) {
        if (!file.exists()) return
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        json.put("syncAttemptCount", attempt.coerceAtLeast(0))
        json.put("nextRetryAt", nextRetryAt.coerceAtLeast(0L))
        val temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        temp.writeText(json.toString(2), Charsets.UTF_8)
        if (!temp.renameTo(file)) temp.delete()
    }

    fun publishFileSilently(context: Context, file: File, registryUrl: String? = null): Boolean {
        if (!file.exists()) return false
        val publishedDir = File(context.filesDir, "evidence_registry_published").also { it.mkdirs() }
        val target = File(publishedDir, file.name)
        val json = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (json != null) {
            json.put("registryStatus", "PUBLIC_RECORD")
            json.put("publishedAt", System.currentTimeMillis())
            json.put("syncAttemptCount", json.optInt("syncAttemptCount", 0))
            json.put("nextRetryAt", 0L)
            if (!registryUrl.isNullOrBlank()) json.put("registryUrl", registryUrl)
            target.writeText(json.toString(2))
            file.delete()
            runCatching {
                EvidenceAuditTrail.append(context, EvidenceAuditTrail.Event(
                    evidenceId = json.optString("evidenceId"),
                    type = EvidenceAuditTrail.EventType.REGISTERED,
                    timestamp = System.currentTimeMillis(),
                    reference = registryUrl.orEmpty(),
                    details = JSONObject().put("registryStatus", "PUBLIC_RECORD")
                ))
            }
            return true
        }
        return false
    }

    private fun markPublished(context: Context, record: PendingRecord, registryUrl: String?): Boolean =
        publishFileSilently(context, record.file, registryUrl)

    fun publishedRecord(context: Context, evidenceId: String): JSONObject? {
        val file = File(
            File(context.filesDir, "evidence_registry_published"),
            "${safeFileName(evidenceId)}.json"
        )
        return if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() else null
    }

    fun delete(record: PendingRecord): Boolean = record.file.delete()

    fun clearAll(context: Context): Int {
        var deleted = 0
        pending(context).forEach { if (it.file.delete()) deleted++ }
        return deleted
    }

    private fun queueDir(context: Context): File =
        File(context.filesDir, "evidence_registry_outbox").also { it.mkdirs() }

    private fun safeFileName(value: String): String =
        value.trim().lowercase(Locale.ENGLISH)
            .replace(Regex("[^a-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
}
