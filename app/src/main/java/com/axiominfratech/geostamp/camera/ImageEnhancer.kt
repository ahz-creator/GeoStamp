package com.axiominfratech.geostamp.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * ImageEnhancer — full portrait pipeline.
 *
 * Stage 1  Global tone        — exposure lift, highlight ceiling, shadow recovery, green cast
 * Stage 2  Face region lift   — bell-curve brightness boost on centre 50% (face zone)
 * Stage 3  Eye zone lift      — targeted ellipse on upper-centre 20% (eyes/brow)
 * Stage 4  Anti-roughness     — 4% base-frequency blend reduces micro-shadow harshness
 * Stage 5  Hair depth boost   — USM applied only to dark pixels (luma < 80)
 * Stage 6  Clarity USM        — threshold-guarded sharpening for edges, skips flat skin
 * Stage 7  Colour             — saturation + warmth via GPU ColorMatrix
 * Stage 8  Vignette           — radial edge darkening to focus eye on the face
 */
object ImageEnhancer {

    // ── Entry point ───────────────────────────────────────────────────────

    fun enhanceWithParams(src: Bitmap, p: AutoAnalyzer.EnhancementParams): Bitmap {
        var cur = src

        // 1. Global tone
        if (p.exposureLift != 0 || p.highlightCeiling < 255 ||
            p.shadowRecovery || p.removeGreenCast) {
            cur = step(cur, src) { applyGlobalTone(it, p) }
        }

        // 2. Face region lift
        if (p.needsFaceBoost && p.faceRegionLift > 0) {
            cur = step(cur, src) { applyBellLift(it, cx=0.50f, cy=0.50f, rx=0.50f, ry=0.50f, lift=p.faceRegionLift) }
        }

        // 3. Eye zone lift — smaller ellipse, upper face
        if (p.eyeZoneLift > 0) {
            cur = step(cur, src) { applyBellLift(it, cx=0.50f, cy=0.32f, rx=0.32f, ry=0.14f, lift=p.eyeZoneLift) }
        }

        // 4. Anti-roughness
        if (p.antiRoughness) {
            cur = step(cur, src) { antiRoughnessMicroBlend(it) }
        }

        // 5. Hair depth boost
        if (p.hairDepthBoost) {
            cur = step(cur, src) { applyHairDepthBoost(it) }
        }

        // 6. Clarity USM
        if (p.sharpness > 0.01f) {
            cur = step(cur, src) { clarityUSM(it, p.sharpness) }
        }

        // 7. Colour
        if (p.saturation != 1.0f || p.warmthR != 0 || p.warmthB != 0) {
            cur = step(cur, src) { applyColour(it, p.saturation, p.warmthR, p.warmthB) }
        }

        // 8. Vignette — always last so it sits on top of all brightness changes
        if (p.applyVignette) {
            cur = step(cur, src) { applyVignette(it, p.vignetteStrength) }
        }

        return cur
    }

    fun enhance(src: Bitmap): Bitmap {
        val sharp  = clarityUSM(src, 0.15f)
        val result = applyColour(sharp, 1.03f, 0, 0)
        if (result !== sharp) sharp.recycle()
        return result
    }

    fun previewColourMatrix(): ColorMatrix = ColorMatrix().also { it.setSaturation(1.01f) }

    // ── Helper: safe recycle ──────────────────────────────────────────────

    private inline fun step(cur: Bitmap, src: Bitmap, block: (Bitmap) -> Bitmap): Bitmap {
        val result = block(cur)
        if (result !== cur && cur !== src) cur.recycle()
        return result
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stage 1 — Global tone LUT
    // ─────────────────────────────────────────────────────────────────────

    private fun applyGlobalTone(src: Bitmap, p: AutoAnalyzer.EnhancementParams): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val lut = IntArray(256) { i ->
            var v = i.toFloat()
            if (p.shadowRecovery && v < 80f) { val t = v/80f; v += 14f*t*(1f-t)*4f }
            v += p.exposureLift
            if (p.highlightCeiling < 255 && v > p.highlightCeiling) {
                val e = (v - p.highlightCeiling) / (255f - p.highlightCeiling)
                v = p.highlightCeiling + (255f - p.highlightCeiling) *
                    Math.sqrt(e.toDouble()).toFloat() * 0.55f
            }
            v.roundToInt().coerceIn(0, 255)
        }
        val gOff = if (p.removeGreenCast) -3 else 0
        for (i in px.indices) {
            val c = px[i]
            px[i] = (0xFF shl 24) or
                    (lut[(c shr 16) and 0xFF] shl 16) or
                    ((lut[(c shr 8) and 0xFF] + gOff).coerceIn(0,255) shl 8) or
                     lut[c and 0xFF]
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stage 2 & 3 — Bell-curve lift (face region + eye zone)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Adds [lift] brightness within an ellipse defined by normalised
     * centre (cx, cy) and radii (rx, ry).  Weight = cos²(πd) where d is
     * the normalised distance from centre — peak at centre, zero at edge.
     * No hard boundary visible.
     *
     * Used for both the face-region lift (large ellipse) and the eye-zone
     * lift (small upper ellipse).
     */
    private fun applyBellLift(src: Bitmap, cx: Float, cy: Float,
                               rx: Float, ry: Float, lift: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val cxPx = cx * w; val cyPx = cy * h
        val rxPx = rx * w; val ryPx = ry * h

        for (y in 0 until h) {
            val dy = (y - cyPx) / ryPx
            if (dy < -1f || dy > 1f) continue
            val wy = cosWeight(dy)
            val rowOff = y * w
            for (x in 0 until w) {
                val dx = (x - cxPx) / rxPx
                if (dx < -1f || dx > 1f) continue
                val scaledLift = (lift * wy * cosWeight(dx)).roundToInt()
                if (scaledLift == 0) continue
                val c = px[rowOff + x]
                val r = (((c shr 16) and 0xFF) + scaledLift).coerceIn(0, 255)
                val g = (((c shr  8) and 0xFF) + scaledLift).coerceIn(0, 255)
                val b = ((c and 0xFF) + scaledLift).coerceIn(0, 255)
                px[rowOff + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    private fun cosWeight(t: Float): Float {
        val c = Math.cos(Math.PI * t).toFloat()
        return (c * c).coerceIn(0f, 1f)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stage 4 — Anti-roughness micro-blend (4%)
    // ─────────────────────────────────────────────────────────────────────

    private fun antiRoughnessMicroBlend(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val small    = Bitmap.createScaledBitmap(src, (w/16).coerceAtLeast(1), (h/16).coerceAtLeast(1), true)
        val blurFull = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        val sp = IntArray(w*h).also { src.getPixels(it,      0, w, 0, 0, w, h) }
        val bp = IntArray(w*h).also { blurFull.getPixels(it, 0, w, 0, 0, w, h) }
        blurFull.recycle()
        val keep = 0.96f; val blend = 0.04f
        val out = IntArray(w*h)
        for (i in sp.indices) {
            val s = sp[i]; val b = bp[i]
            fun ch(si: Int, bi: Int) = (si*keep + bi*blend).roundToInt().coerceIn(0,255)
            val r  = ch((s shr 16) and 0xFF, (b shr 16) and 0xFF)
            val g  = ch((s shr  8) and 0xFF, (b shr  8) and 0xFF)
            val bl = ch( s         and 0xFF,  b         and 0xFF)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stage 5 — Hair depth boost
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Unsharp mask applied ONLY to pixels where luma < 80.
     * This sharpens hair strands and beard without touching skin (luma > 80).
     * Amount 0.30 — stronger than the general USM because we are targeting
     * a very specific tonal range where contrast is safe to boost.
     */
    private fun applyHairDepthBoost(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val small    = Bitmap.createScaledBitmap(src, (w/4).coerceAtLeast(1), (h/4).coerceAtLeast(1), true)
        val blurFull = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        val sp = IntArray(w*h).also { src.getPixels(it,      0, w, 0, 0, w, h) }
        val bp = IntArray(w*h).also { blurFull.getPixels(it, 0, w, 0, 0, w, h) }
        blurFull.recycle()
        val amount = 0.30f
        val out = IntArray(w*h)
        for (i in sp.indices) {
            val s = sp[i]; val b = bp[i]
            val sr = (s shr 16) and 0xFF
            val sg = (s shr  8) and 0xFF
            val sb =  s         and 0xFF
            // Luma of this pixel
            val luma = (0.299f*sr + 0.587f*sg + 0.114f*sb).roundToInt()
            if (luma >= 80) {
                // Bright pixel (skin, shirt, background) — pass through untouched
                out[i] = s
            } else {
                // Dark pixel (hair, beard) — apply USM
                fun ch(si: Int, bi: Int): Int {
                    val d = si - bi
                    return if (d > -5 && d < 5) si
                    else (si + amount * d).roundToInt().coerceIn(0, 255)
                }
                val r  = ch(sr,                    (b shr 16) and 0xFF)
                val g  = ch(sg,                    (b shr  8) and 0xFF)
                val bl = ch(sb,                     b         and 0xFF)
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stage 6 — Clarity USM (edges only, threshold 8 protects skin)
    // ─────────────────────────────────────────────────────────────────────

    private fun clarityUSM(src: Bitmap, amount: Float): Bitmap {
        val w = src.width; val h = src.height
        val small    = Bitmap.createScaledBitmap(src, (w/4).coerceAtLeast(1), (h/4).coerceAtLeast(1), true)
        val blurFull = Bitmap.createScaledBitmap(small, w, h, true)
        small.recycle()
        val sp = IntArray(w*h).also { src.getPixels(it,      0, w, 0, 0, w, h) }
        val bp = IntArray(w*h).also { blurFull.getPixels(it, 0, w, 0, 0, w, h) }
        blurFull.recycle()
        val threshold = 8
        val out = IntArray(w*h)
        for (i in sp.indices) {
            val s = sp[i]; val b = bp[i]
            fun ch(si: Int, bi: Int): Int {
                val d = si - bi
                return if (d > -threshold && d < threshold) si
                else (si + amount*d).roundToInt().coerceIn(0, 255)
            }
            val r  = ch((s shr 16) and 0xFF, (b shr 16) and 0xFF)
            val g  = ch((s shr  8) and 0xFF, (b shr  8) and 0xFF)
            val bl = ch( s         and 0xFF,  b         and 0xFF)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(out, 0, w, 0, 0, w, h) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stage 7 — Colour
    // ─────────────────────────────────────────────────────────────────────

    private fun applyColour(src: Bitmap, saturation: Float, warmthR: Int, warmthB: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val m   = ColorMatrix().also { it.setSaturation(saturation) }
        if (warmthR != 0 || warmthB != 0) {
            m.postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, warmthR.toFloat(),
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, warmthB.toFloat(),
                0f, 0f, 0f, 1f, 0f
            )))
        }
        Canvas(out).drawBitmap(src, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(m)
            })
        return out
    }

    // ─────────────────────────────────────────────────────────────────────
    // Stage 8 — Vignette
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Radial edge darkening using a cos² falloff from centre.
     *
     * vignetteWeight(x,y) = 1 − strength × (1 − cos²(π·d/2))
     * where d = distance from centre normalised 0..1 (1 = corner).
     *
     * d is computed as the elliptical radius so the vignette is
     * circular rather than rectangular — no squared-off dark corners.
     *
     * [strength] 0.18 → edges are 18% darker than centre.
     * Applied last so it sits on top of all brightness adjustments.
     */
    private fun applyVignette(src: Bitmap, strength: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val cx = w / 2f; val cy = h / 2f
        // Use the longer half-dimension so the falloff covers corners
        val maxR = Math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()

        for (y in 0 until h) {
            val dy = (y - cy)
            val rowOff = y * w
            for (x in 0 until w) {
                val dx = (x - cx)
                // Normalised elliptical radius 0..1 (0=centre, 1=corner)
                val d = Math.sqrt((dx*dx + dy*dy).toDouble()).toFloat() / maxR
                // Smooth falloff: 0 at centre, strength at edge
                val darken = strength * (1f - Math.cos(Math.PI * d / 2).toFloat().let { it * it })
                val factor = (1f - darken).coerceIn(0.75f, 1f)

                val c = px[rowOff + x]
                val r  = ((c shr 16) and 0xFF) * factor
                val g  = ((c shr  8) and 0xFF) * factor
                val b  = ( c         and 0xFF) * factor
                px[rowOff + x] = (0xFF shl 24) or
                        (r.roundToInt().coerceIn(0,255) shl 16) or
                        (g.roundToInt().coerceIn(0,255) shl  8) or
                         b.roundToInt().coerceIn(0,255)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }
}
