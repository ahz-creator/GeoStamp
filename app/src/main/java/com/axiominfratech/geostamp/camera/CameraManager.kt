package com.axiominfratech.geostamp.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.axiominfratech.geostamp.overlay.OverlayRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var manualRotation: Int? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    data class CaptureResult(val file: File, val bitmap: Bitmap, val rotation: Int)

    // ─────────────────────────────────────────────────────────────────────
    // Rotation helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun getDisplayRotation(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    private fun getDisplayRotationDegrees() = when (getDisplayRotation()) {
        Surface.ROTATION_90  -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    // ─────────────────────────────────────────────────────────────────────
    // startCamera
    // ─────────────────────────────────────────────────────────────────────

    suspend fun startCamera(previewView: PreviewView) {
        val provider   = getCameraProvider()
        val surfaceRot = manualRotation ?: when (getDisplayRotationDegrees()) {
            90  -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // Camera extensions are deliberately not auto-enabled. HDR/Night extensions can
        // add many seconds on mid-range phones; they can be exposed later as an opt-in mode.

        // ── Preview ──────────────────────────────────────────────────────
        // Minimal Camera2 overrides — let the ISP run freely.
        // No FPS lock (was causing jerky preview), no AWB override.
        // PERFORMANCE mode gives the lowest-latency colour-accurate preview.
        val previewBuilder = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(surfaceRot)

        Camera2Interop.Extender(previewBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        previewView.scaleType          = PreviewView.ScaleType.FILL_CENTER

        // ── ImageCapture ─────────────────────────────────────────────────
        //
        // Core philosophy change from previous versions:
        //
        // TONEMAP_MODE → HIGH_QUALITY (was FAST)
        //   FAST gives a nearly-linear/flat output expecting the app to
        //   apply its own tone mapping.  When combined with our tone curve
        //   it caused double-processing artefacts.  HIGH_QUALITY lets the
        //   manufacturer's ISP apply its own well-tuned tone curve — the
        //   same curve used in the stock camera app.  We then apply only a
        //   gentle finishing pass on top.
        //
        // EDGE_MODE → HIGH_QUALITY (was ZERO_SHUTTER_LAG)
        //   ZSL was bypassing the ISP's sharpening entirely, making images
        //   soft before our USM.  HIGH_QUALITY applies the ISP's own
        //   controlled sharpening (usually equivalent to 0.10–0.15 USM).
        //   Our ImageEnhancer then adds a final gentle clarity pass on top.
        //
        // EXPOSURE_COMPENSATION = 0 (no override — neutral)
        //   Any underexposure is handled by the ISP's AE.
        //
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(surfaceRot)
            .setJpegQuality(92)   // 100 has no visible benefit over 98; 98 keeps file size sane

        Camera2Interop.Extender(captureBuilder)
            // Let ISP do its full job
            .setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            .setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
            .setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
            )
            .setCaptureRequestOption(
                CaptureRequest.SHADING_MODE,
                CaptureRequest.SHADING_MODE_HIGH_QUALITY
            )
            .setCaptureRequestOption(
                CaptureRequest.HOT_PIXEL_MODE,
                CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
            )
            .setCaptureRequestOption(
                CaptureRequest.TONEMAP_MODE,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY   // ISP applies its own well-tuned curve
            )
            // Standard AE/AF/AWB — let ISP meter normally
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            // Face detection — HAL biases AE/AWB toward face region when detected
            .setCaptureRequestOption(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
            )
            // OIS: ignored silently if not available
            .setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )

        imageCapture = captureBuilder.build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            applyPortraitMetering()
        } catch (e: Exception) {
            throw CameraInitException("Failed to bind camera: ${e.message}")
        }
    }

    /**
     * Centre-weighted metering for portrait / field work.
     * 35% of frame area centred — biases AE/AF/AWB toward a face
     * without requiring face detection to be active first.
     * Exposure compensation = 0 (neutral).
     */
    private fun applyPortraitMetering() {
        val cam = camera ?: return
        cam.cameraControl.setExposureCompensationIndex(0)

        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point   = factory.createPoint(0.5f, 0.5f, 0.35f)

        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AF or
                FocusMeteringAction.FLAG_AWB
            )
            .setAutoCancelDuration(6, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Extensions: HDR → Night → Auto → plain
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun buildBestSelector(
        provider: ProcessCameraProvider,
        base: CameraSelector
    ): CameraSelector = try {
        val em = suspendCancellableCoroutine<ExtensionsManager> { cont ->
            val f = ExtensionsManager.getInstanceAsync(context, provider)
            f.addListener({
                try { cont.resume(f.get()) }
                catch (e: Exception) { cont.resumeWithException(e) }
            }, ContextCompat.getMainExecutor(context))
        }
        listOf(ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.AUTO)
            .firstOrNull { em.isExtensionAvailable(base, it) }
            ?.let { em.getExtensionEnabledCameraSelector(base, it) }
            ?: base
    } catch (_: Exception) { base }

    // ─────────────────────────────────────────────────────────────────────
    // Camera controls
    // ─────────────────────────────────────────────────────────────────────

    fun updateTargetRotation(rotation: Int = getDisplayRotation()) {
        manualRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    suspend fun flipCamera(previewView: PreviewView) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera(previewView)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Flash
    // ─────────────────────────────────────────────────────────────────────

    enum class FlashMode { OFF, ON, AUTO }
    private var currentFlashMode = FlashMode.OFF

    fun cycleFlash(): FlashMode {
        val cam     = camera       ?: return FlashMode.OFF
        val capture = imageCapture ?: return FlashMode.OFF
        if (!cam.cameraInfo.hasFlashUnit()) return FlashMode.OFF
        currentFlashMode = when (currentFlashMode) {
            FlashMode.OFF  -> FlashMode.ON
            FlashMode.ON   -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.OFF
        }
        when (currentFlashMode) {
            FlashMode.OFF  -> { capture.flashMode = ImageCapture.FLASH_MODE_OFF;  cam.cameraControl.enableTorch(false) }
            FlashMode.ON   -> { capture.flashMode = ImageCapture.FLASH_MODE_ON;   cam.cameraControl.enableTorch(true)  }
            FlashMode.AUTO -> { capture.flashMode = ImageCapture.FLASH_MODE_AUTO; cam.cameraControl.enableTorch(false) }
        }
        return currentFlashMode
    }

    fun getCurrentFlash() = currentFlashMode
    fun toggleFlash(): Boolean = cycleFlash() != FlashMode.OFF

    // ─────────────────────────────────────────────────────────────────────
    // Capture pipeline
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun capturePhoto(): CaptureResult = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(CameraInitException("Camera not initialized"))
            return@suspendCancellableCoroutine
        }
        val dir      = File(context.cacheDir, "captures").also { it.mkdirs() }
        val tempFile = File(dir, "raw_${System.currentTimeMillis()}.jpg")

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(tempFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val exifDeg = readExifRotation(tempFile)
                    val raw = BitmapFactory.decodeFile(
                        tempFile.absolutePath,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    ) ?: run { cont.resumeWithException(Exception("Decode failed")); return }

                    val upright = if (exifDeg != 0)
                        rotateBitmap(raw, exifDeg).also { if (it !== raw) raw.recycle() }
                    else raw

                    // Limit the processing bitmap to a practical evidence resolution.
                    // This reduces memory use and JPEG encoding time while keeping text/QR sharp.
                    val processingBitmap = downscaleForProcessing(upright, MAX_PROCESSING_LONG_EDGE)
                    if (processingBitmap !== upright) upright.recycle()

                    // Subtle finishing pass: gentle USM + minimal saturation lift.
                    val enhanced = ImageEnhancer.enhance(processingBitmap)
                    if (enhanced !== processingBitmap) processingBitmap.recycle()

                    cont.resume(CaptureResult(tempFile, enhanced, exifDeg))
                }
                override fun onError(e: ImageCaptureException) { cont.resumeWithException(e) }
            }
        )
    }

    private fun readExifRotation(file: File): Int = try {
        val exif = ExifInterface(file.absolutePath)
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90  -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) { 0 }

    private fun rotateBitmap(src: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height,
            Matrix().apply { postRotate(deg.toFloat()) }, true)
            .also { if (it !== src) src.recycle() }
    }

    private fun downscaleForProcessing(src: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    suspend fun captureAndStamp(overlayData: OverlayRenderer.OverlayData, outputDir: File): File =
        withContext(Dispatchers.IO) {
            val raw     = capturePhoto()
            val outFile = File(outputDir,
                "GeoStamp_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())}.jpg")
            outputDir.mkdirs()
            OverlayRenderer.renderAndSave(raw.bitmap, overlayData, outFile, context)
            raw.file.delete()
            outFile
        }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val f = ProcessCameraProvider.getInstance(context)
            f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(context))
        }

    fun shutdown() { cameraExecutor.shutdown() }

    companion object {
        const val MAX_DIM = 2048
        const val MAX_PROCESSING_LONG_EDGE = 3200
    }
}

class CameraInitException(message: String) : Exception(message)
