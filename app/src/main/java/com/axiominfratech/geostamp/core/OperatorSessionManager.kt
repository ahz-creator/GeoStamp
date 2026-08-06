package com.axiominfratech.geostamp.core

import android.content.Context
import com.axiominfratech.geostamp.config.RemoteConfigManager
import org.json.JSONArray
import java.util.UUID

/** Stores one active operator clock-in session locally for offline use. */
class OperatorSessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("geostamp_prefs", Context.MODE_PRIVATE)

    data class Session(
        val id: String,
        val operatorId: String,
        val operatorName: String,
        val operatorCode: String,
        val aliases: List<String>,
        val startedAt: Long,
        val photoCount: Int,
        val siteIds: Set<String>
    )

    fun active(): Session? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val operatorId = prefs.getString(KEY_OPERATOR_ID, null) ?: return null
        val operatorName = prefs.getString(KEY_OPERATOR_NAME, null) ?: return null
        val aliases = jsonArrayToList(prefs.getString(KEY_ALIASES, "[]") ?: "[]")
        val sites = jsonArrayToList(prefs.getString(KEY_SITES, "[]") ?: "[]").toSet()
        return Session(
            id = id,
            operatorId = operatorId,
            operatorName = operatorName,
            operatorCode = prefs.getString(KEY_OPERATOR_CODE, "OP") ?: "OP",
            aliases = aliases,
            startedAt = prefs.getLong(KEY_STARTED, 0L),
            photoCount = prefs.getInt(KEY_PHOTOS, 0),
            siteIds = sites
        )
    }

    fun start(operator: RemoteConfigManager.OperatorConfig): Session {
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
            .putInt(KEY_PHOTOS, 0)
            .putString(KEY_SITES, "[]")
            .apply()
        return active() ?: error("Unable to create operator session")
    }

    fun end(): Session? {
        val current = active()
        prefs.edit()
            .remove(KEY_ID)
            .remove(KEY_OPERATOR_ID)
            .remove(KEY_OPERATOR_NAME)
            .remove(KEY_OPERATOR_CODE)
            .remove(KEY_ALIASES)
            .remove(KEY_STARTED)
            .remove(KEY_PHOTOS)
            .remove(KEY_SITES)
            .apply()
        return current
    }

    fun recordCapture(siteId: String?) {
        val current = active() ?: return
        val sites = current.siteIds.toMutableSet().apply {
            siteId?.trim()?.takeIf { it.isNotEmpty() && it != "–" }?.let(::add)
        }
        prefs.edit()
            .putInt(KEY_PHOTOS, current.photoCount + 1)
            .putString(KEY_SITES, JSONArray(sites.toList()).toString())
            .apply()
    }

    private fun jsonArrayToList(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        buildList { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY_ID = "operator_session_id"
        private const val KEY_OPERATOR_ID = "operator_session_operator_id"
        private const val KEY_OPERATOR_NAME = "operator_session_operator_name"
        private const val KEY_OPERATOR_CODE = "operator_session_operator_code"
        private const val KEY_ALIASES = "operator_session_aliases"
        private const val KEY_STARTED = "operator_session_started_at"
        private const val KEY_PHOTOS = "operator_session_photo_count"
        private const val KEY_SITES = "operator_session_site_ids"
    }
}
