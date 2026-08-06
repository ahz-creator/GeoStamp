package com.axiominfratech.geostamp.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import com.axiominfratech.geostamp.security.AntiSpoofManager
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LocationEngine — FusedLocationProvider with anti-spoof validation.
 *
 * GPS FIX STRATEGY (fastest lock):
 *  1. Immediately emit cached lastLocation — instant if available.
 *  2. Start continuous HIGH_ACCURACY updates; emit on each fix.
 *  3. setWaitForAccurateLocation(false) — never block.
 *  4. Geocoding runs with a 2s timeout; on timeout show coords only.
 */
class LocationEngine(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val antiSpoof = AntiSpoofManager(context)

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).apply {
        setMinUpdateIntervalMillis(1000L)
        setMinUpdateDistanceMeters(0f)
        setMaxUpdateDelayMillis(4000L)
        setGranularity(Granularity.GRANULARITY_FINE)
        setWaitForAccurateLocation(false)
    }.build()

    data class GeoStampLocation(
        val location: Location,
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float,
        val altitudeM: Double?,
        val bearing: Float,
        val addressLine: String,
        val city: String,
        val province: String,
        val timestampMs: Long,
        val isVerified: Boolean,
        val isCoarse: Boolean = false,
        val mockLocationDetected: Boolean = false,
        val integrityReason: String = "",
        val providerName: String = location.provider ?: "unknown"
    ) {
        val latDMS: String get() = toDMS(latitude, isLat = true)
        val lngDMS: String get() = toDMS(longitude, isLat = false)
        val coordDecimal: String get() = "%.6f, %.6f".format(latitude, longitude)

        private fun toDMS(value: Double, isLat: Boolean): String {
            val abs = Math.abs(value)
            val deg = abs.toInt()
            val minFull = (abs - deg) * 60
            val min = minFull.toInt()
            val sec = (minFull - min) * 60
            val dir = when {
                isLat && value >= 0 -> "N"
                isLat -> "S"
                value >= 0 -> "E"
                else -> "W"
            }
            return "%d°%d'%.2f\"%s".format(deg, min, sec, dir)
        }
    }

    @SuppressLint("MissingPermission")
    fun locationFlow(): Flow<Result<GeoStampLocation>> = callbackFlow {

        // Step 1: Emit cached last location immediately (zero wait)
        fusedClient.lastLocation.addOnSuccessListener { cached ->
            if (cached != null && (System.currentTimeMillis() - cached.time) < 120_000L) {
                GlobalScope.launch(Dispatchers.IO) {
                    val validation = antiSpoof.validateLocation(cached)
                    val verified = validation.isValid
                    val quick = buildLocation(cached, "", "", "", isCoarse = true,
                        isVerified = verified, integrityReason = validation.reason,
                        mockDetected = isForgeryIndicator(validation))
                    trySend(Result.success(quick))
                    val enriched = enrichLocation(cached, isCoarse = true,
                        isVerified = verified, integrityReason = validation.reason,
                        mockDetected = isForgeryIndicator(validation))
                    trySend(Result.success(enriched))
                }
            }
        }

        // Step 2: Live GPS updates
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                GlobalScope.launch(Dispatchers.IO) {
                    val validation = antiSpoof.validateLocation(location)
                    val isCoarse = location.accuracy > 50f
                    val verified = validation.isValid
                    val quick = buildLocation(location, "", "", "", isCoarse,
                        isVerified = verified, integrityReason = validation.reason,
                        mockDetected = isForgeryIndicator(validation))
                    trySend(Result.success(quick))
                    val enriched = enrichLocation(location, isCoarse,
                        isVerified = verified, integrityReason = validation.reason,
                        mockDetected = isForgeryIndicator(validation))
                    trySend(Result.success(enriched))
                }
            }
        }

        fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Result<GeoStampLocation> =
        suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation.addOnSuccessListener { cached ->
                if (cached != null && !cont.isCompleted) {
                    GlobalScope.launch(Dispatchers.IO) {
                        val validation = antiSpoof.validateLocation(cached)
                        if (!cont.isCompleted) {
                            cont.resume(Result.success(enrichLocation(cached,
                                isCoarse = cached.accuracy > 50f,
                                isVerified = validation.isValid,
                                integrityReason = validation.reason,
                                mockDetected = isForgeryIndicator(validation))))
                        }
                    }
                }
            }

            val cts = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            cts.addOnSuccessListener { location ->
                if (location == null) {
                    if (!cont.isCompleted)
                        cont.resume(Result.failure(Exception("No GPS fix — move outdoors")))
                    return@addOnSuccessListener
                }
                GlobalScope.launch(Dispatchers.IO) {
                    val validation = antiSpoof.validateLocation(location)
                    if (!cont.isCompleted) cont.resume(Result.success(enrichLocation(location,
                        location.accuracy > 50f, validation.isValid, validation.reason,
                        isForgeryIndicator(validation))))
                }
            }
            cts.addOnFailureListener { e ->
                if (!cont.isCompleted) cont.resume(Result.failure(e))
            }
        }

    private fun buildLocation(
        location: Location, addressLine: String, city: String,
        province: String, isCoarse: Boolean,
        isVerified: Boolean = true, integrityReason: String = "",
        mockDetected: Boolean = false
    ) = GeoStampLocation(
        location = location,
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyM = location.accuracy,
        altitudeM = if (location.hasAltitude()) location.altitude else null,
        bearing = location.bearing,
        addressLine = addressLine,
        city = city,
        province = province,
        timestampMs = location.time,
        isVerified = isVerified,
        isCoarse = isCoarse,
        mockLocationDetected = mockDetected,
        integrityReason = integrityReason,
        providerName = location.provider ?: "unknown"
    )

    private suspend fun enrichLocation(
        location: Location, isCoarse: Boolean,
        isVerified: Boolean = true, integrityReason: String = "",
        mockDetected: Boolean = false
    ): GeoStampLocation {
        var addressLine = ""
        var city = ""
        var province = ""

        try {
            val geocoder = Geocoder(context, Locale.ENGLISH)
            // 2-second timeout — if geocoder is slow, we still show coords
            val addresses = withTimeoutOrNull(2000L) {
                getAddressesAsync(geocoder, location.latitude, location.longitude)
            }
            addresses?.firstOrNull()?.let { addr ->
                addressLine = (0..addr.maxAddressLineIndex)
                    .joinToString(", ") { addr.getAddressLine(it) }
                city = addr.locality ?: addr.subAdminArea ?: ""
                province = addr.adminArea ?: ""
            }
        } catch (_: Exception) { /* geocoding failure is non-fatal */ }

        return buildLocation(location, addressLine, city, province, isCoarse, isVerified, integrityReason, mockDetected)
    }

    /** Suspend wrapper for Geocoder — async on API 33+, blocking on older */
    private suspend fun getAddressesAsync(
        geocoder: Geocoder, lat: Double, lng: Double
    ): List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                if (!cont.isCompleted) cont.resume(addresses)
            }
        }
    } else {
        @Suppress("DEPRECATION")
        withTimeoutOrNull(2000L) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                geocoder.getFromLocation(lat, lng, 1)
            }
        }
    }

    private fun isForgeryIndicator(validation: AntiSpoofManager.ValidationResult): Boolean {
        val reason = validation.reason.lowercase(Locale.ENGLISH)
        return validation.threatLevel == AntiSpoofManager.ThreatLevel.CRITICAL ||
            reason.contains("mock") || reason.contains("spoof") || reason.contains("impossible location")
    }

    fun getSecurityAudit() = antiSpoof.auditDeviceSecurity()
}

class SpoofedLocationException(message: String) : Exception(message)
