package com.axiominfratech.geostamp.overlay

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import com.axiominfratech.geostamp.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream

/**
 * GeoStamp Enterprise Overlay Renderer — v12 "Forensic Minimal"
 * Precise reproduction of the enterprise layout with custom vector icons.
 */
object OverlayRenderer {

    private val logoCache = mutableMapOf<String, Bitmap>()

    fun initLogos(context: Context) {
        if (logoCache.isNotEmpty()) return
        val res = context.resources
        loadLogo(res, R.drawable.operator_telenor, "Telenor")
        loadLogo(res, R.drawable.operator_jazz,    "Jazz")
        loadLogo(res, R.drawable.operator_zong,    "Zong")
        loadLogo(res, R.drawable.operator_ufone,   "Ufone")
        // Runtime lookup — compiles fine even if ic_verified_shield.png is not in drawable yet.
        // Once you add the file it loads automatically; until then the vector fallback is used.
        val shieldResId = res.getIdentifier("ic_verified_shield", "drawable", context.packageName)
        if (shieldResId != 0) loadLogo(res, shieldResId, "_verified_shield")
    }

    private fun loadLogo(res: android.content.res.Resources, id: Int, key: String) {
        try { BitmapFactory.decodeResource(res, id)?.let { logoCache[key] = it } }
        catch (_: Exception) {}
    }

    data class OverlayData(
        val locationLine: String,
        val addressLine: String,
        val dateTimeLine: String,
        val operatorLine: String,
        val operatorFullName: String,
        val siteIdLine: String,
        val workspaceMode: String = "organization",
        val primaryLabel: String = "ORGANIZATION",
        val secondaryLabel: String = "SITE ID",
        val accuracyLine: String,
        val altitudeLine: String,
        val directionLine: String,
        val username: String,
        val batteryPercentage: String = "78%",
        val networkType: String = "Jazz 4G",
        val weatherTemp: String = "34°C",
        val weatherHaze: String = "Haze",
        val deviceType: String = "Android",
        val cameraType: String = "Rear Camera",
        val overlayScale: Float = 1.0f,
        val savedOverlayHeightFraction: Float = 0.25f,
        val savedStampLayout: SavedStampLayout = SavedStampLayout.CARD,
        val stampTheme: StampTheme = StampTheme.DARK,
        val overlayX: Float = 0f,
        val overlayY: Float = 0f,
        val overlayW: Float = 0f,
        val overlayH: Float = 0f,
        val previewW: Int = 1080,
        val previewH: Int = 1920,
        val backgroundAlpha: Float = 0.8f,
        val locationIntegrityRisk: Boolean = false,
        val verificationId: String = "",
        val verificationPayload: String = "",
        val evidenceStatus: String = "CAPTURE SEALED"
    )

    fun render(source: Bitmap, data: OverlayData, context: Context): Bitmap {
        initLogos(context)
        val out    = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val isPortrait = h > w
        
        when (data.savedStampLayout) {
            SavedStampLayout.CARD -> drawMasterBanner(canvas, data, w, h, isPortrait)
            SavedStampLayout.STRIP -> drawStripBanner(canvas, data, w, h, isPortrait)
            SavedStampLayout.FOOTER -> drawFooterBanner(canvas, data, w, h, isPortrait)
        }
        if (data.locationIntegrityRisk) drawIntegrityMarker(canvas, w, h)
        return out
    }

    fun renderAndSave(source: Bitmap, data: OverlayData, outFile: File, context: Context) {
        val rendered = render(source, data, context)
        try {
            FileOutputStream(outFile).use { out ->
                rendered.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        } finally {
            if (rendered !== source) {
                rendered.recycle()
            }
        }
    }

    private fun drawIntegrityMarker(canvas: Canvas, w: Float, h: Float) {
        // Deliberately discreet, but deterministic and recoverable during verification.
        // It is an integrity indicator, not a fraud accusation.
        val size = minOf(w, h) * 0.022f
        val cx = w * 0.035f
        val cy = h * 0.115f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 15, 15, 15) }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (size * 0.08f).coerceAtLeast(1f)
            color = Color.argb(180, 255, 82, 82)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 82, 82)
            textSize = size * 0.72f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawCircle(cx, cy, size * 0.5f, fill)
        canvas.drawCircle(cx, cy, size * 0.5f, ring)
        canvas.drawText("F", cx, cy - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun ref(w: Float, h: Float): Float {
        return if (w > h) {
            h * 1.18f
        } else {
            w
        }
    }

    /**
     * Permanent evidence stamp — v12 "Forensic Minimal".
     *
     * Design goals:
     *  - Preserve the photograph (20–30% panel height).
     *  - Make the four forensic questions immediately readable: WHERE / WHEN /
     *    INTEGRITY / HOW TO VERIFY.
     *  - Never truncate primary evidence fields such as coordinates or Evidence ID.
     *  - Keep secondary telemetry (battery/network/weather/compass) out of the
     *    permanent image. It remains available in the app/metadata where needed.
     */
    private fun drawMasterBanner(cv: Canvas, data: OverlayData, w: Float, h: Float, isPortrait: Boolean) {
        val safeScale = data.overlayScale.coerceIn(0.8f, 1.2f)
        val r = ref(w, h) * safeScale
        val landscapeScale = if (!isPortrait) 0.96f else 1f

        cv.save()
        cv.scale(landscapeScale, landscapeScale, w / 2f, h / 2f)

        val margin = r * 0.025f
        val radius = r * 0.014f
        val panelH = (h * data.savedOverlayHeightFraction.coerceIn(0.20f, 0.30f))
            .coerceIn(h * 0.20f, h * 0.30f)
        val rect = RectF(margin, h - margin - panelH, w - margin, h - margin)
        val alpha = (data.backgroundAlpha * 255).toInt().coerceIn(0, 255)
        val light = data.stampTheme == StampTheme.LIGHT
        val panelRgb = if (light) Color.rgb(255,255,255) else Color.rgb(8,12,18)
        val primary = if (light) Color.rgb(11,24,48) else Color.WHITE
        val secondary = if (light) Color.rgb(100,116,139) else Color.rgb(203,213,225)
        val muted = if (light) Color.rgb(100,116,139) else Color.rgb(148,163,184)
        val border = if (light) Color.argb(160,11,24,48) else Color.argb(220,255,255,255)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(alpha, Color.red(panelRgb), Color.green(panelRgb), Color.blue(panelRgb)) }
        cv.drawRoundRect(rect, radius, radius, bg)
        cv.drawRoundRect(rect, radius, radius, strokePaint(border, r * 0.0035f))

        val pad = r * 0.022f
        val inner = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
        val headerH = inner.height() * 0.22f
        val bodyH = inner.height() * 0.59f
        val footerH = inner.height() * 0.19f
        val bodyT = inner.top + headerH
        val footerT = bodyT + bodyH

        // Header: operator identity | site | user.
        drawHeader(cv, data, inner, inner.top, headerH, r)
        cv.drawLine(inner.left, bodyT, inner.right, bodyT, dividerPaint(r))

        // Body is split into an evidence block and an explicit verification block.
        val qrBoxW = inner.width() * 0.245f
        val evidenceRight = inner.right - qrBoxW - r * 0.018f
        val evidenceW = evidenceRight - inner.left
        val evidenceX = inner.left

        val parts = data.dateTimeLine.split("|")
        val dateStr = parts.getOrNull(0).orEmpty()
        val dayStr = parts.getOrNull(1).orEmpty()
        val timeStr = parts.getOrNull(2).orEmpty()

        val labelPaint = tp(r * 0.014f, muted, bold = true).apply {
            letterSpacing = 0.10f
        }
        val coordPaint = tp(r * 0.026f, primary, bold = true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(0.6f, 0f, 0.5f, Color.BLACK)
        }
        val secondaryPaint = tp(r * 0.0145f, secondary)
        val strongPaint = tp(r * 0.018f, primary, bold = true)
        // WHERE
        cv.drawText("LOCATION", evidenceX, bodyT + r * 0.035f - labelPaint.ascent(), labelPaint)
        val coords = formatCoords(data.locationLine)
        cv.drawText(coords, evidenceX, bodyT + r * 0.035f + r * 0.030f - coordPaint.ascent(), coordPaint)
        if (data.addressLine.isNotBlank()) {
            cv.drawText(singleLine(data.addressLine, evidenceW * 0.98f, secondaryPaint),
                evidenceX, bodyT + r * 0.095f - secondaryPaint.ascent(), secondaryPaint)
        }

        // WHEN + accuracy on one clean row.
        val whenY = bodyT + bodyH * 0.50f
        cv.drawLine(evidenceX, whenY - r * 0.018f, evidenceRight, whenY - r * 0.018f, dividerPaint(r))
        val whenLeft = "${dateStr.ifBlank { "—" }} · ${timeStr.ifBlank { "—" }}"
        cv.drawText(whenLeft, evidenceX, whenY + r * 0.010f - strongPaint.ascent(), strongPaint)
        val accuracyPaint = tp(r * 0.015f,
            if (data.locationIntegrityRisk) Color.rgb(248, 113, 113) else Color.rgb(134, 239, 172),
            bold = true)
        val accuracy = data.accuracyLine.ifBlank { "Accuracy unavailable" }
        val accuracyW = accuracyPaint.measureText(accuracy)
        cv.drawText(accuracy, evidenceRight - accuracyW, whenY + r * 0.010f - accuracyPaint.ascent(), accuracyPaint)
        cv.drawText(if (dayStr.isBlank()) "" else dayStr,
            evidenceX, whenY + r * 0.060f - secondaryPaint.ascent(), secondaryPaint)

        // Integrity + Evidence ID.
        val statusY = bodyT + bodyH * 0.68f
        val statusColor = when {
            data.locationIntegrityRisk -> Color.rgb(248, 113, 113)
            data.evidenceStatus.contains("RISK", true) -> Color.rgb(248, 113, 113)
            data.evidenceStatus.contains("WARNING", true) -> Color.rgb(251, 191, 36)
            else -> Color.rgb(74, 222, 128)
        }
        val statusLabel = when {
            data.locationIntegrityRisk -> "LOCATION RISK"
            data.evidenceStatus.contains("WARNING", true) -> "LOCATION REVIEW"
            else -> "CAPTURE SEALED"
        }
        val statusPaint = tp(r * 0.017f, statusColor, bold = true)
        cv.drawText("✓  $statusLabel", evidenceX, statusY - statusPaint.ascent(), statusPaint)

        val id = buildVerifyId(data)
        val idPaint = tp(r * 0.0145f, if (light) Color.rgb(72, 55, 130) else Color.rgb(196, 181, 253), bold = true)
        val idPrefix = "Evidence ID  "
        val idText = idPrefix + id
        cv.drawText(singleLine(idText, evidenceW * 0.98f, idPaint),
            evidenceX, statusY + r * 0.045f - idPaint.ascent(), idPaint)

        // Verification panel — deliberately explicit so the QR has meaning.
        val qrPad = r * 0.010f
        val qrRect = RectF(evidenceRight + r * 0.012f, bodyT + r * 0.015f,
            inner.right, footerT - r * 0.015f)
        cv.drawRoundRect(qrRect, r * 0.008f,
            r * 0.008f, solidPaint(Color.argb(70, 255, 255, 255)))
        cv.drawRoundRect(qrRect, r * 0.008f, r * 0.008f,
            strokePaint(Color.argb(90, 255, 255, 255), r * 0.0015f))

        val qrLabel = tp(r * 0.0125f, primary, bold = true)
        qrLabel.textAlign = Paint.Align.CENTER
        cv.drawText("SCAN TO VERIFY", qrRect.centerX(), qrRect.top + r * 0.025f - qrLabel.ascent(), qrLabel)
        val qrSize = minOf(qrRect.width() - qrPad * 2f, qrRect.height() * 0.68f)
        val qrY = qrRect.top + r * 0.055f
        drawQR(cv, data, qrRect.centerX() - qrSize / 2f, qrY, qrSize)
        val qrId = tp(r * 0.0115f, muted, bold = true)
        qrId.textAlign = Paint.Align.CENTER
        cv.drawText(shortVerifyId(data), qrRect.centerX(), qrRect.bottom - r * 0.018f - qrId.descent(), qrId)
        qrLabel.textAlign = Paint.Align.LEFT
        qrId.textAlign = Paint.Align.LEFT

        // Footer: branding + permanent evidence ID. No battery/network in the saved image.
        cv.drawLine(inner.left, footerT, inner.right, footerT, dividerPaint(r))
        val footerPaint = tp(r * 0.0135f, secondary, bold = true)
        val footerMuted = tp(r * 0.0115f, muted)
        val footerY = footerT + footerH * 0.60f - (footerPaint.ascent() + footerPaint.descent()) / 2f
        cv.drawText("GeoStamp  •  Axiom Infratech", inner.left, footerY, footerPaint)
        val footerRight = "${data.cameraType}  •  ${data.operatorFullName.ifBlank { data.operatorLine }}"
        val footerRightVisible = singleLine(footerRight, inner.width() * 0.52f, footerMuted)
        val fw = footerMuted.measureText(footerRightVisible)
        cv.drawText(footerRightVisible, inner.right - fw, footerY, footerMuted)

        cv.restore()
    }

    private fun drawStripBanner(cv: Canvas, data: OverlayData, w: Float, h: Float, isPortrait: Boolean) {
        val r = ref(w,h) * data.overlayScale.coerceIn(0.8f,1.2f)
        val panelH = (h * 0.13f).coerceIn(h*0.10f,h*0.18f)
        val y = h - panelH - r*0.018f
        val light = data.stampTheme == StampTheme.LIGHT
        val panel = if (light) Color.WHITE else Color.rgb(8,12,18)
        val text = if (light) Color.rgb(11,24,48) else Color.WHITE
        val sub = if (light) Color.rgb(100,116,139) else Color.rgb(203,213,225)
        cv.drawRect(0f,y,w.toFloat(),h.toFloat(), solidPaint(Color.argb((data.backgroundAlpha*255).toInt(),Color.red(panel),Color.green(panel),Color.blue(panel))))
        cv.drawLine(0f,y,w,y,strokePaint(if(light) Color.rgb(216,226,238) else Color.argb(160,255,255,255),r*0.002f))
        val p = tp(r*0.018f,text,true); val s=tp(r*0.0125f,sub)
        cv.drawText(formatCoords(data.locationLine),r*0.025f,y+r*0.045f-p.ascent(),p)
        cv.drawText("${data.siteIdLine}  •  ${data.dateTimeLine.substringBefore('|')} ${data.dateTimeLine.substringAfter('|').substringAfter('|')}",r*0.025f,y+r*0.088f-s.ascent(),s)
        val idp=tp(r*0.012f,if(light) Color.rgb(72,55,130) else Color.rgb(196,181,253),true)
        val id=shortVerifyId(data)
        cv.drawText(id,w-r*0.025f-idp.measureText(id),y+r*0.065f-idp.ascent(),idp)
    }

    private fun drawFooterBanner(cv: Canvas, data: OverlayData, w: Float, h: Float, isPortrait: Boolean) {
        val r = ref(w,h) * data.overlayScale.coerceIn(0.8f,1.2f)
        val panelH = (h * 0.075f).coerceIn(h*0.06f,h*0.10f)
        val y = h - panelH
        val light = data.stampTheme == StampTheme.LIGHT
        val panel = if (light) Color.WHITE else Color.rgb(8,12,18)
        val text = if (light) Color.rgb(11,24,48) else Color.WHITE
        cv.drawRect(0f,y,w,h,solidPaint(Color.argb((data.backgroundAlpha*255).toInt(),Color.red(panel),Color.green(panel),Color.blue(panel))))
        val p=tp(r*0.0125f,text,true)
        val line="GeoStamp  •  ${data.siteIdLine}  •  ${shortVerifyId(data)}"
        cv.drawText(singleLine(line,w-r*0.05f,p),r*0.025f,y+panelH*0.62f-(p.ascent()+p.descent())/2f,p)
    }

    private fun singleLine(text: String, maxW: Float, paint: TextPaint): String =
        TextUtils.ellipsize(text.replace("\n", " "), paint, maxW.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

    /**
     * Header row: [Operator logo] │ [SITE ID label / value] │ [Person icon / User label / username]
     * Spans the full panel width (QR is only shown in row1+row2, not the header).
     */
    private fun drawHeader(cv: Canvas, data: OverlayData, rect: RectF, y: Float, h: Float, r: Float) {
        val centerY = y + h / 2f
        val light = data.stampTheme == StampTheme.LIGHT
        val primary = if (light) Color.rgb(11,24,48) else Color.WHITE
        val secondary = if (light) Color.rgb(100,116,139) else Color.LTGRAY
        val accent  = if (data.workspaceMode == "personal") Color.parseColor("#CE93D8") else accentColor(data.operatorLine)
        val totalW  = rect.width()

        // ── Section widths ───────────────────────────────────────────────
        val logoSectionW = totalW * 0.38f   // ~38 % for operator branding
        val siteSectionW = totalW * 0.35f   // ~35 % for site ID
        // remaining ~27 % for user

        // ── LEFT: Operator logo (or name fallback) ───────────────────────
        val logoX    = rect.left + r * 0.03f
        val logoH    = h * 0.72f
        val logoMaxW = logoSectionW - r * 0.06f
        val logo     = if (data.workspaceMode == "personal") null else resolveLogoFor(data.operatorLine)

        if (logo != null) {
            val aspect = logo.width.toFloat() / logo.height.toFloat()
            val drawW  = (logoH * aspect).coerceAtMost(logoMaxW)
            cv.drawBitmap(
                logo, null,
                RectF(logoX, centerY - logoH / 2f, logoX + drawW, centerY + logoH / 2f),
                Paint(Paint.ANTI_ALIAS_FLAG)
            )
        } else {
            val namePaint = tp(r * 0.025f, accent, bold = true)
            val prefix = if (data.workspaceMode == "personal") "PERSONAL  •  " else ""
            cv.drawText(trunc(prefix + data.operatorLine, logoMaxW, namePaint), logoX,
                centerY - (namePaint.descent() + namePaint.ascent()) / 2f, namePaint)
        }

        val div1X = rect.left + logoSectionW
        drawDiv(cv, div1X, y, h, r)

        // ── MIDDLE: "SITE ID" label (top) + value (bottom), metrics-centered ─
        val siteX  = div1X + r * 0.025f
        val labelP = tp(r * 0.017f, secondary)
        val siteP  = tp(r * 0.031f, accent, bold = true)
        run {
            val blkH = (labelP.descent() - labelP.ascent()) + (siteP.descent() - siteP.ascent())
            val top  = centerY - blkH / 2f
            val ln1  = top - labelP.ascent()
            val ln2  = top + (labelP.descent() - labelP.ascent()) - siteP.ascent()
            cv.drawText(data.secondaryLabel, siteX, ln1, labelP)
            cv.drawText(data.siteIdLine, siteX, ln2, siteP)
        }

        val div2X = rect.left + logoSectionW + siteSectionW
        drawDiv(cv, div2X, y, h, r)

        // ── RIGHT: Person icon + "User" label (top) + username (bottom), metrics-centered ─
        val userX    = div2X + r * 0.025f
        val iconSize = r * 0.038f
        drawVectorIcon(cv, "PERSON", userX, centerY, iconSize, r, Color.LTGRAY)

        val userTextX  = userX + r * 0.062f
        val userLabelP = tp(r * 0.017f, secondary)
        val userNameP  = tp(r * 0.022f, primary, bold = true)
        val maxNameW   = (rect.right - r * 0.02f - userTextX).coerceAtLeast(1f)
        run {
            val blkH = (userLabelP.descent() - userLabelP.ascent()) + (userNameP.descent() - userNameP.ascent())
            val top  = centerY - blkH / 2f
            val ln1  = top - userLabelP.ascent()
            val ln2  = top + (userLabelP.descent() - userLabelP.ascent()) - userNameP.ascent()
            cv.drawText("User", userTextX, ln1, userLabelP)
            cv.drawText(trunc(data.username.ifBlank { "—" }, maxNameW, userNameP), userTextX, ln2, userNameP)
        }
    }

    private fun drawCol(cv: Canvas, val1: String, val2: String, type: String, x: Float, y: Float, w: Float, h: Float, r: Float) {
        val iconSize = r * 0.045f
        val centerY  = y + h / 2f
        val textX    = x + r * 0.070f
        val maxW     = (w - r * 0.070f - r * 0.010f).coerceAtLeast(1f)

        if (val2.isBlank()) {
            drawVectorIcon(cv, type, x + r * 0.010f, centerY, iconSize, r)
            val vp = tp(r * 0.022f, Color.WHITE, bold = true)
            cv.drawText(trunc(val1, maxW, vp), textX, centerY - (vp.descent() + vp.ascent()) / 2f, vp)
        } else {
            val vp1  = tp(r * 0.022f, Color.WHITE, bold = true)
            val vp2  = tp(r * 0.016f, Color.LTGRAY)
            val gap  = 0f
            val blkH = (vp1.descent() - vp1.ascent()) + gap + (vp2.descent() - vp2.ascent())
            val top  = centerY - blkH / 2f
            val l1   = top - vp1.ascent()
            val l2   = top + (vp1.descent() - vp1.ascent()) + gap - vp2.ascent()
            drawVectorIcon(cv, type, x + r * 0.010f, centerY, iconSize, r)
            cv.drawText(trunc(val1, maxW, vp1), textX, l1, vp1)
            cv.drawText(trunc(val2, maxW, vp2), textX, l2, vp2)
        }
    }

    private fun drawStatus(cv: Canvas, type: String, t1: String, t2: String, color: Int, x: Float, y: Float, w: Float, h: Float, r: Float) {
        val iconSize = r * 0.034f
        val centerY  = y + h / 2f
        drawVectorIcon(cv, type, x + r * 0.010f, centerY, iconSize, r, color)

        val textX = x + r * 0.055f
        val maxW  = (w - r * 0.055f - r * 0.008f).coerceAtLeast(1f)

        if (t2.isBlank()) {
            val tp1 = tp(r * 0.019f, color, bold = true)
            cv.drawText(trunc(t1, maxW, tp1), textX, centerY - (tp1.descent() + tp1.ascent()) / 2f, tp1)
        } else {
            val tp1 = tp(r * 0.019f, color, bold = true)
            val tp2 = if (type == "SHIELD") {
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize   = r * 0.015f
                    this.color = Color.parseColor("#88FFAA")
                    typeface   = Typeface.DEFAULT
                    setShadowLayer(r * 0.018f, 0f, 0f, Color.parseColor("#00FF88"))
                }
            } else {
                tp(r * 0.015f, Color.LTGRAY)
            }
            val gap  = 0f
            val blkH = (tp1.descent() - tp1.ascent()) + gap + (tp2.descent() - tp2.ascent())
            val top  = centerY - blkH / 2f
            val l1   = top - tp1.ascent()
            val l2   = top + (tp1.descent() - tp1.ascent()) + gap - tp2.ascent()
            cv.drawText(trunc(t1, maxW, tp1), textX, l1, tp1)
            cv.drawText(trunc(t2, maxW, tp2), textX, l2, tp2)
        }
    }

    private fun drawFooter(cv: Canvas, data: OverlayData, rect: RectF, y: Float, h: Float, r: Float) {
        cv.drawLine(rect.left + r*0.02f, y, rect.right - r*0.02f, y, dividerPaint(r))
        val fs = r * 0.018f
        val tp = tp(fs, Color.WHITE)
        val gp = tp(fs, Color.parseColor("#2ECC71"), bold = true)
        
        val centerY = y + h * 0.62f
        val iconSize = r * 0.032f
        val vOffset = (tp.descent() + tp.ascent()) / 2f
        
        drawVectorIcon(cv, "SHIELD_BLUE", rect.left + r*0.03f, centerY, iconSize, r, Color.parseColor("#00B4E6"))
        
        val footerText = "Captured & Sealed by GeoStamp"
        val netText = "  ${data.networkType}"
        val batText = "  ${data.batteryPercentage}"
        val netW = tp.measureText(netText)
        val batW = gp.measureText(batText)
        
        val sigX = rect.right - r*0.05f - netW - r*0.035f
        drawVectorIcon(cv, "SIGNAL", sigX, centerY, iconSize, r, Color.WHITE)
        cv.drawText(netText, sigX + r*0.03f, centerY - vOffset, tp)
        
        val batX = sigX - batW - r*0.07f
        val footerX = rect.left + r * 0.065f
        val footerMaxW = (batX - r * 0.03f - footerX).coerceAtLeast(1f)
        cv.drawText(trunc(footerText, footerMaxW, tp), footerX, centerY - vOffset, tp)
        drawVectorIcon(cv, "BATTERY", batX, centerY, iconSize, r, Color.parseColor("#2ECC71"))
        cv.drawText(batText, batX + r*0.035f, centerY - (gp.descent() + gp.ascent())/2f, gp)
    }

    private fun drawVectorIcon(cv: Canvas, type: String, x: Float, y: Float, size: Float, r: Float, color: Int = Color.WHITE) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = r * 0.003f; style = Paint.Style.STROKE }
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        
        cv.save()
        cv.translate(x, y - size * 0.48f)
        when(type) {
            "PIN" -> {
                // Professional Gradient Pin (Image 1 style)
                val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(size/2, size/3, size, 
                        Color.parseColor("#00E5FF"), Color.parseColor("#0077D4"), Shader.TileMode.CLAMP)
                }
                val path = Path().apply {
                    moveTo(size/2, size)
                    // Control points clamped to [0, size] — left edge stays exactly at x=0
                    cubicTo(0f, size * 0.55f, 0f, 0f, size/2, 0f)
                    cubicTo(size, 0f, size, size * 0.55f, size/2, size)
                }
                cv.drawPath(path, pinPaint)
                cv.drawCircle(size/2, size/3, size/5.5f, solidPaint(Color.WHITE))
                cv.drawCircle(size/2, size/3, size/10f, solidPaint(Color.parseColor("#002A4D")))
            }
            "PERSON" -> {
                // Simple person silhouette: circle head + body arc
                val personP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
                cv.drawCircle(size / 2f, size * 0.28f, size * 0.22f, personP)
                val bodyPath = Path().apply {
                    moveTo(0f, size)
                    quadTo(0f, size * 0.58f, size / 2f, size * 0.54f)
                    quadTo(size, size * 0.58f, size, size)
                    close()
                }
                cv.drawPath(bodyPath, personP)
            }
            "CLOCK" -> {
                val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = size * 0.08f
                    strokeCap = Paint.Cap.ROUND
                }
                cv.drawCircle(size/2, size/2, size*0.45f, clockPaint)
                // Center pin
                cv.drawCircle(size/2, size/2, size*0.06f, solidPaint(Color.WHITE))
                // Hands
                val handPaint = Paint(clockPaint).apply { strokeWidth = size * 0.09f }
                cv.drawLine(size/2, size/2, size/2, size*0.22f, handPaint) // Hour
                cv.drawLine(size/2, size/2, size*0.78f, size/2, handPaint) // Minute
                // Ticks
                val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; strokeWidth = size * 0.04f }
                for (i in 0 until 4) {
                    cv.save()
                    cv.rotate(i * 90f, size/2, size/2)
                    cv.drawLine(size/2, size*0.1f, size/2, size*0.2f, tickPaint)
                    cv.restore()
                }
            }
            "CAL" -> {
                val calP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = size * 0.07f }
                cv.drawRoundRect(0f, size*0.15f, size, size, size*0.1f, size*0.1f, calP)
                cv.drawLine(0f, size*0.42f, size, size*0.42f, calP) // Header line
                // Binding rings
                cv.drawRoundRect(size*0.18f, 0f, size*0.32f, size*0.3f, size*0.05f, size*0.05f, solidPaint(Color.WHITE))
                cv.drawRoundRect(size*0.68f, 0f, size*0.82f, size*0.3f, size*0.05f, size*0.05f, solidPaint(Color.WHITE))
                // Grid dots
                val dotP = solidPaint(Color.argb(180, 255, 255, 255))
                for (row in 0..1) for (col in 0..2) {
                    cv.drawCircle(size*0.25f + col*size*0.25f, size*0.62f + row*size*0.22f, size*0.04f, dotP)
                }
            }
            "TARGET" -> {
                val accent = Color.parseColor("#00E5FF")
                val tPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = accent; style = Paint.Style.STROKE; strokeWidth = size * 0.08f }
                cv.drawCircle(size/2, size/2, size*0.42f, tPaint)
                cv.drawCircle(size/2, size/2, size*0.15f, solidPaint(accent))
                cv.drawLine(0f, size/2, size*0.3f, size/2, tPaint)
                cv.drawLine(size*0.7f, size/2, size, size/2, tPaint)
                cv.drawLine(size/2, 0f, size/2, size*0.3f, tPaint)
                cv.drawLine(size/2, size*0.7f, size/2, size, tPaint)
            }
            "MOUNTAIN" -> {
                val mountP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, 0f, size, 
                        Color.parseColor("#90A4AE"), Color.parseColor("#455A64"), Shader.TileMode.CLAMP)
                }
                val path = Path().apply {
                    moveTo(0f, size); lineTo(size*0.5f, size*0.1f); lineTo(size, size); close()
                }
                cv.drawPath(path, mountP)
                // Snow Cap
                val snowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
                val snowPath = Path().apply {
                    moveTo(size*0.5f, size*0.1f); lineTo(size*0.65f, size*0.4f); lineTo(size*0.35f, size*0.4f); close()
                }
                cv.drawPath(snowPath, snowP)
                // Secondary Peak
                val peak2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(140, 69, 90, 100) }
                cv.drawPath(Path().apply { moveTo(size*0.3f, size); lineTo(size*0.7f, size*0.45f); lineTo(size, size); close() }, peak2)
            }
            "SHIELD" -> {
                val bmp = logoCache["_verified_shield"]
                if (bmp != null) {
                    // Use the ic_verified_shield drawable the user added to res/drawable
                    cv.drawBitmap(bmp, null, RectF(0f, 0f, size, size), Paint(Paint.ANTI_ALIAS_FLAG))
                } else {
                    // Fallback: outline shield + circle + checkmark (matches uploaded icon style)
                    val green = Color.parseColor("#4CAF50")
                    val outlineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = green; style = Paint.Style.STROKE
                        strokeWidth = size * 0.07f; strokeCap = Paint.Cap.ROUND
                    }
                    val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = green; style = Paint.Style.FILL }
                    // Shield outline
                    val path = Path().apply {
                        moveTo(size/2, size*0.02f); lineTo(size*0.95f, size*0.18f)
                        lineTo(size*0.95f, size*0.72f)
                        quadTo(size/2, size*0.98f, size*0.05f, size*0.72f)
                        lineTo(size*0.05f, size*0.18f); close()
                    }
                    cv.drawPath(path, outlineP)
                    // Circle fill
                    cv.drawCircle(size/2, size/2, size*0.30f, fillP)
                    // Checkmark in white
                    val ck = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = Color.BLACK; strokeWidth = size*0.09f
                        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    }
                    cv.drawPath(Path().apply {
                        moveTo(size*0.32f, size*0.50f); lineTo(size*0.45f, size*0.63f); lineTo(size*0.70f, size*0.37f)
                    }, ck)
                }
            }
            "SHIELD_BLUE" -> {
                val bP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, 0f, size, 
                        Color.parseColor("#00B4E6"), Color.parseColor("#0077D4"), Shader.TileMode.CLAMP)
                }
                val path = Path().apply {
                    moveTo(size/2, 0f); lineTo(size, size*0.15f); lineTo(size, size*0.75f)
                    quadTo(size/2, size, 0f, size*0.75f); lineTo(0f, size*0.15f); close()
                }
                cv.drawPath(path, bP)
            }
            "PHONE" -> {
                // Android Bot Icon (Image 5 style)
                val botPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#A4C639") }
                cv.drawArc(size*0.2f, size*0.1f, size*0.8f, size*0.7f, 180f, 180f, true, botPaint) // Head
                cv.drawRect(size*0.2f, size*0.45f, size*0.8f, size*0.85f, botPaint) // Body
                cv.drawCircle(size*0.35f, size*0.3f, size*0.05f, solidPaint(Color.WHITE)) // Eye L
                cv.drawCircle(size*0.65f, size*0.3f, size*0.05f, solidPaint(Color.WHITE)) // Eye R
                // Antennas
                val ap = Paint(botPaint).apply { strokeWidth = size*0.05f; style = Paint.Style.STROKE; this.color = Color.parseColor("#A4C639") }
                cv.drawLine(size*0.35f, size*0.15f, size*0.25f, 0f, ap)
                cv.drawLine(size*0.65f, size*0.15f, size*0.75f, 0f, ap)
            }
            "CAMERA" -> {
                cv.drawRoundRect(0f, size*0.2f, size, size*0.9f, size*0.1f, size*0.1f, p)
                cv.drawCircle(size/2, size*0.55f, size*0.22f, p)
                cv.drawCircle(size/2, size*0.55f, size*0.1f, s)
                cv.drawRect(size*0.7f, size*0.3f, size*0.85f, size*0.4f, s)
            }
            "LOCK" -> {
                cv.drawRoundRect(0f, size*0.45f, size, size, size*0.1f, size*0.1f, p)
                cv.drawArc(size*0.2f, 0f, size*0.8f, size*0.8f, 180f, 180f, false, p)
            }
            "WEATHER" -> {
                // Sun + Clouds (Image 3 style)
                val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#FFD54F") }
                cv.drawCircle(size*0.7f, size*0.3f, size*0.3f, sunPaint)
                
                val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#B3E5FC") }
                cv.drawCircle(size*0.3f, size*0.7f, size*0.25f, cloudPaint)
                cv.drawCircle(size*0.55f, size*0.75f, size*0.25f, cloudPaint)
                cv.drawRect(size*0.3f, size*0.75f, size*0.6f, size*1.0f, cloudPaint)
            }
            "COMPASS" -> {
                cv.drawCircle(size/2, size/2, size/2, p)
                val needleN = Path().apply { moveTo(size/2, size*0.1f); lineTo(size*0.7f, size/2); lineTo(size*0.3f, size/2); close() }
                cv.drawPath(needleN, solidPaint(Color.RED))
                val needleS = Path().apply { moveTo(size/2, size*0.9f); lineTo(size*0.7f, size/2); lineTo(size*0.3f, size/2); close() }
                cv.drawPath(needleS, s)
            }
            "SIGNAL" -> {
                for(i in 0..3) {
                    val barH = size * (0.25f * (i + 1))
                    cv.drawRect(i*size*0.25f, size - barH, (i+0.75f)*size*0.25f, size, s)
                }
            }
            "BATTERY" -> {
                cv.drawRoundRect(0f, size*0.25f, size*0.85f, size*0.75f, size*0.08f, size*0.08f, p)
                cv.drawRect(size*0.85f, size*0.42f, size, size*0.58f, s)
                cv.drawRect(size*0.08f, size*0.32f, size*0.65f, size*0.68f, s)
            }
        }
        cv.restore()
    }

    /**
     * Reformat a coordinate string to exactly 4 decimal places.
     * Input:  "25.374542° N, 68.368027° E"
     * Output: "25.3745° N, 68.3680° E"
     * Falls back to the original string if the pattern doesn't match.
     */
    private fun formatCoords(raw: String): String {
        val m = Regex("""([\d.]+)°\s*([NS]),\s*([\d.]+)°\s*([EW])""").find(raw) ?: return raw
        val lat = m.groupValues[1].toDoubleOrNull() ?: return raw
        val lon = m.groupValues[3].toDoubleOrNull() ?: return raw
        return "%.4f° %s, %.4f° %s".format(lat, m.groupValues[2], lon, m.groupValues[4])
    }

    private fun measureColW(val1: String, val2: String, r: Float, isPin: Boolean): Float {
        val fs1 = r * 0.024f
        val vp = tp(fs1, Color.WHITE, bold = true)
        val lp = tp(r * 0.018f, Color.LTGRAY)
        val iconSpace = if (isPin) (r * 0.065f + r * 0.055f) else r * 0.075f  // matches drawCol
        val textW = vp.measureText(val1 + " ") + (if (val2.isBlank()) 0f else lp.measureText(val2))
        return iconSpace + textW + r * 0.02f
    }

    private fun drawDiv(cv: Canvas, x: Float, y: Float, h: Float, r: Float) {
        cv.drawLine(x - r*0.015f, y + h*0.2f, x - r*0.015f, y + h*0.8f, dividerPaint(r))
    }

    private fun drawQR(cv: Canvas, data: OverlayData, x: Float, y: Float, size: Float) {
        val payload = data.verificationPayload.ifBlank { buildVerifyId(data) }
        val qr = generateQR(payload, size.toInt()) ?: return
        cv.drawRect(x, y, x + size, y + size, solidPaint(Color.WHITE))
        cv.drawBitmap(qr, null, RectF(x + 2, y + 2, x + size - 2, y + size - 2), null)
    }

    private fun buildVerifyId(data: OverlayData): String =
        data.verificationId.ifBlank { "GST-UNREGISTERED" }

    private fun shortVerifyId(data: OverlayData): String {
        val full = buildVerifyId(data)
        if (full.length <= 18) return full
        val parts = full.split("-")
        return if (parts.size >= 3) {
            "GST-${parts.takeLast(2).joinToString("-")}"
        } else {
            full.take(8) + "…" + full.takeLast(6)
        }
    }

    private fun accentColor(op: String): Int = when {
        op.contains("Telenor", ignoreCase = true) -> Color.parseColor("#00B4E6")
        op.contains("Jazz",    ignoreCase = true) -> Color.parseColor("#E31837")
        op.contains("Zong",    ignoreCase = true) -> Color.parseColor("#8CC63F")
        op.contains("Ufone",   ignoreCase = true) -> Color.parseColor("#F36F21")
        else                                       -> Color.parseColor("#00B4E6")
    }

    private fun resolveLogoFor(op: String): Bitmap? = when {
        op.contains("Telenor", ignoreCase = true) -> logoCache["Telenor"]
        op.contains("Jazz",    ignoreCase = true) -> logoCache["Jazz"]
        op.contains("Zong",    ignoreCase = true) -> logoCache["Zong"]
        op.contains("Ufone",   ignoreCase = true) -> logoCache["Ufone"]
        else -> null
    }

    private fun generateQR(content: String, sizePx: Int): Bitmap? = try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 0))
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
            for (py in 0 until sizePx) for (px in 0 until sizePx)
                bmp.setPixel(px, py, if (matrix[px, py]) Color.BLACK else Color.WHITE)
        }
    } catch (_: Exception) { null }

    private fun solidPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width; style = Paint.Style.STROKE }
    private fun dividerPaint(r: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255); strokeWidth = r * 0.0016f }
    private fun tp(size: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // Sharp shadow for high visibility against any background
        setShadowLayer(0.8f, 0.5f, 0.5f, Color.BLACK)
        if (bold) {
            style = Paint.Style.FILL
        }
    }
    private fun trunc(text: String, maxW: Float, paint: TextPaint): String = TextUtils.ellipsize(text, paint, maxW.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
}
