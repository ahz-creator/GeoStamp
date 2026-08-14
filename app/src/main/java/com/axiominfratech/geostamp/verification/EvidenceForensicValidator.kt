package com.axiominfratech.geostamp.verification

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import org.json.JSONObject

/**
 * P21 forensic validation: validates the evidence record itself instead of
 * treating the presence of a hash/signature as proof that those values match.
 * No network access is performed here; registry confirmation is supplied by
 * the caller and kept separate from local cryptographic checks.
 */
object EvidenceForensicValidator {

    enum class State { PASS, REVIEW, FAIL }

    data class Result(
        val state: State,
        val idConsistent: Boolean,
        val hashFormatValid: Boolean,
        val signatureVerified: Boolean?,
        val signedPayloadConsistent: Boolean,
        val thumbnailHashVerified: Boolean?,
        val reasons: List<String>
    ) {
        val isCryptographicallyValid: Boolean
            get() = hashFormatValid && signatureVerified == true && signedPayloadConsistent
    }

    fun validate(record: JSONObject, requestedId: String? = null): Result {
        val reasons = mutableListOf<String>()
        val id = first(record, "evidenceId", "verificationId", "id")
        val requested = requestedId?.trim()?.uppercase(Locale.ENGLISH).orEmpty()
        val idConsistent = id.isNotBlank() && (requested.isBlank() || id.equals(requested, true))
        if (idConsistent) reasons += "PASS · Evidence ID is internally consistent"
        else reasons += "FAIL · Evidence ID is missing or does not match the requested ID"

        val hash = first(record, "imageSha256", "sha256", "imageHash")
        val hashFormatValid = HASH_REGEX.matches(hash)
        if (hashFormatValid) reasons += "PASS · SHA-256 format is valid"
        else reasons += "FAIL · Image SHA-256 is missing or malformed"

        val payload = record.optString("captureSignedPayload").trim()
        val signature = record.optString("captureSignature").trim()
        val publicKey = record.optString("capturePublicKey").trim()
        val signatureVerified: Boolean? = if (payload.isBlank() || signature.isBlank() || publicKey.isBlank()) {
            reasons += "REVIEW · Capture signature material is incomplete"
            null
        } else {
            runCatching {
                val keyBytes = Base64.decode(publicKey, Base64.DEFAULT)
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
                Signature.getInstance(record.optString("captureSignatureAlgorithm", "SHA256withECDSA")).run {
                    initVerify(key)
                    update(payload.toByteArray(Charsets.UTF_8))
                    verify(Base64.decode(signature, Base64.DEFAULT))
                }
            }.getOrElse { false }.also {
                reasons += if (it) "PASS · Capture signature cryptographically verified" else "FAIL · Capture signature verification failed"
            }
        }

        val signedPayloadConsistent = signedPayloadMatchesRecord(payload, record)
        if (payload.isBlank()) {
            // Already explained by signature material state above.
        } else if (signedPayloadConsistent) {
            reasons += "PASS · Signed payload matches recorded evidence fields"
        } else {
            reasons += "FAIL · Signed payload does not match recorded evidence fields"
        }

        val thumbnailHash = first(record, "thumbnailSha256")
        val thumbnail = first(record, "thumbnailBase64", "thumbnailJpegBase64", "thumb")
        val thumbnailHashVerified: Boolean? = if (thumbnailHash.isBlank() || thumbnail.isBlank()) {
            null
        } else {
            val ok = runCatching {
                val bytes = Base64.decode(thumbnail, Base64.DEFAULT)
                EvidenceRecord.sha256(bytes).equals(thumbnailHash, true)
            }.getOrDefault(false)
            reasons += if (ok) "PASS · Cached evidence thumbnail hash matches" else "FAIL · Cached evidence thumbnail hash mismatch"
            ok
        }

        val state = when {
            !idConsistent || !hashFormatValid || signatureVerified == false || !signedPayloadConsistent || thumbnailHashVerified == false -> State.FAIL
            signatureVerified == true && (thumbnailHashVerified != false) -> State.PASS
            else -> State.REVIEW
        }

        return Result(state, idConsistent, hashFormatValid, signatureVerified, signedPayloadConsistent, thumbnailHashVerified, reasons)
    }

    private fun signedPayloadMatchesRecord(payload: String, record: JSONObject): Boolean {
        if (payload.isBlank()) return false
        val parts = payload.split('|')
        if (parts.size < 13 || parts[0] != "GEOSTAMP_CAPTURE_V1") return false

        fun matches(index: Int, expected: String): Boolean = expected.isBlank() || parts[index] == expected

        val id = first(record, "evidenceId", "verificationId", "id")
        val imageHash = first(record, "imageSha256", "sha256", "imageHash").lowercase(Locale.ENGLISH)
        val capturedAt = record.optLong("capturedAt", record.optLong("timestamp", record.optLong("ts", 0L)))
        val lat = record.optDouble("latitude", record.optDouble("lat", Double.NaN))
        val lon = record.optDouble("longitude", record.optDouble("lon", Double.NaN))
        val accuracy = record.optDouble("accuracyM", record.optDouble("accuracy", record.optDouble("acc", Double.NaN)))
        val workspace = record.optString("workspaceMode").trim()
        val primary = first(record, "primaryValue", "operator", "p")
        val secondary = first(record, "secondaryValue", "siteId", "s")
        val riskKnown = record.has("locationRisk") || record.has("locationIntegrityRisk") || record.has("lr")
        val risk = record.optBoolean("locationRisk", false) || record.optBoolean("locationIntegrityRisk", false) || record.optInt("lr", 0) == 1
        val fingerprint = record.optString("captureKeyFingerprint").trim()
        val deviceIdentity = record.optString("geoStampDeviceIdentity").trim()

        if (!matches(1, id.trim())) return false
        if (!matches(2, imageHash)) return false
        if (capturedAt > 0L && !matches(3, capturedAt.toString())) return false
        if (lat.isFinite() && !matches(4, "%.7f".format(Locale.US, lat))) return false
        if (lon.isFinite() && !matches(5, "%.7f".format(Locale.US, lon))) return false
        if (accuracy.isFinite() && !matches(6, "%.2f".format(Locale.US, accuracy))) return false
        if (!matches(7, workspace)) return false
        if (!matches(8, primary)) return false
        if (!matches(9, secondary)) return false
        if (riskKnown && !matches(10, risk.toString())) return false
        if (!matches(11, deviceIdentity)) return false
        if (!matches(12, fingerprint)) return false
        return true
    }

    private fun first(record: JSONObject, vararg keys: String): String =
        keys.asSequence().map { record.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()

    private val HASH_REGEX = Regex("^[0-9a-fA-F]{64}$")
}
