package com.axiominfratech.geostamp.verification

import android.content.Context
import com.axiominfratech.geostamp.core.OperatorSessionManager
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Finalizes every evidence record belonging to a closed operator session.
 *
 * At capture time the record can only know how many photos existed before the
 * reference image. When the session closes, this class writes the definitive
 * after-counts, final totals, sites visited, clock-out time and reason.
 *
 * Published records are copied back to the outbox after finalization so the
 * normal registry publisher can replace the earlier provisional record.
 */
object OperatorSessionEvidenceFinalizer {

    data class Result(
        val updatedPending: Int,
        val updatedPublished: Int,
        val queuedForRepublish: Int
    )

    fun finalize(
        context: Context,
        session: OperatorSessionManager.Session,
        clockOutAt: Long,
        clockOutReason: String
    ): Result {
        val pendingDir = File(context.filesDir, "evidence_registry_outbox").also { it.mkdirs() }
        val publishedDir = File(context.filesDir, "evidence_registry_published").also { it.mkdirs() }

        var pendingCount = 0
        var publishedCount = 0
        var republishCount = 0

        pendingDir.listFiles(::isJson).orEmpty().forEach { file ->
            if (finalizeFile(file, session, clockOutAt, clockOutReason)) pendingCount++
        }

        publishedDir.listFiles(::isJson).orEmpty().forEach { file ->
            if (finalizeFile(file, session, clockOutAt, clockOutReason)) {
                publishedCount++
                val target = File(pendingDir, file.name)
                file.copyTo(target, overwrite = true)
                // Mark as pending update while retaining the original publication time.
                runCatching {
                    val json = JSONObject(target.readText())
                    json.put("registryStatus", "FINALIZATION_PENDING")
                    json.put("registryUpdateType", "SESSION_FINALIZATION")
                    target.writeText(json.toString(2))
                }
                republishCount++
            }
        }

        return Result(pendingCount, publishedCount, republishCount)
    }

    private fun finalizeFile(
        file: File,
        session: OperatorSessionManager.Session,
        clockOutAt: Long,
        clockOutReason: String
    ): Boolean = runCatching {
        val json = JSONObject(file.readText())
        if (json.optString("operatorSessionId") != session.id) return false

        val siteId = json.optString("secondaryValue")
            .removePrefix("~")
            .trim()
        val siteBefore = json.optInt("sitePhotosBefore", 0).coerceAtLeast(0)
        val sessionBefore = json.optInt("operatorSessionPhotosBefore", 0).coerceAtLeast(0)
        val finalSiteTotal = if (siteId.isBlank() || siteId == "–") {
            json.optInt("siteSessionPhotoTotal", 1).coerceAtLeast(1)
        } else {
            session.sitePhotoCounts[siteId] ?: json.optInt("siteSessionPhotoTotal", 1)
        }.coerceAtLeast(1)

        val siteAfter = max(0, finalSiteTotal - siteBefore - 1)
        val sessionAfter = max(0, session.photoCount - sessionBefore - 1)

        json.put("sitePhotosAfter", siteAfter)
        json.put("operatorSessionPhotosAfter", sessionAfter)
        json.put("siteSessionPhotoTotal", finalSiteTotal)
        json.put("operatorSessionPhotoTotal", session.photoCount)
        json.put("operatorSessionSitesVisited", session.siteIds.size)
        json.put("operatorSessionLastActivityAt", session.lastActivityAt)
        json.put("operatorSessionClockOutAt", clockOutAt)
        json.put("operatorSessionClockOutReason", clockOutReason)
        json.put("operatorSessionDurationMs", max(0L, clockOutAt - session.startedAt))
        json.put("sessionFinalized", true)
        json.put("sessionFinalizedAt", System.currentTimeMillis())
        file.writeText(json.toString(2))
        true
    }.getOrDefault(false)

    private fun isJson(file: File): Boolean =
        file.isFile && file.extension.equals("json", ignoreCase = true)
}
