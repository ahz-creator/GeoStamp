from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'

out.write_text(r'''package com.axiominfratech.geostamp.verification

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
import kotlin.math.*

object EvidencePdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 14f

    private val NAVY = Color.rgb(5, 33, 86)
    private val NAVY2 = Color.rgb(8, 45, 104)
    private val TEXT = Color.rgb(22, 32, 44)
    private val MUTED = Color.rgb(93, 108, 124)
    private val LINE = Color.rgb(207, 216, 225)
    private val HEAD = Color.rgb(227, 233, 238)
    private val PALE = Color.rgb(247, 249, 251)
    private val GREEN = Color.rgb(20, 145, 67)
    private val CYAN = Color.rgb(27, 126, 190)
    private val PURPLE = Color.rgb(112, 71, 207)

    fun exportAndShare(context: Context, record: JSONObject): Result<File> = runCatching {
        require(decodeThumbnail(record) != null) { "Mandatory evidence thumbnail is unavailable." }
        val id = id(record)
        val file = File(File(context.cacheDir, "shared_reports").also { it.mkdirs() }, "GeoStamp-$id.pdf")
        createPdf(file, record)
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

    private fun createPdf(file: File, r: JSONObject) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
            draw(page.canvas, r)
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    private fun draw(c: Canvas, r: JSONObject) {
        c.drawColor(Color.WHITE)
        header(c, r)
        var y = 62f
        y = sectionIdentity(c, r, y)
        y = sectionDescription(c, r, y)
        y = sectionExhibit(c, r, y)
        y = sectionControlMatrix(c, r, y)
        y = sectionCrypto(c, r, y)
        y = sectionLifecycle(c, r, y)
        y = sectionInventory(c, r, y)
        sectionScope(c, r, y)
        footer(c, r)
    }

    private fun header(c: Canvas, r: JSONObject) {
        text(c, "GEOSTAMP", 18f, 29f, 10.5f, NAVY, true)
        text(c, "BY AXIOM INFRATECH", 18f, 41f, 4.7f, NAVY, true)
        text(c, "DIGITAL EVIDENCE", W/2f, 21f, 16.5f, NAVY, true, Paint.Align.CENTER)
        text(c, "FORENSIC CERTIFICATE", W/2f, 38f, 16.5f, NAVY, true, Paint.Align.CENTER)
        text(c, "AUTHENTICATED FIELD RECORD • CRYPTOGRAPHICALLY SEALED & REGISTERED", W/2f, 52f, 5.3f, TEXT, true, Paint.Align.CENTER)
        val pass = overallPass(r)
        round(c, W-112f, 10f, W-18f, 48f, 5f, if (pass) Color.rgb(242,250,244) else Color.rgb(255,247,231), if(pass) GREEN else Color.rgb(192,126,15))
        text(c, if(pass) "VERIFIED" else "REVIEW", W-65f, 26f, 8.4f, if(pass) GREEN else Color.rgb(192,126,15), true, Paint.Align.CENTER)
        text(c, "MACHINE-CHECKED", W-65f, 36f, 4.6f, TEXT, true, Paint.Align.CENTER)
        text(c, "RECORD", W-65f, 44f, 4.6f, TEXT, true, Paint.Align.CENTER)
    }

    private fun sectionIdentity(c: Canvas, r: JSONObject, top: Float): Float {
        val h = 69f; sectionBar(c, top, "01  CERTIFICATE IDENTITY • REGISTRY • CLASSIFICATION")
        val y = top + 14f
        val cols = floatArrayOf(M, 158f, 309f, 448f, W-M)
        val captured = capturedAt(r)
        val published = publishedAt(r)
        cell(c, cols[0],y,cols[1]-cols[0],26f,"EVIDENCE ID",id(r),true)
        cell(c, cols[1],y,cols[2]-cols[1],26f,"CLASSIFICATION","DIGITAL FIELD EVIDENCE",true)
        cell(c, cols[2],y,cols[3]-cols[2],26f,"OPERATOR / SITE","${operator(r)} / ${site(r)}",true)
        cell(c, cols[3],y,cols[4]-cols[3],26f,"REGISTRY STATUS",registryLabel(r),true, if(registryPass(r)) GREEN else TEXT)
        val y2=y+26f
        cell(c, cols[0],y2,cols[1]-cols[0],29f,"CAPTURED",time(captured),true)
        cell(c, cols[1],y2,cols[2]-cols[1],29f,"REGISTERED",time(published),true)
        cell(c, cols[2],y2,cols[3]-cols[2],29f,"CERTIFICATE ISSUED",time(System.currentTimeMillis()),true)
        cell(c, cols[3],y2,cols[4]-cols[3],29f,"ISSUER","Axiom Infratech",true)
        return top+h
    }

    private fun sectionDescription(c: Canvas, r: JSONObject, top: Float): Float {
        val h=48f; sectionBar(c,top,"02  EVIDENCE DESCRIPTION • CAPTURE PROVENANCE • FIELD CONTEXT")
        val y=top+14f
        cell(c,M,y,280f,34f,"EVIDENCE DESCRIPTION","Registered field photographic evidence captured through GeoStamp and associated with the recorded site/reference identifier.",false)
        cell(c,M+280f,y,W-2*M-280f,34f,"CAPTURE PURPOSE / CONTEXT","This certificate records digital evidence identity, capture provenance, location data, integrity controls and registry state available to GeoStamp.",false)
        return top+h
    }

    private fun sectionExhibit(c: Canvas, r: JSONObject, top: Float): Float {
        val h=176f; sectionBar(c,top,"03  REGISTERED EXHIBIT • LOCATION • DEVICE • SESSION")
        val y=top+15f
        val photoX=M; val photoW=143f; val mapX=photoX+photoW+5f; val mapW=218f; val dataX=mapX+mapW+5f; val dataW=W-M-dataX
        stroke(c,photoX,y,photoX+photoW,y+151f,LINE)
        text(c,"REGISTERED SOURCE VISUAL",photoX+6f,y+12f,5.5f,NAVY,true)
        val bmp=decodeThumbnail(r)!!
        val dest=fit(bmp.width,bmp.height,photoX+6f,y+18f,photoW-12f,128f)
        c.drawBitmap(bmp,null,dest,Paint(Paint.ANTI_ALIAS_FLAG))
        text(c,"Visual evidence present",photoX+6f,y+148f,4.6f,MUTED,false)

        stroke(c,mapX,y,mapX+mapW,y+151f,LINE)
        text(c,"LOCATION MAP  (SITE / CLOCK-IN vs PHOTO CAPTURE)",mapX+6f,y+12f,5.4f,NAVY,true)
        drawMap(c,r,mapX+6f,y+18f,mapW-12f,126f)

        val rows=listOf(
            "CAPTURE GPS" to gps(r),
            "ACCURACY" to accuracy(r),
            "CLOCK-IN (SITE)" to time(r.optLong("operatorSessionStartedAt",0L)),
            "DEVICE" to device(r),
            "DEVICE IDENTITY" to first(r.optString("maskedGeoStampDeviceIdentity"),"Unavailable"),
            "SESSION EVIDENCE" to sessionPhotos(r),
            "SESSION ID" to first(r.optString("operatorSessionId"),"Unavailable"),
            "SCHEMA / MARKER" to "${r.optInt("schemaVersion",0)} / ${r.optInt("markerVersion",0)}",
            "CAPTURE KEY" to if(r.optBoolean("captureKeyHardwareBacked",false)) "Hardware-backed • YES" else "Recorded"
        )
        var ry=y
        rows.forEach { (a,b)-> tableRow(c,dataX,ry,dataW,16.8f,a,b); ry+=16.8f }
        return top+h
    }

    private fun sectionControlMatrix(c: Canvas, r: JSONObject, top: Float): Float {
        val h=109f; sectionBar(c,top,"04  FORENSIC CONTROL MATRIX • VERIFICATION OUTCOME")
        val y=top+14f
        val x=floatArrayOf(M,112f,290f,478f,W-M)
        tableHeader(c,x,y,16f,listOf("CONTROL","VERIFICATION TEST","RECORDED RESULT","STATE"))
        val sig=verifySignature(r)
        val risk=r.optBoolean("locationIntegrityRisk",false)||r.optBoolean("locationRisk",false)
        val rows=listOf(
            listOf("Registry","Registry record available",if(registryPass(r))"Public record confirmed" else "Registry state recorded",if(registryPass(r))"PASS" else "RECORDED"),
            listOf("Integrity","SHA-256 fingerprint comparison",if(r.optString("imageSha256").isNotBlank())"SHA-256 recorded" else "Unavailable",if(r.optString("imageSha256").isNotBlank())"PASS" else "REVIEW"),
            listOf("Signature","Digital capture signature",when(sig){true->"ECDSA verified";false->"Signature mismatch";null->"ECDSA recorded"},when(sig){true->"PASS";false->"FAIL";null->"RECORDED"}),
            listOf("Key protection","Capture signing key status",if(r.optBoolean("captureKeyHardwareBacked",false))"Hardware-backed" else "Recorded",if(r.optBoolean("captureKeyHardwareBacked",false))"YES" else "RECORDED"),
            listOf("Visual","Registered evidence presence","Thumbnail present","PRESENT"),
            listOf("Session","Capture-chain continuity",if(r.optString("operatorSessionId").isNotBlank())"Session chain recorded" else "Session not available",if(r.optString("operatorSessionId").isNotBlank())"RECORDED" else "N/A")
        )
        var yy=y+16f
        rows.forEach{ row-> tableData(c,x,yy,13.1f,row); yy+=13.1f }
        return top+h
    }

    private fun sectionCrypto(c: Canvas, r: JSONObject, top: Float): Float {
        val h=72f; sectionBar(c,top,"05  CRYPTOGRAPHIC IDENTITY • HASH • SIGNATURE • KEY FINGERPRINT")
        val y=top+14f
        val x=floatArrayOf(M,255f,390f,W-M)
        cell(c,x[0],y,x[1]-x[0],31f,"IMAGE SHA-256 FINGERPRINT",first(r.optString("imageSha256"),"Unavailable"),false,true)
        cell(c,x[1],y,x[2]-x[1],31f,"SIGNATURE ALGORITHM",first(r.optString("captureSignatureAlgorithm"),"Unavailable"),true)
        cell(c,x[2],y,x[3]-x[2],31f,"KEY FINGERPRINT",first(r.optString("captureKeyFingerprint"),"Unavailable"),false,true)
        val y2=y+31f
        cell(c,x[0],y2,190f,27f,"HASH PURPOSE","Digital image integrity reference",false)
        cell(c,x[0]+190f,y2,190f,27f,"SIGNATURE PURPOSE","Capture authenticity record",false)
        cell(c,x[0]+380f,y2,x[3]-(x[0]+380f),27f,"KEY STATUS",if(r.optBoolean("captureKeyHardwareBacked",false))"Hardware-backed • YES" else "Recorded",true)
        return top+h
    }

    private fun sectionLifecycle(c: Canvas, r: JSONObject, top: Float): Float {
        val h=70f; sectionBar(c,top,"06  EVIDENCE LIFECYCLE • TEMPORAL RECORD • CHAIN OF VERIFICATION")
        val y=top+15f
        val captured=capturedAt(r); val published=publishedAt(r)
        val events=listOf(
            "01 • CLOCK-IN" to timeShort(r.optLong("operatorSessionStartedAt",0L)),
            "02 • CAPTURE" to timeShort(captured),
            "03 • SIGN" to "AT CAPTURE",
            "04 • REGISTER" to timeShort(published),
            "05 • CERTIFICATE" to timeShort(System.currentTimeMillis())
        )
        val cw=(W-2*M)/5f
        events.forEachIndexed{i,(a,b)->
            val xx=M+i*cw
            if(i>0) text(c,"→",xx-8f,y+27f,14f,NAVY,false,Paint.Align.CENTER)
            text(c,a,xx+8f,y+13f,5.1f,NAVY,true)
            text(c,b,xx+8f,y+30f,7f,TEXT,true)
            text(c,if(i==2)"" else dateShort(if(i==0)r.optLong("operatorSessionStartedAt",0L) else if(i==1)captured else if(i==3)published else System.currentTimeMillis()),xx+8f,y+41f,4.8f,TEXT,true)
        }
        text(c,"🔒  CAPTURE → HASH → SIGN → LOCATION → REGISTER → VERIFY",W/2f,y+56f,6.4f,NAVY,true,Paint.Align.CENTER)
        return top+h
    }

    private fun sectionInventory(c: Canvas, r: JSONObject, top: Float): Float {
        val total=(r.optInt("operatorSessionPhotoTotal",r.optInt("sessionPhotoTotal",1))).coerceAtLeast(1).coerceAtMost(6)
        val h=35f+total*13.5f; sectionBar(c,top,"07  EVIDENCE INVENTORY • SESSION CONTENT")
        val y=top+14f
        val x=floatArrayOf(M,92f,255f,470f,W-M)
        tableHeader(c,x,y,15f,listOf("EXHIBIT","TYPE","SESSION RELATIONSHIP","STATUS"))
        var yy=y+15f
        for(i in 1..total){
            val current=i==1
            val row=listOf("EX-%02d".format(i),if(current)"Current evidence photo" else "Photo %02d".format(i),if(current)"Registered session evidence" else "Session evidence • $i of $total",if(current)"PRESENT" else "RECORDED")
            tableData(c,x,yy,13f,row); yy+=13f
        }
        return top+h
    }

    private fun sectionScope(c: Canvas, r: JSONObject, top: Float) {
        sectionBar(c,top,"08  AUTHENTICATION SCOPE • HANDLING NOTE • PUBLIC VERIFICATION")
        val y=top+14f; val h=76f
        cell(c,M,y,205f,h,"AUTHENTICATION SCOPE","GeoStamp authenticates the digital record and recorded capture provenance available to the system, including registered identity, cryptographic integrity, capture signature, location data and session continuity.",false)
        cell(c,M+205f,y,205f,h,"EVIDENCE HANDLING NOTE","This certificate reflects the evidence state and machine-verifiable controls recorded by GeoStamp at registration. Source values shown here are derived from the registered evidence record.",false)
        stroke(c,M+410f,y,W-M,y+h,LINE)
        text(c,"PUBLIC VERIFICATION",M+420f,y+12f,5.4f,NAVY,true)
        text(c,"SCAN QR",M+420f,y+27f,6f,TEXT,true)
        text(c,id(r),M+420f,y+44f,5.7f,NAVY,true)
        val qrValue="https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}"
        drawQr(c,qrValue,W-M-58f,y+7f,50f)
        round(c,M,y+h+5f,W-M-220f,y+h+37f,3f,Color.rgb(255,251,240),Color.rgb(229,204,139))
        text(c,"INTERPRETATION BOUNDARY",M+8f,y+h+17f,5.2f,NAVY,true)
        text(c,"This automated report does not independently determine whether the photographed scene is truthful, complete, lawful or materially significant.",M+8f,y+h+29f,4.9f,TEXT,false)
        stroke(c,W-M-212f,y+h+5f,W-M,y+h+37f,LINE)
        text(c,"ISSUER / AUTHORITY",W-M-202f,y+h+17f,5f,MUTED,true)
        text(c,"AXIOM INFRATECH",W-M-202f,y+h+29f,5.8f,TEXT,true)
    }

    private fun footer(c: Canvas, r: JSONObject) {
        val y=H-15f
        stroke(c,18f,y-7f,W-18f,y-7f,NAVY)
        text(c,"GEOSTAMP • DIGITAL EVIDENCE AUTHENTICATION • AXIOM INFRATECH",18f,y+4f,4.6f,MUTED,true)
        text(c,id(r),W/2f,y+4f,4.6f,MUTED,true,Paint.Align.CENTER)
        text(c,"PAGE 1 / 1",W-18f,y+4f,4.6f,MUTED,true,Paint.Align.RIGHT)
    }

    private fun drawMap(c:Canvas,r:JSONObject,x:Float,y:Float,w:Float,h:Float){
        fill(c,x,y,x+w,y+h,Color.rgb(244,247,249)); stroke(c,x,y,x+w,y+h,LINE)
        // deterministic schematic road network; never invents geographic labels.
        val roads=listOf(
            floatArrayOf(.02f,.25f,.24f,.34f,.49f,.31f,.72f,.48f,.98f,.43f),
            floatArrayOf(.08f,.82f,.28f,.66f,.52f,.69f,.77f,.56f,.94f,.73f),
            floatArrayOf(.15f,.05f,.28f,.29f,.27f,.72f,.42f,.96f),
            floatArrayOf(.62f,.04f,.60f,.27f,.69f,.48f,.78f,.94f),
            floatArrayOf(.02f,.55f,.28f,.51f,.50f,.55f,.77f,.57f,.98f,.62f)
        )
        roads.forEach{ pts->
            val p=Path(); p.moveTo(x+pts[0]*w,y+pts[1]*h); var i=2
            while(i<pts.size){p.lineTo(x+pts[i]*w,y+pts[i+1]*h);i+=2}
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.rgb(205,214,222);strokeWidth=2f}
            c.drawPath(p,paint)
        }
        val capX=x+w*.63f; val capY=y+h*.46f
        val distance=r.optDouble("siteDistanceM",r.optDouble("distanceM",Double.NaN))
        val hasSite=distance.isFinite() && distance>=0
        val siteX=if(hasSite) x+w*.34f else capX
        val siteY=if(hasSite) y+h*.56f else capY
        if(hasSite){
            val dash=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=Color.rgb(95,110,125);strokeWidth=1.1f;pathEffect=DashPathEffect(floatArrayOf(4f,3f),0f)}
            c.drawLine(siteX,siteY,capX,capY,dash)
            marker(c,siteX,siteY,PURPLE)
            text(c,"SITE / CLOCK-IN",siteX,siteY-10f,4.5f,PURPLE,true,Paint.Align.CENTER)
        }
        marker(c,capX,capY,CYAN)
        text(c,"PHOTO CAPTURE",capX,capY+15f,4.5f,CYAN,true,Paint.Align.CENTER)
        val acc=r.optDouble("accuracyM",r.optDouble("accuracy",Double.NaN))
        if(acc.isFinite()){
            val radius=(min(38.0,max(10.0,acc/5.0))).toFloat()
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;color=CYAN;strokeWidth=.8f}
            c.drawCircle(capX,capY,radius,p)
        }
        fill(c,x+5f,y+h-30f,x+w-5f,y+h-5f,Color.argb(225,255,255,255))
        text(c,"CAPTURE GPS  ${gps(r)}",x+9f,y+h-19f,4.6f,TEXT,true)
        text(c,"ACCURACY  ${accuracy(r)}",x+9f,y+h-9f,4.4f,MUTED,true)
        if(hasSite) text(c,"DISTANCE  ${"%.0f".format(Locale.US,distance)} m (recorded)",x+w-9f,y+h-9f,4.4f,NAVY,true,Paint.Align.RIGHT)
        else text(c,"SITE DISTANCE NOT RECORDED",x+w-9f,y+h-9f,4.2f,MUTED,true,Paint.Align.RIGHT)
    }

    private fun marker(c:Canvas,x:Float,y:Float,color:Int){
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.FILL}; c.drawCircle(x,y,6f,p)
        p.color=Color.WHITE; c.drawCircle(x,y,2.2f,p)
    }

    private fun cell(c:Canvas,x:Float,y:Float,w:Float,h:Float,label:String,value:String,boldValue:Boolean,valueColor:Int=TEXT,mono:Boolean=false){
        stroke(c,x,y,x+w,y+h,LINE); fill(c,x,y,x+w,y+11f,HEAD)
        text(c,label,x+6f,y+8f,4.7f,MUTED,true)
        val lines=wrapText(value,if(mono)44 else max(18,(w/5.8f).toInt()))
        lines.take(if(h>30)3 else 2).forEachIndexed{i,s-> text(c,s,x+6f,y+20f+i*7.2f,if(mono)4.8f else 5.7f,valueColor,boldValue,Paint.Align.LEFT,if(mono)"monospace" else null)}
    }

    private fun tableRow(c:Canvas,x:Float,y:Float,w:Float,h:Float,a:String,b:String){
        stroke(c,x,y,x+w,y+h,LINE); fill(c,x,y,x+75f,y+h,if(((y/h).toInt()%2)==0)HEAD else PALE)
        text(c,a,x+6f,y+11f,4.7f,MUTED,true); text(c,ellipsize(b,28),x+82f,y+11f,5.6f,TEXT,true)
    }

    private fun tableHeader(c:Canvas,x:FloatArray,y:Float,h:Float,labels:List<String>){
        for(i in labels.indices){fill(c,x[i],y,x[i+1],y+h,HEAD);stroke(c,x[i],y,x[i+1],y+h,LINE);text(c,labels[i],x[i]+6f,y+10.5f,4.7f,MUTED,true)}
    }

    private fun tableData(c:Canvas,x:FloatArray,y:Float,h:Float,row:List<String>){
        for(i in row.indices){stroke(c,x[i],y,x[i+1],y+h,LINE); val col=if(i==row.lastIndex && row[i] in setOf("PASS","PRESENT","YES","RECORDED")) GREEN else TEXT; text(c,ellipsize(row[i],if(i==1||i==2)34 else 18),x[i]+6f,y+9f,5.2f,col,i==0||i==row.lastIndex)}
    }

    private fun sectionBar(c:Canvas,y:Float,title:String){fill(c,M,y,W-M,y+13f,NAVY);text(c,title,M+6f,y+9.5f,6.2f,Color.WHITE,true)}
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
    private fun dateShort(v:Long)=if(v>0)SimpleDateFormat("dd MMM yyyy",Locale.getDefault()).format(Date(v)) else ""
    private fun ellipsize(s:String,n:Int)=if(s.length<=n)s else s.take(n-1)+"…"
    private fun wrapText(s:String,n:Int)=s.chunked(max(8,n))

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

    private fun drawQr(c:Canvas,value:String,x:Float,y:Float,size:Float){runCatching{val m=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,160,160);val b=Bitmap.createBitmap(m.width,m.height,Bitmap.Config.ARGB_8888);for(xx in 0 until m.width)for(yy in 0 until m.height)b.setPixel(xx,yy,if(m[xx,yy])Color.BLACK else Color.WHITE);c.drawBitmap(b,null,RectF(x,y,x+size,y+size),Paint(Paint.ANTI_ALIAS_FLAG));b.recycle()}}
    private fun fit(iw:Int,ih:Int,x:Float,y:Float,w:Float,h:Float):RectF{val s=min(w/iw,h/ih);val nw=iw*s;val nh=ih*s;return RectF(x+(w-nw)/2,y+(h-nh)/2,x+(w+nw)/2,y+(h+nh)/2)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false,align:Paint.Align=Paint.Align.LEFT,font:String?=null){val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;textSize=size;textAlign=align;typeface=if(font=="monospace")Typeface.MONOSPACE else if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT};c.drawText(s,x,y,p)}
    private fun fill(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int){c.drawRect(l,t,r,b,Paint().apply{this.color=color;style=Paint.Style.FILL})}
    private fun stroke(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int){c.drawRect(l,t,r,b,Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.STROKE;strokeWidth=.6f})}
    private fun round(c:Canvas,l:Float,t:Float,r:Float,b:Float,rad:Float,fill:Int,stroke:Int){c.drawRoundRect(RectF(l,t,r,b),rad,rad,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=fill;style=Paint.Style.FILL});c.drawRoundRect(RectF(l,t,r,b),rad,rad,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=stroke;style=Paint.Style.STROKE;strokeWidth=.7f})}
}
''', encoding='utf-8')

# Remove AI dependencies/capture execution if still present. Keep this patch idempotent.
gradle = root / 'app/build.gradle'
gs = gradle.read_text(encoding='utf-8')
gs = gs.replace("    implementation 'com.google.mlkit:object-detection:17.0.2'\n", "")
gs = gs.replace("    implementation 'com.google.mlkit:image-labeling:17.0.9'\n", "")
gradle.write_text(gs, encoding='utf-8')

vm = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt'
vs = vm.read_text(encoding='utf-8')
import re
vs = re.sub(r'''\s*val aiVisual = withContext\(Dispatchers\.IO\) \{.*?\n\s*\}\n''', '\n', vs, count=1, flags=re.S)
for line in [
    '                        put("aiVisualSummary", aiVisual.summary)\n',
    '                        put("aiObjectCountSummary", aiVisual.summary)\n',
    '                        put("aiVisualSummaryStatus", aiVisual.status)\n',
    '                        put("aiVisualSummaryProvider", aiVisual.provider)\n',
]:
    vs = vs.replace(line, '')
vm.write_text(vs, encoding='utf-8')

print('Deployed production-style GeoStamp forensic certificate replica; removed AI report/capture remnants.')
