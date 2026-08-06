package com.axiominfratech.geostamp.verification

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Offline-first verification utilities.
 * No server, account or paid API is required.
 */
object VerificationEngine {

    fun newEvidenceId(epochMs: Long = System.currentTimeMillis()): String {
        val year = java.text.SimpleDateFormat("yy", Locale.ENGLISH).format(java.util.Date(epochMs))
        val random = UUID.randomUUID().toString().replace("-", "").take(12).uppercase(Locale.ENGLISH)
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
     * Public verification URL embedded in the QR code.
     *
     * The portal is a static GitHub Pages site. Change this constant only if the
     * final Pages URL differs. The evidence payload is Base64-URL encoded and is
     * decoded entirely in the receiver's browser; no photo is uploaded.
     */
    const val PUBLIC_VERIFY_BASE_URL =
        "https://ahz-creator.github.io/GeoStamp-Portal/"

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
        markerVersion: Int = 3
    ): String {
        val compact = JSONObject().apply {
            put("v", markerVersion)
            put("id", evidenceId)
            put("ts", capturedAt)
            put("lr", if (locationRisk) 1 else 0)
            put("lat", latitude)
            put("lon", longitude)
            put("acc", accuracyM)
            put("wm", workspaceMode)
            put("p", primaryValue)
            put("s", secondaryValue)

            // Device-of-capture summary. These values are compact and public-safe.
            if (maskedDeviceIdentity.isNotBlank()) put("gdi", maskedDeviceIdentity)
            if (deviceBrand.isNotBlank()) put("db", deviceBrand)
            if (deviceModel.isNotBlank()) put("dm", deviceModel)
            if (captureKeyFingerprint.isNotBlank()) {
                put("kf", captureKeyFingerprint.take(16))
            }

            put("issuer", "Axiom InfraTech")
        }.toString()

        val encoded = Base64.encodeToString(
            compact.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$PUBLIC_VERIFY_BASE_URL?e=$encoded"
    }


    fun evidenceStatus(gpsVerified: Boolean, locationRisk: Boolean, hashPresent: Boolean): IntegrityStatus = when {
        locationRisk -> IntegrityStatus.WARNING
        !hashPresent -> IntegrityStatus.UNAVAILABLE
        gpsVerified -> IntegrityStatus.PASS
        else -> IntegrityStatus.WARNING
    }
}
