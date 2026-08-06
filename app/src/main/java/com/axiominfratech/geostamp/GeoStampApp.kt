package com.axiominfratech.geostamp

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.axiominfratech.geostamp.database.SiteRepository
import com.axiominfratech.geostamp.overlay.OverlayRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.*

class GeoStampApp : Application() {

    val siteRepository: SiteRepository by lazy { SiteRepository.getInstance(this) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        OverlayRenderer.initLogos(this)

        // On first install: seed offline data so app works immediately
        scope.launch {
            val prefs = getSharedPreferences("geostamp_prefs", MODE_PRIVATE)
            if (siteRepository.getCount() == 0) {
                Log.d("GeoStamp", "First install — seeding offline sites")
                siteRepository.seedSampleData()
            }
            // Location-based sync runs when GPS becomes available via MainViewModel
            // We do NOT download on every launch here
        }
    }

    /**
     * Called by MainViewModel when GPS is first locked.
     *
     * Zone strategy (100km grid):
     *  • The globe is divided into ~0.9° × 0.9° cells (≈100km per side at equator).
     *  • Each cell is identified by a "zone key" string like "27_68".
     *  • We download sites for a 3×3 block of cells around the user (≈300km coverage).
     *  • Downloaded zone keys are stored in SharedPreferences.
     *  • If the user's current cells are ALREADY downloaded → skip, use local DB.
     *  • If user moves to a new zone not yet downloaded → download only that zone's sites.
     *  • Sites already in DB are never deleted (REPLACE conflict strategy keeps freshest).
     *  • Net result: first use downloads ~a few hundred sites; subsequent launches = 0 network.
     *  • A full re-sync is available in Settings as a manual button.
     */
    fun onGpsAvailable(lat: Double, lng: Double) {
        val prefs = getSharedPreferences("geostamp_prefs", MODE_PRIVATE)
        val currentZone   = zoneKey(lat, lng)
        val downloadedSet = prefs.getStringSet("downloaded_zones", emptySet())!!.toMutableSet()

        if (currentZone in downloadedSet) {
            Log.d("GeoStamp", "Zone $currentZone already downloaded — no sync needed")
            return
        }

        Log.d("GeoStamp", "New zone $currentZone — downloading sites within 150km radius")
        scope.launch {
            val result = siteRepository.syncRadiusFromGitHub(lat, lng, radiusKm = 150.0)
            Log.d("GeoStamp", "Radius sync: ${result.message}")
            if (result.success) {
                downloadedSet.add(currentZone)
                prefs.edit().putStringSet("downloaded_zones", downloadedSet).apply()
                Log.d("GeoStamp", "Zone $currentZone marked as downloaded. Total zones: ${downloadedSet.size}")
            }
        }
    }

    /** Force full re-sync (called from Settings "Refresh Database" button) */
    fun forceFullSync() {
        val prefs = getSharedPreferences("geostamp_prefs", MODE_PRIVATE)
        scope.launch {
            val result = siteRepository.syncFromGitHub()
            if (result.success) {
                // Mark all zones as needing re-check on next GPS lock
                prefs.edit().remove("downloaded_zones").apply()
            }
            Log.d("GeoStamp", "Force sync: ${result.message}")
        }
    }

    companion object {
        private const val ZONE_DEG = 0.9    // ≈100km per cell at equator

        /** Convert a lat/lng to a zone grid key string e.g. "27_68" */
        fun zoneKey(lat: Double, lng: Double): String {
            val zLat = (lat / ZONE_DEG).toInt()
            val zLng = (lng / ZONE_DEG).toInt()
            return "${zLat}_${zLng}"
        }
    }
}
