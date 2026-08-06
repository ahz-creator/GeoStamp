package com.axiominfratech.geostamp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
import com.axiominfratech.geostamp.verification.EvidenceTrustEngine
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
import java.net.HttpURLConnection
import java.net.URL
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
    private var currentReportUrl: String? = null

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
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        binding.btnVerifyId.setOnClickListener {
            val id = binding.inputVerificationId.text?.toString()?.trim().orEmpty()
            if (id.isBlank()) showError("Enter an Evidence ID.") else verifyById(id)
        }
        binding.btnViewReport.setOnClickListener {
            currentReportUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.scannerPreview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.TRY_HARDER to true))
            }
            analysis.setAnalyzer(analysisExecutor) { image -> analyzeQr(image, reader) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeQr(image: ImageProxy, reader: MultiFormatReader) {
        if (!decoding.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val luminance = extractLuminance(image)
            val rotated = rotateLuminance(luminance, image.width, image.height, image.imageInfo.rotationDegrees)
            val width = if (image.imageInfo.rotationDegrees % 180 == 0) image.width else image.height
            val height = if (image.imageInfo.rotationDegrees % 180 == 0) image.height else image.width
            val source = PlanarYUVLuminanceSource(rotated, width, height, 0, 0, width, height, false)
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            runOnUiThread { handleQrPayload(result.text) }
        } catch (_: Exception) {
            reader.reset()
            decoding.set(false)
        } finally {
            image.close()
        }
    }

    private fun handleQrPayload(raw: String) {
        cameraProvider?.unbindAll()
        binding.scannerContainer.visibility = View.GONE
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val idFromUrl = uri?.getQueryParameter("id")
        val embedded = uri?.getQueryParameter("e")

        when {
            !idFromUrl.isNullOrBlank() -> {
                binding.inputVerificationId.setText(idFromUrl)
                verifyById(idFromUrl)
            }
            !embedded.isNullOrBlank() -> {
                val record = decodeEmbeddedRecord(embedded)
                if (record != null) {
                    val embeddedId = record.optString("verificationId", record.optString("id"))
                    if (embeddedId.isNotBlank()) {
                        binding.inputVerificationId.setText(embeddedId)
                        verifyEmbeddedAgainstRegistry(record, embeddedId)
                    } else {
                        currentReportUrl = embeddedReportUrl(record)
                        showRecord(record, "Embedded GeoStamp QR record", false)
                    }
                } else {
                    showError("The QR was detected but its evidence payload could not be read.")
                }
            }
            raw.startsWith("GST-", true) -> {
                binding.inputVerificationId.setText(raw)
                verifyById(raw)
            }
            else -> showError("This QR is not a supported GeoStamp evidence code.")
        }
    }

    private fun verifyEmbeddedAgainstRegistry(embeddedRecord: JSONObject, id: String) {
        clearMessages()
        binding.progress.visibility = View.VISIBLE
        binding.resultCard.visibility = View.GONE
        currentVerificationId = id.uppercase(Locale.US)
        currentReportUrl = "https://ahz-creator.github.io/GeoStamp-Portal/?id=${Uri.encode(currentVerificationId)}"

        lifecycleScope.launch {
            val publicRecord = withContext(Dispatchers.IO) { fetchPublicRecord(currentVerificationId.orEmpty()) }
            binding.progress.visibility = View.GONE
            if (publicRecord != null) {
                showRecord(publicRecord, "Public registry record found", true)
            } else {
                currentReportUrl = embeddedReportUrl(embeddedRecord)
                showRecord(embeddedRecord, "Embedded GeoStamp QR record", false)
            }
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
        currentVerificationId = id
        currentReportUrl = "https://ahz-creator.github.io/GeoStamp-Portal/?id=${Uri.encode(id)}"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchPublicRecord(id) }
            binding.progress.visibility = View.GONE
            if (result != null) showRecord(result, "Public registry record found", true)
            else showNotRegistered(id)
        }
    }

    private suspend fun fetchPublicRecord(id: String): JSONObject? =
        RegistryPublisher.lookup(this, id)

    private fun showRecord(record: JSONObject, source: String, registryBacked: Boolean) {
        val risk = record.optBoolean("locationRisk", false) ||
            record.optBoolean("locationIntegrityRisk", false) ||
            record.optInt("lr", 0) == 1
        val status = when {
            risk -> "REGISTERED — LOCATION RISK"
            registryBacked -> "REGISTERED EVIDENCE"
            else -> "QR RECORD FOUND"
        }
        binding.tvResultStatus.text = status
        binding.tvResultStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (risk) android.R.color.holo_orange_light else android.R.color.holo_green_light
            )
        )
        binding.tvResultSummary.text = when {
            risk -> "A GeoStamp record was found, but the location-integrity signals require review."
            registryBacked -> "This Evidence ID is confirmed in the GeoStamp Public Registry."
            else -> "A structured GeoStamp evidence record was decoded from the QR. Public registration is not yet confirmed."
        }

        val id = record.optString("verificationId", record.optString("id", currentVerificationId.orEmpty()))
        currentVerificationId = id
        if (registryBacked) {
            currentReportUrl = "https://ahz-creator.github.io/GeoStamp-Portal/?id=${Uri.encode(id)}"
        }
        val capturedAt = record.optLong(
            "capturedAt",
            record.optLong("timestamp", record.optLong("ts", 0L))
        )
        val date = if (capturedAt > 0) {
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(capturedAt))
        } else {
            "Unavailable"
        }
        val primary = firstNonBlank(
            record.optString("primaryValue"),
            record.optString("operator"),
            record.optString("p")
        )
        val secondary = firstNonBlank(
            record.optString("secondaryValue"),
            record.optString("siteId"),
            record.optString("s")
        )
        val lat = record.optDouble("latitude", record.optDouble("lat", Double.NaN))
        val lon = record.optDouble("longitude", record.optDouble("lon", Double.NaN))
        val accuracy = record.optDouble(
            "accuracyM",
            record.optDouble("accuracy", record.optDouble("acc", Double.NaN))
        )
        val coordinates = if (lat.isFinite() && lon.isFinite()) "%.6f, %.6f".format(Locale.US, lat, lon) else "Unavailable"
        val accuracyText = if (accuracy.isFinite()) "±%.1f m".format(Locale.US, accuracy) else "Unavailable"

        binding.tvResultDetails.text = buildString {
            append("Evidence ID\n$id\n\n")
            append("Captured\n$date\n\n")
            append("Operator / Location\n$primary\n\n")
            append("Site / Activity\n$secondary\n\n")
            append("Coordinates\n$coordinates\n\n")
            append("Accuracy\n$accuracyText")
            if (risk) append("\n\nReview required: location-integrity indicator recorded")
        }

        // Mobile verification is intentionally a fast result slip.
        // Detailed trust, signature, hash and timeline findings remain in View Report.
        binding.trustCard.visibility = View.GONE
        binding.timelineCard.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE
        binding.btnViewReport.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE
    }

    private fun showNotRegistered(id: String) {
        binding.tvResultStatus.text = "NOT REGISTERED"
        binding.tvResultStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
        binding.tvResultSummary.text = "No public evidence record was found for this Evidence ID."
        binding.tvResultDetails.text = "Evidence ID\n$id\n\nCheck the ID or scan the QR again. Record not found does not by itself prove that the photo is fake."
        binding.resultCard.visibility = View.VISIBLE
        binding.trustCard.visibility = View.GONE
        binding.timelineCard.visibility = View.GONE
        binding.btnViewReport.visibility = View.GONE
        binding.tvError.visibility = View.GONE
    }

    private fun clearMessages() {
        decoding.set(false)
        binding.tvError.visibility = View.GONE
        binding.btnViewReport.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
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

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() } ?: "Unavailable"

    private fun embeddedReportUrl(record: JSONObject): String {
        val encoded = Base64.encodeToString(
            record.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "https://ahz-creator.github.io/GeoStamp-Portal/?e=${Uri.encode(encoded)}"
    }

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
        90 -> ByteArray(data.size).also { out ->
            var i = 0
            for (x in 0 until width) for (y in height - 1 downTo 0) out[i++] = data[y * width + x]
        }
        180 -> ByteArray(data.size).also { out ->
            for (i in data.indices) out[i] = data[data.lastIndex - i]
        }
        270 -> ByteArray(data.size).also { out ->
            var i = 0
            for (x in width - 1 downTo 0) for (y in 0 until height) out[i++] = data[y * width + x]
        }
        else -> data
    }
}
