package com.axiominfratech.geostamp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.axiominfratech.geostamp.databinding.ActivityVerifyEvidenceBinding
import com.axiominfratech.geostamp.verification.EvidencePdfExporter
import com.axiominfratech.geostamp.verification.EvidenceRegistryOutbox
import com.axiominfratech.geostamp.verification.EvidenceVisualCache
import com.axiominfratech.geostamp.verification.RegistryPublisher
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VerifyEvidenceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerifyEvidenceBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val decoding = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentVerificationId: String? = null
    private var currentRecord: JSONObject? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startQrScanner() else showError("Camera permission is required to scan a QR code.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnScanQr.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startQrScanner()
            } else requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        binding.btnVerifyId.setOnClickListener {
            val id = binding.inputVerificationId.text?.toString()?.trim().orEmpty()
            if (id.isBlank()) showError("Enter an Evidence ID.") else verifyById(id)
        }
        binding.btnViewReport.setOnClickListener {
            val record = currentRecord
            if (record == null) {
                Toast.makeText(this, "Verify an evidence record first.", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnViewReport.isEnabled = false
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = EvidencePdfExporter.exportAndShare(this@VerifyEvidenceActivity, record)
                    withContext(Dispatchers.Main) {
                        binding.btnViewReport.isEnabled = true
                        result.exceptionOrNull()?.let {
                            Toast.makeText(this@VerifyEvidenceActivity, "PDF share failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun startQrScanner() {
        hideKeyboard()
        clearMessages()
        binding.scannerContainer.visibility = View.VISIBLE
        binding.resultCard.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.scannerPreview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.TRY_HARDER to true)) }
            analysis.setAnalyzer(analysisExecutor) { image -> analyzeQr(image, reader) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeQr(image: ImageProxy, reader: MultiFormatReader) {
        if (!decoding.compareAndSet(false, true)) { image.close(); return }
        try {
            val luminance = extractLuminance(image)
            val rotated = rotateLuminance(luminance, image.width, image.height, image.imageInfo.rotationDegrees)
            val width = if (image.imageInfo.rotationDegrees % 180 == 0) image.width else image.height
            val height = if (image.imageInfo.rotationDegrees % 180 == 0) image.height else image.width
            val source = PlanarYUVLuminanceSource(rotated, width, height, 0, 0, width, height, false)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            runOnUiThread { handleQrPayload(result.text) }
        } catch (_: Exception) {
            reader.reset(); decoding.set(false)
        } finally { image.close() }
    }

    private fun handleQrPayload(raw: String) {
        cameraProvider?.unbindAll()
        binding.scannerContainer.visibility = View.GONE
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val idFromUrl = uri?.getQueryParameter("id")
        val embedded = uri?.getQueryParameter("e")
        when {
            !idFromUrl.isNullOrBlank() -> verifyById(idFromUrl)
            !embedded.isNullOrBlank() -> {
                val record = decodeEmbeddedRecord(embedded)
                val id = record?.optString("verificationId", record.optString("evidenceId"))
                if (record != null && !id.isNullOrBlank()) verifyEmbeddedAgainstRegistry(record, id)
                else showError("The GeoStamp QR payload could not be read.")
            }
            raw.startsWith("GST-", true) -> verifyById(raw)
            else -> showError("This QR is not a supported GeoStamp evidence code.")
        }
    }

    private fun verifyEmbeddedAgainstRegistry(embeddedRecord: JSONObject, id: String) {
        clearMessages()
        binding.progress.visibility = View.VISIBLE
        currentVerificationId = id.uppercase(Locale.US)
        lifecycleScope.launch {
            val publicRecord = withContext(Dispatchers.IO) {
                RegistryPublisher.lookup(this@VerifyEvidenceActivity, currentVerificationId.orEmpty())
            }
            binding.progress.visibility = View.GONE
            showRecord(mergeLocalVisualFields(publicRecord ?: embeddedRecord, id), publicRecord != null)
        }
    }

    private fun verifyById(input: String) {
        hideKeyboard()
        cameraProvider?.unbindAll()
        binding.scannerContainer.visibility = View.GONE
        clearMessages()
        binding.progress.visibility = View.VISIBLE
        binding.resultCard.visibility = View.GONE
        val id = input.trim().uppercase(Locale.US)
        binding.inputVerificationId.setText(id)
        currentVerificationId = id
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RegistryPublisher.lookup(this@VerifyEvidenceActivity, id) }
            binding.progress.visibility = View.GONE
            if (result != null) showRecord(mergeLocalVisualFields(result, id), true) else {
                val local = EvidenceRegistryOutbox.publishedRecord(this@VerifyEvidenceActivity, id)
                if (local != null) showRecord(local, true) else showNotRegistered(id)
            }
        }
    }

    private fun showRecord(record: JSONObject, registryBacked: Boolean) {
        currentRecord = record
        val risk = record.optBoolean("locationRisk", false) ||
            record.optBoolean("locationIntegrityRisk", false) || record.optInt("lr", 0) == 1
        val hasVisual = firstNonBlank(
            record.optString("thumbnailBase64"), record.optString("thumbnailJpegBase64"), record.optString("thumb")
        ).isNotBlank()
        binding.tvResultStatus.text = when {
            risk -> "REGISTERED · REVIEW REQUIRED"
            registryBacked && hasVisual -> "VERIFIED · REGISTERED"
            registryBacked -> "REGISTERED · VISUAL INCOMPLETE"
            else -> "QR RECORD FOUND"
        }
        binding.tvResultStatus.setTextColor(
            ContextCompat.getColor(this, if (risk) android.R.color.holo_orange_dark else android.R.color.holo_green_dark)
        )
        binding.tvResultSummary.text = when {
            risk -> "Registry record found; location-integrity signals require review."
            registryBacked && !hasVisual -> "Registry record found, but the mandatory evidence visual is unavailable."
            registryBacked -> "Evidence ID and mandatory visual evidence confirmed for this record."
            else -> "GeoStamp QR record decoded; public registration is not confirmed."
        }

        val id = firstNonBlank(record.optString("evidenceId"), record.optString("verificationId"), record.optString("id"), currentVerificationId.orEmpty())
        currentVerificationId = id
        val capturedAt = record.optLong("capturedAt", record.optLong("timestamp", record.optLong("ts", 0L)))
        val primary = firstNonBlank(record.optString("primaryValue"), record.optString("operator"), record.optString("p"))
        val secondary = firstNonBlank(record.optString("secondaryValue"), record.optString("siteId"), record.optString("s"))
        val lat = record.optDouble("latitude", record.optDouble("lat", Double.NaN))
        val lon = record.optDouble("longitude", record.optDouble("lon", Double.NaN))
        val accuracy = record.optDouble("accuracyM", record.optDouble("accuracy", record.optDouble("acc", Double.NaN)))
        val location = if (lat.isFinite() && lon.isFinite()) "%.6f, %.6f · ±%.1f m".format(Locale.US, lat, lon, accuracy) else "Unavailable"
        val device = firstNonBlank(
            listOf(record.optString("deviceManufacturer"), record.optString("deviceHardwareModel")).filter { it.isNotBlank() }.joinToString(" "),
            record.optString("deviceModel"),
            "Unavailable"
        )
        val maskedDevice = firstNonBlank(record.optString("maskedGeoStampDeviceIdentity"), "Unavailable")

        binding.tvResultDetails.text = buildString {
            append("EVIDENCE  •  $id\n")
            append("CAPTURED  •  ${formatTime(capturedAt)}\n")
            append("OPERATOR  •  $primary\n")
            append("SITE  •  $secondary\n")
            append("GPS  •  $location\n")
            append("DEVICE  •  $device · $maskedDevice")
            val aiObjects = firstNonBlank(
                record.optString("aiObjectCountSummary"),
                record.optString("aiVisualSummary")
            )
            if (aiObjects.isNotBlank()) append("\nAI OBJECTS  •  $aiObjects")
        }

        renderThumbnail(record)
        renderSessionActivity(record, capturedAt, secondary)
        binding.trustCard.visibility = View.GONE
        binding.timelineCard.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE
        binding.btnViewReport.visibility = if (hasVisual) View.VISIBLE else View.GONE
        binding.btnViewReport.text = "SHARE REPORT · PDF"
        binding.tvError.visibility = View.GONE
    }

    private fun renderThumbnail(record: JSONObject) {
        val raw = firstNonBlank(
            record.optString("thumbnailBase64"),
            record.optString("thumbnailJpegBase64"),
            record.optString("thumb")
        ).let { value -> if (value.contains("base64,")) value.substringAfter("base64,") else value }
        if (raw.isBlank()) {
            binding.ivEvidenceThumbnail.visibility = View.GONE
            binding.tvThumbnailUnavailable.visibility = View.VISIBLE
            return
        }
        runCatching {
            val bytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()?.let { bitmap ->
            binding.ivEvidenceThumbnail.setImageBitmap(bitmap)
            binding.ivEvidenceThumbnail.visibility = View.VISIBLE
            binding.tvThumbnailUnavailable.visibility = View.GONE
        } ?: run {
            binding.ivEvidenceThumbnail.visibility = View.GONE
            binding.tvThumbnailUnavailable.visibility = View.VISIBLE
        }
    }

    private fun renderSessionActivity(record: JSONObject, capturedAt: Long, siteId: String) {
        val sessionId = record.optString("operatorSessionId")
        val sessionStarted = record.optLong("operatorSessionStartedAt", 0L)
        if (sessionId.isBlank() && sessionStarted <= 0L) {
            binding.tvSessionActivity.visibility = View.GONE
            return
        }
        val beforeSite = record.optInt("sitePhotosBefore", record.optInt("photosBeforeAtSite", -1))
        val afterSite = record.optInt("sitePhotosAfter", record.optInt("photosAfterAtSite", -1))
        val totalSite = record.optInt("siteSessionPhotoTotal", record.optInt("sitePhotoTotal", -1))
        val totalSession = record.optInt("operatorSessionPhotoTotal", record.optInt("sessionPhotoTotal", -1))
        val sitesVisited = record.optInt("operatorSessionSitesVisited", record.optInt("sessionSitesVisited", -1))
        binding.tvSessionActivity.text = buildString {
            append("THIS FIELD SESSION\n")
            append("Clock-in ${formatTime(sessionStarted)}  •  Reference ${formatTime(capturedAt)}\n")
            append("At $siteId: ${displayCount(beforeSite)} before  •  ${displayCount(afterSite)} after  •  ${displayCount(totalSite)} total\n")
            append("Entire session: ${displayCount(totalSession)} photos  •  ${displayCount(sitesVisited)} sites")
        }
        binding.tvSessionActivity.visibility = View.VISIBLE
    }

    private fun mergeLocalVisualFields(remote: JSONObject, evidenceId: String): JSONObject {
        val published = EvidenceRegistryOutbox.publishedRecord(this, evidenceId)
        val visual = EvidenceVisualCache.loadOrRecover(this, evidenceId)
        val merged = JSONObject(remote.toString())
        val local = published ?: visual ?: return merged
        val keys = arrayOf(
            "thumbnailBase64", "thumbnailJpegBase64",
            "sitePhotosBefore", "sitePhotosAfter", "siteSessionPhotoTotal",
            "operatorSessionPhotoTotal", "operatorSessionSitesVisited",
            "operatorSessionStartedAt", "operatorSessionClockOutAt",
            "operatorSessionClockOutReason", "siteDistanceM", "siteRadiusM",
            "aiObjectCountSummary", "aiVisualSummary", "aiVisualSummaryStatus", "aiVisualSummaryProvider"
        )
        keys.forEach { key ->
            val remoteMissing = !merged.has(key) || merged.isNull(key) || merged.optString(key).isBlank()
            if (remoteMissing && local.has(key) && !local.isNull(key)) merged.put(key, local.opt(key))
        }
        if (merged.optString("thumbnailBase64").isBlank()) {
            val cachedVisual = visual ?: EvidenceVisualCache.loadOrRecover(this, evidenceId)
            val cachedThumb = cachedVisual?.optString("thumbnailBase64").orEmpty()
            if (cachedThumb.isNotBlank()) {
                merged.put("thumbnailBase64", cachedThumb)
                merged.put("thumbnailMimeType", "image/jpeg")
                if (cachedVisual?.has("thumbnailSha256") == true) merged.put("thumbnailSha256", cachedVisual.optString("thumbnailSha256"))
            }
        }
        return merged
    }

    private fun displayCount(value: Int): String = if (value >= 0) value.toString() else "Pending"

    private fun showNotRegistered(id: String) {
        currentRecord = null
        binding.tvResultStatus.text = "NOT REGISTERED"
        binding.tvResultStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        binding.tvResultSummary.text = "No public evidence record was found for this Evidence ID."
        binding.tvResultDetails.text = "EVIDENCE ID\n$id\n\nCheck the ID or scan the QR again."
        binding.ivEvidenceThumbnail.visibility = View.GONE
        binding.tvThumbnailUnavailable.visibility = View.VISIBLE
        binding.tvSessionActivity.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE
        binding.btnViewReport.visibility = View.GONE
        binding.tvError.visibility = View.GONE
    }

    private fun clearMessages() {
        currentRecord = null
        decoding.set(false)
        binding.tvError.visibility = View.GONE
        binding.btnViewReport.visibility = View.GONE
    }

    private fun showError(message: String) {
        currentRecord = null
        binding.progress.visibility = View.GONE
        binding.scannerContainer.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.trustCard.visibility = View.GONE
        binding.timelineCard.visibility = View.GONE
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        decoding.set(false)
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.inputVerificationId.windowToken, 0)
    }

    private fun formatTime(value: Long): String = if (value > 0L) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(value))
    } else "Unavailable"

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() } ?: ""

    private fun decodeEmbeddedRecord(encoded: String): JSONObject? = runCatching {
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(bytes, Charsets.UTF_8))
    }.getOrNull()

    private fun extractLuminance(image: ImageProxy): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = ByteArray(image.width * image.height)
        val row = ByteArray(rowStride)
        var offset = 0
        for (y in 0 until image.height) {
            buffer.position(y * rowStride)
            val length = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, length)
            for (x in 0 until image.width) out[offset++] = row[x * pixelStride]
        }
        return out
    }

    private fun rotateLuminance(data: ByteArray, width: Int, height: Int, rotation: Int): ByteArray = when (rotation) {
        90 -> ByteArray(data.size).also { out -> var i = 0; for (x in 0 until width) for (y in height - 1 downTo 0) out[i++] = data[y * width + x] }
        180 -> ByteArray(data.size).also { out -> for (i in data.indices) out[i] = data[data.lastIndex - i] }
        270 -> ByteArray(data.size).also { out -> var i = 0; for (x in width - 1 downTo 0) for (y in 0 until height) out[i++] = data[y * width + x] }
        else -> data
    }
}
