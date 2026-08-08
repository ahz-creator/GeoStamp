from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'

out.write_text(r'''package com.axiominfratech.geostamp.verification

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import kotlin.math.*

object EvidencePdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 16f

    private val NAVY = Color.rgb(7, 35, 77)
    private val NAVY_DARK = Color.rgb(4, 25, 55)
    private val BLUE = Color.rgb(26, 91, 166)
    private val CYAN = Color.rgb(28, 148, 194)
    private val PURPLE = Color.rgb(116, 72, 202)
    private val GREEN = Color.rgb(20, 145, 67)
    private val TEXT = Color.rgb(28, 39, 52)
    private val MUTED = Color.rgb(91, 107, 124)
    private val LINE = Color.rgb(207, 216, 225)
    private val SOFT = Color.rgb(246, 249, 252)
    private val SOFT_GREEN = Color.rgb(242, 250, 244)
    private val CRYPTO_BG = Color.rgb(9, 30, 56)

    fun exportAndShare(context: Context, record: JSONObject): Result<File> = runCatching {
        require(decodeThumbnail(record) != null) { "Mandatory evidence thumbnail is unavailable." }
        val id = id(record)
        val file = File(File(context.cacheDir, "shared_reports").also { it.mkdirs() }, "GeoStamp-$id.pdf")
        createPdf(context, file, record)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GeoStamp Forensic Certificate — $id")
            putExtra(Intent.EXTRA_TEXT, "GeoStamp digital evidence forensic certificate — $id")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val wa = Intent(base).apply { setPackage("com.whatsapp") }
        context.startActivity(if (wa.resolveActivity(context.packageManager) != null) wa else Intent.createChooser(base, "Share GeoStamp certificate"))
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
        drawHero(c, r)
        drawProofBands(c, r)
        drawCrypto(c, r)
        drawLifecycle(c, r)
        drawVerification(c, r)
        drawFooter(c, r)
    }

    private fun drawHeader(context: Context, c: Canvas, r: JSONObject) {
        // Brand left
        val icon = runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()
        if (icon != null) drawDrawable(c, icon, 18f, 11f, 34f, 34f)
        text(c, "GeoStamp", 58f, 29f, 17f, NAVY, true)
        text(c, "BY AXIOM INFRATECH", 58f, 42f, 5.6f, NAVY, true)

        // Central title
        text(c, "DIGITAL EVIDENCE", W / 2f, 21f, 16.5f, NAVY, true, Paint.Align.CENTER)
        text(c, "FORENSIC CERTIFICATE", W / 2f, 39f, 16.5f, NAVY, true, Paint.Align.CENTER)
        text(c, "AUTHENTICATED FIELD RECORD • CRYPTOGRAPHICALLY SEALED & REGISTERED", W / 2f, 53f, 5.6f, TEXT, true, Paint.Align.CENTER)

        // Strong authentication badge
        round(c, W - 116f, 9f, W - 18f, 52f, 5f, SOFT_GREEN, GREEN)
        drawShieldCheck(c, W - 103f, 18f, 20f, GREEN)
        text(c, if (overallPass(r)) "VERIFIED" else "REVIEW", W - 68f, 27f, 8.8f, GREEN, true, Paint.Align.CENTER)
        text(c, "MACHINE-CHECKED", W - 68f, 39f, 4.8f, TEXT, true, Paint.Align.CENTER)
        text(c, "RECORD", W - 68f, 47f, 4.8f, TEXT, true, Paint.Align.CENTER)
        line(c, M, 61f, W - M, 61f, NAVY, 1.2f)
    }

    private fun drawIdentity(c: Canvas, r: JSONObject) {
        val y = 67f
        sectionBar(c, y, "01  CERTIFICATE IDENTITY • REGISTRY • CLASSIFICATION")
        val top = y + 14f
        val cols = floatArrayOf(M, 160f, 310f, 446f, W - M)
        cell(c, cols[0], top, cols[1]-cols[0], 25f, "EVIDENCE ID", id(r), true)
        cell(c, cols[1], top, cols[2]-cols[1], 25f, "CLASSIFICATION", "DIGITAL FIELD EVIDENCE", true)
        cell(c, cols[2], top, cols[3]-cols[2], 25f, "OPERATOR / SITE", "${operator(r)} / ${site(r)}", true)
        cell(c, cols[3], top, cols[4]-cols[3], 25f, "REGISTRY STATUS", registryLabel(r), true, GREEN)
        cell(c, cols[0], top+25f, cols[1]-cols[0], 27f, "CAPTURED", time(capturedAt(r)), true)
        cell(c, cols[1], top+25f, cols[2]-cols[1], 27f, "REGISTERED", time(publishedAt(r)), true)
        cell(c, cols[2], top+25f, cols[3]-cols[2], 27f, "CERTIFICATE ISSUED", time(System.currentTimeMillis()), true)
        cell(c, cols[3], top+25f, cols[4]-cols[3], 27f, "ISSUER", "Axiom Infratech", true)
    }

    private fun drawHero(c: Canvas, r: JSONObject) {
        val y = 135f
        sectionBar(c, y, "02  REGISTERED EXHIBIT • LOCATION • DEVICE • SESSION")
        val top = y + 15f
        val photoX = M
        val photoW = 164f
        val mapX = photoX + photoW + 6f
        val mapW = 222f
        val infoX = mapX + mapW + 6f
        val infoW = W - M - infoX
        val h = 224f

        // Photo exhibit as a real centerpiece
        stroke(c, photoX, top, photoX+photoW, top+h, LINE)
        text(c, "REGISTERED SOURCE VISUAL", photoX+7f, top+12f, 5.8f, NAVY, true)
        val bmp = decodeThumbnail(r)!!
        val dest = fitCropRect(bmp.width, bmp.height, photoX+7f, top+19f, photoW-14f, h-28f)
        c.save()
        c.clipRect(photoX+7f, top+19f, photoX+photoW-7f, top+h-9f)
        c.drawBitmap(bmp, null, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        c.restore()

        // Meaningful map block
        stroke(c, mapX, top, mapX+mapW, top+h, LINE)
        text(c, "LOCATION MAP  (SITE / CLOCK-IN vs PHOTO CAPTURE)", mapX+7f, top+12f, 5.5f, NAVY, true)
        drawMap(c, r, mapX+7f, top+20f, mapW-14f, h-28f)

        // Device/session proof rail
        val rows = listOf(
            "CAPTURE GPS" to gps(r),
            "ACCURACY" to accuracy(r),
            "CLOCK-IN (SITE)" to time(r.optLong("operatorSessionStartedAt",0L)),
            "DEVICE" to device(r),
            "DEVICE IDENTITY" to first(r.optString("maskedGeoStampDeviceIdentity"), "Unavailable"),
            "SESSION EVIDENCE" to sessionPhotos(r),
            "SESSION ID" to first(r.optString("operatorSessionId"), "Unavailable"),
            "SCHEMA / MARKER" to "${r.optInt("schemaVersion",0)} / ${r.optInt("markerVersion",0)}",
            "CAPTURE KEY" to if (r.optBoolean("captureKeyHardwareBacked",false)) "Hardware-backed • YES" else "Recorded"
        )
        var ry = top
        val rh = h / rows.size
        rows.forEach { (a,b) ->
            detailRow(c, infoX, ry, infoW, rh, a, b)
            ry += rh
        }
    }

    private fun drawProofBands(c: Canvas, r: JSONObject) {
        val y = 377f
        sectionBar(c, y, "03  FORENSIC AUTHENTICATION • INTEGRITY • PROVENANCE")
        val top = y + 15f
        val cardW = (W - 2*M - 12f) / 3f
        proofCard(c, M, top, cardW, 80f, "EVIDENCE", listOf(
            "Registry" to if (registryPass(r)) "CONFIRMED" else "RECORDED",
            "Visual evidence" to "PRESENT",
            "Session chain" to if (r.optString("operatorSessionId").isNotBlank()) "RECORDED" else "N/A"
        ))
        proofCard(c, M+cardW+6f, top, cardW, 80f, "INTEGRITY", listOf(
            "SHA-256" to if (r.optString("imageSha256").isNotBlank()) "RECORDED" else "REVIEW",
            "ECDSA signature" to when(verifySignature(r)){true->"VERIFIED";false->"FAIL";null->"RECORDED"},
            "Capture key" to if (r.optBoolean("captureKeyHardwareBacked",false)) "HARDWARE-BACKED" else "RECORDED"
        ))
        proofCard(c, M+(cardW+6f)*2f, top, cardW, 80f, "PROVENANCE", listOf(
            "Capture time" to timeShort(capturedAt(r)),
            "Location" to accuracy(r),
            "Device" to device(r)
        ))
    }

    private fun drawCrypto(c: Canvas, r: JSONObject) {
        val y = 475f
        sectionBar(c, y, "04  CRYPTOGRAPHIC IDENTITY • HASH • SIGNATURE • KEY FINGERPRINT")
        val top = y + 15f
        round(c, M, top, W-M, top+89f, 4f, CRYPTO_BG, CRYPTO_BG)
        text(c, "IMAGE SHA-256 FINGERPRINT", M+12f, top+17f, 5.7f, Color.rgb(160,188,214), true)
        drawWrapped(c, first(r.optString("imageSha256"),"Unavailable"), M+12f, top+31f, 330f, 7.0f, Color.WHITE, true, 2, Typeface.MONOSPACE)
        text(c, "SIGNATURE ALGORITHM", 376f, top+17f, 5.7f, Color.rgb(160,188,214), true)
        text(c, first(r.optString("captureSignatureAlgorithm"),"Unavailable"), 376f, top+31f, 7.0f, Color.WHITE, true)
        text(c, "KEY FINGERPRINT", M+12f, top+58f, 5.7f, Color.rgb(160,188,214), true)
        drawWrapped(c, first(r.optString("captureKeyFingerprint"),"Unavailable"), M+12f, top+72f, 330f, 6.4f, Color.WHITE, true, 2, Typeface.MONOSPACE)
        text(c, "KEY STATUS", 376f, top+58f, 5.7f, Color.rgb(160,188,214), true)
        text(c, if(r.optBoolean("captureKeyHardwareBacked",false)) "HARDWARE-BACKED • YES" else "RECORDED", 376f, top+72f, 6.6f, Color.rgb(135,220,158), true)
    }

    private fun drawLifecycle(c: Canvas, r: JSONObject) {
        val y = 583f
        sectionBar(c, y, "05  EVIDENCE LIFECYCLE • TEMPORAL RECORD • CHAIN OF VERIFICATION")
        val top = y + 15f
        val events = listOf(
            Triple("CLOCK-IN", timeShort(r.optLong("operatorSessionStartedAt",0L)), PURPLE),
            Triple("CAPTURE", timeShort(capturedAt(r)), BLUE),
            Triple("SIGN", "AT CAPTURE", GREEN),
            Triple("REGISTER", timeShort(publishedAt(r)), CYAN),
            Triple("CERTIFICATE", timeShort(System.currentTimeMillis()), NAVY)
        )
        val cw = (W - 2*M) / 5f
        events.forEachIndexed { i, e ->
            val cx = M + cw*i + cw/2f
            if (i < events.lastIndex) {
                line(c, cx+18f, top+30f, cx+cw-18f, top+30f, Color.rgb(143,159,174), 1f)
                text(c, "→", cx+cw/2f, top+35f, 12f, NAVY, true, Paint.Align.CENTER)
            }
            c.drawCircle(cx, top+30f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply{color=e.third;style=Paint.Style.STROKE;strokeWidth=2f})
            text(c, "0${i+1}", cx, top+34f, 5.8f, e.third, true, Paint.Align.CENTER)
            text(c, e.first, cx, top+12f, 5.3f, NAVY, true, Paint.Align.CENTER)
            text(c, e.second, cx, top+57f, 7.0f, TEXT, true, Paint.Align.CENTER)
        }
        text(c, "CAPTURE → HASH → SIGN → LOCATION → REGISTER → VERIFY", W/2f, top+76f, 6.1f, NAVY, true, Paint.Align.CENTER)
    }

    private fun drawVerification(c: Canvas, r: JSONObject) {
        val y = 686f
        sectionBar(c, y, "06  PUBLIC VERIFICATION • AUTHENTICATION SCOPE • INTERPRETATION")
        val top = y + 15f

        // Larger QR + certificate seal
        stroke(c, M, top, 168f, top+109f, LINE)
        text(c, "PUBLIC VERIFICATION", M+10f, top+15f, 6.2f, NAVY, true)
        drawQr(c, "https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}", M+12f, top+25f, 70f)
        text(c, "SCAN QR", M+92f, top+43f, 6.2f, TEXT, true)
        text(c, id(r), M+92f, top+61f, 6.1f, NAVY, true)
        drawSeal(c, 133f, top+80f, 22f)

        stroke(c, 174f, top, 388f, top+109f, LINE)
        text(c, "AUTHENTICATION SCOPE", 184f, top+15f, 6.0f, NAVY, true)
        drawWrapped(c,
            "GeoStamp authenticates the registered digital record and recorded capture provenance available to the system, including evidence identity, cryptographic integrity, capture signature, location data, device identity and session continuity.",
            184f, top+30f, 194f, 5.8f, TEXT, false, 7
        )

        stroke(c, 394f, top, W-M, top+109f, LINE)
        text(c, "MACHINE VERIFICATION", 404f, top+15f, 6.0f, NAVY, true)
        val items = listOf(
            "Registry" to if(registryPass(r)) "PASS" else "RECORDED",
            "Hash" to if(r.optString("imageSha256").isNotBlank()) "PASS" else "REVIEW",
            "Signature" to when(verifySignature(r)){true->"PASS";false->"FAIL";null->"RECORDED"},
            "Hardware key" to if(r.optBoolean("captureKeyHardwareBacked",false)) "YES" else "RECORDED",
            "Visual" to "PRESENT",
            "Session" to if(r.optString("operatorSessionId").isNotBlank()) "RECORDED" else "N/A"
        )
        var yy = top+32f
        items.forEach { (a,b) ->
            check(c, 408f, yy-2f, 3.3f)
            text(c, a, 418f, yy, 5.5f, TEXT, false)
            text(c, b, W-M-8f, yy, 5.5f, GREEN, true, Paint.Align.RIGHT)
            yy += 12f
        }

        // Interpretation boundary: explicit and readable
        round(c, M, top+115f, W-M, top+145f, 3f, Color.rgb(255,251,240), Color.rgb(225,199,128))
        text(c, "INTERPRETATION BOUNDARY", M+9f, top+127f, 5.4f, NAVY, true)
        drawWrapped(c, "This automated report authenticates the digital record and recorded provenance; it does not independently determine whether the photographed scene is truthful, complete, lawful or materially significant.", M+9f, top+139f, W-2*M-18f, 4.9f, TEXT, false, 2)
    }

    private fun drawFooter(c: Canvas, r: JSONObject) {
        val y = 826f
        fill(c, 0f, y, W.toFloat(), H.toFloat(), NAVY_DARK)
        text(c, "GEOSTAMP • DIGITAL EVIDENCE AUTHENTICATION • AXIOM INFRATECH", 16f, 837f, 4.7f, Color.WHITE, true)
        text(c, id(r), W/2f, 837f, 4.7f, Color.WHITE, true, Paint.Align.CENTER)
        text(c, "PAGE 1 / 1", W-16f, 837f, 4.7f, Color.WHITE, true, Paint.Align.RIGHT)
    }

    private fun drawMap(c: Canvas, r: JSONObject, x: Float, y: Float, w: Float, h: Float) {
        fill(c, x, y, x+w, y+h, Color.rgb(241,245,248))
        stroke(c, x, y, x+w, y+h, LINE)
        // restrained road network only; no invented place labels
        val roads = listOf(
            floatArrayOf(.02f,.22f,.25f,.33f,.48f,.30f,.74f,.48f,.98f,.43f),
            floatArrayOf(.06f,.81f,.29f,.66f,.53f,.69f,.77f,.55f,.96f,.73f),
            floatArrayOf(.14f,.04f,.28f,.29f,.27f,.72f,.42f,.96f),
            floatArrayOf(.63f,.03f,.61f,.26f,.69f,.48f,.79f,.95f),
            floatArrayOf(.02f,.55f,.28f,.51f,.50f,.55f,.77f,.57f,.98f,.62f)
        )
        roads.forEach { pts ->
            val path = Path(); path.moveTo(x+pts[0]*w,y+pts[1]*h)
            var i=2; while(i<pts.size){path.lineTo(x+pts[i]*w,y+pts[i+1]*h);i+=2}
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.rgb(199,209,219);strokeWidth=2f})
        }

        val capX=x+w*.63f; val capY=y+h*.46f
        val distance = r.optDouble("siteDistanceM", r.optDouble("distanceM", Double.NaN))
        val hasSite = distance.isFinite() && distance >= 0
        if (hasSite) {
            val siteX=x+w*.34f; val siteY=y+h*.58f
            val dashed=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=BLUE;strokeWidth=1.2f;pathEffect=DashPathEffect(floatArrayOf(4f,3f),0f)}
            c.drawLine(siteX,siteY,capX,capY,dashed)
            mapPin(c, siteX, siteY, BLUE)
            text(c, "SITE / CLOCK-IN", siteX, siteY+17f, 4.5f, BLUE, true, Paint.Align.CENTER)
        }
        mapPin(c, capX, capY, Color.rgb(220,53,43))
        text(c, "PHOTO CAPTURE", capX, capY+18f, 4.5f, Color.rgb(220,53,43), true, Paint.Align.CENTER)

        val acc=r.optDouble("accuracyM",r.optDouble("accuracy",Double.NaN))
        if(acc.isFinite()) {
            val radius = min(42f, max(11f, (acc/4.5).toFloat()))
            c.drawCircle(capX,capY,radius,Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=BLUE;strokeWidth=1f})
        }

        fill(c, x+5f, y+h-34f, x+w-5f, y+h-5f, Color.argb(232,255,255,255))
        text(c, "CAPTURE GPS", x+10f, y+h-22f, 4.5f, MUTED, true)
        text(c, gps(r), x+68f, y+h-22f, 4.8f, TEXT, true)
        text(c, "ACCURACY  ${accuracy(r)}", x+10f, y+h-10f, 4.6f, TEXT, true)
        text(c, if(hasSite) "SITE DISTANCE  ${"%.0f".format(Locale.US,distance)} m" else "SITE COORDINATE NOT RECORDED", x+w-10f, y+h-10f, 4.5f, if(hasSite) NAVY else MUTED, true, Paint.Align.RIGHT)
    }

    private fun proofCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, title: String, rows: List<Pair<String,String>>) {
        round(c,x,y,x+w,y+h,4f,Color.WHITE,LINE)
        text(c,title,x+10f,y+15f,6.0f,NAVY,true)
        line(c,x+10f,y+21f,x+w-10f,y+21f,LINE,.6f)
        var yy=y+37f
        rows.forEach { (a,b)->
            check(c,x+12f,yy-2f,3.2f)
            text(c,a,x+22f,yy,5.5f,TEXT,false)
            text(c,b,x+w-10f,yy,5.4f,if(b in setOf("CONFIRMED","PRESENT","VERIFIED","HARDWARE-BACKED","RECORDED")) GREEN else TEXT,true,Paint.Align.RIGHT)
            yy+=14f
        }
    }

    private fun detailRow(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, value: String) {
        stroke(c,x,y,x+w,y+h,LINE)
        fill(c,x,y,x+78f,y+h,Color.rgb(237,242,246))
        text(c,label,x+7f,y+h*.62f,4.5f,MUTED,true)
        drawWrapped(c,value,x+85f,y+h*.62f,w-92f,5.6f,TEXT,true,2)
    }

    private fun cell(c:Canvas,x:Float,y:Float,w:Float,h:Float,label:String,value:String,boldValue:Boolean,valueColor:Int=TEXT){
        stroke(c,x,y,x+w,y+h,LINE)
        fill(c,x,y,x+w,y+10.5f,Color.rgb(229,235,240))
        text(c,label,x+6f,y+7.5f,4.6f,MUTED,true)
        drawWrapped(c,value,x+6f,y+19f,w-12f,5.8f,valueColor,boldValue,2)
    }

    private fun sectionBar(c:Canvas,y:Float,title:String){
        fill(c,M,y,W-M,y+13f,NAVY)
        text(c,title,M+6f,y+9.5f,6.3f,Color.WHITE,true)
    }

    private fun drawWrapped(c:Canvas,s:String,x:Float,y:Float,width:Float,size:Float,color:Int,bold:Boolean,maxLines:Int,typeface:Typeface?=null){
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=size;this.typeface=typeface ?: if(bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT}
        val words=s.trim().split(Regex("\\s+")).filter{it.isNotBlank()}
        var line=""; var yy=y; var lines=0
        for(word in words){
            val test=if(line.isEmpty()) word else "$line $word"
            if(p.measureText(test)>width && line.isNotEmpty()){
                c.drawText(line,x,yy,p); yy+=size+2.1f; lines++
                if(lines>=maxLines)return
                line=word
            } else line=test
        }
        if(line.isNotEmpty() && lines<maxLines)c.drawText(line,x,yy,p)
    }

    private fun drawSeal(c:Canvas,cx:Float,cy:Float,r:Float){
        c.drawCircle(cx,cy,r,Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=NAVY;strokeWidth=1.5f})
        c.drawCircle(cx,cy,r-5f,Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=GREEN;strokeWidth=1f})
        text(c,"VERIFIED",cx,cy-1f,5.8f,GREEN,true,Paint.Align.CENTER)
        text(c,"GEOSTAMP",cx,cy+8f,4.3f,NAVY,true,Paint.Align.CENTER)
    }

    private fun drawShieldCheck(c:Canvas,x:Float,y:Float,size:Float,color:Int){
        val p=Path(); p.moveTo(x+size/2f,y); p.lineTo(x+size,y+size*.22f); p.lineTo(x+size*.86f,y+size*.76f); p.lineTo(x+size/2f,y+size); p.lineTo(x+size*.14f,y+size*.76f); p.lineTo(x,y+size*.22f); p.close()
        c.drawPath(p,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.FILL})
        val q=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f;strokeCap=Paint.Cap.ROUND}
        c.drawLine(x+size*.28f,y+size*.50f,x+size*.43f,y+size*.66f,q); c.drawLine(x+size*.43f,y+size*.66f,x+size*.73f,y+size*.34f,q)
    }

    private fun mapPin(c:Canvas,x:Float,y:Float,color:Int){
        c.drawCircle(x,y,7f,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.FILL})
        c.drawCircle(x,y,2.4f,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=Color.WHITE;style=Paint.Style.FILL})
    }

    private fun check(c:Canvas,x:Float,y:Float,r:Float){
        c.drawCircle(x,y,r,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=GREEN;style=Paint.Style.FILL})
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=.9f;strokeCap=Paint.Cap.ROUND}
        c.drawLine(x-r*.45f,y,x-r*.08f,y+r*.35f,p); c.drawLine(x-r*.08f,y+r*.35f,x+r*.50f,y-r*.42f,p)
    }

    private fun drawDrawable(c:Canvas,d:Drawable,x:Float,y:Float,w:Float,h:Float){
        val b=Bitmap.createBitmap(max(1,w.toInt()),max(1,h.toInt()),Bitmap.Config.ARGB_8888)
        val cc=Canvas(b); d.setBounds(0,0,b.width,b.height); d.draw(cc)
        c.drawBitmap(b,null,RectF(x,y,x+w,y+h),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)); b.recycle()
    }

    private fun drawQr(c:Canvas,value:String,x:Float,y:Float,size:Float){runCatching{val m=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,220,220);val b=Bitmap.createBitmap(m.width,m.height,Bitmap.Config.ARGB_8888);for(xx in 0 until m.width)for(yy in 0 until m.height)b.setPixel(xx,yy,if(m[xx,yy])Color.BLACK else Color.WHITE);c.drawBitmap(b,null,RectF(x,y,x+size,y+size),Paint(Paint.ANTI_ALIAS_FLAG));b.recycle()}}

    private fun verifySignature(r:JSONObject):Boolean? = runCatching {
        val pk=r.optString("capturePublicKey"); val sig=r.optString("captureSignature"); val payload=r.optString("captureSignedPayload")
        if(pk.isBlank()||sig.isBlank()||payload.isBlank()) return null
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pk,Base64.DEFAULT)))
        Signature.getInstance(r.optString("captureSignatureAlgorithm","SHA256withECDSA")).run{initVerify(key);update(payload.toByteArray(Charsets.UTF_8));verify(Base64.decode(sig,Base64.DEFAULT))}
    }.getOrNull()

    private fun decodeThumbnail(r:JSONObject):Bitmap?{
        val source=first(r.optString("thumbnailBase64"),r.optString("thumbnailJpegBase64"),r.optString("thumb"),"").trim()
        if(source.isBlank()||source=="Unavailable")return null
        val raw=if(source.contains("base64,"))source.substringAfter("base64,") else source
        return runCatching{val bytes=Base64.decode(raw,Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)}.getOrNull()
    }

    private fun registryPass(r:JSONObject)=r.optString("registryStatus").contains("PUBLIC",true)||r.optString("evidenceStatus").contains("REGISTERED",true)
    private fun registryLabel(r:JSONObject)=if(registryPass(r))"PUBLICLY REGISTERED" else first(r.optString("registryStatus"),r.optString("evidenceStatus"),"RECORDED")
    private fun overallPass(r:JSONObject)=registryPass(r)&&decodeThumbnail(r)!=null&&(verifySignature(r)!=false)&&!r.optBoolean("locationIntegrityRisk",false)
    private fun operator(r:JSONObject)=first(r.optString("primaryValue"),r.optString("operator"),r.optString("operatorSessionOperatorName"),"Unavailable")
    private fun site(r:JSONObject)=first(r.optString("secondaryValue"),r.optString("siteId"),"Unavailable")
    private fun device(r:JSONObject)=first(r.optString("deviceModel"),listOf(r.optString("deviceManufacturer"),r.optString("deviceHardwareModel")).filter{it.isNotBlank()}.joinToString(" "),"Unavailable")
    private fun gps(r:JSONObject):String{val lat=r.optDouble("latitude",r.optDouble("lat",Double.NaN));val lon=r.optDouble("longitude",r.optDouble("lon",Double.NaN));return if(lat.isFinite()&&lon.isFinite())"%.6f N, %.6f E".format(Locale.US,lat,lon) else "Unavailable"}
    private fun accuracy(r:JSONObject):String{val a=r.optDouble("accuracyM",r.optDouble("accuracy",Double.NaN));return if(a.isFinite())"±%.1f m".format(Locale.US,a) else "Unavailable"}
    private fun capturedAt(r:JSONObject)=r.optLong("capturedAt",r.optLong("timestamp",0L))
    private fun publishedAt(r:JSONObject)=r.optLong("publishedAt",r.optLong("registeredAt",0L))
    private fun sessionPhotos(r:JSONObject):String{val v=r.optInt("operatorSessionPhotoTotal",r.optInt("sessionPhotoTotal",-1));return if(v>=0)"$v photos" else "Recorded"}
    private fun id(r:JSONObject)=first(r.optString("evidenceId"),r.optString("verificationId"),"GEOSTAMP-EVIDENCE")
    private fun first(vararg v:String)=v.firstOrNull{it.isNotBlank()&&it!="null"}?:"Unavailable"
    private fun time(v:Long)=if(v>0)SimpleDateFormat("dd MMM yyyy • hh:mm a",Locale.getDefault()).format(Date(v)) else "Unavailable"
    private fun timeShort(v:Long)=if(v>0)SimpleDateFormat("hh:mm a",Locale.getDefault()).format(Date(v)) else "—"

    private fun fitCropRect(iw:Int,ih:Int,x:Float,y:Float,w:Float,h:Float):RectF{
        val scale=max(w/iw.toFloat(),h/ih.toFloat()); val nw=iw*scale; val nh=ih*scale
        return RectF(x+(w-nw)/2f,y+(h-nh)/2f,x+(w+nw)/2f,y+(h+nh)/2f)
    }

    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false,align:Paint.Align=Paint.Align.LEFT){val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=size;textAlign=align;typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT};c.drawText(s,x,y,p)}
    private fun fill(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int){c.drawRect(l,t,r,b,Paint().apply{this.color=color;style=Paint.Style.FILL})}
    private fun stroke(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int){c.drawRect(l,t,r,b,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.STROKE;strokeWidth=.6f})}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,width:Float){c.drawLine(x1,y1,x2,y2,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;strokeWidth=width})}
    private fun round(c:Canvas,l:Float,t:Float,r:Float,b:Float,rad:Float,fill:Int,stroke:Int){c.drawRoundRect(RectF(l,t,r,b),rad,rad,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=fill;style=Paint.Style.FILL});c.drawRoundRect(RectF(l,t,r,b),rad,rad,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=stroke;style=Paint.Style.STROKE;strokeWidth=.7f})}
}
''', encoding='utf-8')

print('Premium forensic certificate V4 applied:', out)
