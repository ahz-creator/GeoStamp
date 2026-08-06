package com.axiominfratech.geostamp.verification

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EvidencePdfExporter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f

    fun exportAndShare(context: Context, record: JSONObject): Result<File> = runCatching {
        val evidenceId = firstNonBlank(
            record.optString("evidenceId"),
            record.optString("verificationId"),
            record.optString("id"),
            "GEOSTAMP-EVIDENCE"
        )
        val dir = File(context.cacheDir, "shared_reports").also { it.mkdirs() }
        val file = File(dir, "GeoStamp-$evidenceId.pdf")
        createPdf(file, record)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GeoStamp Evidence Report — $evidenceId")
            putExtra(Intent.EXTRA_TEXT, "GeoStamp forensic evidence report for $evidenceId")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val whatsapp = Intent(intent).apply { setPackage("com.whatsapp") }
        if (whatsapp.resolveActivity(context.packageManager) != null) {
            context.startActivity(whatsapp)
        } else {
            context.startActivity(Intent.createChooser(intent, "Share GeoStamp PDF report"))
        }
        file
    }

    private fun createPdf(file: File, record: JSONObject) {
        val document = PdfDocument()
        try {
            val bitmap = decodeThumbnail(record)
            val page1 = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            drawSummaryPage(page1.canvas, record, bitmap)
            document.finishPage(page1)

            val page2 = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create())
            drawForensicPage(page2.canvas, record)
            document.finishPage(page2)

            val page3 = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 3).create())
            drawAuditPage(page3.canvas, record)
            document.finishPage(page3)

            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun drawSummaryPage(canvas: Canvas, record: JSONObject, thumbnail: Bitmap?) {
        val navy = Color.rgb(8, 29, 54)
        val cyan = Color.rgb(0, 157, 193)
        val green = Color.rgb(0, 145, 77)
        val light = Color.rgb(244, 247, 250)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = navy
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 105f, paint)

        text(canvas, "GEOSTAMP", MARGIN, 48f, 25f, Color.WHITE, true)
        text(canvas, "Axiom Infratech", MARGIN, 73f, 13f, Color.rgb(94, 208, 230), true)
        text(canvas, "DIGITAL EVIDENCE VERIFICATION", PAGE_W - MARGIN, 48f, 11f, Color.WHITE, true, Paint.Align.RIGHT)
        text(canvas, "MOBILE PDF REPORT", PAGE_W - MARGIN, 69f, 9f, Color.rgb(190, 205, 220), false, Paint.Align.RIGHT)

        val status = if (record.optBoolean("locationRisk", false) || record.optBoolean("locationIntegrityRisk", false)) {
            "REGISTERED — REVIEW REQUIRED"
        } else "VERIFIED — REGISTERED"
        text(canvas, status, MARGIN, 138f, 20f, green, true)

        var y = 160f
        if (thumbnail != null) {
            val rect = fitRect(thumbnail.width, thumbnail.height, MARGIN, y, PAGE_W - 2 * MARGIN, 210f)
            canvas.drawBitmap(thumbnail, null, rect, paint)
            y = rect.bottom + 18f
        } else {
            paint.color = light
            canvas.drawRoundRect(RectF(MARGIN, y, PAGE_W - MARGIN, y + 78f), 8f, 8f, paint)
            text(canvas, "Color thumbnail unavailable in this registry record", PAGE_W / 2f, y + 45f, 12f, Color.DKGRAY, false, Paint.Align.CENTER)
            y += 96f
        }

        val id = firstNonBlank(record.optString("evidenceId"), record.optString("verificationId"), record.optString("id"))
        val captured = time(record.optLong("capturedAt", record.optLong("timestamp", 0L)))
        val operator = firstNonBlank(record.optString("primaryValue"), record.optString("operator"), record.optString("p"))
        val site = firstNonBlank(record.optString("secondaryValue"), record.optString("siteId"), record.optString("s"))
        val lat = record.optDouble("latitude", record.optDouble("lat", Double.NaN))
        val lon = record.optDouble("longitude", record.optDouble("lon", Double.NaN))
        val acc = record.optDouble("accuracyM", record.optDouble("accuracy", Double.NaN))
        val location = if (lat.isFinite() && lon.isFinite()) "%.6f, %.6f  |  ±%.1f m".format(Locale.US, lat, lon, acc) else "Unavailable"
        val device = firstNonBlank(
            listOf(record.optString("deviceManufacturer"), record.optString("deviceHardwareModel")).filter { it.isNotBlank() }.joinToString(" "),
            record.optString("deviceModel"),
            "Unavailable"
        )

        y = drawRow(canvas, "Evidence ID", id, y, cyan)
        y = drawRow(canvas, "Captured", captured, y, cyan)
        y = drawRow(canvas, "Operator / Project", operator, y, cyan)
        y = drawRow(canvas, "Site / Reference", site, y, cyan)
        y = drawRow(canvas, "Location", location, y, cyan)
        y = drawRow(canvas, "Device", device, y, cyan)
        y = drawRow(canvas, "Device identity", record.optString("maskedGeoStampDeviceIdentity", "Unavailable"), y, cyan)

        paint.color = Color.rgb(225, 232, 239)
        canvas.drawLine(MARGIN, 795f, PAGE_W - MARGIN, 795f, paint)
        text(canvas, "Captured & Sealed by GeoStamp · Axiom Infratech", PAGE_W / 2f, 817f, 9f, Color.GRAY, false, Paint.Align.CENTER)
    }

    private fun drawForensicPage(canvas: Canvas, record: JSONObject) {
        canvas.drawColor(Color.WHITE)
        header(canvas, "FORENSIC TECHNICAL RECORD", 2)
        var y = 126f
        val fields = listOf(
            "Evidence ID" to firstNonBlank(record.optString("evidenceId"), record.optString("verificationId")),
            "Registry status" to record.optString("registryStatus", "PUBLIC_RECORD"),
            "Evidence status" to record.optString("evidenceStatus", "Unavailable"),
            "Captured at" to time(record.optLong("capturedAt", record.optLong("timestamp", 0L))),
            "Published at" to time(record.optLong("publishedAt", 0L)),
            "Workspace mode" to record.optString("workspaceMode", "Unavailable"),
            "Operator / project" to firstNonBlank(record.optString("primaryValue"), record.optString("operator")),
            "Site / reference" to firstNonBlank(record.optString("secondaryValue"), record.optString("siteId")),
            "Latitude" to record.optDouble("latitude", record.optDouble("lat", Double.NaN)).toString(),
            "Longitude" to record.optDouble("longitude", record.optDouble("lon", Double.NaN)).toString(),
            "Accuracy" to "${record.optDouble("accuracyM", record.optDouble("accuracy", Double.NaN))} m",
            "Location risk" to record.optBoolean("locationRisk", record.optBoolean("locationIntegrityRisk", false)).toString(),
            "Device manufacturer" to record.optString("deviceManufacturer", "Unavailable"),
            "Device brand" to record.optString("deviceBrand", "Unavailable"),
            "Device model" to firstNonBlank(record.optString("deviceHardwareModel"), record.optString("deviceModel")),
            "Masked device identity" to record.optString("maskedGeoStampDeviceIdentity", "Unavailable"),
            "Hardware-backed key" to record.optBoolean("captureKeyHardwareBacked", false).toString(),
            "Key security level" to record.optString("captureKeySecurityLevel", "Unavailable")
        )
        for ((label, value) in fields) {
            y = drawRow(canvas, label, value, y, Color.rgb(0, 128, 160))
            if (y > 780f) break
        }
        footer(canvas, 2)
    }

    private fun drawAuditPage(canvas: Canvas, record: JSONObject) {
        canvas.drawColor(Color.WHITE)
        header(canvas, "INTEGRITY & SESSION AUDIT", 3)
        var y = 126f
        val fields = listOf(
            "Image SHA-256" to record.optString("imageSha256", "Unavailable"),
            "Signature algorithm" to record.optString("captureSignatureAlgorithm", "Unavailable"),
            "Capture key fingerprint" to record.optString("captureKeyFingerprint", "Unavailable"),
            "Capture signature" to record.optString("captureSignature", "Unavailable"),
            "Operator session ID" to record.optString("operatorSessionId", "Unavailable"),
            "Operator clock-in" to time(record.optLong("operatorSessionStartedAt", 0L)),
            "Photos before at site" to count(record, "sitePhotosBefore", "photosBeforeAtSite"),
            "Photos after at site" to count(record, "sitePhotosAfter", "photosAfterAtSite"),
            "Total at site" to count(record, "siteSessionPhotoTotal", "sitePhotoTotal"),
            "Operator-session total" to count(record, "operatorSessionPhotoTotal", "sessionPhotoTotal"),
            "Sites visited" to count(record, "operatorSessionSitesVisited", "sessionSitesVisited"),
            "Clock-out" to time(record.optLong("operatorSessionClockOutAt", 0L)),
            "Clock-out reason" to record.optString("operatorSessionClockOutReason", "Active / pending")
        )
        for ((label, value) in fields) {
            y = drawWrappedRow(canvas, label, value, y)
            if (y > 760f) break
        }
        text(canvas, "Verification note", MARGIN, y + 18f, 11f, Color.rgb(0, 128, 160), true)
        text(canvas, "This report reproduces registry and device-recorded technical signals. It does not independently prove the truth of the photographed scene.", MARGIN, y + 38f, 9f, Color.DKGRAY, false)
        footer(canvas, 3)
    }

    private fun header(canvas: Canvas, title: String, page: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 29, 54) }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 92f, paint)
        text(canvas, "GEOSTAMP", MARGIN, 39f, 22f, Color.WHITE, true)
        text(canvas, title, MARGIN, 67f, 11f, Color.rgb(94, 208, 230), true)
        text(canvas, "Page $page of 3", PAGE_W - MARGIN, 58f, 9f, Color.WHITE, false, Paint.Align.RIGHT)
    }

    private fun footer(canvas: Canvas, page: Int) {
        text(canvas, "GeoStamp · Axiom Infratech · Page $page of 3", PAGE_W / 2f, 818f, 9f, Color.GRAY, false, Paint.Align.CENTER)
    }

    private fun drawRow(canvas: Canvas, label: String, value: String, y: Float, accent: Int): Float {
        text(canvas, label.uppercase(Locale.US), MARGIN, y, 9f, accent, true)
        text(canvas, value.ifBlank { "Unavailable" }, 190f, y, 10.5f, Color.rgb(25, 39, 58), true)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 238); strokeWidth = 1f }
        canvas.drawLine(MARGIN, y + 13f, PAGE_W - MARGIN, y + 13f, p)
        return y + 31f
    }

    private fun drawWrappedRow(canvas: Canvas, label: String, value: String, y: Float): Float {
        text(canvas, label.uppercase(Locale.US), MARGIN, y, 9f, Color.rgb(0, 128, 160), true)
        val safe = value.ifBlank { "Unavailable" }
        val lines = safe.chunked(76).take(3)
        var yy = y
        lines.forEachIndexed { index, line ->
            text(canvas, line, 190f, yy + index * 13f, 8.5f, Color.rgb(25, 39, 58), false)
        }
        val next = y + maxOf(31f, 15f + lines.size * 13f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 238); strokeWidth = 1f }
        canvas.drawLine(MARGIN, next - 12f, PAGE_W - MARGIN, next - 12f, p)
        return next
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Paint.Align = Paint.Align.LEFT) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = align
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
        canvas.drawText(value, x, y, p)
    }

    private fun decodeThumbnail(record: JSONObject): Bitmap? {
        val raw = firstNonBlank(
            record.optString("thumbnailBase64"),
            record.optString("thumbnailJpegBase64"),
            record.optString("thumb")
        ).substringAfter("base64,", "")
        if (raw.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun fitRect(srcW: Int, srcH: Int, x: Float, y: Float, maxW: Float, maxH: Float): RectF {
        val scale = minOf(maxW / srcW, maxH / srcH)
        val w = srcW * scale
        val h = srcH * scale
        return RectF(x + (maxW - w) / 2f, y, x + (maxW - w) / 2f + w, y + h)
    }

    private fun time(value: Long): String = if (value > 0L) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(value))
    } else "Unavailable"

    private fun count(record: JSONObject, first: String, second: String): String {
        val value = record.optInt(first, record.optInt(second, -1))
        return if (value >= 0) value.toString() else "Pending"
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() } ?: ""
}
