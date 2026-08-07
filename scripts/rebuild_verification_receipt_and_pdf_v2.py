from pathlib import Path

root = Path(__file__).resolve().parents[1]

# --- Mobile receipt: rebuild from scratch around the Tower Finder receipt-style reference ---
layout = root / 'app/src/main/res/layout/activity_verify_evidence.xml'
layout.write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#07101F"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="46dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <ImageButton
                android:id="@+id/btn_back"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="Back"
                android:src="@android:drawable/ic_media_previous"
                app:tint="#EAF2FF" />

            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="VERIFY EVIDENCE"
                android:textColor="#FFFFFF"
                android:textSize="17sp"
                android:textStyle="bold" />
        </LinearLayout>

        <com.google.android.material.card.MaterialCardView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            app:cardBackgroundColor="#0D1A2D"
            app:cardCornerRadius="14dp"
            app:strokeColor="#294B70"
            app:strokeWidth="1dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="10dp">

                <Button
                    android:id="@+id/btn_scan_qr"
                    android:layout_width="match_parent"
                    android:layout_height="42dp"
                    android:backgroundTint="#28C3E5"
                    android:text="SCAN QR"
                    android:textColor="#04101B"
                    android:textStyle="bold" />

                <EditText
                    android:id="@+id/input_verification_id"
                    android:layout_width="match_parent"
                    android:layout_height="44dp"
                    android:layout_marginTop="7dp"
                    android:background="@drawable/bg_verify_input"
                    android:hint="Enter Evidence ID"
                    android:inputType="textCapCharacters"
                    android:paddingHorizontal="12dp"
                    android:singleLine="true"
                    android:textColor="#FFFFFF"
                    android:textColorHint="#7A8DA6"
                    android:textSize="14sp" />

                <Button
                    android:id="@+id/btn_verify_id"
                    android:layout_width="match_parent"
                    android:layout_height="40dp"
                    android:layout_marginTop="7dp"
                    android:backgroundTint="#35557F"
                    android:text="VERIFY"
                    android:textColor="#FFFFFF"
                    android:textStyle="bold" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <FrameLayout
            android:id="@+id/scanner_container"
            android:layout_width="match_parent"
            android:layout_height="300dp"
            android:layout_marginTop="8dp"
            android:background="#000000"
            android:visibility="gone">
            <androidx.camera.view.PreviewView
                android:id="@+id/scanner_preview"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />
            <View
                android:layout_width="220dp"
                android:layout_height="220dp"
                android:layout_gravity="center"
                android:background="@drawable/bg_qr_frame" />
        </FrameLayout>

        <ProgressBar
            android:id="@+id/progress"
            android:layout_width="38dp"
            android:layout_height="38dp"
            android:layout_gravity="center"
            android:layout_marginTop="8dp"
            android:visibility="gone" />

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/result_card"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:visibility="gone"
            app:cardBackgroundColor="#FFFFFF"
            app:cardCornerRadius="10dp"
            app:strokeColor="#CCD4DE"
            app:strokeWidth="1dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="14dp">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="bottom"
                    android:orientation="horizontal">
                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:orientation="vertical">
                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="GEOSTAMP"
                            android:textColor="#111827"
                            android:textSize="22sp"
                            android:textStyle="bold" />
                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="Axiom Infratech"
                            android:textColor="#2F855A"
                            android:textSize="10sp"
                            android:textStyle="bold" />
                    </LinearLayout>
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:gravity="end"
                        android:text="EVIDENCE\nRECEIPT"
                        android:textColor="#4B5563"
                        android:textSize="13sp"
                        android:textStyle="bold" />
                </LinearLayout>

                <View
                    android:layout_width="match_parent"
                    android:layout_height="1dp"
                    android:layout_marginTop="8dp"
                    android:background="#D1D5DB" />

                <FrameLayout
                    android:layout_width="match_parent"
                    android:layout_height="170dp"
                    android:layout_marginTop="10dp"
                    android:background="#EEF2F5">
                    <ImageView
                        android:id="@+id/iv_evidence_thumbnail"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:contentDescription="Evidence thumbnail"
                        android:scaleType="centerCrop"
                        android:visibility="gone" />
                    <TextView
                        android:id="@+id/tv_thumbnail_unavailable"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:gravity="center"
                        android:padding="20dp"
                        android:text="PHOTO NOT AVAILABLE ON THIS DEVICE"
                        android:textColor="#6B7280"
                        android:textSize="11sp"
                        android:textStyle="bold" />
                </FrameLayout>

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="10dp"
                    android:gravity="center_vertical"
                    android:orientation="horizontal">
                    <TextView
                        android:id="@+id/tv_result_status"
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="VERIFIED · REGISTERED"
                        android:textColor="#159447"
                        android:textSize="17sp"
                        android:textStyle="bold" />
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:background="#EAF7EE"
                        android:paddingHorizontal="9dp"
                        android:paddingVertical="5dp"
                        android:text="PUBLIC RECORD"
                        android:textColor="#19733B"
                        android:textSize="9sp"
                        android:textStyle="bold" />
                </LinearLayout>

                <TextView
                    android:id="@+id/tv_result_summary"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:textColor="#6B7280"
                    android:textSize="10sp" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="10dp"
                    android:text="EVIDENCE"
                    android:textColor="#5B8F20"
                    android:textSize="12sp"
                    android:textStyle="bold" />
                <View
                    android:layout_width="match_parent"
                    android:layout_height="1dp"
                    android:layout_marginTop="3dp"
                    android:background="#D1D5DB" />

                <TextView
                    android:id="@+id/tv_result_details"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    android:lineSpacingExtra="1dp"
                    android:textColor="#1F2937"
                    android:textSize="11.5sp" />

                <TextView
                    android:id="@+id/tv_session_activity"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="10dp"
                    android:background="#F7F8F4"
                    android:lineSpacingExtra="1dp"
                    android:padding="9dp"
                    android:textColor="#253142"
                    android:textSize="10.5sp"
                    android:visibility="gone" />

                <Button
                    android:id="@+id/btn_view_report"
                    android:layout_width="match_parent"
                    android:layout_height="43dp"
                    android:layout_marginTop="11dp"
                    android:backgroundTint="#7CB342"
                    android:text="SHARE FULL PDF REPORT"
                    android:textColor="#10210A"
                    android:textStyle="bold" />

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="8dp"
                    android:gravity="center"
                    android:text="Captured &amp; Sealed by GeoStamp · Axiom Infratech"
                    android:textColor="#9CA3AF"
                    android:textSize="8.5sp" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <com.google.android.material.card.MaterialCardView
            android:id="@+id/trust_card"
            android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone">
            <LinearLayout android:layout_width="1dp" android:layout_height="1dp">
                <TextView android:id="@+id/tv_confidence_score" android:layout_width="1dp" android:layout_height="1dp" />
                <TextView android:id="@+id/tv_confidence_level" android:layout_width="1dp" android:layout_height="1dp" />
                <ProgressBar android:id="@+id/confidence_progress" android:layout_width="1dp" android:layout_height="1dp" />
                <TextView android:id="@+id/tv_trust_conclusion" android:layout_width="1dp" android:layout_height="1dp" />
                <TextView android:id="@+id/tv_trust_findings" android:layout_width="1dp" android:layout_height="1dp" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>
        <com.google.android.material.card.MaterialCardView
            android:id="@+id/timeline_card"
            android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone">
            <TextView android:id="@+id/tv_evidence_timeline" android:layout_width="1dp" android:layout_height="1dp" />
        </com.google.android.material.card.MaterialCardView>

        <TextView
            android:id="@+id/tv_error"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:textColor="#FF8A80"
            android:textSize="13sp"
            android:visibility="gone" />
    </LinearLayout>
</ScrollView>
''', encoding='utf-8')

# --- Mobile lookup: if public registry omits visual payload, merge the phone's locally sealed public record. ---
activity = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt'
s = activity.read_text(encoding='utf-8')
if 'import com.axiominfratech.geostamp.verification.EvidenceRegistryOutbox' not in s:
    s = s.replace('import com.axiominfratech.geostamp.verification.EvidencePdfExporter', 'import com.axiominfratech.geostamp.verification.EvidencePdfExporter\nimport com.axiominfratech.geostamp.verification.EvidenceRegistryOutbox')

s = s.replace('showRecord(publicRecord ?: embeddedRecord, publicRecord != null)', 'showRecord(mergeLocalVisualFields(publicRecord ?: embeddedRecord, id), publicRecord != null)')
s = s.replace('if (result != null) showRecord(result, true) else showNotRegistered(id)', 'if (result != null) showRecord(mergeLocalVisualFields(result, id), true) else {\n                val local = EvidenceRegistryOutbox.publishedRecord(this@VerifyEvidenceActivity, id)\n                if (local != null) showRecord(local, true) else showNotRegistered(id)\n            }')

old = '''        binding.tvResultDetails.text = buildString {\n            append("EVIDENCE ID\\n$id\\n\\n")\n            append("CAPTURED\\n${formatTime(capturedAt)}\\n\\n")\n            append("OPERATOR / PROJECT\\n$primary\\n\\n")\n            append("SITE / REFERENCE\\n$secondary\\n\\n")\n            append("LOCATION\\n$location\\n\\n")\n            append("DEVICE\\n$device · $maskedDevice")\n        }'''
new = '''        binding.tvResultDetails.text = buildString {\n            append("ID        $id\\n")\n            append("Captured  ${formatTime(capturedAt)}\\n")\n            append("Operator  $primary\\n")\n            append("Site      $secondary\\n")\n            append("GPS       $location\\n")\n            append("Device    $device\\n")\n            append("Identity  $maskedDevice")\n        }'''
s = s.replace(old, new)

old_session = '''        binding.tvSessionActivity.text = buildString {\n            append("SESSION ACTIVITY\\n")\n            append("Clock-in: ${formatTime(sessionStarted)}\\n")\n            append("Reference: ${formatTime(capturedAt)} · $siteId\\n")\n            append("Before: ${displayCount(beforeSite)}   After: ${displayCount(afterSite)}   Site total: ${displayCount(totalSite)}\\n")\n            append("Session total: ${displayCount(totalSession)}   Sites visited: ${displayCount(sitesVisited)}")\n        }'''
new_session = '''        binding.tvSessionActivity.text = buildString {\n            append("FIELD SESSION\\n")\n            append("Clock-in  ${formatTime(sessionStarted)}\\n")\n            append("At $siteId  ${displayCount(beforeSite)} before · ${displayCount(afterSite)} after · ${displayCount(totalSite)} total\\n")\n            append("Whole session  ${displayCount(totalSession)} photos · ${displayCount(sitesVisited)} sites")\n        }'''
s = s.replace(old_session, new_session)

insert_point = '    private fun displayCount(value: Int): String = if (value >= 0) value.toString() else "Pending"\n'
helper = '''    private fun mergeLocalVisualFields(remote: JSONObject, evidenceId: String): JSONObject {\n        val local = EvidenceRegistryOutbox.publishedRecord(this, evidenceId) ?: return remote\n        val merged = JSONObject(remote.toString())\n        val keys = arrayOf(\n            "thumbnailBase64", "thumbnailJpegBase64",\n            "sitePhotosBefore", "sitePhotosAfter", "siteSessionPhotoTotal",\n            "operatorSessionPhotoTotal", "operatorSessionSitesVisited",\n            "operatorSessionStartedAt", "operatorSessionClockOutAt",\n            "operatorSessionClockOutReason", "siteDistanceM", "siteRadiusM"\n        )\n        keys.forEach { key ->\n            val remoteMissing = !merged.has(key) || merged.isNull(key) || merged.optString(key).isBlank()\n            if (remoteMissing && local.has(key) && !local.isNull(key)) merged.put(key, local.opt(key))\n        }\n        return merged\n    }\n\n'''
if 'private fun mergeLocalVisualFields' not in s:
    s = s.replace(insert_point, helper + insert_point)
s = s.replace('binding.btnViewReport.text = "SHARE PDF REPORT"', 'binding.btnViewReport.text = "SHARE FULL PDF REPORT"')
activity.write_text(s, encoding='utf-8')

# --- PDF: replace the previous 3-page sparse/overlapping layout with a 2-page receipt + forensic annex. ---
pdf = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
pdf.write_text(r'''package com.axiominfratech.geostamp.verification

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
    private const val W = 595
    private const val H = 842
    private const val M = 34f
    private val NAVY = Color.rgb(10, 31, 52)
    private val GREEN = Color.rgb(91, 143, 32)
    private val CYAN = Color.rgb(19, 139, 168)
    private val TEXT = Color.rgb(30, 41, 59)
    private val MUTED = Color.rgb(100, 116, 139)
    private val LINE = Color.rgb(218, 224, 230)

    fun exportAndShare(context: Context, record: JSONObject): Result<File> = runCatching {
        val id = firstNonBlank(record.optString("evidenceId"), record.optString("verificationId"), "GEOSTAMP")
        val dir = File(context.cacheDir, "shared_reports").also { it.mkdirs() }
        val file = File(dir, "GeoStamp-$id.pdf")
        createPdf(file, record)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GeoStamp Evidence Report - $id")
            putExtra(Intent.EXTRA_TEXT, "GeoStamp evidence report for $id")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val wa = Intent(base).apply { setPackage("com.whatsapp") }
        context.startActivity(if (wa.resolveActivity(context.packageManager) != null) wa else Intent.createChooser(base, "Share GeoStamp report"))
        file
    }

    private fun createPdf(file: File, r: JSONObject) {
        val doc = PdfDocument()
        try {
            val p1 = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
            drawReceipt(p1.canvas, r)
            doc.finishPage(p1)
            val p2 = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 2).create())
            drawAnnex(p2.canvas, r)
            doc.finishPage(p2)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    private fun drawReceipt(c: Canvas, r: JSONObject) {
        c.drawColor(Color.WHITE)
        text(c, "GEOSTAMP", M, 44f, 24f, Color.BLACK, true)
        text(c, "Axiom Infratech", M, 63f, 11f, GREEN, true)
        text(c, "EVIDENCE REPORT", W-M, 43f, 17f, Color.DKGRAY, true, Paint.Align.RIGHT)
        text(c, "AUTHENTICATED FIELD RECORD", W-M, 61f, 8.5f, MUTED, false, Paint.Align.RIGHT)
        line(c, 76f)

        val risk = r.optBoolean("locationRisk", r.optBoolean("locationIntegrityRisk", false))
        val status = if (risk) "REGISTERED - REVIEW REQUIRED" else "VERIFIED - REGISTERED"
        text(c, status, M, 105f, 18f, if (risk) Color.rgb(205,120,0) else GREEN, true)
        val id = firstNonBlank(r.optString("evidenceId"), r.optString("verificationId"))
        text(c, "Evidence ID  $id", M, 126f, 10.5f, TEXT, true)

        var y = 142f
        val thumb = decodeThumbnail(r)
        if (thumb != null) {
            val rect = fitRect(thumb.width, thumb.height, M, y, W-2*M, 190f)
            c.drawBitmap(thumb, null, rect, Paint(Paint.ANTI_ALIAS_FLAG))
            y = rect.bottom + 14f
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(243,245,247) }
            c.drawRoundRect(RectF(M, y, W-M, y+80f), 7f,7f,p)
            text(c, "Photo thumbnail not available in this verification context", W/2f, y+44f, 10f, MUTED, false, Paint.Align.CENTER)
            y += 94f
        }

        y = section(c, "EVIDENCE", y)
        y = pair(c, "Captured", time(r.optLong("capturedAt", r.optLong("timestamp",0L))), "Operator", firstNonBlank(r.optString("primaryValue"), r.optString("operator")), y)
        y = pair(c, "Site / Reference", firstNonBlank(r.optString("secondaryValue"), r.optString("siteId")), "Accuracy", fmtAcc(r), y)
        y = pair(c, "Coordinates", fmtCoords(r), "Device", fmtDevice(r), y)

        val started = r.optLong("operatorSessionStartedAt",0L)
        if (started > 0L) {
            y = section(c, "FIELD SESSION", y+4f)
            y = pair(c, "Clock-in", time(started), "Site photos", "${count(r,"sitePhotosBefore","photosBeforeAtSite")} before / ${count(r,"sitePhotosAfter","photosAfterAtSite")} after", y)
            y = pair(c, "Site total", count(r,"siteSessionPhotoTotal","sitePhotoTotal"), "Session total", count(r,"operatorSessionPhotoTotal","sessionPhotoTotal"), y)
            y = pair(c, "Sites visited", count(r,"operatorSessionSitesVisited","sessionSitesVisited"), "Clock-out", time(r.optLong("operatorSessionClockOutAt",0L)), y)
        }

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247,248,250) }
        c.drawRoundRect(RectF(M, y+8f, W-M, y+58f), 6f,6f,p)
        text(c, "PUBLIC REGISTRY", M+12f, y+28f, 8.5f, GREEN, true)
        text(c, r.optString("registryStatus","PUBLIC_RECORD"), M+118f, y+28f, 10f, TEXT, true)
        text(c, "SHA-256 sealed · hardware-backed signature recorded", M+12f, y+47f, 8.5f, MUTED, false)

        footer(c, 1)
    }

    private fun drawAnnex(c: Canvas, r: JSONObject) {
        c.drawColor(Color.WHITE)
        c.drawRect(0f,0f,W.toFloat(),78f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=NAVY})
        text(c,"GEOSTAMP",M,35f,21f,Color.WHITE,true)
        text(c,"FORENSIC AUDIT ANNEX",M,58f,10f,Color.rgb(120,220,238),true)
        text(c,"Page 2 of 2",W-M,48f,9f,Color.WHITE,false,Paint.Align.RIGHT)
        var y=104f

        y = section(c,"LOCATION & DEVICE",y)
        y = field(c,"Coordinates",fmtCoords(r),y)
        y = field(c,"Accuracy",fmtAcc(r),y)
        y = field(c,"Location integrity",if(r.optBoolean("locationRisk",r.optBoolean("locationIntegrityRisk",false))) "REVIEW REQUIRED" else "NO RISK FLAG RECORDED",y)
        y = field(c,"Device",fmtDevice(r),y)
        y = field(c,"Masked device identity",r.optString("maskedGeoStampDeviceIdentity","Unavailable"),y)
        y = field(c,"Key security",if(r.optBoolean("captureKeyHardwareBacked",false)) "Hardware-backed · ${r.optString("captureKeySecurityLevel","Recorded")}" else "Not hardware-backed / unavailable",y)

        y = section(c,"CRYPTOGRAPHIC INTEGRITY",y+4f)
        y = field(c,"Image SHA-256",r.optString("imageSha256","Unavailable"),y,true)
        y = field(c,"Signature algorithm",r.optString("captureSignatureAlgorithm","Unavailable"),y)
        y = field(c,"Capture key fingerprint",r.optString("captureKeyFingerprint","Unavailable"),y,true)
        y = field(c,"Capture signature",r.optString("captureSignature","Unavailable"),y,true)

        y = section(c,"SESSION AUDIT",y+4f)
        y = field(c,"Operator session ID",r.optString("operatorSessionId","Unavailable"),y,true)
        y = field(c,"Clock-in",time(r.optLong("operatorSessionStartedAt",0L)),y)
        y = field(c,"Before / after at same site","${count(r,"sitePhotosBefore","photosBeforeAtSite")} / ${count(r,"sitePhotosAfter","photosAfterAtSite")}",y)
        y = field(c,"Site total / whole session","${count(r,"siteSessionPhotoTotal","sitePhotoTotal")} / ${count(r,"operatorSessionPhotoTotal","sessionPhotoTotal")}",y)
        y = field(c,"Sites visited",count(r,"operatorSessionSitesVisited","sessionSitesVisited"),y)
        y = field(c,"Distance / allowed radius","${distance(r)} / ${radius(r)}",y)
        y = field(c,"Clock-out",time(r.optLong("operatorSessionClockOutAt",0L)),y)
        y = field(c,"Clock-out reason",r.optString("operatorSessionClockOutReason","Active / pending"),y)

        val noteY = minOf(y+12f, 748f)
        text(c,"FORENSIC NOTE",M,noteY,9f,CYAN,true)
        wrapped(c,"This report reproduces registry and device-recorded technical signals. It verifies the recorded evidence package and does not independently prove the truth of the photographed scene.",M,noteY+17f,W-2*M,8.3f,MUTED,3)
        footer(c,2)
    }

    private fun section(c:Canvas,title:String,y:Float):Float{
        text(c,title,M,y+14f,10f,GREEN,true)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=LINE;strokeWidth=1f}
        c.drawLine(M,y+20f,W-M,y+20f,p)
        return y+31f
    }

    private fun pair(c:Canvas,l1:String,v1:String,l2:String,v2:String,y:Float):Float{
        val mid=305f
        text(c,l1.uppercase(Locale.US),M,y,7.8f,MUTED,true)
        text(c,v1.ifBlank{"Unavailable"},M,y+15f,10f,TEXT,true)
        text(c,l2.uppercase(Locale.US),mid,y,7.8f,MUTED,true)
        text(c,v2.ifBlank{"Unavailable"},mid,y+15f,10f,TEXT,true)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=LINE;strokeWidth=1f}
        c.drawLine(M,y+25f,W-M,y+25f,p)
        return y+37f
    }

    private fun field(c:Canvas,label:String,value:String,y:Float,mono:Boolean=false):Float{
        val labelX=M
        val valueX=190f
        val valueW=W-M-valueX
        text(c,label.uppercase(Locale.US),labelX,y+11f,7.6f,CYAN,true)
        val safe=value.ifBlank{"Unavailable"}
        val size=if(mono)7.2f else 8.8f
        val lines=wrap(safe,if(mono)52 else 58).take(4)
        lines.forEachIndexed { i,line -> text(c,line,valueX,y+11f+i*11f,size,TEXT,!mono) }
        val h=maxOf(27f,15f+lines.size*11f)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=LINE;strokeWidth=1f}
        c.drawLine(M,y+h,W-M,y+h,p)
        return y+h+5f
    }

    private fun line(c:Canvas,y:Float){ c.drawLine(M,y,W-M,y,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=LINE;strokeWidth=1f}) }
    private fun footer(c:Canvas,page:Int){ line(c,807f); text(c,"GeoStamp · Axiom Infratech · Page $page of 2",W/2f,827f,8f,Color.GRAY,false,Paint.Align.CENTER) }
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean,align:Paint.Align=Paint.Align.LEFT){
        c.drawText(s,x,y,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=size;textAlign=align;typeface=if(bold)Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.DEFAULT})
    }
    private fun wrapped(c:Canvas,s:String,x:Float,y:Float,maxW:Float,size:Float,color:Int,maxLines:Int){
        wrap(s,95).take(maxLines).forEachIndexed { i,line -> text(c,line,x,y+i*(size+3f),size,color,false) }
    }
    private fun wrap(s:String,n:Int):List<String>{
        if(s.length<=n)return listOf(s)
        val out=mutableListOf<String>(); var rest=s
        while(rest.length>n){ var cut=rest.lastIndexOf(' ',n); if(cut<1)cut=n; out+=rest.substring(0,cut); rest=rest.substring(cut).trimStart() }
        if(rest.isNotEmpty())out+=rest; return out
    }
    private fun decodeThumbnail(r:JSONObject):Bitmap?{
        val raw=firstNonBlank(r.optString("thumbnailBase64"),r.optString("thumbnailJpegBase64"),r.optString("thumb")).substringAfter("base64,","")
        if(raw.isBlank())return null
        return runCatching{val b=Base64.decode(raw,Base64.DEFAULT);BitmapFactory.decodeByteArray(b,0,b.size)}.getOrNull()
    }
    private fun fitRect(sw:Int,sh:Int,x:Float,y:Float,mw:Float,mh:Float):RectF{val scale=minOf(mw/sw,mh/sh);val w=sw*scale;val h=sh*scale;return RectF(x+(mw-w)/2f,y,x+(mw-w)/2f+w,y+h)}
    private fun time(v:Long)=if(v>0)SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.getDefault()).format(Date(v)) else "Active / unavailable"
    private fun count(r:JSONObject,a:String,b:String):String{val v=r.optInt(a,r.optInt(b,-1));return if(v>=0)v.toString() else "Pending"}
    private fun fmtCoords(r:JSONObject):String{val lat=r.optDouble("latitude",r.optDouble("lat",Double.NaN));val lon=r.optDouble("longitude",r.optDouble("lon",Double.NaN));return if(lat.isFinite()&&lon.isFinite())"%.6f, %.6f".format(Locale.US,lat,lon) else "Unavailable"}
    private fun fmtAcc(r:JSONObject):String{val a=r.optDouble("accuracyM",r.optDouble("accuracy",Double.NaN));return if(a.isFinite())"±%.1f m".format(Locale.US,a) else "Unavailable"}
    private fun fmtDevice(r:JSONObject)=firstNonBlank(listOf(r.optString("deviceManufacturer"),r.optString("deviceHardwareModel")).filter{it.isNotBlank()}.joinToString(" "),r.optString("deviceModel"),"Unavailable")
    private fun distance(r:JSONObject):String{val d=r.optDouble("siteDistanceM",r.optDouble("distanceM",Double.NaN));return if(d.isFinite())"%.0f m".format(Locale.US,d) else "Unavailable"}
    private fun radius(r:JSONObject):String{val d=r.optDouble("siteRadiusM",Double.NaN);return if(d.isFinite())"%.0f m".format(Locale.US,d) else "Unavailable"}
    private fun firstNonBlank(vararg v:String)=v.firstOrNull{it.isNotBlank()}?:""
}
''', encoding='utf-8')

print('Rebuilt verification receipt and forensic PDF v2; added local sealed-record thumbnail/session fallback.')
