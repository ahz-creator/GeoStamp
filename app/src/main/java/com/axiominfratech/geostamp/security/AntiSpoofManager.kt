package com.axiominfratech.geostamp.security

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AntiSpoofManager — GPS spoofing detection (production-safe).
 *
 * FIXES vs original:
 *  - Removed ALLOW_MOCK_LOCATION check: this API is deprecated and returns "1"
 *    on ALL modern Android phones regardless of mock state, falsely blocking
 *    every real device.
 *  - Accuracy gate relaxed to 100m: 30m is only achievable outdoors with a
 *    clear sky view for 30+ seconds. 100m still blocks WiFi-grade spoofing
 *    while allowing real GPS fixes to register quickly.
 *  - Network provider no longer hard-rejected: accepted as coarse fallback
 *    while GPS hardware warms up. The stamp will show "coarse" indicator.
 *  - Speed check retained (teleport detection).
 *  - isMock flag check retained (API 31+ is reliable).
 *  - Spoof app detection retained.
 *  - Altitude sanity check retained.
 */
class AntiSpoofManager(private val context: Context) {

    companion object {
        private const val MAX_ACCEPTABLE_ACCURACY_M = 100f      // was 30m — too strict
        private const val MAX_PLAUSIBLE_SPEED_MS = 55.5f        // ~200 km/h
        private const val MAX_ALTITUDE_PAKISTAN_M = 9000.0
        private const val MIN_ALTITUDE_PAKISTAN_M = -50.0

        private val SPOOF_PACKAGES = setOf(
            "com.lexa.fakegps",
            "com.blogspot.newapphorizons.fakegps",
            "com.incorporateapps.fakegps.fre",
            "com.fake.gps.location",
            "com.fakegps.mock",
            "com.gmd.fakegps",
            "net.marlove.fakegps",
            "com.lkstudios.fakegpsjoystick",
            "com.theappninjas.gpsjoystick",
            "com.rosteam.gpsemulator",
            "com.incorporateapps.fakegps",
            "com.mockgps",
            "com.fly.gps",
            "com.uc.premiumgps",
            "de.robv.android.xposed.installer",
            "com.topjohnwu.magisk",
            "io.github.vvb2060.magisk",
        )
    }

    private var lastValidLocation: Location? = null

    data class ValidationResult(
        val isValid: Boolean,
        val reason: String,
        val threatLevel: ThreatLevel = ThreatLevel.NONE
    )

    enum class ThreatLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    suspend fun validateLocation(location: Location): ValidationResult = withContext(Dispatchers.IO) {

        // ── Layer 1: isMock flag — reliable on API 31+ ────────────────
        if (isMockLocation(location)) {
            return@withContext ValidationResult(
                isValid = false,
                reason = "Mock location detected",
                threatLevel = ThreatLevel.CRITICAL
            )
        }

        // ── Layer 2: Accuracy gate (relaxed to 100m) ──────────────────
        // 100m rejects WiFi-level spoofing but accepts normal GPS fixes
        if (location.accuracy > MAX_ACCEPTABLE_ACCURACY_M) {
            return@withContext ValidationResult(
                isValid = false,
                reason = "Weak signal (±${location.accuracy.toInt()}m). Move outdoors for GPS lock.",
                threatLevel = ThreatLevel.LOW
            )
        }

        // ── Layer 3: Altitude sanity ───────────────────────────────────
        if (location.hasAltitude()) {
            val alt = location.altitude
            if (alt < MIN_ALTITUDE_PAKISTAN_M || alt > MAX_ALTITUDE_PAKISTAN_M) {
                return@withContext ValidationResult(
                    isValid = false,
                    reason = "Implausible altitude: ${alt.toInt()}m",
                    threatLevel = ThreatLevel.HIGH
                )
            }
        }

        // ── Layer 4: Speed / teleport detection ───────────────────────
        lastValidLocation?.let { last ->
            val timeDeltaSec = (location.time - last.time) / 1000f
            if (timeDeltaSec > 0 && timeDeltaSec < 300f) { // only check if < 5 min gap
                val distanceM = last.distanceTo(location)
                val speedMs = distanceM / timeDeltaSec
                if (speedMs > MAX_PLAUSIBLE_SPEED_MS) {
                    return@withContext ValidationResult(
                        isValid = false,
                        reason = "Impossible location jump detected — possible spoofing",
                        threatLevel = ThreatLevel.CRITICAL
                    )
                }
            }
        }

        // ── Layer 5: Spoof app detection ──────────────────────────────
        val spoofApp = detectSpoofApps()
        if (spoofApp != null) {
            return@withContext ValidationResult(
                isValid = false,
                reason = "GPS spoofing app detected: $spoofApp",
                threatLevel = ThreatLevel.CRITICAL
            )
        }

        lastValidLocation = location
        ValidationResult(isValid = true, reason = "OK", threatLevel = ThreatLevel.NONE)
    }

    private fun isMockLocation(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    private fun detectSpoofApps(): String? {
        val pm = context.packageManager
        for (pkg in SPOOF_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: Exception) { }
        }
        return null
    }

    fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT)
    }

    fun auditDeviceSecurity(): List<SecurityWarning> {
        val warnings = mutableListOf<SecurityWarning>()

        if (isEmulator()) {
            warnings.add(SecurityWarning(
                "Emulator detected",
                "GeoStamp requires a physical device with real GPS hardware.",
                ThreatLevel.CRITICAL
            ))
        }

        detectSpoofApps()?.let {
            warnings.add(SecurityWarning(
                "Spoofing app installed",
                "Remove $it before using GeoStamp.",
                ThreatLevel.CRITICAL
            ))
        }

        return warnings
    }

    data class SecurityWarning(
        val title: String,
        val message: String,
        val level: ThreatLevel
    )
}
