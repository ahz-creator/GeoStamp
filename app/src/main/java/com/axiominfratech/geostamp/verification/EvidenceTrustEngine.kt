package com.axiominfratech.geostamp.verification

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Produces a transparent evidence-confidence summary from signals that are
 * actually present in a GeoStamp record. It does not claim that the scene itself
 * is true and does not invent missing evidence.
 */
object EvidenceTrustEngine {

    data class Assessment(
        val score: Int,
        val level: String,
        val conclusion: String,
        val findings: List<String>,
        val timeline: List<String>
    )

    fun assess(record: JSONObject, registryBacked: Boolean): Assessment {
        var score = 0
        val findings = mutableListOf<String>()
        val timeline = mutableListOf<String>()

        val id = record.optString("verificationId", record.optString("id"))
        if (id.isNotBlank()) {
            score += 10
            findings += "PASS · Verification ID is present"
        } else {
            findings += "LIMITED · Verification ID is unavailable"
        }

        if (registryBacked) {
            score += 25
            findings += "PASS · Public registry record matched"
        } else {
            score += 8
            findings += "LIMITED · Embedded QR record only; public registry not confirmed"
        }

        val capturedAt = record.optLong("capturedAt", record.optLong("timestamp", record.optLong("ts", 0L)))
        if (capturedAt > 0L) {
            score += 15
            findings += "PASS · Capture timestamp is recorded"
            timeline += "${formatTime(capturedAt)} · Capture recorded"
        } else {
            findings += "LIMITED · Capture timestamp is unavailable"
        }

        val lat = record.optDouble("latitude", record.optDouble("lat", Double.NaN))
        val lon = record.optDouble("longitude", record.optDouble("lon", Double.NaN))
        if (lat.isFinite() && lon.isFinite()) {
            score += 12
            findings += "PASS · Coordinates are recorded"
        } else {
            findings += "LIMITED · Coordinates are unavailable"
        }

        val accuracy = record.optDouble("accuracyM", record.optDouble("accuracy", record.optDouble("acc", Double.NaN)))
        if (accuracy.isFinite() && accuracy > 0.0) {
            score += when {
                accuracy <= 10.0 -> 10
                accuracy <= 30.0 -> 7
                else -> 3
            }
            findings += when {
                accuracy <= 10.0 -> "PASS · GPS accuracy is high (±%.1f m)".format(Locale.US, accuracy)
                accuracy <= 30.0 -> "REVIEW · GPS accuracy is moderate (±%.1f m)".format(Locale.US, accuracy)
                else -> "REVIEW · GPS accuracy is weak (±%.1f m)".format(Locale.US, accuracy)
            }
        } else {
            findings += "LIMITED · GPS accuracy is unavailable"
        }

        val locationRisk = record.optBoolean("locationRisk", false) ||
            record.optBoolean("locationIntegrityRisk", false) ||
            record.optInt("lr", 0) == 1
        if (locationRisk) {
            findings += "RISK · Location-integrity indicator was recorded"
        } else {
            score += 15
            findings += "PASS · No mock-location indicator is recorded"
        }

        val hash = record.optString("imageSha256", record.optString("sha256"))
        if (hash.length >= 32) {
            score += 10
            findings += "PASS · Image SHA-256 is available"
            timeline += "Image integrity hash recorded"
        } else {
            findings += "LIMITED · Image hash is not publicly available"
        }

        val markerVersion = record.optInt("markerVersion", record.optInt("v", 0))
        if (markerVersion > 0) {
            score += 3
            findings += "PASS · GeoStamp marker version $markerVersion is identified"
        }

        val publishedAt = record.optLong("publishedAt", 0L)
        if (publishedAt > 0L) timeline += "${formatTime(publishedAt)} · Public registry record published"
        if (registryBacked) timeline += "Current check · Public registry confirmed"
        else timeline += "Current check · Embedded QR decoded"

        score = score.coerceIn(0, 100)
        val level = when {
            locationRisk -> "LOCATION REVIEW"
            score >= 85 -> "HIGH CONFIDENCE"
            score >= 65 -> "MODERATE CONFIDENCE"
            else -> "LIMITED EVIDENCE"
        }
        val conclusion = when {
            locationRisk -> "GeoStamp evidence was found, but the recorded location signals require review."
            registryBacked && score >= 85 -> "The evidence record is strongly supported by the available GeoStamp integrity signals."
            registryBacked -> "The public record is confirmed, but some integrity signals are unavailable or limited."
            else -> "A structured GeoStamp QR record was decoded. Public registration and unavailable signals must be reviewed separately."
        }

        return Assessment(score, level, conclusion, findings, timeline)
    }

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(epochMs))
}
