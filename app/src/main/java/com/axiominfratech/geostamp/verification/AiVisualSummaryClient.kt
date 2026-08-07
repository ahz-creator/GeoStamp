package com.axiominfratech.geostamp.verification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Free on-device visual helper.
 *
 * It detects visible object regions and labels each region locally on the phone.
 * It never affects cryptographic verification PASS/FAIL results.
 * No cloud API key and no per-photo API quota are used.
 */
object AiVisualSummaryClient {

    data class Summary(
        val summary: String,
        val purpose: String = "",
        val status: String,
        val provider: String = "ML Kit On-device"
    )

    fun analyze(
        context: Context,
        thumbnailBase64: String,
        operator: String,
        siteId: String,
        capturedAt: Long
    ): Summary {
        if (thumbnailBase64.isBlank()) return Summary("", status = "NO_VISUAL")

        return runCatching {
            val raw = if (thumbnailBase64.contains("base64,")) {
                thumbnailBase64.substringAfter("base64,")
            } else thumbnailBase64
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Summary("", status = "DECODE_FAILED")

            val detectorOptions = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()
            val detector = ObjectDetection.getClient(detectorOptions)

            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.55f)
                    .build()
            )

            try {
                val objects = Tasks.await(
                    detector.process(InputImage.fromBitmap(bitmap, 0)),
                    20, TimeUnit.SECONDS
                )

                if (objects.isEmpty()) {
                    return Summary("No reliably countable object detected", status = "NO_OBJECTS")
                }

                val counts = linkedMapOf<String, Int>()
                objects.forEach { obj ->
                    val box = obj.boundingBox
                    val left = box.left.coerceIn(0, bitmap.width - 1)
                    val top = box.top.coerceIn(0, bitmap.height - 1)
                    val right = box.right.coerceIn(left + 1, bitmap.width)
                    val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
                    if (right <= left || bottom <= top) return@forEach

                    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    try {
                        val labels = Tasks.await(
                            labeler.process(InputImage.fromBitmap(crop, 0)),
                            15, TimeUnit.SECONDS
                        )
                        val label = labels
                            .filter { it.confidence >= 0.55f }
                            .sortedByDescending { it.confidence }
                            .map { normalizeLabel(it.text) }
                            .firstOrNull { isUsefulLabel(it) }
                            ?: "Object"
                        counts[label] = (counts[label] ?: 0) + 1
                    } finally {
                        if (crop !== bitmap && !crop.isRecycled) crop.recycle()
                    }
                }

                val useful = counts.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .joinToString(" · ") { "${it.key}: ${it.value}" }

                Summary(
                    summary = useful.ifBlank { "No reliably countable object detected" },
                    status = if (useful.isBlank()) "NO_OBJECTS" else "GENERATED"
                )
            } finally {
                detector.close()
                labeler.close()
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }.getOrElse {
            Summary("", status = "FAILED")
        }
    }

    private fun normalizeLabel(value: String): String {
        return value.trim().lowercase(Locale.US)
            .split(' ', '-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> token.replaceFirstChar { c -> c.titlecase(Locale.US) } }
    }

    private fun isUsefulLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val generic = setOf(
            "Image", "Photography", "Photograph", "Snapshot", "Room", "Indoor",
            "Outdoor", "Property", "Material", "Pattern", "Design", "Event"
        )
        return label !in generic
    }
}
