from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = p.read_text(encoding='utf-8')

# Geometry closer to the locked reference: tighter margins, larger readable type,
# fuller bottom authority/control zone, real application trademark in header.
s = s.replace('private const val M = 14f', 'private const val M = 12f')
s = s.replace('createPdf(file, record)', 'createPdf(context, file, record)')
s = s.replace('private fun createPdf(file: File, r: JSONObject)', 'private fun createPdf(context: Context, file: File, r: JSONObject)')
s = s.replace('draw(page.canvas, r)', 'draw(context, page.canvas, r)')
s = s.replace('private fun draw(c: Canvas, r: JSONObject)', 'private fun draw(context: Context, c: Canvas, r: JSONObject)')
s = s.replace('header(c, r)', 'header(context, c, r)')
s = s.replace('private fun header(c: Canvas, r: JSONObject)', 'private fun header(context: Context, c: Canvas, r: JSONObject)')

old_header = '''        text(c, "GEOSTAMP", 18f, 29f, 10.5f, NAVY, true)\n        text(c, "BY AXIOM INFRATECH", 18f, 41f, 4.7f, NAVY, true)\n        text(c, "DIGITAL EVIDENCE", W/2f, 21f, 16.5f, NAVY, true, Paint.Align.CENTER)\n        text(c, "FORENSIC CERTIFICATE", W/2f, 38f, 16.5f, NAVY, true, Paint.Align.CENTER)\n        text(c, "AUTHENTICATED FIELD RECORD • CRYPTOGRAPHICALLY SEALED & REGISTERED", W/2f, 52f, 5.3f, TEXT, true, Paint.Align.CENTER)\n        val pass = overallPass(r)\n        round(c, W-112f, 10f, W-18f, 48f, 5f, if (pass) Color.rgb(242,250,244) else Color.rgb(255,247,231), if(pass) GREEN else Color.rgb(192,126,15))\n        text(c, if(pass) "VERIFIED" else "REVIEW", W-65f, 26f, 8.4f, if(pass) GREEN else Color.rgb(192,126,15), true, Paint.Align.CENTER)\n        text(c, "MACHINE-CHECKED", W-65f, 36f, 4.6f, TEXT, true, Paint.Align.CENTER)\n        text(c, "RECORD", W-65f, 44f, 4.6f, TEXT, true, Paint.Align.CENTER)'''
new_header = '''        // Use the installed GeoStamp application icon exactly as the brand mark.\n        runCatching {\n            val icon = BitmapFactory.decodeResource(context.resources, context.applicationInfo.icon)\n            if (icon != null) c.drawBitmap(icon, null, RectF(16f, 10f, 52f, 46f), Paint(Paint.ANTI_ALIAS_FLAG))\n        }\n        text(c, "GeoStamp", 58f, 29f, 13.2f, NAVY, true)\n        text(c, "BY AXIOM INFRATECH", 58f, 42f, 5.2f, NAVY, true)\n        text(c, "DIGITAL EVIDENCE", W/2f, 20f, 17.4f, NAVY, true, Paint.Align.CENTER)\n        text(c, "FORENSIC CERTIFICATE", W/2f, 38f, 17.4f, NAVY, true, Paint.Align.CENTER)\n        text(c, "AUTHENTICATED FIELD RECORD • CRYPTOGRAPHICALLY SEALED & REGISTERED", W/2f, 52f, 5.5f, TEXT, true, Paint.Align.CENTER)\n        val pass = overallPass(r)\n        round(c, W-113f, 8f, W-12f, 50f, 5f, if (pass) Color.rgb(242,250,244) else Color.rgb(255,247,231), if(pass) GREEN else Color.rgb(192,126,15))\n        // verification shield\n        val cx=W-96f; val cy=26f\n        val sp=Path().apply{moveTo(cx,cy-10f);lineTo(cx+9f,cy-6f);lineTo(cx+7f,cy+5f);lineTo(cx,cy+11f);lineTo(cx-7f,cy+5f);lineTo(cx-9f,cy-6f);close()}\n        c.drawPath(sp,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(pass) GREEN else Color.rgb(192,126,15);style=Paint.Style.FILL})\n        text(c,"✓",cx,cy+4f,9f,Color.WHITE,true,Paint.Align.CENTER)\n        text(c, if(pass) "VERIFIED" else "REVIEW", W-60f, 24f, 8.8f, if(pass) GREEN else Color.rgb(192,126,15), true, Paint.Align.CENTER)\n        text(c, "MACHINE-CHECKED", W-60f, 36f, 4.8f, TEXT, true, Paint.Align.CENTER)\n        text(c, "RECORD", W-60f, 44f, 4.8f, TEXT, true, Paint.Align.CENTER)'''
if old_header not in s:
    raise SystemExit('Header pattern not found. Run deploy_forensic_certificate_replica.py and compile hotfix first.')
s = s.replace(old_header, new_header)

# Increase readability while preserving locked table proportions.
s = s.replace('text(c,label,x+6f,y+8f,4.7f,MUTED,true)', 'text(c,label,x+6f,y+8.5f,5.1f,MUTED,true)')
s = s.replace('if(mono)4.8f else 5.7f', 'if(mono)5.05f else 6.05f')
s = s.replace('text(c,a,x+6f,y+11f,4.7f,MUTED,true)', 'text(c,a,x+6f,y+11.2f,5.0f,MUTED,true)')
s = s.replace('text(c,ellipsize(b,28),x+82f,y+11f,5.6f,TEXT,true)', 'text(c,ellipsize(b,28),x+82f,y+11.2f,5.9f,TEXT,true)')
s = s.replace('text(c,labels[i],x[i]+6f,y+10.5f,4.7f,MUTED,true)', 'text(c,labels[i],x[i]+6f,y+10.8f,5.0f,MUTED,true)')
s = s.replace('x[i]+6f,y+9f,5.2f,col', 'x[i]+6f,y+9.2f,5.5f,col')
s = s.replace('private fun sectionBar(c:Canvas,y:Float,title:String){fill(c,M,y,W-M,y+13f,NAVY);text(c,title,M+6f,y+9.5f,6.2f,Color.WHITE,true)}',
'''private fun sectionBar(c:Canvas,y:Float,title:String){\n        fill(c,M,y,W-M,y+14f,NAVY)\n        text(c,title,M+6f,y+10.3f,6.6f,Color.WHITE,true)\n    }''')

# Match locked reference visual proportions in the exhibit section.
s = s.replace('val h=176f; sectionBar(c,top,"03  REGISTERED EXHIBIT • LOCATION • DEVICE • SESSION")', 'val h=182f; sectionBar(c,top,"03  REGISTERED EXHIBIT • LOCATION • DEVICE • SESSION")')
s = s.replace('val y=top+15f\n        val photoX=M; val photoW=143f; val mapX=photoX+photoW+5f; val mapW=218f; val dataX=mapX+mapW+5f; val dataW=W-M-dataX',
'''val y=top+16f\n        val usable=W-2*M\n        val photoX=M; val photoW=142f\n        val mapX=photoX+photoW+5f; val mapW=217f\n        val dataX=mapX+mapW+5f; val dataW=W-M-dataX''')
s = s.replace('y+151f', 'y+157f')
s = s.replace('photoW-12f,128f', 'photoW-12f,134f')
s = s.replace('y+148f', 'y+154f')
s = s.replace('mapW-12f,126f', 'mapW-12f,132f')
s = s.replace('16.8f,a,b); ry+=16.8f', '17.45f,a,b); ry+=17.45f')

# Make map visually closer to the approved master: stronger blue accuracy circle, two markers when distance exists.
s = s.replace('strokeWidth=2f}', 'strokeWidth=1.35f}')
s = s.replace('color=CYAN;strokeWidth=.8f', 'color=Color.rgb(37,105,220);strokeWidth=1.15f')
s = s.replace('marker(c,siteX,siteY,PURPLE)', 'marker(c,siteX,siteY,Color.rgb(45,105,220))')
s = s.replace('marker(c,capX,capY,CYAN)', 'marker(c,capX,capY,Color.rgb(220,46,35))')
s = s.replace('text(c,"PHOTO CAPTURE",capX,capY+15f,4.5f,CYAN,true,Paint.Align.CENTER)', 'text(c,"PHOTO CAPTURE",capX,capY+15f,4.6f,Color.rgb(220,46,35),true,Paint.Align.CENTER)')

# Lifecycle: larger events/icons and no unsupported lock glyph.
s = s.replace('text(c,"🔒  CAPTURE → HASH → SIGN → LOCATION → REGISTER → VERIFY",W/2f,y+56f,6.4f,NAVY,true,Paint.Align.CENTER)',
'''text(c,"CAPTURE  →  HASH  →  SIGN  →  LOCATION  →  REGISTER  →  VERIFY",W/2f,y+57f,6.7f,NAVY,true,Paint.Align.CENTER)''')
s = s.replace('text(c,b,xx+8f,y+30f,7f,TEXT,true)', 'text(c,b,xx+8f,y+30f,7.6f,TEXT,true)')
s = s.replace('text(c,a,xx+8f,y+13f,5.1f,NAVY,true)', 'text(c,a,xx+8f,y+13f,5.5f,NAVY,true)')

# Expand section 08 into the dead space and restore locked authority/document-control/standards blocks.
old_scope_tail = '''        val y=top+14f; val h=76f\n        cell(c,M,y,205f,h,"AUTHENTICATION SCOPE","GeoStamp authenticates the digital record and recorded capture provenance available to the system, including registered identity, cryptographic integrity, capture signature, location data and session continuity.",false)\n        cell(c,M+205f,y,205f,h,"EVIDENCE HANDLING NOTE","This certificate reflects the evidence state and machine-verifiable controls recorded by GeoStamp at registration. Source values shown here are derived from the registered evidence record.",false)\n        stroke(c,M+410f,y,W-M,y+h,LINE)\n        text(c,"PUBLIC VERIFICATION",M+420f,y+12f,5.4f,NAVY,true)\n        text(c,"SCAN QR",M+420f,y+27f,6f,TEXT,true)\n        text(c,id(r),M+420f,y+44f,5.7f,NAVY,true)\n        val qrValue="https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}"\n        drawQr(c,qrValue,W-M-58f,y+7f,50f)\n        round(c,M,y+h+5f,W-M-220f,y+h+37f,3f,Color.rgb(255,251,240),Color.rgb(229,204,139))\n        text(c,"INTERPRETATION BOUNDARY",M+8f,y+h+17f,5.2f,NAVY,true)\n        text(c,"This automated report does not independently determine whether the photographed scene is truthful, complete, lawful or materially significant.",M+8f,y+h+29f,4.9f,TEXT,false)\n        stroke(c,W-M-212f,y+h+5f,W-M,y+h+37f,LINE)\n        text(c,"ISSUER / AUTHORITY",W-M-202f,y+h+17f,5f,MUTED,true)\n        text(c,"AXIOM INFRATECH",W-M-202f,y+h+29f,5.8f,TEXT,true)'''
new_scope_tail = '''        val y=top+15f; val h=70f\n        cell(c,M,y,202f,h,"AUTHENTICATION SCOPE","GeoStamp authenticates the digital record and recorded capture provenance available to the system, including registered identity, cryptographic integrity, capture signature, location data and session continuity.",false)\n        cell(c,M+202f,y,202f,h,"EVIDENCE HANDLING NOTE","This certificate reflects the evidence state and machine-verifiable controls recorded by GeoStamp at registration. Source values shown here are derived from the registered evidence record.",false)\n        stroke(c,M+404f,y,W-M,y+h,LINE)\n        text(c,"PUBLIC VERIFICATION",M+414f,y+13f,5.6f,NAVY,true)\n        text(c,"Scan QR or verify by Evidence ID",M+414f,y+26f,5.1f,TEXT,false)\n        text(c,id(r),M+414f,y+42f,6.2f,NAVY,true)\n        val qrValue="https://ahz-creator.github.io/GeoStamp-Portal/?id=${id(r)}"\n        drawQr(c,qrValue,W-M-57f,y+8f,48f)\n\n        val boundaryTop=y+h+5f\n        round(c,M,boundaryTop,W-M,boundaryTop+28f,3f,Color.rgb(255,251,240),Color.rgb(229,204,139))\n        text(c,"INTERPRETATION BOUNDARY",M+9f,boundaryTop+11f,5.3f,NAVY,true)\n        text(c,"This automated report does not independently determine whether the photographed scene is truthful, complete, lawful or materially significant.",M+9f,boundaryTop+22f,5.0f,TEXT,false)\n\n        val authTop=boundaryTop+34f\n        val col=(W-2*M)/3f\n        stroke(c,M,authTop,M+col,authTop+39f,LINE)\n        text(c,"ISSUER / AUTHORITY",M+8f,authTop+11f,5.1f,MUTED,true)\n        text(c,"AXIOM INFRATECH",M+8f,authTop+24f,6.1f,NAVY,true)\n        text(c,"GeoStamp Digital Evidence Platform",M+8f,authTop+34f,4.9f,TEXT,false)\n\n        stroke(c,M+col,authTop,M+2*col,authTop+39f,LINE)\n        text(c,"DOCUMENT CONTROL",M+col+8f,authTop+11f,5.1f,MUTED,true)\n        text(c,"Certificate: Extended",M+col+8f,authTop+24f,5.4f,TEXT,true)\n        text(c,"Page 1 / 1  •  Evidence: ${id(r)}",M+col+8f,authTop+34f,4.8f,TEXT,false)\n\n        stroke(c,M+2*col,authTop,W-M,authTop+39f,LINE)\n        text(c,"STANDARD & COMPLIANCE",M+2*col+8f,authTop+11f,5.1f,MUTED,true)\n        text(c,"Forensic Best Practices",M+2*col+8f,authTop+24f,5.4f,TEXT,true)\n        text(c,"ISO/IEC 27037 • ISO/IEC 27042",M+2*col+8f,authTop+34f,4.8f,NAVY,false)'''
if old_scope_tail not in s:
    raise SystemExit('Scope/footer pattern not found; local exporter differs from expected deployed version.')
s = s.replace(old_scope_tail, new_scope_tail)

# Footer becomes a locked-reference navy strip rather than a floating line.
old_footer = '''        val y=H-15f\n        stroke(c,18f,y-7f,W-18f,y-7f,NAVY)\n        text(c,"GEOSTAMP • DIGITAL EVIDENCE AUTHENTICATION • AXIOM INFRATECH",18f,y+4f,4.6f,MUTED,true)\n        text(c,id(r),W/2f,y+4f,4.6f,MUTED,true,Paint.Align.CENTER)\n        text(c,"PAGE 1 / 1",W-18f,y+4f,4.6f,MUTED,true,Paint.Align.RIGHT)'''
new_footer = '''        val y=H-14f\n        fill(c,0f,y-9f,W.toFloat(),H.toFloat(),NAVY)\n        text(c,"GEOSTAMP • DIGITAL EVIDENCE AUTHENTICATION • AXIOM INFRATECH",12f,y+1f,4.5f,Color.WHITE,true)\n        text(c,id(r),W/2f,y+1f,4.5f,Color.WHITE,true,Paint.Align.CENTER)\n        text(c,"PAGE 1 / 1",W-12f,y+1f,4.5f,Color.WHITE,true,Paint.Align.RIGHT)'''
s = s.replace(old_footer, new_footer)

# The original deploy script had two positional mono booleans that caused a compile error;
# keep this patch safe even if the hotfix was not run.
s = s.replace('cell(c,x[0],y,x[1]-x[0],31f,"IMAGE SHA-256 FINGERPRINT",first(r.optString("imageSha256"),"Unavailable"),false,true)',
              'cell(c,x[0],y,x[1]-x[0],31f,"IMAGE SHA-256 FINGERPRINT",first(r.optString("imageSha256"),"Unavailable"),false,mono=true)')
s = s.replace('cell(c,x[2],y,x[3]-x[2],31f,"KEY FINGERPRINT",first(r.optString("captureKeyFingerprint"),"Unavailable"),false,true)',
              'cell(c,x[2],y,x[3]-x[2],31f,"KEY FINGERPRINT",first(r.optString("captureKeyFingerprint"),"Unavailable"),false,mono=true)')

p.write_text(s, encoding='utf-8')
print('Locked-reference replica V2 patch applied: brand icon, typography, map styling, section density, bottom authority/document-control/standards blocks, footer strip.')
