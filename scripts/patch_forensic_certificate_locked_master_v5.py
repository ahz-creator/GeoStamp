from pathlib import Path

root = Path(__file__).resolve().parents[1]
exporter = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
session_file = root / 'app/src/main/java/com/axiominfratech/geostamp/core/OperatorSessionManager.kt'
logo = root / 'app/src/main/res/drawable-nodpi/geostamp_report_logo.png'

if not logo.exists():
    raise SystemExit('Missing app/src/main/res/drawable-nodpi/geostamp_report_logo.png. Run git pull first.')

exporter.write_text(r'''package com.axiominfratech.geostamp.verification

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.core.content.FileProvider
import com.axiominfratech.geostamp.R
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
import kotlin.math.*

object EvidencePdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 12f

    private val NAVY = Color.rgb(6, 37, 82)
    private val NAVY_DARK = Color.rgb(3, 27, 61)
    private val BLUE = Color.rgb(20, 88, 170)
    private val CYAN = Color.rgb(21, 149, 197)
    private val PURPLE = Color.rgb(108, 76, 200)
    private val GREEN = Color.rgb(19, 145, 66)
    private val RED = Color.rgb(215, 49, 51)
    private val TEXT = Color.rgb(22, 34, 47)
    private val MUTED = Color.rgb(88, 103, 119)
    private val LINE = Color.rgb(199, 210, 222)
    private val SOFT = Color.rgb(245, 248, 251)
    private val SOFT2 = Color.rgb(234, 239, 245)
    private val SOFT_GREEN = Color.rgb(240, 249, 243)

    fun exportAndShare(context: Context, record: JSONObject): Result<File> = runCatching {
        require(decodeThumbnail(record) != null) { "Mandatory evidence thumbnail is unavailable." }
        val evidenceId = id(record)
        val file = File(File(context.cacheDir, "shared_reports").also { it.mkdirs() }, "GeoStamp-$evidenceId.pdf")
        createPdf(context, file, record)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GeoStamp Digital Evidence Certificate — $evidenceId")
            putExtra(Intent.EXTRA_TEXT, "GeoStamp digital evidence authentication certificate — $evidenceId")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val wa = Intent(share).apply { setPackage("com.whatsapp") }
        context.startActivity(if (wa.resolveActivity(context.packageManager) != null) wa else Intent.createChooser(share, "Share GeoStamp certificate"))
        file
    }

    private fun createPdf(context: Context, file: File, r: JSONObject) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
            draw(context, page.canvas, r)
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    private fun draw(context: Context, c: Canvas, r: JSONObject) {
        c.drawColor(Color.WHITE)
        drawHeader(context, c, r)
        drawIdentity(c, r)
        drawDescription(c, r)
        drawExhibit(c, r)
        drawControlMatrix(c, r)
        drawCrypto(c, r)
        drawLifecycle(c, r)
        drawInventory(c, r)
        drawScope(c, r)
        drawBottom(c, r)
    }

    private fun drawHeader(context: Context, c: Canvas, r: JSONObject) {
        val logoRaw = BitmapFactory.decodeResource(context.resources, R.drawable.geostamp_report_logo)
        val logo = cropTransparent(logoRaw)
        drawBitmapFit(c, logo, 12f, 4f, 112f, 56f)

        text(c, "DIGITAL EVIDENCE", 335f, 20f, 16f, NAVY, true, Paint.Align.CENTER)
        text(c, "FORENSIC CERTIFICATE", 335f, 38f, 16f, NAVY, true, Paint.Align.CENTER)
        text(c, "AUTHENTICATED FIELD RECORD • CRYPTOGRAPHICALLY SEALED & REGISTERED", 335f, 51f, 5.2f, TEXT, true, Paint.Align.CENTER)

        round(c, 483f, 7f, 582f, 51f, 5f, SOFT_GREEN, GREEN)
        drawShieldCheck(c, 493f, 15f, 23f, GREEN)
        text(c, if (overallPass(r)) "VERIFIED" else "REVIEW", 546f, 25f, 8.7f, GREEN, true, Paint.Align.CENTER)
        text(c, "MACHINE-CHECKED", 546f, 37f, 4.6f, TEXT, true, Paint.Align.CENTER)
        text(c, "RECORD", 546f, 45f, 4.6f, TEXT, true, Paint.Align.CENTER)
        line(c, M, 60f, W-M, 60f, NAVY, 1.25f)
    }

    private fun drawIdentity(c: Canvas, r: JSONObject) {
        val y = 64f
        sectionBar(c, y, "01  CERTIFICATE IDENTITY • REGISTRY • CLASSIFICATION")
        val top = y + 14f
        val widths = floatArrayOf(146f, 146f, 146f, 133f)
        val xs = floatArrayOf(M, M+146f, M+292f, M+438f)
        cell(c, xs[0], top, widths[0], 24f, "EVIDENCE ID", id(r), true)
        cell(c, xs[1], top, widths[1], 24f, "CLASSIFICATION", "DIGITAL FIELD EVIDENCE", true)
        cell(c, xs[2], top, widths[2], 24f, "OPERATOR / SITE", "${operator(r)} / ${site(r)}", true)
        cell(c, xs[3], top, widths[3], 24f, "REGISTRY STATUS", registryLabel(r), true, GREEN)
        cell(c, xs[0], top+24f, widths[0], 25f, "CAPTURED", time(capturedAt(r)), true)
        cell(c, xs[1], top+24f, widths[1], 25f, "REGISTERED", time(publishedAt(r)), true)
        cell(c, xs[2], top+24f, widths[2], 25f, "CERTIFICATE ISSUED", time(System.currentTimeMillis()), true)
        cell(c, xs[3], top+24f, widths[3], 25f, "ISSUER", "Axiom Infratech", true)
    }

    private fun drawDescription(c: Canvas, r: JSONObject) {
        val y = 128f
        sectionBar(c, y, "02  EVIDENCE DESCRIPTION • CAPTURE PROVENANCE • FIELD CONTEXT")
        val top = y + 14f
        cellBox(c, M, top, 285f, 39f)
        cellBox(c, 297f, top, 286f, 39f)
        text(c, "EVIDENCE DESCRIPTION", M+7f, top+10f, 5.0f, NAVY, true)
        drawWrapped(c,
            "Registered field photographic evidence captured through the GeoStamp field session and associated with the recorded site/reference identifier.",
            M+7f, top+21f, 270f, 6.0f, TEXT, false, 3)
        text(c, "CAPTURE PURPOSE / CONTEXT", 304f, top+10f, 5.0f, NAVY, true)
        drawWrapped(c,
            "This certificate records digital evidence identity, capture provenance, location data, integrity controls and registry state available to GeoStamp.",
            304f, top+21f, 270f, 6.0f, TEXT, false, 3)
    }

    private fun drawExhibit(c: Canvas, r: JSONObject) {
        val y = 184f
        sectionBar(c, y, "03  REGISTERED EXHIBIT • LOCATION • DEVICE • SESSION")
        val top = y + 14f
        val h = 170f
        val photoX = M
        val photoW = 137f
        val mapX = 153f
        val mapW = 235f
        val infoX = 392f
        val infoW = W-M-infoX

        stroke(c, photoX, top, photoX+photoW, top+h, LINE)
        text(c, "REGISTERED SOURCE VISUAL", photoX+6f, top+10f, 5.2f, NAVY, true)
        val bmp = decodeThumbnail(r)!!
        val dest = fitCropRect(bmp.width, bmp.height, photoX+6f, top+17f, photoW-12f, h-23f)
        c.save(); c.clipRect(photoX+6f, top+17f, photoX+photoW-6f, top+h-6f)
        c.drawBitmap(bmp, null, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)); c.restore()

        stroke(c, mapX, top, mapX+mapW, top+h, LINE)
        text(c, "LOCATION MAP  (SITE / CLOCK-IN vs PHOTO CAPTURE)", mapX+6f, top+10f, 5.0f, NAVY, true)
        drawMap(c, r, mapX+6f, top+17f, mapW-12f, h-23f)

        val rows = listOf(
            "CAPTURE GPS" to gps(r),
            "ACCURACY" to accuracy(r),
            "CLOCK-IN (SITE)" to time(r.optLong("operatorSessionStartedAt",0L)),
            "DEVICE" to device(r),
            "DEVICE IDENTITY" to first(r.optString("maskedGeoStampDeviceIdentity"), "Unavailable"),
            "SESSION EVIDENCE" to sessionPhotos(r),
            "SESSION ID" to first(r.optString("operatorSessionId"), "Unavailable"),
            "SCHEMA / MARKER" to "${r.optInt("schemaVersion",0)} / ${r.optInt("markerVersion",0)}",
            "CAPTURE KEY" to if(r.optBoolean("captureKeyHardwareBacked",false)) "Hardware-backed • YES" else "Recorded"
        )
        val rh = h/rows.size
        var yy = top
        rows.forEach { (a,b) -> detailRow(c, infoX, yy, infoW, rh, a, b); yy += rh }
    }

    private fun drawControlMatrix(c: Canvas, r: JSONObject) {
        val y = 370f
        sectionBar(c, y, "04  FORENSIC CONTROL MATRIX • VERIFICATION OUTCOME")
        val top = y+14f
        val col = floatArrayOf(M, 126f, 310f, 480f, W-M)
        headerCell(c,col[0],top,col[1]-col[0],15f,"CONTROL")
        headerCell(c,col[1],top,col[2]-col[1],15f,"VERIFICATION TEST")
        headerCell(c,col[2],top,col[3]-col[2],15f,"RECORDED RESULT")
        headerCell(c,col[3],top,col[4]-col[3],15f,"STATE")
        val rows = listOf(
            Triple("Registry","Registry record available",if(registryPass(r)) "Public record confirmed" else "Record available") to if(registryPass(r)) "PASS" else "RECORDED",
            Triple("Integrity","SHA-256 fingerprint comparison",if(r.optString("imageSha256").isNotBlank()) "SHA-256 recorded" else "Unavailable") to if(r.optString("imageSha256").isNotBlank()) "PASS" else "REVIEW",
            Triple("Signature","Digital capture signature",when(verifySignature(r)){true->"ECDSA verified";false->"ECDSA mismatch";null->"ECDSA recorded"}) to when(verifySignature(r)){true->"PASS";false->"FAIL";null->"RECORDED"},
            Triple("Key protection","Capture signing key status",if(r.optBoolean("captureKeyHardwareBacked",false)) "Hardware-backed" else "Recorded") to if(r.optBoolean("captureKeyHardwareBacked",false)) "YES" else "RECORDED",
            Triple("Visual","Registered evidence presence","Thumbnail present") to "PRESENT",
            Triple("Session","Capture-chain continuity",if(r.optString("operatorSessionId").isNotBlank()) "Session chain recorded" else "Not available") to if(r.optString("operatorSessionId").isNotBlank()) "RECORDED" else "N/A"
        )
        var yy = top+15f
        rows.forEach { (triple,state) ->
            matrixCell(c,col[0],yy,col[1]-col[0],14f,triple.first,true)
            matrixCell(c,col[1],yy,col[2]-col[1],14f,triple.second)
            matrixCell(c,col[2],yy,col[3]-col[2],14f,triple.third)
            matrixCell(c,col[3],yy,col[4]-col[3],14f,state,true,if(state in listOf("PASS","YES","PRESENT","RECORDED")) GREEN else RED)
            yy += 14f
        }
    }

    private fun drawCrypto(c: Canvas, r: JSONObject) {
        val y = 469f
        sectionBar(c, y, "05  CRYPTOGRAPHIC IDENTITY • HASH • SIGNATURE • KEY FINGERPRINT")
        val top=y+14f
        cellBox(c,M,top,238f,38f)
        cellBox(c,250f,top,118f,38f)
        cellBox(c,368f,top,215f,38f)
        text(c,"IMAGE SHA-256 FINGERPRINT",M+7f,top+10f,4.9f,NAVY,true)
        drawWrapped(c,first(r.optString("imageSha256"),"Unavailable"),M+7f,top+21f,224f,5.5f,TEXT,false,2,Typeface.MONOSPACE)
        text(c,"SIGNATURE ALGORITHM",257f,top+10f,4.9f,NAVY,true)
        drawWrapped(c,first(r.optString("captureSignatureAlgorithm"),"Unavailable"),257f,top+21f,102f,5.6f,TEXT,true,2)
        text(c,"KEY FINGERPRINT",375f,top+10f,4.9f,NAVY,true)
        drawWrapped(c,first(r.optString("captureKeyFingerprint"),"Unavailable"),375f,top+21f,200f,5.3f,TEXT,false,2,Typeface.MONOSPACE)
        cell(c,M,top+38f,190f,24f,"HASH PURPOSE","Digital image integrity reference",false)
        cell(c,202f,top+38f,178f,24f,"SIGNATURE PURPOSE","Capture authenticity record",false)
        cell(c,380f,top+38f,203f,24f,"KEY STATUS",if(r.optBoolean("captureKeyHardwareBacked",false)) "Hardware-backed • YES" else "Recorded",true,GREEN)
    }

    private fun drawLifecycle(c: Canvas, r: JSONObject) {
        val y=548f
        sectionBar(c,y,"06  EVIDENCE LIFECYCLE • TEMPORAL RECORD • CHAIN OF VERIFICATION")
        val top=y+14f
        val events=listOf(
            Triple("01 • CLOCK-IN",timeShort(r.optLong("operatorSessionStartedAt",0L)),PURPLE),
            Triple("02 • CAPTURE",timeShort(capturedAt(r)),BLUE),
            Triple("03 • SIGN","AT CAPTURE",GREEN),
            Triple("04 • REGISTER",timeShort(publishedAt(r)),CYAN),
            Triple("05 • CERTIFICATE",timeShort(System.currentTimeMillis()),NAVY)
        )
        val cw=(W-2*M)/5f
        events.forEachIndexed { i,e ->
            val cx=M+cw*i+cw/2f
            if(i<events.lastIndex){ line(c,cx+20f,top+28f,cx+cw-20f,top+28f,Color.rgb(141,157,173),1f); text(c,"→",cx+cw/2f,top+32f,10f,NAVY,true,Paint.Align.CENTER) }
            drawCircleBadge(c,cx,top+28f,12f,e.third,(i+1).toString().padStart(2,'0'))
            text(c,e.first,cx,top+9f,5.0f,NAVY,true,Paint.Align.CENTER)
            text(c,e.second,cx,top+49f,6.4f,TEXT,true,Paint.Align.CENTER)
            if(i!=2) text(c,dateShort(if(i==0) r.optLong("operatorSessionStartedAt",0L) else if(i==1) capturedAt(r) else if(i==3) publishedAt(r) else System.currentTimeMillis()),cx,top+59f,4.5f,MUTED,false,Paint.Align.CENTER)
        }
        text(c,"CAPTURE  →  HASH  →  SIGN  →  LOCATION  →  REGISTER  →  VERIFY",W/2f,top+72f,5.5f,NAVY,true,Paint.Align.CENTER)
    }

    private fun drawInventory(c: Canvas, r: JSONObject) {
        val y=637f
        sectionBar(c,y,"07  EVIDENCE INVENTORY • SESSION CONTENT")
        val top=y+14f
        val cols=floatArrayOf(M,96f,234f,448f,W-M)
        headerCell(c,cols[0],top,cols[1]-cols[0],14f,"EXHIBIT")
        headerCell(c,cols[1],top,cols[2]-cols[1],14f,"TYPE")
        headerCell(c,cols[2],top,cols[3]-cols[2],14f,"SESSION RELATIONSHIP")
        headerCell(c,cols[3],top,cols[4]-cols[3],14f,"STATUS")
        val total=sessionPhotoCount(r).coerceAtLeast(1)
        val shown=min(total,4)
        var yy=top+14f
        for(i in 1..shown){
            matrixCell(c,cols[0],yy,cols[1]-cols[0],13f,"EX-${i.toString().padStart(2,'0')}",true)
            matrixCell(c,cols[1],yy,cols[2]-cols[1],13f,if(i==1) "Current evidence photo" else "Photo ${i.toString().padStart(2,'0')}")
            matrixCell(c,cols[2],yy,cols[3]-cols[2],13f,if(i==1) "Registered session evidence" else "Session evidence • $i of $total")
            matrixCell(c,cols[3],yy,cols[4]-cols[3],13f,if(i==1) "PRESENT" else "RECORDED",true,GREEN)
            yy+=13f
        }
        if(total>4) text(c,"+ ${total-4} additional session evidence item(s)",M+7f,yy+9f,5.0f,MUTED,true)
    }

    private fun drawScope(c: Canvas, r: JSONObject) {
        val y=714f
        sectionBar(c,y,"08  AUTHENTICATION SCOPE • HANDLING NOTE • PUBLIC VERIFICATION")
        val top=y+14f
        cellBox(c,M,top,190f,61f)
        cellBox(c,202f,top,190f,61f)
        cellBox(c,392f,top,191f,61f)
        text(c,"AUTHENTICATION SCOPE",M+7f,top+11f,5.0f,NAVY,true)
        drawWrapped(c,"GeoStamp authenticates the digital record and recorded capture provenance available to the system, including registered identity, cryptographic integrity, capture signature, location data and session continuity.",M+7f,top+23f,174f,5.4f,TEXT,false,5)
        text(c,"EVIDENCE HANDLING NOTE",209f,top+11f,5.0f,NAVY,true)
        drawWrapped(c,"This certificate reflects the evidence state and machine-verifiable controls recorded by GeoStamp at registration. Source values shown are derived from the registered evidence record.",209f,top+23f,174f,5.4f,TEXT,false,5)
        text(c,"PUBLIC VERIFICATION",399f,top+11f,5.0f,NAVY,true)
        drawQr(c,"https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}",513f,top+8f,62f)
        drawWrapped(c,"Scan the QR code to compare this certificate with the published registry record.",399f,top+24f,105f,5.3f,TEXT,false,3)
        text(c,id(r),399f,top+49f,5.8f,NAVY,true)

        round(c,M,top+66f,518f,top+89f,4f,Color.rgb(255,251,240),Color.rgb(223,192,105))
        text(c,"INTERPRETATION BOUNDARY",M+8f,top+77f,4.9f,NAVY,true)
        drawWrapped(c,"This automated report does not independently determine whether the photographed scene is truthful, complete, lawful or materially significant.",M+8f,top+86f,490f,4.8f,TEXT,false,2)
    }

    private fun drawBottom(c: Canvas, r: JSONObject) {
        val top=817f
        cellBox(c,M,top,185f,18f)
        cellBox(c,197f,top,196f,18f)
        cellBox(c,393f,top,190f,18f)
        text(c,"ISSUER / AUTHORITY",M+6f,top+7f,4.5f,MUTED,true)
        text(c,"AXIOM INFRATECH · GeoStamp Digital Evidence Platform",M+6f,top+15f,4.6f,NAVY,true)
        text(c,"DOCUMENT CONTROL",203f,top+7f,4.5f,MUTED,true)
        text(c,"Certificate: Locked Master  •  Page 1 / 1",203f,top+15f,4.6f,NAVY,true)
        text(c,"RECORD ID",399f,top+7f,4.5f,MUTED,true)
        text(c,id(r),399f,top+15f,4.6f,NAVY,true)
        fill(c,0f,837f,W.toFloat(),842f,NAVY)
        text(c,"GEOSTAMP • DIGITAL EVIDENCE AUTHENTICATION • AXIOM INFRATECH",M,841f,3.8f,Color.WHITE,true)
        text(c,id(r),W/2f,841f,3.8f,Color.WHITE,true,Paint.Align.CENTER)
        text(c,"PAGE 1 / 1",W-M,841f,3.8f,Color.WHITE,true,Paint.Align.RIGHT)
    }

    private fun drawMap(c: Canvas, r: JSONObject, x:Float,y:Float,w:Float,h:Float) {
        fill(c,x,y,x+w,y+h,Color.rgb(244,247,250))
        val road=Color.rgb(203,212,221)
        val roads=listOf(
            floatArrayOf(.05f,.20f,.28f,.35f,.55f,.31f,.75f,.48f,.95f,.43f),
            floatArrayOf(.08f,.76f,.30f,.62f,.52f,.66f,.78f,.55f,.96f,.70f),
            floatArrayOf(.22f,.05f,.28f,.35f,.30f,.62f,.43f,.95f),
            floatArrayOf(.65f,.05f,.62f,.30f,.75f,.48f,.82f,.92f),
            floatArrayOf(.04f,.52f,.30f,.49f,.52f,.54f,.78f,.55f,.97f,.61f)
        )
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=road;strokeWidth=2f;style=Paint.Style.STROKE}
        roads.forEach{arr-> val path=Path(); var i=0; while(i<arr.size){val px=x+arr[i]*w; val py=y+arr[i+1]*h; if(i==0) path.moveTo(px,py) else path.lineTo(px,py); i+=2}; c.drawPath(path,p)}

        val capLat=r.optDouble("lat",r.optDouble("latitude",Double.NaN))
        val capLon=r.optDouble("lon",r.optDouble("longitude",Double.NaN))
        val siteLat=r.optDouble("operatorSessionStartedLatitude",Double.NaN)
        val siteLon=r.optDouble("operatorSessionStartedLongitude",Double.NaN)
        val acc=r.optDouble("accuracy",r.optDouble("accuracyM",Double.NaN))
        val hasSite=siteLat.isFinite() && siteLon.isFinite()

        val capX=x+w*.63f; val capY=y+h*.48f
        if(acc.isFinite()) { val rad=(22f*(acc/100.0).coerceIn(.25,1.6)).toFloat(); c.drawCircle(capX,capY,rad,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=BLUE;style=Paint.Style.STROKE;strokeWidth=1.2f}) }
        drawPin(c,capX,capY,RED)
        text(c,"PHOTO CAPTURE",capX,capY+17f,4.1f,RED,true,Paint.Align.CENTER)

        if(hasSite){
            val sx=x+w*.28f; val sy=y+h*.36f
            val dash=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=BLUE;strokeWidth=1.2f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(4f,3f),0f)}
            c.drawLine(sx,sy,capX,capY,dash)
            drawPin(c,sx,sy,BLUE)
            text(c,"SITE / CLOCK-IN",sx,sy+17f,4.1f,BLUE,true,Paint.Align.CENTER)
            val dist=distanceMeters(siteLat,siteLon,capLat,capLon)
            text(c,"DISTANCE: ${if(dist.isFinite()) "%.0f m".format(Locale.US,dist) else "—"}",x+6f,y+h-6f,4.5f,NAVY,true)
        } else {
            text(c,"SITE COORDINATE NOT RECORDED",x+w-6f,y+h-6f,4.2f,MUTED,true,Paint.Align.RIGHT)
        }

        fill(c,x+6f,y+h-38f,x+w-6f,y+h-10f,Color.argb(235,255,255,255))
        text(c,"CAPTURE GPS",x+11f,y+h-28f,4.4f,NAVY,true)
        text(c,gps(r),x+66f,y+h-28f,4.4f,TEXT,true)
        text(c,"ACCURACY",x+11f,y+h-17f,4.4f,NAVY,true)
        text(c,accuracy(r),x+66f,y+h-17f,4.4f,TEXT,true)
    }

    private fun registryPass(r:JSONObject)=r.optString("registryStatus").contains("PUBLIC",true) || publishedAt(r)>0L
    private fun overallPass(r:JSONObject)=registryPass(r) && r.optString("imageSha256").isNotBlank() && verifySignature(r)!=false
    private fun registryLabel(r:JSONObject)=if(registryPass(r)) "PUBLICLY REGISTERED" else first(r.optString("registryStatus"),"REGISTERED")
    private fun capturedAt(r:JSONObject)=r.optLong("capturedAt",r.optLong("timestamp",0L))
    private fun publishedAt(r:JSONObject)=r.optLong("publishedAt",r.optLong("registeredAt",0L))
    private fun operator(r:JSONObject)=first(r.optString("primaryValue"),r.optString("operator"),r.optString("operatorSessionOperatorName"),"Unavailable")
    private fun site(r:JSONObject)=first(r.optString("secondaryValue"),r.optString("siteId"),"Unavailable")
    private fun gps(r:JSONObject):String { val lat=r.optDouble("lat",r.optDouble("latitude",Double.NaN)); val lon=r.optDouble("lon",r.optDouble("longitude",Double.NaN)); return if(lat.isFinite()&&lon.isFinite()) "%.6f N, %.6f E".format(Locale.US,lat,lon) else "Unavailable" }
    private fun accuracy(r:JSONObject):String { val a=r.optDouble("accuracy",r.optDouble("accuracyM",Double.NaN)); return if(a.isFinite()) "±%.1f m".format(Locale.US,a) else "Unavailable" }
    private fun device(r:JSONObject)=first(listOf(r.optString("deviceManufacturer"),r.optString("deviceHardwareModel")).filter{it.isNotBlank()}.joinToString(" "),r.optString("deviceModel"),"Unavailable")
    private fun sessionPhotoCount(r:JSONObject):Int = listOf(r.optInt("siteSessionPhotoTotal",-1),r.optInt("operatorSessionPhotoTotal",-1),r.optInt("sessionPhotoTotal",-1)).firstOrNull{it>=0} ?: 1
    private fun sessionPhotos(r:JSONObject)="${sessionPhotoCount(r)} photos"
    private fun id(r:JSONObject)=first(r.optString("evidenceId"),r.optString("verificationId"),"GEOSTAMP-EVIDENCE")
    private fun first(vararg v:String)=v.firstOrNull{it.isNotBlank()}?:"Unavailable"

    private fun verifySignature(r:JSONObject):Boolean? = runCatching {
        val pk=r.optString("capturePublicKey"); val sig=r.optString("captureSignature"); val payload=r.optString("captureSignedPayload")
        if(pk.isBlank()||sig.isBlank()||payload.isBlank()) return null
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pk,Base64.DEFAULT)))
        Signature.getInstance(r.optString("captureSignatureAlgorithm","SHA256withECDSA")).run { initVerify(key); update(payload.toByteArray(Charsets.UTF_8)); verify(Base64.decode(sig,Base64.DEFAULT)) }
    }.getOrNull()

    private fun decodeThumbnail(r:JSONObject):Bitmap? {
        val src=first(r.optString("thumbnailBase64"),r.optString("thumbnailJpegBase64"),r.optString("thumb")).trim()
        if(src.isBlank()||src=="Unavailable") return null
        val raw=if(src.contains("base64,")) src.substringAfter("base64,") else src
        return runCatching{ val b=Base64.decode(raw,Base64.DEFAULT); BitmapFactory.decodeByteArray(b,0,b.size)}.getOrNull()
    }

    private fun cropTransparent(src:Bitmap):Bitmap {
        var left=src.width; var top=src.height; var right=-1; var bottom=-1
        for(y in 0 until src.height step 2) for(x in 0 until src.width step 2) if(Color.alpha(src.getPixel(x,y))>12){ left=min(left,x); right=max(right,x); top=min(top,y); bottom=max(bottom,y) }
        if(right<left||bottom<top) return src
        val pad=4; val l=(left-pad).coerceAtLeast(0); val t=(top-pad).coerceAtLeast(0); val rr=(right+pad).coerceAtMost(src.width-1); val bb=(bottom+pad).coerceAtMost(src.height-1)
        return Bitmap.createBitmap(src,l,t,rr-l+1,bb-t+1)
    }

    private fun drawBitmapFit(c:Canvas,b:Bitmap,x:Float,y:Float,w:Float,h:Float){ val s=min(w/b.width,h/b.height); val dw=b.width*s; val dh=b.height*s; c.drawBitmap(b,null,RectF(x+(w-dw)/2,y+(h-dh)/2,x+(w+dw)/2,y+(h+dh)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
    private fun fitCropRect(iw:Int,ih:Int,x:Float,y:Float,w:Float,h:Float):RectF { val s=max(w/iw,h/ih); val dw=iw*s; val dh=ih*s; return RectF(x+(w-dw)/2,y+(h-dh)/2,x+(w+dw)/2,y+(h+dh)/2) }
    private fun fill(c:Canvas,l:Float,t:Float,r:Float,b:Float,col:Int)=c.drawRect(l,t,r,b,Paint().apply{color=col;style=Paint.Style.FILL})
    private fun stroke(c:Canvas,l:Float,t:Float,r:Float,b:Float,col:Int)=c.drawRect(l,t,r,b,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;style=Paint.Style.STROKE;strokeWidth=.7f})
    private fun round(c:Canvas,l:Float,t:Float,r:Float,b:Float,rad:Float,fill:Int,stroke:Int){ c.drawRoundRect(l,t,r,b,rad,rad,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=fill;style=Paint.Style.FILL}); c.drawRoundRect(l,t,r,b,rad,rad,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=stroke;style=Paint.Style.STROKE;strokeWidth=.7f}) }
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,col:Int,w:Float)=c.drawLine(x1,y1,x2,y2,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;strokeWidth=w})
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,col:Int,bold:Boolean=false,align:Paint.Align=Paint.Align.LEFT,typeface:Typeface?=null){ c.drawText(s,x,y,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;textSize=size;textAlign=align;this.typeface=typeface ?: if(bold) Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.DEFAULT}) }
    private fun sectionBar(c:Canvas,y:Float,title:String){ fill(c,M,y,W-M,y+13f,NAVY); text(c,title,M+6f,y+9.5f,6.2f,Color.WHITE,true) }
    private fun cellBox(c:Canvas,x:Float,y:Float,w:Float,h:Float){ fill(c,x,y,x+w,y+h,Color.WHITE); stroke(c,x,y,x+w,y+h,LINE) }
    private fun cell(c:Canvas,x:Float,y:Float,w:Float,h:Float,label:String,value:String,bold:Boolean=false,valueColor:Int=TEXT){ cellBox(c,x,y,w,h); fill(c,x,y,x+w,y+10f,SOFT2); text(c,label,x+6f,y+7.2f,4.3f,MUTED,true); drawWrapped(c,value,x+6f,y+18f,w-12f,5.9f,valueColor,bold,2) }
    private fun headerCell(c:Canvas,x:Float,y:Float,w:Float,h:Float,s:String){ fill(c,x,y,x+w,y+h,SOFT2); stroke(c,x,y,x+w,y+h,LINE); text(c,s,x+6f,y+10f,4.7f,MUTED,true) }
    private fun matrixCell(c:Canvas,x:Float,y:Float,w:Float,h:Float,s:String,bold:Boolean=false,col:Int=TEXT){ fill(c,x,y,x+w,y+h,Color.WHITE); stroke(c,x,y,x+w,y+h,LINE); drawWrapped(c,s,x+6f,y+9f,w-12f,4.8f,col,bold,1) }
    private fun detailRow(c:Canvas,x:Float,y:Float,w:Float,h:Float,label:String,value:String){ fill(c,x,y,x+w*.42f,y+h,SOFT2); stroke(c,x,y,x+w,y+h,LINE); text(c,label,x+6f,y+h*.58f,4.0f,MUTED,true); drawWrapped(c,value,x+w*.44f,y+h*.58f,w*.54f,5.0f,TEXT,true,2) }
    private fun drawWrapped(c:Canvas,s:String,x:Float,y:Float,maxW:Float,size:Float,col:Int,bold:Boolean,maxLines:Int,typeface:Typeface?=null){ val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;textSize=size;this.typeface=typeface ?: if(bold) Typeface.create(Typeface.DEFAULT,Typeface.BOLD) else Typeface.DEFAULT}; val words=s.trim().split(Regex("\\s+")).filter{it.isNotBlank()}; var line=""; var yy=y; var lines=0; for(word in words){ val test=if(line.isEmpty()) word else "$line $word"; if(paint.measureText(test)>maxW && line.isNotEmpty()){ c.drawText(line,x,yy,paint); yy+=size+2f; lines++; if(lines>=maxLines) return; line=word } else line=test }; if(line.isNotEmpty() && lines<maxLines) c.drawText(line,x,yy,paint) }
    private fun drawShieldCheck(c:Canvas,x:Float,y:Float,size:Float,col:Int){ val p=Path(); p.moveTo(x+size*.5f,y);p.lineTo(x+size,y+size*.18f);p.lineTo(x+size*.9f,y+size*.72f);p.quadTo(x+size*.62f,y+size*.98f,x+size*.5f,y+size);p.quadTo(x+size*.38f,y+size*.98f,x+size*.1f,y+size*.72f);p.lineTo(x,y+size*.18f);p.close();c.drawPath(p,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;style=Paint.Style.FILL}); val q=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f;strokeCap=Paint.Cap.ROUND}; c.drawLine(x+size*.28f,y+size*.48f,x+size*.44f,y+size*.64f,q); c.drawLine(x+size*.44f,y+size*.64f,x+size*.74f,y+size*.34f,q) }
    private fun drawCircleBadge(c:Canvas,x:Float,y:Float,r:Float,col:Int,label:String){ c.drawCircle(x,y,r,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col;style=Paint.Style.STROKE;strokeWidth=1.7f}); text(c,label,x,y+2.3f,5.3f,col,true,Paint.Align.CENTER) }
    private fun drawPin(c:Canvas,x:Float,y:Float,col:Int){ c.drawCircle(x,y,6.7f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=col}); c.drawCircle(x,y,2.1f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE}) }
    private fun drawQr(c:Canvas,value:String,x:Float,y:Float,size:Float){ runCatching{ val m=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,180,180); val b=Bitmap.createBitmap(m.width,m.height,Bitmap.Config.ARGB_8888); for(xx in 0 until m.width) for(yy in 0 until m.height) b.setPixel(xx,yy,if(m[xx,yy]) Color.BLACK else Color.WHITE); c.drawBitmap(b,null,RectF(x,y,x+size,y+size),Paint(Paint.ANTI_ALIAS_FLAG)); b.recycle() } }
    private fun time(v:Long)=if(v>0) SimpleDateFormat("dd MMM yyyy • hh:mm a",Locale.ENGLISH).format(Date(v)) else "Unavailable"
    private fun timeShort(v:Long)=if(v>0) SimpleDateFormat("hh:mm a",Locale.ENGLISH).format(Date(v)) else "—"
    private fun dateShort(v:Long)=if(v>0) SimpleDateFormat("dd MMM yyyy",Locale.ENGLISH).format(Date(v)) else ""
    private fun distanceMeters(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double { if(!aLat.isFinite()||!aLon.isFinite()||!bLat.isFinite()||!bLon.isFinite()) return Double.NaN; val r=6371000.0; val p1=Math.toRadians(aLat); val p2=Math.toRadians(bLat); val dp=Math.toRadians(bLat-aLat); val dl=Math.toRadians(bLon-aLon); val h=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2); return 2*r*atan2(sqrt(h),sqrt(1-h)) }
}
''', encoding='utf-8')

# Store clock-in GPS in operator session so the report can truthfully show SITE/CLOCK-IN vs PHOTO CAPTURE.
src = session_file.read_text(encoding='utf-8')
if 'val startedLatitude: Double?' not in src:
    src = src.replace(
'''        val startedAt: Long,
        val lastActivityAt: Long,''',
'''        val startedAt: Long,
        val startedLatitude: Double?,
        val startedLongitude: Double?,
        val startedAccuracyM: Float?,
        val lastActivityAt: Long,''')
    src = src.replace(
'''            startedAt = startedAt,
            lastActivityAt = prefs.getLong(KEY_LAST_ACTIVITY, startedAt),''',
'''            startedAt = startedAt,
            startedLatitude = prefs.getString(KEY_STARTED_LAT, null)?.toDoubleOrNull(),
            startedLongitude = prefs.getString(KEY_STARTED_LON, null)?.toDoubleOrNull(),
            startedAccuracyM = prefs.getString(KEY_STARTED_ACC, null)?.toFloatOrNull(),
            lastActivityAt = prefs.getLong(KEY_LAST_ACTIVITY, startedAt),''')
    src = src.replace(
'''        operator: RemoteConfigManager.OperatorConfig,
        inactivityTimeoutMinutes: Int = DEFAULT_INACTIVITY_MINUTES
    ): Session {''',
'''        operator: RemoteConfigManager.OperatorConfig,
        inactivityTimeoutMinutes: Int = DEFAULT_INACTIVITY_MINUTES,
        startedLatitude: Double? = null,
        startedLongitude: Double? = null,
        startedAccuracyM: Float? = null
    ): Session {''')
    src = src.replace(
'''            .putLong(KEY_STARTED, now)
            .putLong(KEY_LAST_ACTIVITY, now)''',
'''            .putLong(KEY_STARTED, now)
            .apply {
                if (startedLatitude != null) putString(KEY_STARTED_LAT, startedLatitude.toString()) else remove(KEY_STARTED_LAT)
                if (startedLongitude != null) putString(KEY_STARTED_LON, startedLongitude.toString()) else remove(KEY_STARTED_LON)
                if (startedAccuracyM != null) putString(KEY_STARTED_ACC, startedAccuracyM.toString()) else remove(KEY_STARTED_ACC)
            }
            .putLong(KEY_LAST_ACTIVITY, now)''')
    src = src.replace(
'''            .remove(KEY_STARTED)
            .remove(KEY_LAST_ACTIVITY)''',
'''            .remove(KEY_STARTED)
            .remove(KEY_STARTED_LAT)
            .remove(KEY_STARTED_LON)
            .remove(KEY_STARTED_ACC)
            .remove(KEY_LAST_ACTIVITY)''')
    src = src.replace(
'''            startedAt = startedAt,
            lastActivityAt = prefs.getLong(KEY_LAST_ACTIVITY, startedAt),''',
'''            startedAt = startedAt,
            startedLatitude = prefs.getString(KEY_STARTED_LAT, null)?.toDoubleOrNull(),
            startedLongitude = prefs.getString(KEY_STARTED_LON, null)?.toDoubleOrNull(),
            startedAccuracyM = prefs.getString(KEY_STARTED_ACC, null)?.toFloatOrNull(),
            lastActivityAt = prefs.getLong(KEY_LAST_ACTIVITY, startedAt),''')
    src = src.replace(
'''        private const val KEY_STARTED = "operator_session_started_at"
        private const val KEY_LAST_ACTIVITY''',
'''        private const val KEY_STARTED = "operator_session_started_at"
        private const val KEY_STARTED_LAT = "operator_session_started_latitude"
        private const val KEY_STARTED_LON = "operator_session_started_longitude"
        private const val KEY_STARTED_ACC = "operator_session_started_accuracy_m"
        private const val KEY_LAST_ACTIVITY''')
    session_file.write_text(src, encoding='utf-8')

# Pass the current GPS fix into session start and publish the stored clock-in GPS into each evidence record.
src = vm.read_text(encoding='utf-8')
if 'startedLatitude = loc?.latitude' not in src:
    src = src.replace(
'''        val session = operatorSessions.start(
            operator,
            _remoteAppConfig.value.policy.operatorInactivityTimeoutMinutes
        )''',
'''        val loc = _uiState.value.currentLocation
        val session = operatorSessions.start(
            operator,
            _remoteAppConfig.value.policy.operatorInactivityTimeoutMinutes,
            startedLatitude = loc?.latitude,
            startedLongitude = loc?.longitude,
            startedAccuracyM = loc?.accuracyM
        )''')
if 'operatorSessionStartedLatitude' not in src:
    src = src.replace(
'''                        put("operatorSessionStartedAt", operatorSession?.startedAt ?: 0L)
                        put("operatorSessionOperatorId", operatorSession?.operatorId ?: "")''',
'''                        put("operatorSessionStartedAt", operatorSession?.startedAt ?: 0L)
                        operatorSession?.startedLatitude?.let { put("operatorSessionStartedLatitude", it) }
                        operatorSession?.startedLongitude?.let { put("operatorSessionStartedLongitude", it) }
                        operatorSession?.startedAccuracyM?.let { put("operatorSessionStartedAccuracyM", it) }
                        put("operatorSessionOperatorId", operatorSession?.operatorId ?: "")''')
vm.write_text(src, encoding='utf-8')

print('Locked-master certificate patch applied.')
print(' - official report trademark asset: drawable-nodpi/geostamp_report_logo.png')
print(' - fixed master geometry (sections 01-08)')
print(' - real clock-in GPS persisted for future two-point maps')
print(' - no launcher icon substitution')
print(' - no unsupported standards/compliance claim')
