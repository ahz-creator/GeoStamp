package com.axiominfratech.geostamp.verification

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class IntegrityStatus { PASS, WARNING, FAIL, UNAVAILABLE }

data class LocationIntegrity(
    val status: IntegrityStatus,
    val mockLocationDetected: Boolean,
    val suppliedLatitude: Double,
    val suppliedLongitude: Double,
    val accuracyM: Float,
    val provider: String,
    val reason: String = ""
)

data class EvidenceRecord(
    val verificationId: String,
    val capturedAtEpochMs: Long,
    val workspaceId: String,
    val organizationId: String? = null,
    val projectId: String? = null,
    val locationId: String? = null,
    val imageSha256: String,
    val appVersion: String,
    val deviceModel: String,
    val locationIntegrity: LocationIntegrity,
    val imageIntegrity: IntegrityStatus = IntegrityStatus.PASS,
    val markerVersion: Int = 1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("verificationId", verificationId)
        put("capturedAt", capturedAtEpochMs)
        put("workspaceId", workspaceId)
        put("organizationId", organizationId ?: JSONObject.NULL)
        put("projectId", projectId ?: JSONObject.NULL)
        put("locationId", locationId ?: JSONObject.NULL)
        put("imageSha256", imageSha256)
        put("appVersion", appVersion)
        put("deviceModel", deviceModel)
        put("imageIntegrity", imageIntegrity.name)
        put("markerVersion", markerVersion)
        put("locationIntegrity", JSONObject().apply {
            put("status", locationIntegrity.status.name)
            put("mockLocationDetected", locationIntegrity.mockLocationDetected)
            put("suppliedLatitude", locationIntegrity.suppliedLatitude)
            put("suppliedLongitude", locationIntegrity.suppliedLongitude)
            put("accuracyM", locationIntegrity.accuracyM)
            put("provider", locationIntegrity.provider)
            put("reason", locationIntegrity.reason)
        })
    }

    companion object {
        fun newVerificationId(): String = "AXM-" + UUID.randomUUID().toString()
            .replace("-", "").take(16).uppercase()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
