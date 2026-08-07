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
