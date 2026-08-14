package com.axiominfratech.geostamp.core

import android.content.Context
import com.axiominfratech.geostamp.config.RemoteConfigManager
import com.axiominfratech.geostamp.verification.OperatorSessionEvidenceFinalizer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Stores one active operator session locally for offline use.
 *
 * The session belongs to the operator, not to one site. As the user moves, the
 * nearest valid site may change while the same operator session remains active.
 * The session expires only after the configured inactivity period or manual
 * clock-out.
 */
class OperatorSessionManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("geostamp_prefs", Context.MODE_PRIVATE)

    data class Session(
        val id: String,
        val operatorId: String,
        val operatorName: String,
        val operatorCode: String,
        val aliases: List<String>,
        val startedAt: Long,
        val startedLatitude: Double?,
        val startedLongitude: Double?,
        val startedAccuracyM: Float?,
        val lastActivityAt: Long,
        val inactivityTimeoutMinutes: Int,
        val photoCount: Int,
        val siteIds: Set<String>,
        val sitePhotoCounts: Map<String, Int>
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            now - lastActivityAt >= inactivityTimeoutMinutes * 60_000L

        fun photosAtSite(siteId: String): Int = sitePhotoCounts[siteId.trim()] ?: 0
    }

    fun active(now: Long = System.currentTimeMillis()): Session? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val operatorId = prefs.getString(KEY_OPERATOR_ID, null) ?: return null
        val operatorName = prefs.getString(KEY_OPERATOR_NAME, null) ?: return null
        val aliases = jsonArrayToList(prefs.getString(KEY_ALIASES, "[]") ?: "[]")
        val sites = jsonArrayToList(prefs.getString(KEY_SITES, "[]") ?: "[]").toSet()
        val startedAt = prefs.getLong(KEY_STARTED, 0L)
        val session = Session(
            id = id,
            operatorId = operatorId,
            operatorName = operatorName,
            operatorCode = prefs.getString(KEY_OPERATOR_CODE, "OP") ?: "OP",
            aliases = aliases,
            startedAt = startedAt,
            startedLatitude = prefs.getString(KEY_STARTED_LAT, null)?.toDoubleOrNull(),
            startedLongitude = prefs.getString(KEY_STARTED_LON, null)?.toDoubleOrNull(),
            startedAccuracyM = prefs.getString(KEY_STARTED_ACC, null)?.toFloatOrNull(),
            lastActivityAt = prefs.getLong(KEY_LAST_ACTIVITY, startedAt),
            inactivityTimeoutMinutes = prefs.getInt(KEY_TIMEOUT_MINUTES, DEFAULT_INACTIVITY_MINUTES)
                .coerceIn(1, 24 * 60),
            photoCount = prefs.getInt(KEY_PHOTOS, 0),
            siteIds = sites,
            sitePhotoCounts = jsonObjectToIntMap(
                prefs.getString(KEY_SITE_PHOTO_COUNTS, "{}") ?: "{}"
            )
        )

        if (session.isExpired(now)) {
            end(CLOCK_OUT_INACTIVITY, now)
            return null
        }
        return session
    }

    fun start(
        operator: RemoteConfigManager.OperatorConfig,
        inactivityTimeoutMinutes: Int = DEFAULT_INACTIVITY_MINUTES,
        startedLatitude: Double? = null,
        startedLongitude: Double? = null,
        startedAccuracyM: Float? = null
    ): Session {
        active()?.let { current ->
            if (current.operatorId == operator.id) return current
            error("Clock out from ${current.operatorName} first")
        }
        val now = System.currentTimeMillis()
        val id = "OPS-${operator.code}-${now}-${UUID.randomUUID().toString().take(6).uppercase()}"
        prefs.edit()
            .putString(KEY_ID, id)
            .putString(KEY_OPERATOR_ID, operator.id)
            .putString(KEY_OPERATOR_NAME, operator.name)
            .putString(KEY_OPERATOR_CODE, operator.code)
            .putString(KEY_ALIASES, JSONArray(operator.aliases).toString())
            .putLong(KEY_STARTED, now)
            .apply {
                if (startedLatitude != null) putString(KEY_STARTED_LAT, startedLatitude.toString()) else remove(KEY_STARTED_LAT)
                if (startedLongitude != null) putString(KEY_STARTED_LON, startedLongitude.toString()) else remove(KEY_STARTED_LON)
                if (startedAccuracyM != null) putString(KEY_STARTED_ACC, startedAccuracyM.toString()) else remove(KEY_STARTED_ACC)
            }
            .putLong(KEY_LAST_ACTIVITY, now)
            .putInt(KEY_TIMEOUT_MINUTES, inactivityTimeoutMinutes.coerceIn(1, 24 * 60))
            .putInt(KEY_PHOTOS, 0)
            .putString(KEY_SITES, "[]")
            .putString(KEY_SITE_PHOTO_COUNTS, "{}")
            .remove(KEY_CURRENT_SITE)
            .putString(KEY_SITE_VISIT_ORDER, "[]")
            .putString(KEY_SITE_FIRST_SEEN, "{}")
            .remove(KEY_LAST_CLOCK_OUT_REASON)
            .remove(KEY_LAST_CLOCK_OUT_AT)
            .apply()
        return active() ?: error("Unable to create operator session")
    }

    /** Manual clock-out. */
    fun end(): Session? = end(CLOCK_OUT_MANUAL)

    fun end(reason: String, endedAt: Long = System.currentTimeMillis()): Session? {
        val current = readWithoutExpiry()
        if (current != null) {
            OperatorSessionEvidenceFinalizer.finalize(
                context = appContext,
                session = current,
                clockOutAt = endedAt,
                clockOutReason = reason
            )
        }
        prefs.edit()
            .putString(KEY_LAST_CLOCK_OUT_REASON, reason)
            .putLong(KEY_LAST_CLOCK_OUT_AT, endedAt)
            .remove(KEY_ID)
            .remove(KEY_OPERATOR_ID)
            .remove(KEY_OPERATOR_NAME)
            .remove(KEY_OPERATOR_CODE)
            .remove(KEY_ALIASES)
            .remove(KEY_STARTED)
            .remove(KEY_STARTED_LAT)
            .remove(KEY_STARTED_LON)
            .remove(KEY_STARTED_ACC)
            .remove(KEY_LAST_ACTIVITY)
            .remove(KEY_TIMEOUT_MINUTES)
            .remove(KEY_PHOTOS)
            .remove(KEY_SITES)
            .remove(KEY_SITE_PHOTO_COUNTS)
            .remove(KEY_CURRENT_SITE)
            .remove(KEY_SITE_VISIT_ORDER)
            .remove(KEY_SITE_FIRST_SEEN)
            .apply()
        return current
    }

    /**
     * Updates the currently GPS-locked site while keeping the same operator session.
     * A site is appended only once to the ordered visit history.
     */
    fun updateSiteLock(siteId: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (readWithoutExpiry() == null) return false
        val clean = siteId?.trim()?.takeIf { it.isNotEmpty() && it != "–" }
        val previous = prefs.getString(KEY_CURRENT_SITE, null)
        if (previous == clean) return false

        val order = jsonArrayToList(prefs.getString(KEY_SITE_VISIT_ORDER, "[]") ?: "[]").toMutableList()
        val firstSeenRaw = prefs.getString(KEY_SITE_FIRST_SEEN, "{}") ?: "{}"
        val firstSeen = runCatching { JSONObject(firstSeenRaw) }.getOrElse { JSONObject() }
        if (clean != null && !order.contains(clean)) {
            order += clean
            firstSeen.put(clean, now)
        }

        prefs.edit()
            .putString(KEY_CURRENT_SITE, clean)
            .putString(KEY_SITE_VISIT_ORDER, JSONArray(order).toString())
            .putString(KEY_SITE_FIRST_SEEN, firstSeen.toString())
            .apply()
        return true
    }

    fun currentSiteId(): String? = prefs.getString(KEY_CURRENT_SITE, null)

    fun siteVisitOrder(): List<String> =
        jsonArrayToList(prefs.getString(KEY_SITE_VISIT_ORDER, "[]") ?: "[]")

    fun siteFirstSeenAt(siteId: String): Long = runCatching {
        JSONObject(prefs.getString(KEY_SITE_FIRST_SEEN, "{}") ?: "{}").optLong(siteId, 0L)
    }.getOrDefault(0L)

    /**
     * Records a successful capture and resets the four-hour inactivity timer.
     * The operator remains unchanged; each site receives its own count/folder.
     */
    fun recordCapture(siteId: String?) {
        val current = active() ?: return
        val cleanSite = siteId?.trim()?.takeIf { it.isNotEmpty() && it != "–" }
        val sites = current.siteIds.toMutableSet()
        val counts = current.sitePhotoCounts.toMutableMap()
        if (cleanSite != null) {
            sites += cleanSite
            counts[cleanSite] = (counts[cleanSite] ?: 0) + 1
        }
        prefs.edit()
            .putInt(KEY_PHOTOS, current.photoCount + 1)
            .putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())
            .putString(KEY_SITES, JSONArray(sites.toList()).toString())
            .putString(KEY_SITE_PHOTO_COUNTS, JSONObject(counts).toString())
            .apply()
    }

    /** Any meaningful operator action may refresh inactivity without adding a photo. */
    fun touchActivity(now: Long = System.currentTimeMillis()) {
        if (readWithoutExpiry() != null) prefs.edit().putLong(KEY_LAST_ACTIVITY, now).apply()
    }

    fun lastClockOutReason(): String? = prefs.getString(KEY_LAST_CLOCK_OUT_REASON, null)
    fun lastClockOutAt(): Long = prefs.getLong(KEY_LAST_CLOCK_OUT_AT, 0L)

    private fun readWithoutExpiry(): Session? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val operatorId = prefs.getString(KEY_OPERATOR_ID, null) ?: return null
        val operatorName = prefs.getString(KEY_OPERATOR_NAME, null) ?: return null
        val startedAt = prefs.getLong(KEY_STARTED, 0L)
        return Session(
            id = id,
            operatorId = operatorId,
            operatorName = operatorName,
            operatorCode = prefs.getString(KEY_OPERATOR_CODE, "OP") ?: "OP",
            aliases = jsonArrayToList(prefs.getString(KEY_ALIASES, "[]") ?: "[]"),
            startedAt = startedAt,
            startedLatitude = prefs.getString(KEY_STARTED_LAT, null)?.toDoubleOrNull(),
            startedLongitude = prefs.getString(KEY_STARTED_LON, null)?.toDoubleOrNull(),
            startedAccuracyM = prefs.getString(KEY_STARTED_ACC, null)?.toFloatOrNull(),
            lastActivityAt = prefs.getLong(KEY_LAST_ACTIVITY, startedAt),
            inactivityTimeoutMinutes = prefs.getInt(KEY_TIMEOUT_MINUTES, DEFAULT_INACTIVITY_MINUTES),
            photoCount = prefs.getInt(KEY_PHOTOS, 0),
            siteIds = jsonArrayToList(prefs.getString(KEY_SITES, "[]") ?: "[]").toSet(),
            sitePhotoCounts = jsonObjectToIntMap(
                prefs.getString(KEY_SITE_PHOTO_COUNTS, "{}") ?: "{}"
            )
        )
    }

    private fun jsonArrayToList(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun jsonObjectToIntMap(raw: String): Map<String, Int> = runCatching {
        val obj = JSONObject(raw)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, obj.optInt(key, 0))
            }
        }
    }.getOrDefault(emptyMap())

    companion object {
        const val DEFAULT_INACTIVITY_MINUTES = 240
        const val CLOCK_OUT_MANUAL = "Manual clock-out"
        const val CLOCK_OUT_INACTIVITY = "Four-hour inactivity timeout"

        private const val KEY_ID = "operator_session_id"
        private const val KEY_OPERATOR_ID = "operator_session_operator_id"
        private const val KEY_OPERATOR_NAME = "operator_session_operator_name"
        private const val KEY_OPERATOR_CODE = "operator_session_operator_code"
        private const val KEY_ALIASES = "operator_session_aliases"
        private const val KEY_STARTED = "operator_session_started_at"
        private const val KEY_STARTED_LAT = "operator_session_started_latitude"
        private const val KEY_STARTED_LON = "operator_session_started_longitude"
        private const val KEY_STARTED_ACC = "operator_session_started_accuracy_m"
        private const val KEY_LAST_ACTIVITY = "operator_session_last_activity_at"
        private const val KEY_TIMEOUT_MINUTES = "operator_session_inactivity_timeout_minutes"
        private const val KEY_PHOTOS = "operator_session_photo_count"
        private const val KEY_SITES = "operator_session_site_ids"
        private const val KEY_SITE_PHOTO_COUNTS = "operator_session_site_photo_counts"
        private const val KEY_CURRENT_SITE = "operator_session_current_site"
        private const val KEY_SITE_VISIT_ORDER = "operator_session_site_visit_order"
        private const val KEY_SITE_FIRST_SEEN = "operator_session_site_first_seen"
        private const val KEY_LAST_CLOCK_OUT_REASON = "operator_last_clock_out_reason"
        private const val KEY_LAST_CLOCK_OUT_AT = "operator_last_clock_out_at"
    }
}
