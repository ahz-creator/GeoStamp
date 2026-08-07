from pathlib import Path

root = Path(__file__).resolve().parents[1]

# 1) Add bundled image labeling + ML Kit object detector dependencies.
gradle = root / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
needle = "    implementation 'com.google.zxing:core:3.5.3'\n"
addition = """    implementation 'com.google.zxing:core:3.5.3'\n\n    // Free on-device AI object counting. No per-photo cloud/API quota.\n    implementation 'com.google.mlkit:object-detection:17.0.2'\n    implementation 'com.google.mlkit:image-labeling:17.0.9'\n"""
if "com.google.mlkit:object-detection" not in s:
    s = s.replace(needle, addition)
gradle.write_text(s, encoding='utf-8')

# 2) Replace cloud AI client internals with on-device ML Kit detection + crop labeling.
client = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/AiVisualSummaryClient.kt'
client.write_text(r'''package com.axiominfratech.geostamp.verification

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
''', encoding='utf-8')

# 3) Put object count in the mobile evidence receipt exactly once.
verify = root / 'app/src/main/java/com/axiominfratech/geostamp/ui/VerifyEvidenceActivity.kt'
s = verify.read_text(encoding='utf-8')
old = '''            append("GPS  •  $location\\n")\n            append("DEVICE  •  $device · $maskedDevice")'''
new = '''            append("GPS  •  $location\\n")\n            append("DEVICE  •  $device · $maskedDevice")\n            val aiObjects = firstNonBlank(\n                record.optString("aiObjectCountSummary"),\n                record.optString("aiVisualSummary")\n            )\n            if (aiObjects.isNotBlank()) append("\\nAI OBJECTS  •  $aiObjects")'''
if old in s:
    s = s.replace(old, new, 1)

# Merge AI fields from local record when a remote response temporarily lacks them.
needle = '            "operatorSessionClockOutReason", "siteDistanceM", "siteRadiusM"\n'
replacement = '            "operatorSessionClockOutReason", "siteDistanceM", "siteRadiusM",\n            "aiObjectCountSummary", "aiVisualSummary", "aiVisualSummaryStatus", "aiVisualSummaryProvider"\n'
if needle in s:
    s = s.replace(needle, replacement, 1)
verify.write_text(s, encoding='utf-8')

# 4) Ensure PDF uses the field and always shows an AI Object Count line for new records.
pdf = root / 'app/src/main/java/com/axiominfratech/geostamp/verification/EvidencePdfExporter.kt'
s = pdf.read_text(encoding='utf-8')
# Normalize current/fallback field selection.
s = s.replace(
    'val ai = r.optString("aiVisualSummary").trim()',
    'val ai = first(r.optString("aiObjectCountSummary"), r.optString("aiVisualSummary"), "").takeIf { it != "Unavailable" }.orEmpty().trim()'
)
s = s.replace('AI VISUAL SUMMARY', 'AI OBJECT COUNT')
s = s.replace('AI description only · excluded from PASS/FAIL authentication', 'On-device object detection/count · excluded from PASS/FAIL authentication')
# If prior script added purpose rendering, suppress it.
s = s.replace('val purpose = first(r.optString("aiVisualPurpose"), r.optString("aiLikelyPurpose"), "").takeIf { it != "Unavailable" }.orEmpty().trim()', 'val purpose = ""')
pdf.write_text(s, encoding='utf-8')

print('Applied free on-device AI object count to capture metadata, mobile receipt, and forensic PDF.')
print('Note: ML Kit default object detector can detect up to five objects in one image; results are assistive, not forensic PASS/FAIL evidence.')
