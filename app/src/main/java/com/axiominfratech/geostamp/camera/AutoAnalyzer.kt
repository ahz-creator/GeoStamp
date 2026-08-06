package com.axiominfratech.geostamp.camera

import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * AutoAnalyzer — on-device histogram + face-region analysis.
 * Free, unlimited, ~8 ms on 12 MP. No API calls, works offline.
 */
object AutoAnalyzer {

    data class EnhancementParams(
        // ── Global tone ───────────────────────────────────────────────────
        val exposureLift: Int        = 0,
        val highlightCeiling: Int    = 255,
        val sharpness: Float         = 0.15f,
        val saturation: Float        = 1.03f,
        val warmthR: Int             = 0,
        val warmthB: Int             = 0,
        val shadowRecovery: Boolean  = false,
        val removeGreenCast: Boolean = false,
        // ── Portrait-specific ─────────────────────────────────────────────
        /** Bell-curve brightness lift applied to centre 50% (face zone). */
        val faceRegionLift: Int      = 0,
        val needsFaceBoost: Boolean  = false,
        /** Reduce micro-shadow harshness via 4% base-frequency blend. */
        val antiRoughness: Boolean   = false,
        // ── New: four requested improvements ─────────────────────────────
        /** Darken edges by vignetteStrength (0.0–0.25). Always true for portraits. */
        val applyVignette: Boolean   = true,
        /** 0.0–0.25 — how much to darken the very edge corners. */
        val vignetteStrength: Float  = 0.18f,
        /** Extra brightness for the eye-region ellipse (upper-centre face). */
        val eyeZoneLift: Int         = 0,
        /** Apply micro-contrast only to dark pixels (luma < 80) for hair depth. */
        val hairDepthBoost: Boolean  = false
    )

    fun analyze(bitmap: Bitmap): EnhancementParams {
        val w = bitmap.width
        val h = bitmap.height
        val step = 4

        // ── 1. Full-image histogram ───────────────────────────────────────
        val histR = IntArray(256); val histG = IntArray(256); val histB = IntArray(256)
        var total = 0L
        val rowBuf = IntArray(w)
        for (y in 0 until h step step) {
            bitmap.getPixels(rowBuf, 0, w, 0, y, w, 1)
            for (x in 0 until w step step) {
                val px = rowBuf[x]
                histR[(px shr 16) and 0xFF]++
                histG[(px shr  8) and 0xFF]++
                histB[ px         and 0xFF]++
                total++
            }
        }
        if (total == 0L) return EnhancementParams()

        var sumR = 0L; var sumG = 0L; var sumB = 0L
        for (i in 0..255) { sumR += i*histR[i]; sumG += i*histG[i]; sumB += i*histB[i] }
        val meanR = (sumR / total).toInt()
        val meanG = (sumG / total).toInt()
        val meanB = (sumB / total).toInt()
        val meanLuma = (0.299f*meanR + 0.587f*meanG + 0.114f*meanB).roundToInt()

        // ── 2. Face-region luma (centre 50%) ─────────────────────────────
        val faceX = w / 4; val faceY = h / 4; val faceW = w / 2; val faceH = h / 2
        var faceLumaSum = 0L; var faceSamples = 0L
        val faceRow = IntArray(faceW)
        for (y in faceY until (faceY + faceH) step step) {
            bitmap.getPixels(faceRow, 0, faceW, faceX, y, faceW, 1)
            for (x in 0 until faceW step step) {
                val px = faceRow[x]
                faceLumaSum += (0.299f*((px shr 16) and 0xFF) +
                        0.587f*((px shr  8) and 0xFF) +
                        0.114f* (px         and 0xFF)).roundToInt()
                faceSamples++
            }
        }
        val faceLuma = if (faceSamples > 0) (faceLumaSum / faceSamples).toInt() else meanLuma

        val needsFaceBoost = (meanLuma - faceLuma) > 12 || faceLuma < 95
        val faceRegionLift = when {
            faceLuma < 75  -> 28
            faceLuma < 90  -> 20
            faceLuma < 105 -> 14
            faceLuma < 118 -> 8
            else           -> 0
        }

        // ── 3. Eye zone luma (upper-centre ~25% strip) ───────────────────
        // Eye region: x = centre 50%, y = top 25%..45% of image
        val eyeX = w / 4; val eyeY = (h * 0.25f).toInt(); val eyeW = w / 2
        val eyeH = (h * 0.20f).toInt().coerceAtLeast(1)
        var eyeLumaSum = 0L; var eyeSamples = 0L
        val eyeRow = IntArray(eyeW)
        for (y in eyeY until (eyeY + eyeH) step step) {
            bitmap.getPixels(eyeRow, 0, eyeW, eyeX, y, eyeW, 1)
            for (x in 0 until eyeW step step) {
                val px = eyeRow[x]
                eyeLumaSum += (0.299f*((px shr 16) and 0xFF) +
                        0.587f*((px shr  8) and 0xFF) +
                        0.114f* (px         and 0xFF)).roundToInt()
                eyeSamples++
            }
        }
        val eyeLuma = if (eyeSamples > 0) (eyeLumaSum / eyeSamples).toInt() else faceLuma
        // Eye zone lift: independent of face lift — adds on top of it
        val eyeZoneLift = when {
            eyeLuma < 80  -> 16
            eyeLuma < 95  -> 12
            eyeLuma < 110 -> 8
            eyeLuma < 125 -> 4
            else          -> 0
        }

        // ── 4. Hair depth — dark pixel percentage ─────────────────────────
        // If > 8% of pixels are very dark (hair/beard), benefit from
        // micro-contrast boost in that luma range.
        var darkCount = 0L
        for (i in 0..80) darkCount += (histR[i] + histG[i] + histB[i]).toLong() / 3
        val hairDepthBoost = darkCount.toFloat() / total > 0.08f

        // ── 5. Global exposure ────────────────────────────────────────────
        val targetLuma = 118
        val exposureLift = when {
            meanLuma < 90  -> ((targetLuma - meanLuma) * 0.55f).roundToInt().coerceIn(6, 25)
            meanLuma < 105 -> ((targetLuma - meanLuma) * 0.40f).roundToInt().coerceIn(2, 12)
            meanLuma > 165 -> ((targetLuma - meanLuma) * 0.40f).roundToInt().coerceIn(-18, -5)
            meanLuma > 148 -> ((targetLuma - meanLuma) * 0.30f).roundToInt().coerceIn(-10, -2)
            else           -> 0
        }

        // ── 6. Highlights ─────────────────────────────────────────────────
        var clipped = 0L
        for (i in 242..255) clipped += maxOf(histR[i], histG[i], histB[i]).toLong()
        val clippedPct = clipped.toFloat() / total
        val highlightCeiling = when {
            clippedPct > 0.06f -> 225
            clippedPct > 0.03f -> 235
            clippedPct > 0.01f -> 245
            else               -> 255
        }

        // ── 7. Shadows ────────────────────────────────────────────────────
        var shadows = 0L
        for (i in 0..45) shadows += (histR[i] + histG[i] + histB[i]).toLong() / 3
        val shadowRecovery = shadows.toFloat() / total > 0.14f

        // ── 8. Colour cast ────────────────────────────────────────────────
        val rgbAvg = (meanR + meanG + meanB) / 3
        val removeGreenCast = (meanG - rgbAvg) > 7
        val (warmthR, warmthB) = when {
            removeGreenCast      -> Pair(0, 0)
            meanR - meanB < -18  -> Pair(3, -2)
            meanR - meanB >  28  -> Pair(-2, 2)
            meanR - meanB >  18  -> Pair(-1, 1)
            else                 -> Pair(0, 0)
        }

        // ── 9. Saturation ─────────────────────────────────────────────────
        val rDev = (meanR - meanLuma).toFloat()
        val gDev = (meanG - meanLuma).toFloat()
        val bDev = (meanB - meanLuma).toFloat()
        val colourfulness = sqrt((rDev*rDev + gDev*gDev + bDev*bDev) / 3f)
        val saturation = when {
            colourfulness < 12f -> 1.10f
            colourfulness < 20f -> 1.06f
            colourfulness > 55f -> 1.01f
            else                -> 1.04f
        }

        // ── 10. Sharpness ─────────────────────────────────────────────────
        val sharpness = estimateSharpness(bitmap, w, h)

        // ── 11. Vignette strength ─────────────────────────────────────────
        // Brighter backgrounds need stronger vignette to focus on the face.
        val bgBrightness = (meanLuma - faceLuma).coerceAtLeast(0)
        val vignetteStrength = when {
            bgBrightness > 30 -> 0.22f
            bgBrightness > 15 -> 0.18f
            else              -> 0.14f
        }

        return EnhancementParams(
            exposureLift     = exposureLift,
            highlightCeiling = highlightCeiling,
            sharpness        = sharpness,
            saturation       = saturation,
            warmthR          = warmthR,
            warmthB          = warmthB,
            shadowRecovery   = shadowRecovery,
            removeGreenCast  = removeGreenCast,
            faceRegionLift   = faceRegionLift,
            needsFaceBoost   = needsFaceBoost,
            antiRoughness    = faceLuma < 120,
            applyVignette    = true,
            vignetteStrength = vignetteStrength,
            eyeZoneLift      = eyeZoneLift,
            hairDepthBoost   = hairDepthBoost
        )
    }

    private fun estimateSharpness(bitmap: Bitmap, w: Int, h: Int): Float {
        val pw = 64.coerceAtMost(w); val ph = 64.coerceAtMost(h)
        val px = IntArray(pw * ph)
        bitmap.getPixels(px, 0, pw, (w-pw)/2, (h-ph)/2, pw, ph)
        var sumL = 0L; var sumL2 = 0L
        for (p in px) {
            val l = (0.299f*((p shr 16) and 0xFF) + 0.587f*((p shr 8) and 0xFF) +
                    0.114f*(p and 0xFF)).roundToInt()
            sumL += l; sumL2 += l.toLong()*l
        }
        val n = px.size.toLong()
        val variance = sumL2.toFloat()/n - (sumL.toFloat()/n).let { it*it }
        return when {
            variance > 1200f -> 0.10f
            variance > 700f  -> 0.13f
            variance > 350f  -> 0.17f
            variance > 150f  -> 0.22f
            else             -> 0.26f
        }
    }
}
