package com.axiominfratech.geostamp.verification

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Offline-first verification utilities.
 * No server, account or paid API is required for capture and sealing.
 */
object VerificationEngine {

    fun newEvidenceId(epochMs: Long = System.currentTimeMillis()): String {
        val year = java.text.SimpleDateFormat("yy", Locale.ENGLISH)
            .format(java.util.Date(epochMs))
        val random = UUID.randomUUID().toString()
            .replace("-", "")
            .take(12)
            .uppercase(Locale.ENGLISH)
        return "GST-$year-${random.take(4)}-${random.drop(4).take(4)}-${random.drop(8).take(4)}"
    }

    @Deprecated("Use newEvidenceId(). GeoStamp uses one permanent Evidence ID.")
    fun newVerificationId(epochMs: Long = System.currentTimeMillis()): String =
        newEvidenceId(epochMs)

    fun sha256(file: File): String = file.inputStream().use(::sha256)

    fun sha256(resolver: ContentResolver, uri: Uri): String? =
        resolver.openInputStream(uri)?.use(::sha256)

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Stable public verification entry point embedded in every stamped QR.
     *
     * The QR contains only the permanent Evidence ID URL. The public portal then
     * opens the registry-backed certificate. This keeps the QR compact, reliable,
     * and independent of metadata payload size while retaining one universal ID.
     */
    const val PUBLIC_VERIFY_BASE_URL =
        "https://ahz-creator.github.io/GeoStamp-Portal/"

    fun publicCertificateUrl(evidenceId: String): String =
        "$PUBLIC_VERIFY_BASE_URL?id=${Uri.encode(evidenceId.trim().uppercase(Locale.ENGLISH))}"

    @Suppress("UNUSED_PARAMETER")
    fun qrPayload(
        evidenceId: String,
        capturedAt: Long,
        locationRisk: Boolean,
        latitude: Double,
        longitude: Double,
        accuracyM: Double,
        workspaceMode: String,
        primaryValue: String,
        secondaryValue: String,
        maskedDeviceIdentity: String = "",
        deviceBrand: String = "",
        deviceModel: String = "",
        captureKeyFingerprint: String = "",
        markerVersion: Int = 4
    ): String = publicCertificateUrl(evidenceId)

    fun evidenceStatus(
        gpsVerified: Boolean,
        locationRisk: Boolean,
        hashPresent: Boolean
    ): IntegrityStatus = when {
        locationRisk -> IntegrityStatus.WARNING
        !hashPresent -> IntegrityStatus.UNAVAILABLE
        gpsVerified -> IntegrityStatus.PASS
        else -> IntegrityStatus.WARNING
    }
}
