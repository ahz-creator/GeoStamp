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
        maxWidth: Int = 320,
        maxHeight: Int = 240,
        targetBase64Chars: Int = 28000
    ): String = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Evidence image could not be decoded" }

        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("Evidence thumbnail decode failed")

        var working = decoded
        val initialScale = minOf(maxWidth.toFloat() / decoded.width, maxHeight.toFloat() / decoded.height, 1f)
        if (initialScale < 1f) {
            working = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * initialScale).toInt().coerceAtLeast(1),
                (decoded.height * initialScale).toInt().coerceAtLeast(1),
                true
            )
        }

        var quality = 62
        var encoded = ""
        repeat(8) {
            val out = ByteArrayOutputStream()
            working.compress(Bitmap.CompressFormat.JPEG, quality, out)
            encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (encoded.length <= targetBase64Chars) return@repeat
            quality = (quality - 7).coerceAtLeast(35)
            if (quality <= 42 && encoded.length > targetBase64Chars) {
                val nextW = (working.width * 0.86f).toInt().coerceAtLeast(180)
                val nextH = (working.height * 0.86f).toInt().coerceAtLeast(120)
                val next = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (working !== decoded) working.recycle()
                working = next
            }
        }
        if (working !== decoded) working.recycle()
        decoded.recycle()
        require(encoded.isNotBlank()) { "Evidence thumbnail generation failed" }
        encoded
    }.getOrElse { "" }


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
