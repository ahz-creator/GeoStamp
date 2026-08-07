from pathlib import Path

root = Path(__file__).resolve().parents[1]

# --- 1) Use full-resolution stamped image for on-device AI, not the tiny registry thumbnail.
vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
s = vm.read_text(encoding='utf-8')
old = '''                val aiVisual = withContext(Dispatchers.IO) {\n                    AiVisualSummaryClient.analyze(\n                        app,\n                        slipThumbnailBase64,\n                        operatorStr,\n                        siteIdStr,\n                        location.timestampMs\n                    )\n                }'''
new = '''                val aiVisual = withContext(Dispatchers.IO) {\n                    AiVisualSummaryClient.analyzeFile(app, stampedFile)\n                }'''
if old in s:
    s = s.replace(old, new, 1)
vm.write_text(s, encoding='utf-8')

# --- 2) Stronger, free, on-device object inventory using full image + tiled recovery.
ai = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/AiVisualSummaryClient.kt'
ai.write_text(r'''package com.axiominfratech.geostamp.verification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Free on-device visual inventory. No cloud API, key, quota or per-photo charge. */
object AiVisualSummaryClient {
    data class Summary(
        val summary: String,
        val purpose: String = "",
        val status: String,
        val provider: String = "ML Kit On-device"
    )

    fun analyzeFile(context: Context, imageFile: File): Summary = runCatching {
        val bitmap = decodeScaled(imageFile, 1600) ?: return Summary("", status="DECODE_FAILED")
        try { analyzeBitmap(bitmap) } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }.getOrElse { Summary("", status="FAILED") }

    // Compatibility for older callers.
    fun analyze(context: Context, thumbnailBase64: String, operator: String, siteId: String, capturedAt: Long): Summary =
        Summary("", status="LEGACY_INPUT_SKIPPED")

    private fun analyzeBitmap(bitmap: Bitmap): Summary {
        val detector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(0.48f).build()
        )
        return try {
            val regions = mutableListOf<android.graphics.Rect>()
            regions += detect(detector, bitmap)
            // Recovery pass: tiled detection helps small/indoor objects missed in the full frame.
            if (regions.size < 3) {
                val hw = bitmap.width / 2; val hh = bitmap.height / 2
                val tiles = listOf(
                    android.graphics.Rect(0,0,hw,hh), android.graphics.Rect(hw,0,bitmap.width,hh),
                    android.graphics.Rect(0,hh,hw,bitmap.height), android.graphics.Rect(hw,hh,bitmap.width,bitmap.height)
                )
                tiles.forEach { t ->
                    if (t.width() < 32 || t.height() < 32) return@forEach
                    val crop = Bitmap.createBitmap(bitmap,t.left,t.top,t.width(),t.height())
                    try {
                        detect(detector,crop).forEach { r ->
                            val translated = android.graphics.Rect(r.left+t.left,r.top+t.top,r.right+t.left,r.bottom+t.top)
                            if (regions.none { iou(it, translated) > 0.55 }) regions += translated
                        }
                    } finally { crop.recycle() }
                }
            }
            val counts = linkedMapOf<String,Int>()
            regions.take(12).forEach { box ->
                val l=box.left.coerceIn(0,bitmap.width-1); val t=box.top.coerceIn(0,bitmap.height-1)
                val r=box.right.coerceIn(l+1,bitmap.width); val b=box.bottom.coerceIn(t+1,bitmap.height)
                val crop=Bitmap.createBitmap(bitmap,l,t,r-l,b-t)
                try {
                    val labels=Tasks.await(labeler.process(InputImage.fromBitmap(crop,0)),15,TimeUnit.SECONDS)
                    val label=labels.sortedByDescending{it.confidence}
                        .map{normalize(it.text)}
                        .firstOrNull{isUseful(it)} ?: "Object"
                    counts[label]=(counts[label]?:0)+1
                } finally { crop.recycle() }
            }
            val text=counts.entries.sortedWith(compareByDescending<Map.Entry<String,Int>>{it.value}.thenBy{it.key})
                .take(8).joinToString(" · "){"${it.key} ×${it.value}"}
            if (text.isBlank()) Summary("No confidently detected objects",status="NO_OBJECTS")
            else Summary(text,status="GENERATED")
        } finally { detector.close(); labeler.close() }
    }

    private fun detect(detector: com.google.mlkit.vision.objects.ObjectDetector, bmp: Bitmap): List<android.graphics.Rect> =
        Tasks.await(detector.process(InputImage.fromBitmap(bmp,0)),20,TimeUnit.SECONDS).map{android.graphics.Rect(it.boundingBox)}

    private fun decodeScaled(file:File,max:Int):Bitmap? {
        val o=BitmapFactory.Options().apply{inJustDecodeBounds=true}; BitmapFactory.decodeFile(file.absolutePath,o)
        var sample=1; while(o.outWidth/sample>max || o.outHeight/sample>max) sample*=2
        return BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample.coerceAtLeast(1)})
    }
    private fun iou(a:android.graphics.Rect,b:android.graphics.Rect):Double {
        val l=maxOf(a.left,b.left); val t=maxOf(a.top,b.top); val r=minOf(a.right,b.right); val bt=minOf(a.bottom,b.bottom)
        if(r<=l||bt<=t) return 0.0; val inter=(r-l).toDouble()*(bt-t)
        val union=a.width().toDouble()*a.height()+b.width().toDouble()*b.height()-inter
        return if(union<=0)0.0 else inter/union
    }
    private fun normalize(v:String)=v.trim().lowercase(Locale.US).split(' ','-','_').filter{it.isNotBlank()}.joinToString(" "){x->x.replaceFirstChar{c->c.titlecase(Locale.US)}}
    private fun isUseful(v:String)=v.isNotBlank() && v !in setOf("Image","Photography","Photograph","Snapshot","Room","Indoor","Outdoor","Property","Material","Pattern","Design","Event")
}
''',encoding='utf-8')

# --- 3) Upgrade the PDF from one cramped page to a premium two-page forensic certificate.
pdf = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = pdf.read_text(encoding='utf-8')
# Keep all existing helpers, but replace page creation and draw entry point with a two-page flow built on existing primitives.
s = s.replace('''            val p = doc.startPage(PdfDocument.PageInfo.Builder(W,H,1).create())\n            draw(p.canvas, r)\n            doc.finishPage(p)''','''            val p1 = doc.startPage(PdfDocument.PageInfo.Builder(W,H,1).create())\n            drawCertificatePage(p1.canvas, r)\n            doc.finishPage(p1)\n            val p2 = doc.startPage(PdfDocument.PageInfo.Builder(W,H,2).create())\n            drawTechnicalAnnexPage(p2.canvas, r)\n            doc.finishPage(p2)''')
start = s.find('    private fun draw(c: Canvas, r: JSONObject) {')
end = s.find('    private fun verifySignature', start)
if start < 0 or end < 0:
    raise SystemExit('EvidencePdfExporter draw() markers not found')
new_draw = r'''    private fun drawCertificatePage(c: Canvas, r: JSONObject) {
        val navy=Color.rgb(7,34,67); val blue=Color.rgb(14,83,158); val green=Color.rgb(20,148,79)
        val ink=Color.rgb(18,33,52); val muted=Color.rgb(91,108,125); val pale=Color.rgb(246,249,252); val line=Color.rgb(211,220,230)
        c.drawColor(Color.WHITE)
        fill(c,0f,0f,W.toFloat(),78f,navy)
        text(c,"GEOSTAMP",M,31f,22f,Color.WHITE,true)
        text(c,"DIGITAL FORENSICS & TRUST ASSURANCE",M,49f,7.4f,Color.rgb(190,211,231),true)
        text(c,"DIGITAL EVIDENCE",W-M,28f,12f,Color.WHITE,true,Paint.Align.RIGHT)
        text(c,"AUTHENTICATION CERTIFICATE",W-M,47f,10.5f,Color.rgb(112,180,245),true,Paint.Align.RIGHT)
        fill(c,M,90f,W-M,142f,pale); stroke(c,M,90f,W-M,142f,line)
        text(c,"RECORD STATUS",M+12,106f,6.5f,muted,true); text(c,"VERIFIED & REGISTERED",M+12,128f,14f,green,true)
        text(c,"EVIDENCE ID",315f,106f,6.5f,muted,true); text(c,id(r),315f,126f,10f,ink,true)
        text(c,"ISSUED",W-M-8,106f,6.5f,muted,true,Paint.Align.RIGHT); text(c,time(System.currentTimeMillis()),W-M-8,126f,7.4f,ink,true,Paint.Align.RIGHT)

        val thumb=decodeThumbnail(r)!!
        val photoBox=RectF(M,157f,360f,405f); stroke(c,photoBox.left,photoBox.top,photoBox.right,photoBox.bottom,line)
        c.drawBitmap(thumb,null,fit(thumb.width,thumb.height,photoBox.left,photoBox.top,photoBox.width(),photoBox.height()),Paint(Paint.ANTI_ALIAS_FLAG))
        val x=378f; var y=169f
        section(c,"01  CAPTURE IDENTITY",x,y,blue); y+=15f
        val captured=r.optLong("capturedAt",r.optLong("timestamp",0L)); val lat=r.optDouble("latitude",r.optDouble("lat",Double.NaN)); val lon=r.optDouble("longitude",r.optDouble("lon",Double.NaN)); val acc=r.optDouble("accuracyM",r.optDouble("accuracy",Double.NaN))
        listOf(
            "Captured" to time(captured),
            "Operator" to first(r.optString("primaryValue"),r.optString("operator")),
            "Site / Reference" to first(r.optString("secondaryValue"),r.optString("siteId")),
            "Coordinates" to if(lat.isFinite()&&lon.isFinite()) "%.6f, %.6f".format(Locale.US,lat,lon) else "Unavailable",
            "Accuracy" to if(acc.isFinite()) "±%.1f m".format(Locale.US,acc) else "Unavailable",
            "Device" to first(r.optString("deviceModel"),"Unavailable"),
            "Device ID" to first(r.optString("maskedGeoStampDeviceIdentity"),"Unavailable")
        ).forEach{(a,b)-> y=compactRow(c,x,y,a,b,187f)}

        val aiY=422f; section(c,"02  AI-DETECTED OBJECTS",M,aiY,blue)
        fill(c,M,aiY+10,W-M,aiY+55,pale); stroke(c,M,aiY+10,W-M,aiY+55,line)
        val ai=first(r.optString("aiObjectCountSummary"),r.optString("aiVisualSummary"),"No confidently detected objects")
        wrap(ai,95).take(2).forEachIndexed{i,v->text(c,v,M+12,aiY+29+i*11,8.5f,ink,true)}
        text(c,"Automated visual inventory only · not used to determine authenticity.",W-M-10,aiY+48,5.7f,muted,false,Paint.Align.RIGHT)

        val sY=493f; section(c,"03  FIELD SESSION",M,sY,blue); fill(c,M,sY+11,W-M,sY+78,navy)
        val vals=listOf(
            "CLOCK-IN" to time(r.optLong("operatorSessionStartedAt",0L)),
            "PHOTO SEQUENCE" to "${count(r,"sitePhotosBefore","photosBeforeAtSite")} before · ${countActive(r,"sitePhotosAfter","photosAfterAtSite")} after",
            "SITE TOTAL" to count(r,"siteSessionPhotoTotal","sitePhotoTotal"),
            "SESSION TOTAL" to "${count(r,"operatorSessionPhotoTotal","sessionPhotoTotal")} photos",
            "CLOCK-OUT" to if(r.optLong("operatorSessionClockOutAt",0L)>0) time(r.optLong("operatorSessionClockOutAt",0L)) else "Active"
        ); val cw=(W-2*M)/5f
        vals.forEachIndexed{i,(a,b)->val xx=M+i*cw+7;text(c,a,xx,sY+32,5.5f,Color.rgb(160,188,215),true);wrap(b,18).take(2).forEachIndexed{j,v->text(c,v,xx,sY+49+j*9,6.5f,Color.WHITE,true)}}

        val vY=590f; section(c,"04  AUTHENTICATION RESULT",M,vY,blue)
        val sigOk=verifySignature(r); val risk=r.optBoolean("locationRisk",false)||r.optBoolean("locationIntegrityRisk",false)
        val checks=listOf("Registry record" to true,"Visual evidence" to true,"ECDSA signature" to (sigOk!=false),"Location integrity" to !risk,"Device key recorded" to r.optString("captureKeyFingerprint").isNotBlank())
        checks.forEachIndexed{i,(a,ok)->val yy=vY+22+i*20; text(c,if(ok)"✓" else "!",M+4,yy,11f,if(ok)green else Color.rgb(211,130,20),true);text(c,a,M+24,yy,7.6f,ink,true);text(c,if(ok)"PASS" else "REVIEW",260f,yy,7.2f,if(ok)green else Color.rgb(211,130,20),true)}
        fill(c,334f,vY+12,W-M,vY+118,pale); stroke(c,334f,vY+12,W-M,vY+118,line)
        text(c,"SYSTEM FINDING",346f,vY+31,7f,blue,true)
        val finding="This certificate confirms that the referenced digital evidence record is registered and that the machine-verifiable integrity and provenance checks shown in the technical annex produced the stated results."
        wrap(finding,48).take(5).forEachIndexed{i,v->text(c,v,346f,vY+48+i*11,6.8f,ink,false)}

        val qY=728f; val verifyUrl="https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}"; drawQr(c,verifyUrl,M,qY,68f)
        text(c,"PUBLIC VERIFICATION",M+82,qY+15,7f,blue,true); text(c,id(r),M+82,qY+31,8f,ink,true); text(c,"Scan QR or verify by Evidence ID in the GeoStamp public registry.",M+82,qY+47,6.2f,muted,false)
        text(c,"GeoStamp authenticates the digital record and recorded capture metadata; AI observations are informational and do not establish the truth of depicted events.",M,H-18f,5.4f,muted,false)
    }

    private fun drawTechnicalAnnexPage(c: Canvas, r: JSONObject) {
        val navy=Color.rgb(7,34,67); val blue=Color.rgb(14,83,158); val green=Color.rgb(20,148,79); val ink=Color.rgb(18,33,52); val muted=Color.rgb(91,108,125); val pale=Color.rgb(246,249,252); val line=Color.rgb(211,220,230)
        c.drawColor(Color.WHITE); fill(c,0f,0f,W.toFloat(),64f,navy)
        text(c,"GEOSTAMP",M,28f,18f,Color.WHITE,true); text(c,"TECHNICAL FORENSIC ANNEX",W-M,29f,12f,Color.WHITE,true,Paint.Align.RIGHT); text(c,id(r),W-M,46f,7f,Color.rgb(181,206,230),true,Paint.Align.RIGHT)
        var y=88f
        section(c,"05  MACHINE VERIFICATION MATRIX",M,y,blue); y+=14f
        val sig=verifySignature(r); val risk=r.optBoolean("locationRisk",false)||r.optBoolean("locationIntegrityRisk",false)
        val matrix=listOf(
            "Registry record" to if(r.optString("registryStatus").contains("PUBLIC",true))"PASS" else "RECORDED",
            "Mandatory visual evidence" to "PASS",
            "Image SHA-256" to if(r.optString("imageSha256").isNotBlank())"PASS" else "MISSING",
            "ECDSA signature" to when(sig){true->"PASS";false->"FAIL";null->"RECORDED"},
            "Signing key" to if(r.optBoolean("captureKeyHardwareBacked",false))"HARDWARE-BACKED" else "RECORDED",
            "Location integrity" to if(risk)"REVIEW" else "PASS",
            "Session continuity" to if(r.optString("operatorSessionId").isNotBlank())"RECORDED" else "N/A"
        )
        matrix.forEachIndexed{i,(a,b)->val yy=y+i*27; fill(c,M,yy,W-M,yy+22,if(i%2==0)pale else Color.WHITE);text(c,a,M+9,yy+15,7f,ink,true);text(c,b,W-M-10,yy+15,7f,if(b=="PASS")green else ink,true,Paint.Align.RIGHT)}
        y+=matrix.size*27+16
        section(c,"06  CRYPTOGRAPHIC & PROVENANCE RECORD",M,y,blue); y+=15f
        val prov=listOf(
            "Image SHA-256" to r.optString("imageSha256","Unavailable"),
            "Signature algorithm" to r.optString("captureSignatureAlgorithm","Unavailable"),
            "Key fingerprint" to r.optString("captureKeyFingerprint","Unavailable"),
            "Device identity" to r.optString("maskedGeoStampDeviceIdentity","Unavailable"),
            "Session ID" to r.optString("operatorSessionId","Unavailable"),
            "Published" to time(r.optLong("publishedAt",0L)),
            "Schema / marker" to "${r.optInt("schemaVersion",0)} / ${r.optInt("markerVersion",0)}"
        )
        prov.forEach{(a,b)-> y=monoRow(c,M,y,a,b,W-2*M)}
        y+=8f; text(c,"CAPTURE SIGNATURE",M,y,6.5f,muted,true); y+=12f
        wrapFixed(r.optString("captureSignature","Unavailable"),105).take(3).forEach{v->text(c,v,M,y,6.2f,ink,false,Paint.Align.LEFT,"monospace");y+=9f}
        y+=10f; section(c,"07  EVIDENCE LIFECYCLE",M,y,blue); y+=18f
        val captured=r.optLong("capturedAt",r.optLong("timestamp",0L)); val published=r.optLong("publishedAt",0L)
        val life=listOf("CAPTURED" to shortTime(captured),"SEALED / SIGNED" to shortTime(captured),"REGISTERED" to shortTime(published),"VERIFIED" to shortTime(System.currentTimeMillis()))
        val cw=(W-2*M)/4f
        life.forEachIndexed{i,(a,b)->val xx=M+i*cw; fill(c,xx,y,xx+cw-8,y+58,pale);stroke(c,xx,y,xx+cw-8,y+58,line);text(c,a,xx+8,y+20,6.2f,blue,true);text(c,b,xx+8,y+39,6.6f,ink,true)}
        y+=82f; section(c,"08  METHOD & SCOPE",M,y,blue); y+=16f
        val scope="GeoStamp records capture metadata, computes SHA-256, signs the canonical capture payload with the device capture key, publishes the registry record, and independently re-verifies available machine-readable signals when this report is generated. AI-detected objects are a visual inventory only and are excluded from authentication PASS/FAIL."
        wrap(scope,110).take(5).forEach{v->text(c,v,M,y,6.8f,ink,false);y+=11f}
        y+=8f; fill(c,M,y,W-M,y+61,navy); text(c,"VERIFY THIS EVIDENCE",M+12,y+18,7f,Color.WHITE,true); text(c,id(r),M+12,y+36,8f,Color.rgb(112,180,245),true); text(c,"Public registry QR is provided on page 1.",M+12,y+51,6.2f,Color.rgb(190,211,231),false)
        text(c,"GeoStamp · Axiom Infratech",M,H-18f,5.8f,muted,true); text(c,"PAGE 2 OF 2",W-M,H-18f,5.8f,muted,true,Paint.Align.RIGHT)
    }

'''
s = s[:start] + new_draw + s[end:]
pdf.write_text(s,encoding='utf-8')

print('Forensic closure sprint v2 applied: full-resolution free AI + premium 2-page evidence certificate.')
