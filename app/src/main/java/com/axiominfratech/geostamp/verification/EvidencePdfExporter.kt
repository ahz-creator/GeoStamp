package com.axiominfratech.geostamp.verification

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.*

object EvidencePdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 28f

    fun exportAndShare(context: Context, record: JSONObject): Result<File> = runCatching {
        require(decodeThumbnail(record) != null) { "Mandatory evidence thumbnail is unavailable." }
        val id = id(record)
        val file = File(File(context.cacheDir, "shared_reports").also { it.mkdirs() }, "GeoStamp-$id.pdf")
        createPdf(file, record)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GeoStamp Evidence Report — $id")
            putExtra(Intent.EXTRA_TEXT, "GeoStamp digital evidence authentication report — $id")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val wa = Intent(base).apply { setPackage("com.whatsapp") }
        context.startActivity(if (wa.resolveActivity(context.packageManager) != null) wa else Intent.createChooser(base, "Share GeoStamp report"))
        file
    }

    private fun createPdf(file: File, r: JSONObject) {
        val doc = PdfDocument()
        try {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W,H,1).create())
            draw(p.canvas, r)
            doc.finishPage(p)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    private fun draw(c: Canvas, r: JSONObject) {
        val navy = Color.rgb(9,29,52); val green = Color.rgb(93,153,31)
        val pale = Color.rgb(240,246,235); val line = Color.rgb(220,226,232)
        c.drawColor(Color.WHITE)

        // Header
        fill(c, 0f, 0f, W.toFloat(), 72f, navy)
        text(c,"GEOSTAMP",M,34f,23f,Color.WHITE,true)
        text(c,"DIGITAL EVIDENCE AUTHENTICATION REPORT",M,52f,8.5f,Color.rgb(190,207,224),true)
        text(c,"AXIOM INFRATECH",W-M,32f,10f,Color.rgb(61,200,229),true,Paint.Align.RIGHT)
        text(c,"PUBLIC REGISTRY RECORD",W-M,49f,7f,Color.WHITE,false,Paint.Align.RIGHT)

        // Identity/status strip
        fill(c,M,83f,W-M,109f,pale)
        text(c,"VERIFIED · REGISTERED",M+10,101f,12.5f,green,true)
        text(c,id(r),W-M-10,100f,9f,Color.rgb(25,38,54),true,Paint.Align.RIGHT)

        // Evidence visual left / capture record right
        val thumb = decodeThumbnail(r)!!
        val photo = fit(thumb.width,thumb.height,M,121f,315f,205f)
        c.drawBitmap(thumb,null,photo,Paint(Paint.ANTI_ALIAS_FLAG))
        stroke(c,M,121f,M+315f,326f,line)

        var y = 126f
        val x = 360f
        section(c,"CAPTURE RECORD",x,y,green); y += 18f
        val captured = r.optLong("capturedAt",r.optLong("timestamp",0L))
        val lat = r.optDouble("latitude",r.optDouble("lat",Double.NaN))
        val lon = r.optDouble("longitude",r.optDouble("lon",Double.NaN))
        val acc = r.optDouble("accuracyM",r.optDouble("accuracy",Double.NaN))
        val device = first(listOf(r.optString("deviceManufacturer"),r.optString("deviceHardwareModel")).filter{it.isNotBlank()}.joinToString(" "),r.optString("deviceModel"),"Unavailable")
        val captureRows = listOf(
            "Captured" to time(captured),
            "Operator" to first(r.optString("primaryValue"),r.optString("operator")),
            "Site" to first(r.optString("secondaryValue"),r.optString("siteId")),
            "Coordinates" to if(lat.isFinite()&&lon.isFinite()) "%.6f, %.6f".format(Locale.US,lat,lon) else "Unavailable",
            "Accuracy" to if(acc.isFinite()) "±%.1f m".format(Locale.US,acc) else "Unavailable",
            "Device" to device,
            "Device identity" to first(r.optString("maskedGeoStampDeviceIdentity"),"Unavailable"),
            "Workspace" to first(r.optString("workspaceMode"),"Unavailable")
        )
        captureRows.forEach { (a,b) -> y = compactRow(c,x,y,a,b,207f) }

        // AI summary: descriptive only
        var bandY = 340f
        val ai = r.optString("aiVisualSummary").trim()
        val purpose = first(r.optString("aiVisualPurpose"), r.optString("aiLikelyPurpose"), "").takeIf { it != "Unavailable" }.orEmpty().trim()
        if (ai.isNotBlank() || purpose.isNotBlank()) {
            section(c,"AI VISUAL SUMMARY",M,bandY,green)
            val summary = listOfNotNull(
                ai.takeIf { it.isNotBlank() },
                purpose.takeIf { it.isNotBlank() }?.let { "Likely documentation purpose: $it" }
            ).joinToString("  ")
            wrap(summary, 82).take(2).forEachIndexed { i,v ->
                text(c,v,M,bandY+16+i*10,7.2f,Color.rgb(55,65,75),false)
            }
            text(c,"AI description only · excluded from PASS/FAIL authentication",W-M,bandY+16,6.1f,Color.GRAY,false,Paint.Align.RIGHT)
            bandY += 42f
        }

        // Field session context
        section(c,"FIELD SESSION CONTEXT",M,bandY,green); bandY += 17f
        fill(c,M,bandY,W-M,bandY+58f,navy)
        val before = count(r,"sitePhotosBefore","photosBeforeAtSite")
        val after = countActive(r,"sitePhotosAfter","photosAfterAtSite")
        val totalSite = count(r,"siteSessionPhotoTotal","sitePhotoTotal")
        val totalSession = count(r,"operatorSessionPhotoTotal","sessionPhotoTotal")
        val sites = count(r,"operatorSessionSitesVisited","sessionSitesVisited")
        val clockOut = r.optLong("operatorSessionClockOutAt",0L)
        val session = listOf(
            "CLOCK-IN" to time(r.optLong("operatorSessionStartedAt",0L)),
            "BEFORE / AFTER" to "$before / $after",
            "SITE TOTAL" to totalSite,
            "WHOLE SESSION" to "$totalSession photos · $sites sites",
            "CLOCK-OUT" to if(clockOut>0) time(clockOut) else "Active"
        )
        val colW=(W-2*M)/5f
        session.forEachIndexed { i,(a,b) ->
            val xx=M+i*colW+8f
            text(c,a,xx,bandY+18f,5.6f,Color.rgb(163,184,205),true)
            wrap(b,22).take(2).forEachIndexed { j,v -> text(c,v,xx,bandY+33+j*9f,7f,Color.WHITE,true) }
        }
        bandY += 72f

        // Machine verification + forensic provenance side by side
        section(c,"MACHINE VERIFICATION RESULT",M,bandY,green)
        section(c,"CRYPTOGRAPHIC & PROVENANCE RECORD",305f,bandY,green)
        bandY += 17f
        val signaturePass = verifySignature(r)
        val risk = r.optBoolean("locationRisk",false)||r.optBoolean("locationIntegrityRisk",false)
        val distance = firstFinite(r.optDouble("siteDistanceM",Double.NaN),r.optDouble("distanceM",Double.NaN))
        val radius = r.optDouble("siteRadiusM",Double.NaN)
        val siteResult = if(distance.isFinite()&&radius.isFinite()) if(distance<=radius) "PASS" else "REVIEW" else "RECORDED"
        val checks = listOf(
            "Registry record" to if(r.optString("registryStatus").contains("PUBLIC",true)) "PASS" else "RECORDED",
            "Visual evidence" to "PASS",
            "ECDSA signature" to when(signaturePass){true->"PASS";false->"FAIL";null->"RECORDED"},
            "Signing key" to if(r.optBoolean("captureKeyHardwareBacked",false)) "HARDWARE-BACKED" else "RECORDED",
            "Location integrity" to if(risk) "REVIEW" else "PASS",
            "Site association" to siteResult,
            "Session continuity" to if(r.optString("operatorSessionId").isNotBlank()) "RECORDED" else "N/A"
        )
        var ly=bandY
        checks.forEach { (a,b) ->
            text(c,a.uppercase(Locale.US),M,ly,6f,Color.GRAY,true)
            text(c,b,190f,ly,7f,if(b=="PASS") green else Color.rgb(35,48,62),true)
            ly+=15f
        }

        var ry=bandY
        val prov = listOf(
            "IMAGE SHA-256" to r.optString("imageSha256","Unavailable"),
            "SIGNATURE ALGORITHM" to r.optString("captureSignatureAlgorithm","Unavailable"),
            "KEY FINGERPRINT" to r.optString("captureKeyFingerprint","Unavailable"),
            "SESSION ID" to r.optString("operatorSessionId","Unavailable"),
            "PUBLISHED" to time(r.optLong("publishedAt",0L)),
            "SCHEMA / MARKER" to "${r.optInt("schemaVersion",0)} / ${r.optInt("markerVersion",0)}"
        )
        prov.forEach { (a,b) -> ry = monoRow(c,305f,ry,a,b,262f) }

        // Capture signature once, full width
        val sigY = maxOf(ly,ry)+7f
        text(c,"CAPTURE SIGNATURE",M,sigY,6f,Color.GRAY,true)
        val sig = r.optString("captureSignature","Unavailable")
        wrapFixed(sig,94).take(2).forEachIndexed { i,v -> text(c,v,M,sigY+11+i*9,6f,Color.rgb(30,42,55),false,Paint.Align.LEFT,"monospace") }

        // Evidence lifecycle + system finding
        val lifeY=sigY+38f
        section(c,"EVIDENCE LIFECYCLE",M,lifeY,green)
        val lifecycle="CAPTURED ${shortTime(captured)}  →  SEALED/SIGNED  →  REGISTERED ${shortTime(r.optLong("publishedAt",0L))}  →  VERIFIED AT REPORT GENERATION"
        text(c,lifecycle,M,lifeY+16,6.8f,Color.rgb(35,48,62),true)

        val findingY=lifeY+35f
        fill(c,M,findingY,W-M,findingY+43f,pale)
        text(c,"SYSTEM FINDING",M+9,findingY+14,6.4f,green,true)
        val finding="The GeoStamp registry record and the machine-verifiable integrity, provenance, device, location and session checks above produced the stated results."
        wrap(finding,100).take(2).forEachIndexed { i,v -> text(c,v,M+9,findingY+27+i*9,6.5f,Color.rgb(40,52,62),false) }

        val noteY=findingY+57f
        text(c,"GeoStamp authenticates the digital evidence record and recorded capture metadata; it does not independently establish the truth of objects or events depicted.",M,noteY,5.8f,Color.GRAY,false)

        // Public verification: machine-readable independent lookup reference.
        val verifyUrl = "https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}"
        val qrTop = noteY + 12f
        val qrSize = 58f
        drawQr(c, verifyUrl, M, qrTop, qrSize)
        text(c,"PUBLIC VERIFICATION",M+qrSize+10f,qrTop+12f,6.2f,green,true)
        text(c,id(r),M+qrSize+10f,qrTop+25f,7.2f,Color.rgb(30,42,55),true)
        text(c,"Scan QR or verify by Evidence ID in the GeoStamp public registry.",M+qrSize+10f,qrTop+38f,6.0f,Color.GRAY,false)
        text(c,"Report generated ${time(System.currentTimeMillis())}",M+qrSize+10f,qrTop+51f,5.8f,Color.GRAY,false)

        text(c,"GeoStamp · Axiom Infratech",M,H-20f,6.2f,Color.GRAY,true)
        text(c,"PAGE 1 OF 1",W-M,H-20f,6.2f,Color.GRAY,true,Paint.Align.RIGHT)
    }

    private fun verifySignature(r:JSONObject):Boolean? = runCatching {
        val pk=r.optString("capturePublicKey"); val sig=r.optString("captureSignature"); val payload=r.optString("captureSignedPayload")
        if(pk.isBlank()||sig.isBlank()||payload.isBlank()) return null
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pk,Base64.DEFAULT)))
        Signature.getInstance(r.optString("captureSignatureAlgorithm","SHA256withECDSA")).run {
            initVerify(key); update(payload.toByteArray(Charsets.UTF_8)); verify(Base64.decode(sig,Base64.DEFAULT))
        }
    }.getOrNull()

    private fun drawQr(c: Canvas, value: String, x: Float, y: Float, size: Float) {
        runCatching {
            val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 160, 160)
            val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            for (xx in 0 until matrix.width) {
                for (yy in 0 until matrix.height) {
                    bitmap.setPixel(xx, yy, if (matrix[xx, yy]) Color.BLACK else Color.WHITE)
                }
            }
            c.drawBitmap(bitmap, null, RectF(x, y, x + size, y + size), Paint(Paint.ANTI_ALIAS_FLAG))
            bitmap.recycle()
        }
    }

    private fun decodeThumbnail(r: JSONObject): Bitmap? {
        val source = first(
            r.optString("thumbnailBase64"),
            r.optString("thumbnailJpegBase64"),
            r.optString("thumb")
        ).trim()
        if (source.isBlank() || source == "Unavailable") return null
        val raw = if (source.contains("base64,")) source.substringAfter("base64,") else source
        if (raw.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    private fun id(r:JSONObject)=first(r.optString("evidenceId"),r.optString("verificationId"),"GEOSTAMP-EVIDENCE")
    private fun first(vararg v:String)=v.firstOrNull{it.isNotBlank()}?:"Unavailable"
    private fun firstFinite(vararg v:Double)=v.firstOrNull{it.isFinite()}?:Double.NaN
    private fun count(r:JSONObject,a:String,b:String):String { val v=r.optInt(a,r.optInt(b,-1)); return if(v>=0)v.toString() else "Pending" }
    private fun countActive(r:JSONObject,a:String,b:String):String { if(r.has(a)&&r.isNull(a)) return "Active"; val v=r.optInt(a,r.optInt(b,-1)); return if(v>=0)v.toString() else "Active" }
    private fun time(v:Long)=if(v>0) SimpleDateFormat("dd MMM yyyy, hh:mm a",Locale.getDefault()).format(Date(v)) else "Unavailable"
    private fun shortTime(v:Long)=if(v>0) SimpleDateFormat("dd MMM HH:mm:ss",Locale.getDefault()).format(Date(v)) else "—"
    private fun section(c:Canvas,s:String,x:Float,y:Float,col:Int)=text(c,s,x,y,7.6f,col,true)
    private fun compactRow(c:Canvas,x:Float,y:Float,a:String,b:String,w:Float):Float { text(c,a.uppercase(Locale.US),x,y,5.7f,Color.GRAY,true); wrap(b,34).take(2).forEachIndexed{i,v->text(c,v,x,y+10+i*8,7f,Color.rgb(28,40,54),true)}; return y+25f }
    private fun monoRow(c:Canvas,x:Float,y:Float,a:String,b:String,w:Float):Float { text(c,a,x,y,5.6f,Color.GRAY,true); val ls=wrapFixed(b,46).take(2); ls.forEachIndexed{i,v->text(c,v,x,y+9+i*7,5.5f,Color.rgb(28,40,54),false,Paint.Align.LEFT,"monospace")}; return y+16+maxOf(0,ls.size-1)*7 }
    private fun text(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,bold:Boolean,align:Paint.Align=Paint.Align.LEFT,font:String?=null){ val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;textSize=z;textAlign=align;typeface=if(font=="monospace")Typeface.MONOSPACE else if(bold)Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.DEFAULT}; c.drawText(s,x,y,p) }
    private fun fill(c:Canvas,l:Float,t:Float,r:Float,b:Float,col:Int){c.drawRect(l,t,r,b,Paint().apply{color=col})}
    private fun stroke(c:Canvas,l:Float,t:Float,r:Float,b:Float,col:Int){c.drawRect(l,t,r,b,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;style=Paint.Style.STROKE;strokeWidth=1f})}
    private fun fit(sw:Int,sh:Int,x:Float,y:Float,mw:Float,mh:Float):RectF{val s=minOf(mw/sw,mh/sh);val w=sw*s;val h=sh*s;return RectF(x+(mw-w)/2,y+(mh-h)/2,x+(mw-w)/2+w,y+(mh-h)/2+h)}
    private fun wrap(s:String,n:Int):List<String>{val out=mutableListOf<String>();var cur="";s.split(Regex("\\s+")).forEach{w->if((cur+" "+w).trim().length>n){if(cur.isNotBlank())out+=cur;cur=w}else cur=(cur+" "+w).trim()};if(cur.isNotBlank())out+=cur;return out}
    private fun wrapFixed(s:String,n:Int)=if(s.isBlank()) listOf("Unavailable") else s.chunked(n)
}
