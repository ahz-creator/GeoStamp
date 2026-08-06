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
 * GeoStamp Enterprise Overlay Renderer — v11.1 "Enterprise Precision"
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
        val evidenceStatus: String = "VERIFIED"
    )

    fun render(source: Bitmap, data: OverlayData, context: Context): Bitmap {
        initLogos(context)
        val out    = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val isPortrait = h > w
        
        drawMasterBanner(canvas, data, w, h, isPortrait)
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

    private fun drawMasterBanner(cv: Canvas, data: OverlayData, w: Float, h: Float, isPortrait: Boolean) {
        val safeScale = data.overlayScale.coerceIn(0.8f, 1.2f)
        val r = ref(w, h) * safeScale

        // Landscape safe scaling
        val landscapeScale = if (!isPortrait) 0.96f else 1f

        cv.save()

        cv.scale(
            landscapeScale,
            landscapeScale,
            w / 2f,
            h / 2f
        )
        val margin = r * 0.025f
        val radius = r * 0.015f

        // Preserve the photograph: the saved evidence panel is always 20–30% of image height.
        val requestedFraction = data.savedOverlayHeightFraction.coerceIn(0.20f, 0.30f)
        val panelH = (h * requestedFraction).coerceIn(h * 0.20f, h * 0.30f)
        val rect = RectF(margin, h - margin - panelH, w - margin, h - margin)
        
        val bgAlpha = (data.backgroundAlpha * 255).toInt().coerceIn(0, 255)
        
        // Premium Glassmorphism Background with Gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(Color.argb(bgAlpha, 40, 40, 40), Color.argb(bgAlpha, 5, 5, 5)),
                null, Shader.TileMode.CLAMP)
        }
        cv.drawRoundRect(rect, radius, radius, bgPaint)
        
        // Clean high-contrast border
        cv.drawRoundRect(rect, radius, radius, strokePaint(Color.WHITE, r * 0.005f))
        
        val contentT = rect.top + r * 0.025f
        val contentB = rect.bottom - r * 0.02f
        val contentH = contentB - contentT

        // 4-row layout: header + row1 + row2 + footer
        val headerH = contentH * 0.19f
        val row1H   = contentH * 0.32f
        val row2H   = contentH * 0.31f   // extra height for 2-line status cells
        val row3H   = contentH * 0.18f

        // Row 1 starts below the header + a thin gap for the separator
        val row1T = contentT + headerH + r * 0.008f

        // QR size fixed at r*0.19f for both orientations — decoupled from row heights
        val qrSize = minOf(r * 0.155f, panelH * 0.64f)
        val availableW = rect.width() - qrSize - r * 0.09f

        // Split dateTimeLine: "Date|Day|Time"
        val parts = data.dateTimeLine.split("|")
        val dateStr = parts.getOrNull(0) ?: ""
        val dayStr = parts.getOrNull(1) ?: ""
        val timeStr = parts.getOrNull(2) ?: ""

        // ── HEADER ROW (Operator logo │ SITE ID │ User) ──────────────────
        drawHeader(cv, data, rect, contentT, headerH, r)
        cv.drawLine(rect.left + r * 0.02f, row1T - r * 0.006f,
                    rect.right - r * 0.02f, row1T - r * 0.006f, dividerPaint(r))

        // ── ROW 1 columns — 4 columns in both orientations (no altitude) ─
        var curX = rect.left + r * 0.02f   // 0.02 so PIN icon (x+0.01) aligns with logo (x=r*0.03)
        if (isPortrait) {
            val w1 = availableW * 0.36f
            val w2 = availableW * 0.22f
            val w3 = availableW * 0.26f
            val w4 = availableW * 0.16f
            drawCol(cv, formatCoords(data.locationLine), data.addressLine, "PIN",    curX, row1T, w1, row1H, r); curX += w1; drawDiv(cv, curX, row1T, row1H, r)
            drawCol(cv, timeStr, "",                                        "CLOCK",  curX, row1T, w2, row1H, r); curX += w2; drawDiv(cv, curX, row1T, row1H, r)
            drawCol(cv, dateStr, dayStr,                                    "CAL",    curX, row1T, w3, row1H, r); curX += w3; drawDiv(cv, curX, row1T, row1H, r)
            drawCol(cv, data.accuracyLine, "Accuracy",                      "TARGET", curX, row1T, w4, row1H, r)
        } else {
            val w1 = availableW * 0.33f   // slightly wider GPS col in landscape
            val w2 = availableW * 0.22f
            val w3 = availableW * 0.28f
            val w4 = availableW * 0.17f
            drawCol(cv, formatCoords(data.locationLine), data.addressLine, "PIN",    curX, row1T, w1, row1H, r); curX += w1; drawDiv(cv, curX, row1T, row1H, r)
            drawCol(cv, timeStr, "",                                        "CLOCK",  curX, row1T, w2, row1H, r); curX += w2; drawDiv(cv, curX, row1T, row1H, r)
            drawCol(cv, dateStr, dayStr,                                    "CAL",    curX, row1T, w3, row1H, r); curX += w3; drawDiv(cv, curX, row1T, row1H, r)
            drawCol(cv, data.accuracyLine, "Accuracy",                      "TARGET", curX, row1T, w4, row1H, r)
        }
        
        // ROW 2
        val row2T = row1T + row1H + r * 0.01f
        cv.drawLine(rect.left + r*0.02f, row2T, rect.right - qrSize - r*0.08f, row2T, dividerPaint(r))
        
        var r2X = rect.left + r * 0.02f
        // Column widths sized to content — VERIFIED needs most space for the full ID text.
        // Portrait has less horizontal room so VERIFIED gets a larger share (38%).
        // Landscape has more room so other columns are a bit wider too.
        val (vW, camW, gpsW, wthW, cmpW) = if (isPortrait) {
            listOf(availableW*0.38f, availableW*0.18f, availableW*0.20f, availableW*0.13f, availableW*0.11f)
        } else {
            listOf(availableW*0.30f, availableW*0.20f, availableW*0.22f, availableW*0.15f, availableW*0.13f)
        }

        val statusColor = if (data.locationIntegrityRisk) Color.parseColor("#FF5252") else Color.parseColor("#2ECC71")
        drawStatus(cv, "SHIELD", data.evidenceStatus, "ID: ${shortVerifyId(data)}", statusColor, r2X, row2T, vW, row2H, r); r2X += vW;   drawDiv(cv, r2X, row2T, row2H, r)
        drawStatus(cv, "CAMERA",  data.cameraType,  "Camera",  Color.WHITE, r2X, row2T, camW, row2H, r); r2X += camW; drawDiv(cv, r2X, row2T, row2H, r)
        drawStatus(cv, "LOCK",    if (data.locationIntegrityRisk) "Location Risk" else "GPS Accepted", if (data.locationIntegrityRisk) "Review" else "Recorded", if (data.locationIntegrityRisk) Color.parseColor("#FF5252") else Color.WHITE, r2X, row2T, gpsW, row2H, r); r2X += gpsW; drawDiv(cv, r2X, row2T, row2H, r)
        drawStatus(cv, "WEATHER", data.weatherTemp,  data.weatherHaze, Color.WHITE, r2X, row2T, wthW, row2H, r); r2X += wthW; drawDiv(cv, r2X, row2T, row2H, r)
        val dirParts = data.directionLine.split(" ")
        drawStatus(cv, "COMPASS", dirParts.getOrNull(0) ?: "–", dirParts.getOrNull(1) ?: "", Color.WHITE, r2X, row2T, cmpW, row2H, r)
        
        // QR CODE — vertically centred across row1 + row2
        drawQR(cv, data, rect.right - qrSize - r*0.025f, row1T + (row1H + row2H - qrSize) / 2f, qrSize)
        
        // FOOTER
        drawFooter(cv, data, rect, contentB - row3H, row3H, r)
        cv.restore()
    }

    /**
     * Header row: [Operator logo] │ [SITE ID label / value] │ [Person icon / User label / username]
     * Spans the full panel width (QR is only shown in row1+row2, not the header).
     */
    private fun drawHeader(cv: Canvas, data: OverlayData, rect: RectF, y: Float, h: Float, r: Float) {
        val centerY = y + h / 2f
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
        val labelP = tp(r * 0.017f, Color.LTGRAY)
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
        val userLabelP = tp(r * 0.017f, Color.LTGRAY)
        val userNameP  = tp(r * 0.022f, Color.WHITE, bold = true)
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
