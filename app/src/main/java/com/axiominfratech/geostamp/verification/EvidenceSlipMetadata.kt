package com.axiominfratech.geostamp.verification

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.axiominfratech.geostamp.core.OperatorSessionManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Builds public-safe fields used by the compact GeoStamp verification slip. */
object EvidenceSlipMetadata {

    fun thumbnailBase64(
        imageFile: File,
        maxWidth: Int = 480,
        maxHeight: Int = 320,
        quality: Int = 68
    ): String = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        var sample = 1
        while (options.outWidth / sample > maxWidth * 2 || options.outHeight / sample > maxHeight * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return ""
        val ratio = minOf(maxWidth.toFloat() / decoded.width, maxHeight.toFloat() / decoded.height, 1f)
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else decoded
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(45, 80), output)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")

    /** Snapshot taken immediately before the reference capture is counted. */
    fun sessionSnapshot(
        session: OperatorSessionManager.Session?,
        siteId: String
    ): JSONObject = JSONObject().apply {
        if (session == null) return@apply
        val cleanSite = siteId.trim().takeIf { it.isNotBlank() && it != "–" }.orEmpty()
        put("operatorSessionId", session.id)
        put("operatorSessionStartedAt", session.startedAt)
        put("operatorSessionLastActivityAt", session.lastActivityAt)
        put("operatorSessionInactivityMinutes", session.inactivityTimeoutMinutes)
        put("sitePhotosBefore", if (cleanSite.isBlank()) 0 else session.photosAtSite(cleanSite))
        put("operatorSessionPhotosBefore", session.photoCount)
        put("operatorSessionSitesVisitedBefore", session.siteIds.size)
        put("siteSessionPhotoTotal", if (cleanSite.isBlank()) 1 else session.photosAtSite(cleanSite) + 1)
        put("operatorSessionPhotoTotal", session.photoCount + 1)
        put("operatorSessionSitesVisited", (session.siteIds + listOfNotNull(cleanSite.takeIf { it.isNotBlank() })).size)
        // Final after-counts are unknown until later captures/session closure.
        put("sitePhotosAfter", JSONObject.NULL)
        put("operatorSessionPhotosAfter", JSONObject.NULL)
        put("sessionFinalized", false)
    }
}
