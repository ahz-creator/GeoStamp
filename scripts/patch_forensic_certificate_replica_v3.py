from pathlib import Path
import runpy, re

root = Path(__file__).resolve().parents[1]
base_patch = root / 'scripts' / 'patch_forensic_certificate_replica_v2.py'
if base_patch.exists():
    runpy.run_path(str(base_patch), run_name='__main__')

p = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = p.read_text(encoding='utf-8')

# 1) Keep the locked-reference compact page margin.
s = s.replace('private const val M = 28f', 'private const val M = 14f')

# 2) Make createPdf/draw context-aware so the exact installed GeoStamp app icon is used.
s = s.replace('createPdf(file, record)', 'createPdf(context, file, record)')
s = s.replace('private fun createPdf(file: File, r: JSONObject) {', 'private fun createPdf(context: Context, file: File, r: JSONObject) {')
s = s.replace('draw(page.canvas, r)', 'draw(page.canvas, r, context)')
s = s.replace('draw(p.canvas, r)', 'draw(p.canvas, r, context)')
s = s.replace('private fun draw(c: Canvas, r: JSONObject) {', 'private fun draw(c: Canvas, r: JSONObject, context: Context) {')
s = s.replace('header(c, r)', 'header(c, r, context)')

# 3) Replace header with locked master geometry and real installed trademark icon.
header_pat = re.compile(r'    private fun header\(c: Canvas, r: JSONObject(?:, context: Context)?\) \{.*?\n    \}\n\n    private fun sectionIdentity', re.S)
header_new = r'''    private fun header(c: Canvas, r: JSONObject, context: Context) {
        val icon = runCatching { drawableToBitmap(context.packageManager.getApplicationIcon(context.packageName), 30, 30) }.getOrNull()
        icon?.let { c.drawBitmap(it, null, RectF(18f, 11f, 48f, 41f), Paint(Paint.ANTI_ALIAS_FLAG)) }
        text(c, "GeoStamp", 54f, 30f, 14.2f, NAVY, true)
        text(c, "BY AXIOM INFRATECH", 54f, 43f, 5.1f, NAVY, true)

        text(c, "DIGITAL EVIDENCE", W/2f, 18f, 16.8f, NAVY, true, Paint.Align.CENTER)
        text(c, "FORENSIC CERTIFICATE", W/2f, 36f, 16.8f, NAVY, true, Paint.Align.CENTER)
        text(c, "AUTHENTICATED FIELD RECORD • CRYPTOGRAPHICALLY SEALED & REGISTERED", W/2f, 51f, 5.4f, TEXT, true, Paint.Align.CENTER)

        val pass = overallPass(r)
        round(c, W-110f, 8f, W-17f, 50f, 5f,
            if (pass) Color.rgb(242,250,244) else Color.rgb(255,247,231),
            if (pass) GREEN else Color.rgb(192,126,15))
        val badgeX = W-94f
        val badgeY = 15f
        val shield = Path().apply {
            moveTo(badgeX+10f,badgeY); lineTo(badgeX+20f,badgeY+4f); lineTo(badgeX+18f,badgeY+19f)
            lineTo(badgeX+10f,badgeY+27f); lineTo(badgeX+2f,badgeY+19f); lineTo(badgeX,badgeY+4f); close()
        }
        c.drawPath(shield, Paint(Paint.ANTI_ALIAS_FLAG).apply { color=if(pass) GREEN else Color.rgb(192,126,15); style=Paint.Style.FILL })
        text(c, "✓", badgeX+10f, badgeY+18f, 11f, Color.WHITE, true, Paint.Align.CENTER)
        text(c, if(pass) "VERIFIED" else "REVIEW", W-55f, 23f, 8.6f, if(pass) GREEN else Color.rgb(192,126,15), true, Paint.Align.CENTER)
        text(c, "MACHINE-CHECKED", W-55f, 35f, 4.6f, TEXT, true, Paint.Align.CENTER)
        text(c, "RECORD", W-55f, 43f, 4.6f, TEXT, true, Paint.Align.CENTER)
    }

    private fun sectionIdentity'''
s, n = header_pat.subn(header_new, s, count=1)
if n != 1:
    print('Warning: header replacement did not match exactly; continuing with geometry fixes.')

# 4) Increase description block height and use word-aware wrapping, eliminating clipped text.
s = s.replace('val h=48f; sectionBar(c,top,"02  EVIDENCE DESCRIPTION • CAPTURE PROVENANCE • FIELD CONTEXT")',
              'val h=58f; sectionBar(c,top,"02  EVIDENCE DESCRIPTION • CAPTURE PROVENANCE • FIELD CONTEXT")')
s = s.replace('cell(c,M,y,280f,34f,"EVIDENCE DESCRIPTION"', 'cell(c,M,y,280f,44f,"EVIDENCE DESCRIPTION"')
s = s.replace('cell(c,M+280f,y,W-2*M-280f,34f,"CAPTURE PURPOSE / CONTEXT"', 'cell(c,M+280f,y,W-2*M-280f,44f,"CAPTURE PURPOSE / CONTEXT"')

# 5) Give Section 03 the locked proportions and stronger visual area.
s = s.replace('val h=176f; sectionBar(c,top,"03  REGISTERED EXHIBIT • LOCATION • DEVICE • SESSION")',
              'val h=184f; sectionBar(c,top,"03  REGISTERED EXHIBIT • LOCATION • DEVICE • SESSION")')
s = s.replace('val photoX=M; val photoW=143f; val mapX=photoX+photoW+5f; val mapW=218f;',
              'val photoX=M; val photoW=139f; val mapX=photoX+photoW+5f; val mapW=222f;')
s = s.replace('y+151f', 'y+159f')
s = s.replace('photoW-12f,128f', 'photoW-12f,136f')
s = s.replace('y+148f', 'y+156f')
s = s.replace('mapW-12f,126f', 'mapW-12f,134f')

# 6) Larger readable table typography without changing locked column geometry.
s = s.replace('text(c,label,x+6f,y+8f,4.7f,MUTED,true)', 'text(c,label,x+6f,y+8f,5.1f,MUTED,true)')
s = s.replace('if(mono)4.8f else 5.7f', 'if(mono)5.0f else 6.0f')
s = s.replace('text(c,ellipsize(row[i],if(i==1||i==2)34 else 18),x[i]+6f,y+9f,5.2f',
              'text(c,ellipsize(row[i],if(i==1||i==2)34 else 18),x[i]+6f,y+9.2f,5.5f')
s = s.replace('text(c,labels[i],x[i]+6f,y+10.5f,4.7f', 'text(c,labels[i],x[i]+6f,y+10.5f,5.0f')

# 7) Lifecycle: reserve enough vertical room so the methodology line never collides with Section 07.
s = s.replace('val h=70f; sectionBar(c,top,"06  EVIDENCE LIFECYCLE • TEMPORAL RECORD • CHAIN OF VERIFICATION")',
              'val h=82f; sectionBar(c,top,"06  EVIDENCE LIFECYCLE • TEMPORAL RECORD • CHAIN OF VERIFICATION")')
s = s.replace('text(c,"🔒  CAPTURE → HASH → SIGN → LOCATION → REGISTER → VERIFY",W/2f,y+56f,6.4f',
              'text(c,"CAPTURE  →  HASH  →  SIGN  →  LOCATION  →  REGISTER  →  VERIFY",W/2f,y+61f,6.8f')

# 8) Section 08: increase block depth and restore fully visible final control row.
s = s.replace('val y=top+14f; val h=76f', 'val y=top+14f; val h=88f')
s = s.replace('y+h+37f', 'y+h+34f')

# 9) Move footer to the true page edge so it cannot cover authority/document-control/compliance blocks.
footer_pat = re.compile(r'    private fun footer\(c: Canvas, r: JSONObject\) \{.*?\n    \}\n\n    private fun drawMap', re.S)
footer_new = r'''    private fun footer(c: Canvas, r: JSONObject) {
        val top = H-13f
        fill(c, 0f, top, W.toFloat(), H.toFloat(), NAVY)
        text(c,"GEOSTAMP • DIGITAL EVIDENCE AUTHENTICATION • AXIOM INFRATECH",18f,top+9f,4.6f,Color.WHITE,true)
        text(c,id(r),W/2f,top+9f,4.6f,Color.WHITE,true,Paint.Align.CENTER)
        text(c,"PAGE 1 / 1",W-18f,top+9f,4.6f,Color.WHITE,true,Paint.Align.RIGHT)
    }

    private fun drawMap'''
s, n2 = footer_pat.subn(footer_new, s, count=1)
if n2 != 1:
    print('Warning: footer replacement did not match exactly.')

# 10) Word-aware wrapping instead of fixed chunking. This fixes broken words such as 'referenc/e'.
wrap_pat = re.compile(r'    private fun wrapText\(s:String,n:Int\)=s\.chunked\(max\(8,n\)\)')
wrap_new = r'''    private fun wrapText(s:String,n:Int):List<String>{
        val limit=max(8,n); val out=mutableListOf<String>(); var cur=""
        s.trim().split(Regex("\\s+")).filter{it.isNotBlank()}.forEach{ w ->
            val trial=if(cur.isBlank()) w else "$cur $w"
            if(trial.length>limit && cur.isNotBlank()){ out+=cur; cur=w } else cur=trial
        }
        if(cur.isNotBlank()) out+=cur
        return if(out.isEmpty()) listOf("Unavailable") else out
    }'''
s, _ = wrap_pat.subn(wrap_new, s, count=1)

# 11) Helper for exact installed app icon rendering.
insert_before = '    private fun drawQr(c:Canvas,value:String,x:Float,y:Float,size:Float)'
helper = '''    private fun drawableToBitmap(d: android.graphics.drawable.Drawable, w:Int, h:Int): Bitmap {
        if(d is android.graphics.drawable.BitmapDrawable && d.bitmap != null) return d.bitmap
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val cc=Canvas(b); d.setBounds(0,0,w,h); d.draw(cc); return b
    }\n\n'''
if 'private fun drawableToBitmap(' not in s and insert_before in s:
    s=s.replace(insert_before, helper+insert_before)

p.write_text(s, encoding='utf-8')
print('Replica V3 applied: exact app trademark header, word-safe wrapping, larger typography, locked section geometry, lifecycle clearance, expanded Section 08, and footer overlap fix.')
