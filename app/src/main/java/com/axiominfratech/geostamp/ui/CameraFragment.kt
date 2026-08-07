package com.axiominfratech.geostamp.ui

import android.annotation.SuppressLint
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.OrientationEventListener
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.axiominfratech.geostamp.R
import com.axiominfratech.geostamp.camera.CameraManager
import com.axiominfratech.geostamp.database.Operator
import com.axiominfratech.geostamp.overlay.LiveInfoMode
import com.axiominfratech.geostamp.databinding.FragmentCameraBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var cameraManager: CameraManager

    private var overlayDragX = 0f
    private var overlayDragY = 0f
    private var isCompactMode = false

    private var sidebarPinned  = false
    private var sidebarOpen    = false
    private val sidebarHandler = Handler(Looper.getMainLooper())
    private val autoHideTask   = Runnable { if (!sidebarPinned) collapseSidebar() }
    private lateinit var sidebarGesture: GestureDetector

    private var dotDragStartY    = 0f
    private var dotDragStartRawY = 0f
    private var isDotDragging    = false

    private var activeCountDown: CountDownTimer? = null
    private var isLandscape = false
    private var orientationListener: OrientationEventListener? = null

    private val prefs by lazy {
        requireContext().getSharedPreferences("geostamp_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Handle window insets for status bar and bottom navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.cameraRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Top inset for brand header
            binding.brandHeaderInner.setPadding(
                binding.brandHeaderInner.paddingLeft,
                systemBars.top,
                binding.brandHeaderInner.paddingRight,
                binding.brandHeaderInner.paddingBottom
            )
            
            // Bottom inset for bottom panel
            binding.bottomPanel.setPadding(
                binding.bottomPanel.paddingLeft,
                binding.bottomPanel.paddingTop,
                binding.bottomPanel.paddingRight,
                systemBars.bottom
            )

            insets
        }

        cameraManager = CameraManager(requireContext(), viewLifecycleOwner)
        startCamera()
        setupControls()
        setupWorkspaceSelector()
        setupRightSidebar()
        observeState()
        observeEvents()
        observeOverlayAlpha()
        observeStampConfig()
        observeOverlayScale()
        setupDraggableOverlay()
        ensureGeoStampFolderExists()
        setupOrientationListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sidebarHandler.removeCallbacksAndMessages(null)
        activeCountDown?.cancel()
        cameraManager.shutdown()
        _binding = null
    }

    private fun startCamera() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                cameraManager.startCamera(binding.previewView)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupControls() {
        binding.btnCapture.setOnClickListener {
            val secs = viewModel.uiState.value.timerSeconds
            if (secs > 0) startTimerThenCapture(secs) else captureNow()
        }
        binding.btnSwitchSide.setOnClickListener { openStampOptions() }
        binding.cardOperatorTap.setOnClickListener {
            val mode = prefs.getString("workspace_mode", "organization") ?: "organization"
            val activeSession = viewModel.activeOperatorSession()
            if (mode == "organization" && activeSession == null) {
                showOperatorPicker()
            } else {
                startActivity(Intent(requireContext(), VerifyEvidenceActivity::class.java))
            }
        }
        binding.cardSiteTap.setOnClickListener { if (prefs.getString("workspace_mode", "organization") == "personal") showPersonalWorkspaceDialog() else showSiteIdInfo() }
        binding.btnGalleryNav.setOnClickListener { openGeoStampGallery() }
        binding.btnMenu.setOnClickListener { openStampOptions() }
        binding.workspaceSelector.setOnClickListener {
            val mode = prefs.getString("workspace_mode", "organization") ?: "organization"
            val activeSession = viewModel.activeOperatorSession()
            when {
                mode == "organization" && activeSession != null -> showOperatorSessionDialog()
                mode == "organization" -> showOperatorPicker()
                else -> showWorkspacePicker()
            }
        }
    }

    private fun captureNow() {
        val dir = requireContext().filesDir.resolve("photos").also { it.mkdirs() }
        triggerCaptureWithFade(dir)
    }

    private fun startTimerThenCapture(seconds: Int) {
        binding.timerCountdownOverlay.visibility = View.VISIBLE
        binding.tvCountdown.text = seconds.toString()
        binding.btnCapture.isEnabled = false
        activeCountDown?.cancel()
        activeCountDown = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(msRemaining: Long) {
                val s = ((msRemaining + 500) / 1000).toInt()
                binding.tvCountdown.text = s.toString()
                binding.tvCountdown.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200)
                    .withEndAction { binding.tvCountdown.animate().scaleX(1f).scaleY(1f).setDuration(200).start() }.start()
            }
            override fun onFinish() {
                binding.timerCountdownOverlay.visibility = View.GONE
                binding.btnCapture.isEnabled = true
                captureNow()
            }
        }.start()
    }

    private fun pushOverlayState() {
        val preview = binding.previewView
        if (preview.width == 0 || preview.height == 0) return
        val v = if (isCompactMode) binding.infoOverlayCompact else binding.infoOverlay
        if (v.width == 0 || v.height == 0) return
        viewModel.updateOverlayState(MainViewModel.OverlayState(
            x = v.x, y = v.y, width = v.width * v.scaleX, height = v.height * v.scaleY,
            previewW = preview.width, previewH = preview.height, isCompact = isCompactMode))
    }

    private fun triggerCaptureWithFade(dir: File) {
        pushOverlayState()
        val uiViews = listOf(binding.infoOverlay, binding.infoOverlayCompact, binding.bottomPanel, binding.bottomInfoStrip)
        uiViews.forEach { it.animate().alpha(0f).setDuration(150).start() }
        viewLifecycleOwner.lifecycleScope.launch {
            delay(160); viewModel.onCaptureRequested(cameraManager, dir)
            delay(400); uiViews.forEach { it.animate().alpha(1f).setDuration(250).start() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRightSidebar() {
        binding.flashFlipPanel.post { positionSidebarInitial() }

        sidebarGesture = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isDotDragging) { isDotDragging = false; return true }
                if (!sidebarOpen) { sidebarPinned = false; expandSidebar(); scheduleAutoHide() }
                else { sidebarPinned = false; sidebarHandler.removeCallbacks(autoHideTask); collapseSidebar() }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isDotDragging) return true
                if (!sidebarOpen) { sidebarPinned = true; sidebarHandler.removeCallbacks(autoHideTask); expandSidebar() }
                else { sidebarPinned = false; sidebarHandler.removeCallbacks(autoHideTask); collapseSidebar() }
                return true
            }
        })

        binding.flashFlipTrigger.setOnTouchListener { _, event ->
            sidebarGesture.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dotDragStartY = binding.flashFlipPanel.y
                    dotDragStartRawY = event.rawY
                    isDotDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - dotDragStartRawY
                    if (!isDotDragging && Math.abs(delta) > 10f) isDotDragging = true
                    if (isDotDragging) {
                        val panelH = binding.flashFlipPanel.height.toFloat()
                        val minY = binding.brandHeader.bottom.toFloat() + 8f
                        val maxY = (requireView().height - binding.bottomPanel.height - panelH - 8f).coerceAtLeast(minY)
                        binding.flashFlipPanel.y = (dotDragStartY + delta).coerceIn(minY, maxY)
                        prefs.edit().putFloat("sidebar_y", binding.flashFlipPanel.y).apply()
                    }
                }
                MotionEvent.ACTION_UP -> { /* leave isDotDragging for gesture detector */ }
            }
            true
        }

        binding.btnFlash.setOnClickListener {
            val mode = cameraManager.cycleFlash(); updateFlashUI(mode)
            if (!sidebarPinned) { sidebarHandler.removeCallbacks(autoHideTask); scheduleAutoHide() }
        }
        binding.btnFlip.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                cameraManager.flipCamera(binding.previewView)
            }
            if (!sidebarPinned) { sidebarHandler.removeCallbacks(autoHideTask); scheduleAutoHide() }
        }
        binding.btnGrid.setOnClickListener {
            viewModel.toggleGrid()
            val on = viewModel.uiState.value.showGrid
            binding.gridOverlay.visibility = if (on) View.VISIBLE else View.GONE
            binding.icGridState.alpha = if (on) 1.0f else 0.45f
            binding.tvGridLabel.text = if (on) "ON" else "OFF"
            binding.tvGridLabel.setTextColor(android.graphics.Color.parseColor(if (on) "#40C4FF" else "#8B5CF6"))
            if (!sidebarPinned) { sidebarHandler.removeCallbacks(autoHideTask); scheduleAutoHide() }
        }
        binding.btnTimer.setOnClickListener {
            val secs = viewModel.cycleTimer()
            val (label, tint, alpha) = when (secs) {
                0 -> Triple("OFF", "#8B5CF6", 0.45f); 3 -> Triple("3s", "#FFD740", 1.0f)
                5 -> Triple("5s", "#40C4FF", 1.0f); else -> Triple("10s", "#FF6E40", 1.0f)
            }
            binding.tvTimerLabel.text = label
            binding.tvTimerLabel.setTextColor(android.graphics.Color.parseColor(tint))
            binding.icTimerState.alpha = alpha
            if (!sidebarPinned) { sidebarHandler.removeCallbacks(autoHideTask); scheduleAutoHide() }
        }
    }

    private fun positionSidebarInitial() {
        val savedY = prefs.getFloat("sidebar_y", -1f)
        val panelH = binding.flashFlipPanel.height.toFloat()
        val minY = binding.brandHeader.bottom.toFloat() + 8f
        val maxY = (requireView().height - binding.bottomPanel.height - panelH - 8f).coerceAtLeast(minY)
        binding.flashFlipPanel.y = if (savedY >= 0f) savedY.coerceIn(minY, maxY) else (minY + maxY) / 2f
    }

    private fun scheduleAutoHide() { sidebarHandler.removeCallbacks(autoHideTask); sidebarHandler.postDelayed(autoHideTask, 3000) }

    private fun expandSidebar() {
        sidebarOpen = true
        binding.flashFlipTrigger.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(100).withEndAction {
            binding.flashFlipTrigger.visibility = View.GONE
            binding.flashFlipTrigger.alpha = 1f; binding.flashFlipTrigger.scaleX = 1f; binding.flashFlipTrigger.scaleY = 1f
            binding.flashFlipExpanded.alpha = 0f; binding.flashFlipExpanded.translationX = 40f
            binding.flashFlipExpanded.visibility = View.VISIBLE
            binding.flashFlipExpanded.animate().alpha(1f).translationX(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun collapseSidebar() {
        sidebarOpen = false
        binding.flashFlipExpanded.animate().alpha(0f).translationX(40f).setDuration(150).withEndAction {
            binding.flashFlipExpanded.visibility = View.GONE
            binding.flashFlipExpanded.translationX = 0f; binding.flashFlipExpanded.alpha = 1f
            binding.flashFlipTrigger.visibility = View.VISIBLE
        }.start()
    }

    private fun updateFlashUI(mode: CameraManager.FlashMode) {
        val (label, alpha, colorHex) = when (mode) {
            CameraManager.FlashMode.OFF  -> Triple("OFF",  0.45f, "#8B5CF6")
            CameraManager.FlashMode.ON   -> Triple("ON",   1.00f, "#FFD740")
            CameraManager.FlashMode.AUTO -> Triple("AUTO", 1.00f, "#40C4FF")
        }
        binding.tvFlashLabel.text = label
        binding.tvFlashLabel.setTextColor(android.graphics.Color.parseColor(colorHex))
        binding.icFlashState.alpha = alpha
        binding.icFlashState.setColorFilter(android.graphics.Color.parseColor(
            when (mode) { CameraManager.FlashMode.OFF -> "#FFFFFF"; CameraManager.FlashMode.ON -> "#FFD740"; CameraManager.FlashMode.AUTO -> "#40C4FF" }
        ), android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private fun observeOverlayAlpha() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stampConfig.collect { config -> applyOverlayColor(config); applyOverlayVisibility(config) }
        }
    }

    private fun resolveBaseColor(config: com.axiominfratech.geostamp.overlay.StampConfig): Int {
        val alpha = (config.overlayAlpha.coerceIn(0.2f, 0.9f) * 255).toInt()
        return if (config.colorScheme == com.axiominfratech.geostamp.overlay.OverlayColorScheme.CUSTOM) {
            val rgb = config.customColorRgb
            android.graphics.Color.argb(alpha, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        } else { val schemeRgb = config.colorScheme.baseArgb and 0x00FFFFFF; (alpha shl 24) or schemeRgb }
    }

    private fun applyOverlayColor(config: com.axiominfratech.geostamp.overlay.StampConfig) {
        val bgColor = resolveBaseColor(config)
        val cornerPx = 20f * resources.displayMetrics.density
        val pillPx = 100f * resources.displayMetrics.density
        val stroke = android.graphics.Color.argb(51, 255, 255, 255)
        val sw = (1f * resources.displayMetrics.density).toInt()
        binding.infoOverlay.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(bgColor); cornerRadius = cornerPx; setStroke(sw, stroke) }
        binding.infoOverlayCompact.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(bgColor); cornerRadius = pillPx; setStroke(sw, stroke) }
        try { binding.bottomInfoStrip.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(bgColor); cornerRadius = pillPx; setStroke(sw, stroke) } } catch (_: Exception) {}
    }

    private fun applyOverlayVisibility(config: com.axiominfratech.geostamp.overlay.StampConfig) {
        when (config.liveInfoMode) {
            LiveInfoMode.FLOATING -> {
                binding.bottomInfoStrip.visibility = View.GONE
                if (isCompactMode) {
                    binding.infoOverlay.visibility = View.GONE
                    binding.infoOverlayCompact.visibility = View.VISIBLE
                } else {
                    binding.infoOverlayCompact.visibility = View.GONE
                    binding.infoOverlay.visibility = View.VISIBLE
                }
            }
            LiveInfoMode.BOTTOM -> {
                binding.infoOverlay.visibility = View.GONE
                binding.infoOverlayCompact.visibility = View.GONE
                binding.bottomInfoStrip.visibility = View.VISIBLE
            }
            LiveInfoMode.OFF -> {
                binding.infoOverlay.visibility = View.GONE
                binding.infoOverlayCompact.visibility = View.GONE
                binding.bottomInfoStrip.visibility = View.GONE
            }
        }
    }

    private fun observeOverlayScale() {
        viewLifecycleOwner.lifecycleScope.launch { viewModel.stampConfig.collect { applyOverlayScale(it.overlayScale) } }
    }

    private fun applyOverlayScale(scale: Float) {
        val c = scale.coerceIn(0.8f, 1.5f)
        binding.infoOverlay.pivotX = 0f; binding.infoOverlay.pivotY = 0f
        binding.infoOverlay.scaleX = c; binding.infoOverlay.scaleY = c
    }

    private fun setupDraggableOverlay() {
        val savedX = prefs.getFloat("overlay_pos_x", -1f)
        val savedY = prefs.getFloat("overlay_pos_y", -1f)
        binding.infoOverlay.post {
            if (savedX >= 0 && savedY >= 0) {
                binding.infoOverlay.x = savedX; binding.infoOverlay.y = savedY
                if (requireView().height > 0 && savedY >= requireView().height * 0.75f) {
                    isCompactMode = true; binding.infoOverlay.visibility = View.GONE
                    snapCompactToBottom(); binding.infoOverlayCompact.visibility = View.VISIBLE
                }
            }
        }
        binding.infoOverlay.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { overlayDragX = v.x - event.rawX; overlayDragY = v.y - event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val pad = 8.dpToPx().toFloat(); val visW = v.width * v.scaleX; val visH = v.height * v.scaleY
                    val minY = binding.brandHeader.bottom.toFloat() + pad
                    val maxY = (requireView().height - binding.bottomPanel.height - visH - pad).coerceAtLeast(minY)
                    v.x = (event.rawX + overlayDragX).coerceIn(pad, (requireView().width - visW - pad).coerceAtLeast(pad))
                    v.y = (event.rawY + overlayDragY).coerceIn(minY, maxY); true
                }
                MotionEvent.ACTION_UP -> {
                    if (v.y > requireView().height * 0.75f) animateToCompactMode()
                    else prefs.edit().putFloat("overlay_pos_x", v.x).putFloat("overlay_pos_y", v.y).apply(); true
                }
                else -> false
            }
        }
        binding.infoOverlayCompact.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { overlayDragY = event.rawY; true }
                MotionEvent.ACTION_UP -> { if (overlayDragY - event.rawY > 80f) { binding.infoOverlay.x = 12f; binding.infoOverlay.y = requireView().height * 0.3f; animateToNormalMode() }; true }
                else -> false
            }
        }
    }

    private fun snapCompactToBottom() {
        binding.infoOverlayCompact.post {
            val barH = binding.infoOverlayCompact.height.takeIf { it > 0 } ?: 44.dpToPx()
            binding.infoOverlayCompact.y = (requireView().height - binding.bottomPanel.height - barH - 8).toFloat()
        }
    }

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    private fun animateToCompactMode() {
        isCompactMode = true
        binding.infoOverlay.animate().scaleX(0.95f).scaleY(0.95f).alpha(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).withEndAction {
            binding.infoOverlay.visibility = View.GONE; binding.infoOverlay.scaleX = 1f; binding.infoOverlay.scaleY = 1f
            binding.infoOverlayCompact.apply { alpha = 0f; scaleX = 0.95f; scaleY = 0.95f; visibility = View.VISIBLE; snapCompactToBottom()
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start() }
            prefs.edit().putFloat("overlay_pos_x", binding.infoOverlay.x).putFloat("overlay_pos_y", binding.infoOverlay.y).apply()
        }.start()
    }

    private fun animateToNormalMode() {
        isCompactMode = false
        binding.infoOverlayCompact.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(150).setInterpolator(DecelerateInterpolator()).withEndAction {
            binding.infoOverlayCompact.visibility = View.GONE; binding.infoOverlayCompact.scaleX = 1f; binding.infoOverlayCompact.scaleY = 1f
            binding.infoOverlay.apply { alpha = 0f; scaleX = 0.95f; scaleY = 0.95f; visibility = View.VISIBLE
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250).setInterpolator(DecelerateInterpolator()).start() }
        }.start()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state.gpsStatus) {
                    MainViewModel.GpsStatus.LOCKED   -> { binding.gpsIndicator.text = "● GPS"; binding.gpsIndicator.setTextColor(requireContext().getColor(R.color.gps_locked)); binding.gpsIndicator.setBackgroundResource(R.drawable.bg_gps_locked) }
                    MainViewModel.GpsStatus.SEARCHING -> { binding.gpsIndicator.text = "◌ GPS"; binding.gpsIndicator.setTextColor(requireContext().getColor(R.color.gps_searching)); binding.gpsIndicator.setBackgroundResource(R.drawable.bg_gps_searching) }
                    MainViewModel.GpsStatus.SPOOFED  -> { binding.gpsIndicator.text = "✕ GPS"; binding.gpsIndicator.setTextColor(requireContext().getColor(R.color.gps_spoofed)); binding.gpsIndicator.setBackgroundResource(R.drawable.bg_gps_spoofed) }
                }
                binding.tvIntegrityMarker.visibility = if (state.gpsStatus == MainViewModel.GpsStatus.SPOOFED) View.VISIBLE else View.GONE
                state.currentLocation?.let { loc ->
                    val latStr = "%.4f° %s".format(Math.abs(loc.latitude), if (loc.latitude >= 0) "N" else "S")
                    val lonStr = "%.4f° %s".format(Math.abs(loc.longitude), if (loc.longitude >= 0) "E" else "W")
                    binding.tvCoords.text = "$latStr, $lonStr"
                    binding.tvStripCoords.text = "%.3f°%s, %.3f°%s".format(Math.abs(loc.latitude), if (loc.latitude>=0)"N" else "S", Math.abs(loc.longitude), if (loc.longitude>=0)"E" else "W")
                    binding.tvAccuracy.text = "± %.1f m".format(loc.accuracyM)
                    binding.tvStripAccuracy.text = "±%.0fm".format(loc.accuracyM)
                    val (qualLabel, qualColor) = when { loc.accuracyM <= 5f -> "High" to requireContext().getColor(R.color.gps_locked); loc.accuracyM <= 20f -> "Medium" to requireContext().getColor(R.color.gps_searching); else -> "Low" to requireContext().getColor(R.color.gps_spoofed) }
                    binding.tvAccuracyQuality.text = qualLabel; binding.tvAccuracyQuality.setTextColor(qualColor)
                    val cal = Calendar.getInstance().apply { timeInMillis = loc.timestampMs }
                    binding.tvDatetime.text = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(cal.time)
                    val cityLabel = when { loc.city.isNotBlank() && loc.province.isNotBlank() -> "${loc.city}, ${loc.province}"; loc.city.isNotBlank() -> loc.city; loc.addressLine.isNotBlank() -> loc.addressLine.take(30); else -> "%.4f, %.4f".format(loc.latitude, loc.longitude) }
                    binding.tvCompactLocation.text = "📍 $cityLabel"
                    binding.tvCompactTime.text = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(cal.time)
                    binding.tvStripTime.text = binding.tvCompactTime.text
                }

                val match  = state.siteMatch
                val site   = match?.site
                val config = viewModel.stampConfig.value
                val activeSession = state.operatorSession
                val opLabel = activeSession?.operatorName?.split(" ")?.first()
                    ?: if (config.selectedOperator == Operator.ALL) "–" else config.selectedOperator.displayName.split(" ").first()

                val siteIdText: String
                when {
                    site == null -> siteIdText = "–"
                    match.distanceM <= 10_000.0 -> siteIdText = site.siteId
                    else -> {
                        val d = if (match.distanceM >= 1000) "%.1fkm away".format(match.distanceM/1000) else "%.0fm away".format(match.distanceM)
                        siteIdText = "~${site.siteId}  ($d)"
                    }
                }
                val workspaceMode = prefs.getString("workspace_mode", "organization") ?: "organization"
                val isPersonal = workspaceMode == "personal"
                val personalTitle = prefs.getString("personal_title", "Personal") ?: "Personal"
                val personalReference = prefs.getString("personal_reference", "New Evidence") ?: "New Evidence"
                if (!isPersonal) {
                    binding.tvWorkspaceMode.text = if (activeSession != null)
                        "ORG  •  ${activeSession.operatorName.split(" ").first()} CLOCKED IN"
                    else "ORGANIZATION  •  SELECT OPERATOR"
                }

                binding.tvOverlayPrimaryLabel.text = if (isPersonal) "Project / Title  " else "Operator  "
                binding.tvOverlaySecondaryLabel.text = if (isPersonal) "Reference  " else "Site ID  "
                binding.tvSiteName.text = if (isPersonal) personalTitle else (site?.operator ?: opLabel)
                binding.tvSiteId.text = if (isPersonal) personalReference else (site?.siteId ?: "–")
                binding.tvSiteIdOverlay.text = if (isPersonal) personalReference else siteIdText
                binding.tvStripOperator.text = if (isPersonal) "Personal" else (site?.operator?.split(" ")?.first() ?: opLabel)
                binding.tvStripSiteid.text = if (isPersonal) personalReference else (site?.siteId ?: "–")
                binding.tvPrimaryLabel.text = if (!isPersonal && activeSession == null) "Operator" else "Verify"
                binding.tvOperatorDisplay.text = if (!isPersonal && activeSession == null) "Clock In" else "Open"
                binding.tvSiteDisplay.text = if (isPersonal) personalReference else (site?.siteId ?: "Auto")
                binding.tvCompactOperator.text = if (isPersonal) personalTitle else (site?.operator?.split(" ")?.first() ?: opLabel)
                binding.tvCompactSiteid.text = if (isPersonal) personalReference else (site?.siteId ?: "–")
                binding.tvCityNameOverlay.text = when (state.gpsStatus) { MainViewModel.GpsStatus.LOCKED -> "Location Acquired"; MainViewModel.GpsStatus.SPOOFED -> "⚠  GPS Spoofed"; else -> "Acquiring GPS…" }
                binding.btnCapture.isEnabled = !state.isCapturing
                binding.btnCapture.alpha = if (state.isCapturing) 0f else 1f
                binding.captureProgress.visibility = if (state.isCapturing) View.VISIBLE else View.GONE
                binding.tvLocationError.visibility = if (state.gpsStatus == MainViewModel.GpsStatus.SPOOFED && state.locationError != null) View.VISIBLE else View.GONE
                if (binding.tvLocationError.visibility == View.VISIBLE) binding.tvLocationError.text = state.locationError
                binding.tvDbCount.text = "${state.dbSiteCount} sites"
            }
        }
    }

    private fun observeStampConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stampConfig.collect { config ->
                val opLabel = if (config.selectedOperator == Operator.ALL) "All" else config.selectedOperator.displayName.split(" ").first()
                val mode = prefs.getString("workspace_mode", "organization") ?: "organization"
                if (mode == "organization" && viewModel.activeOperatorSession() == null) {
                    binding.tvPrimaryLabel.text = "Operator"
                    binding.tvOperatorDisplay.text = "Clock In"
                } else {
                    binding.tvPrimaryLabel.text = "Verify"
                    binding.tvOperatorDisplay.text = "Scan or ID"
                }
                binding.chipOperator.text = config.selectedOperator.displayName
                binding.tvCityName.text = config.username.ifBlank { "–" }
            }
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.captureEvent.collect { event ->
                when (event) {
                    is MainViewModel.CaptureEvent.SavedToGallery -> Toast.makeText(requireContext(), "✓ Saved • Public registry record queued", Toast.LENGTH_SHORT).show()
                    is MainViewModel.CaptureEvent.SavedPreview -> showPreview(event.file)
                    is MainViewModel.CaptureEvent.Error -> Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun setupWorkspaceSelector() {
        applyWorkspaceUi()
    }

    private fun applyWorkspaceUi() {
        val mode = prefs.getString("workspace_mode", "organization") ?: "organization"
        if (mode == "personal") {
            binding.tvWorkspaceMode.text = "PERSONAL WORKSPACE"
            binding.tvPrimaryLabel.text = "Project / Title"
            binding.tvSecondaryLabel.text = "Reference"
            val title = prefs.getString("personal_title", "Personal") ?: "Personal"
            val reference = prefs.getString("personal_reference", "New Evidence") ?: "New Evidence"
            binding.tvOperatorDisplay.text = title
            binding.tvSiteDisplay.text = reference
            binding.tvOverlayPrimaryLabel.text = "Project / Title  "
            binding.tvOverlaySecondaryLabel.text = "Reference  "
            binding.tvSiteName.text = title
            binding.tvSiteIdOverlay.text = reference
            binding.tvStripOperator.text = "Personal"
            binding.tvStripSiteid.text = reference
        } else {
            val session = viewModel.activeOperatorSession()
            binding.tvWorkspaceMode.text = if (session != null)
                "ORG  •  ${session.operatorName.split(" ").first()} CLOCKED IN"
            else "ORGANIZATION  •  SELECT OPERATOR"
            binding.tvPrimaryLabel.text = "Operator"
            binding.tvSecondaryLabel.text = "Site ID"
            binding.tvOverlayPrimaryLabel.text = "Operator  "
            binding.tvOverlaySecondaryLabel.text = "Site ID  "
        }
    }

    private fun showWorkspacePicker() {
        val options = arrayOf("Personal Use", "Organization / Telecom")
        AlertDialog.Builder(requireContext())
            .setTitle("Choose Workspace")
            .setItems(options) { _, which ->
                if (which == 0) showPersonalWorkspaceDialog()
                else {
                    prefs.edit().putString("workspace_mode", "organization").apply()
                    applyWorkspaceUi()
                    if (viewModel.activeOperatorSession() == null) showOperatorPicker()
                    else showOperatorSessionDialog()
                }
            }.show()
    }

    private fun showPersonalWorkspaceDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val titleInput = EditText(requireContext()).apply {
            hint = "Project or title"
            setText(prefs.getString("personal_title", ""))
            maxLines = 1
        }
        val referenceInput = EditText(requireContext()).apply {
            hint = "Reference (optional)"
            setText(prefs.getString("personal_reference", ""))
            maxLines = 1
        }
        container.addView(titleInput)
        container.addView(referenceInput)
        AlertDialog.Builder(requireContext())
            .setTitle("Personal Workspace")
            .setMessage("Use GeoStamp for personal, construction, delivery, inspection or any custom evidence.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim().ifBlank { "Personal" }
                val ref = referenceInput.text.toString().trim().ifBlank { "New Evidence" }
                prefs.edit()
                    .putString("workspace_mode", "personal")
                    .putString("personal_title", title)
                    .putString("personal_reference", ref)
                    .apply()
                applyWorkspaceUi()
                Toast.makeText(requireContext(), "Personal workspace active", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showOperatorPicker() {
        val active = viewModel.activeOperatorSession()
        if (active != null) {
            showOperatorSessionDialog()
            return
        }

        val operators = viewModel.availableOperators()
        if (operators.isEmpty()) {
            Toast.makeText(requireContext(), "No operators are configured", Toast.LENGTH_LONG).show()
            return
        }

        val labels = operators.map { it.name }.toTypedArray()
        var selectedIndex = -1

        // Do not combine setMessage() with setItems()/setSingleChoiceItems().
        // On some Android themes that suppresses the operator list and leaves
        // only the message and Cancel button visible.
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Select Operator & Clock In")
            .setSingleChoiceItems(labels, -1) { _, index ->
                selectedIndex = index
            }
            .setPositiveButton("CLOCK IN", null)
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnShowListener {
            val startButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            startButton.isEnabled = false

            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                selectedIndex = position
                dialog.listView.setItemChecked(position, true)
                startButton.isEnabled = true
            }

            startButton.setOnClickListener {
                if (selectedIndex !in operators.indices) return@setOnClickListener
                val selected = operators[selectedIndex]
                viewModel.startOperatorSession(selected)
                    .onSuccess {
                        dialog.dismiss()
                        applyWorkspaceUi()
                        Toast.makeText(
                            requireContext(),
                            "Clocked in for ${selected.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .onFailure {
                        Toast.makeText(
                            requireContext(),
                            it.message ?: "Unable to start session",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
        dialog.show()
    }

    private fun showOperatorSessionDialog() {
        val session = viewModel.activeOperatorSession()
        if (session == null) {
            showOperatorPicker()
            return
        }
        val started = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date(session.startedAt))
        val visits = viewModel.operatorSessionSiteVisitOrder()
        val currentSite = viewModel.operatorSessionCurrentSite()
        val message = buildString {
            appendLine("Active operator: ${session.operatorName}")
            appendLine("Started: $started")
            appendLine("Photos: ${session.photoCount}")
            appendLine("Current site: ${currentSite ?: "No site locked"}")
            append("Sites visited: ")
            append(if (visits.isEmpty()) "None" else visits.joinToString(" → "))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Operator Clock-In Active")
            .setMessage(message)
            .setPositiveButton("Continue", null)
            .setNegativeButton("CLOCK OUT") { _, _ -> confirmEndOperatorSession(session.operatorName) }
            .show()
    }

    private fun confirmEndOperatorSession(operatorName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Clock out from $operatorName?")
            .setMessage("After clocking out, you can select another operator. Existing evidence keeps this clock-in record.")
            .setPositiveButton("CLOCK OUT") { _, _ ->
                viewModel.endOperatorSession()
                applyWorkspaceUi()
                Toast.makeText(requireContext(), "Clocked out from $operatorName", Toast.LENGTH_SHORT).show()
                showOperatorPicker()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSiteIdInfo() {
        val match = viewModel.uiState.value.siteMatch; val site = match?.site
        val msg = if (site != null) { val d = if (match.distanceM >= 1000) "≈%.1fkm".format(match.distanceM/1000) else "≈%.0fm".format(match.distanceM); "Site: ${site.siteId}\nOperator: ${site.operator}\nDistance: $d" }
        else "No site found. Go to Settings → Sync from GitHub to load full site database."
        AlertDialog.Builder(requireContext()).setTitle("Site ID").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun openGeoStampGallery() = startActivity(Intent(requireContext(), GalleryActivity::class.java))

    private fun openStampOptions() {
        parentFragmentManager.beginTransaction().replace(R.id.fragment_container, StampOptionsFragment()).addToBackStack(null).commit()
    }

    private fun showPreview(file: File) {
        parentFragmentManager.beginTransaction().replace(R.id.fragment_container, ImagePreviewFragment.newInstance(file.absolutePath)).addToBackStack("preview").commit()
    }

    private fun ensureGeoStampFolderExists() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GeoStamp")
                if (!folder.exists()) folder.mkdirs()
            }
        } catch (_: Exception) {}
    }
    private fun updateOrientationBadge() {
    val label = if (isLandscape) "H" else "V"

    try {
        binding.tvOrientationBadge.text = label

        binding.tvOrientationBadge.setTextColor(
            if (isLandscape)
                0xFFF59E0B.toInt()
            else
                0xFF22C55E.toInt()
        )

        binding.tvOrientationBadge.setBackgroundResource(
            if (isLandscape)
                R.drawable.bg_orient_landscape
            else
                R.drawable.bg_orient_portrait
        )

    } catch (_: Exception) {
    }
    }
    private fun setupOrientationListener() {
        orientationListener = object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(angle: Int) {
                if (angle == ORIENTATION_UNKNOWN) return

                // Corrected mapping: 
                // 0   -> ROTATION_0 (Natural Portrait)
                // 90  -> ROTATION_270 (Landscape Left - Button on Right)
                // 180 -> ROTATION_180 (Upside Down Portrait)
                // 270 -> ROTATION_90 (Landscape Right - Button on Left)
                val newRotation = when {
                    angle in 45..134   -> android.view.Surface.ROTATION_270
                    angle in 135..224  -> android.view.Surface.ROTATION_180
                    angle in 225..314  -> android.view.Surface.ROTATION_90
                    else               -> android.view.Surface.ROTATION_0
                }

                // Update CameraX capture rotation so EXIF is correct
                cameraManager.updateTargetRotation(newRotation)

                // UI feedback: Is it landscape or portrait?
                val newLandscape = (newRotation == android.view.Surface.ROTATION_90 ||
                                    newRotation == android.view.Surface.ROTATION_270)

                if (newLandscape != isLandscape) {
                    isLandscape = newLandscape
                    activity?.runOnUiThread {
                        updateOrientationBadge()
                    }
                }
            }
        }

        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // Do NOT call cameraManager.updateTargetRotation() here without arguments
        // because it would reset to getDisplayRotation() (usually ROTATION_0 in locked portrait)
        // overriding our OrientationEventListener's manual setting.

        isLandscape =
            newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    updateOrientationBadge()

    binding.root.post {

        val v =
            if (isCompactMode) binding.infoOverlayCompact
            else binding.infoOverlay

        if (v.width == 0 || v.height == 0) return@post

        val pad = 8.dpToPx().toFloat()

        val minY = binding.brandHeader.bottom.toFloat() + pad

        val maxY =
            (
                requireView().height -
                binding.bottomPanel.height -
                v.height * v.scaleY -
                pad
            ).coerceAtLeast(minY)

        v.x = v.x.coerceIn(
            pad,
            (
                requireView().width -
                v.width * v.scaleX -
                pad
            ).coerceAtLeast(pad)
        )

        v.y = v.y.coerceIn(minY, maxY)

        positionSidebarInitial()
    }
}
}
