package com.axiominfratech.geostamp.verification

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.*

/**
 * Offline, zero-cost visit-history analysis based on GeoStamp's local metadata files.
 * No photo bytes are read and no network/backend is required.
 */
object EvidenceHistoryEngine {

    const val DEFAULT_RADIUS_M = 25.0
    const val DEFAULT_WINDOW_DAYS = 90
    const val DEFAULT_SESSION_GAP_MINUTES = 60

    data class HistorySummary(
        val radiusM: Double,
        val windowDays: Int,
        val sameDayPriorPhotos: Int,
        val photosInWindow: Int,
        val distinctVisitSessions: Int,
        val trustedPhotos: Int,
        val warningPhotos: Int,
        val riskPhotos: Int,
        val lastPreviousVisitMs: Long?,
        val firstVisitInWindowMs: Long?,
        val previousVerificationId: String?,
        val daysSinceLastVisit: Int?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("radiusM", radiusM)
            put("windowDays", windowDays)
            put("sameDayPriorPhotos", sameDayPriorPhotos)
            put("photosInWindow", photosInWindow)
            put("distinctVisitSessions", distinctVisitSessions)
            put("trustedPhotos", trustedPhotos)
            put("warningPhotos", warningPhotos)
            put("riskPhotos", riskPhotos)
            put("lastPreviousVisitMs", lastPreviousVisitMs ?: JSONObject.NULL)
            put("firstVisitInWindowMs", firstVisitInWindowMs ?: JSONObject.NULL)
            put("previousVerificationId", previousVerificationId ?: JSONObject.NULL)
            put("daysSinceLastVisit", daysSinceLastVisit ?: JSONObject.NULL)
        }
    }

    private data class PriorRecord(
        val timestamp: Long,
        val lat: Double,
        val lon: Double,
        val primaryValue: String,
        val secondaryValue: String,
        val workspaceMode: String,
        val verificationId: String,
        val gpsVerified: Boolean,
        val locationRisk: Boolean,
        val locationWarning: Boolean
    )

    fun analyze(
        metadataDir: File,
        latitude: Double,
        longitude: Double,
        captureTimestampMs: Long,
        workspaceMode: String,
        primaryEntity: String,
        secondaryEntity: String,
        radiusM: Double = DEFAULT_RADIUS_M,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        sessionGapMinutes: Int = DEFAULT_SESSION_GAP_MINUTES
    ): HistorySummary {
        val windowStart = captureTimestampMs - windowDays * 86_400_000L
        val records = metadataDir.listFiles { f -> f.isFile && f.extension.equals("meta", true) }
            .orEmpty()
            .mapNotNull(::readRecord)
            .filter { it.timestamp in windowStart until captureTimestampMs }
            .filter { prior ->
                val sameWorkspace = prior.workspaceMode.equals(workspaceMode, ignoreCase = true)
                val sameEntity = if (workspaceMode.equals("personal", ignoreCase = true)) {
                    prior.primaryValue.equals(primaryEntity.trim(), ignoreCase = true) &&
                        prior.secondaryValue.equals(secondaryEntity.trim(), ignoreCase = true)
                } else {
                    secondaryEntity.isNotBlank() &&
                        prior.secondaryValue.equals(secondaryEntity.trim(), ignoreCase = true)
                }
                val near = distanceMeters(latitude, longitude, prior.lat, prior.lon) <= radiusM
                near && sameWorkspace && sameEntity
            }
            .sortedBy { it.timestamp }

        val startOfDay = Calendar.getInstance().apply {
            timeInMillis = captureTimestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val sameDay = records.count { it.timestamp >= startOfDay }
        val gapMs = sessionGapMinutes * 60_000L
        var sessions = 0
        var previousTs: Long? = null
        records.forEach { rec ->
            if (previousTs == null || rec.timestamp - previousTs!! > gapMs) sessions++
            previousTs = rec.timestamp
        }

        val last = records.lastOrNull()
        val daysSince = last?.let {
            floor((captureTimestampMs - it.timestamp) / 86_400_000.0).toInt().coerceAtLeast(0)
        }

        return HistorySummary(
            radiusM = radiusM,
            windowDays = windowDays,
            sameDayPriorPhotos = sameDay,
            photosInWindow = records.size,
            distinctVisitSessions = sessions,
            trustedPhotos = records.count { it.gpsVerified && !it.locationRisk && !it.locationWarning },
            warningPhotos = records.count { !it.locationRisk && (it.locationWarning || !it.gpsVerified) },
            riskPhotos = records.count { it.locationRisk },
            lastPreviousVisitMs = last?.timestamp,
            firstVisitInWindowMs = records.firstOrNull()?.timestamp,
            previousVerificationId = last?.verificationId?.ifBlank { null },
            daysSinceLastVisit = daysSince
        )
    }

    private fun readRecord(file: File): PriorRecord? {
        return try {
            val j = JSONObject(file.readText())
            val lat = j.optDouble("lat", Double.NaN)
            val lon = j.optDouble("lon", Double.NaN)
            val ts = j.optLong("timestamp", 0L)

            if (!lat.isFinite() || !lon.isFinite() || ts <= 0L) {
                null
            } else {
                PriorRecord(
                    timestamp = ts,
                    lat = lat,
                    lon = lon,
                    primaryValue = j.optString("primaryValue", j.optString("operator", "")),
                    secondaryValue = j.optString("secondaryValue", j.optString("siteId", "")),
                    workspaceMode = j.optString("workspaceMode", "organization"),
                    verificationId = j.optString("verificationId", ""),
                    gpsVerified = j.optBoolean("gpsVerified", false),
                    locationRisk = j.optBoolean("locationIntegrityRisk", false),
                    locationWarning = j.optBoolean("locationIntegrityWarning", false)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earth = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return earth * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
