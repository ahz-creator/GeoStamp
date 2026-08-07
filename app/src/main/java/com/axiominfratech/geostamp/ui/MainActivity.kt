package com.axiominfratech.geostamp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.axiominfratech.geostamp.R
import com.axiominfratech.geostamp.databinding.ActivityMainBinding
import com.axiominfratech.geostamp.security.AntiSpoofManager
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var operatorSessionBanner: LinearLayout
    private lateinit var operatorSessionTitle: TextView
    private lateinit var operatorSessionDetails: TextView
    private val operatorSessionHandler = Handler(Looper.getMainLooper())
    private val operatorSessionTick = object : Runnable {
        override fun run() {
            refreshOperatorSessionBanner()
            operatorSessionHandler.postDelayed(this, 30_000L)
        }
    }

    // ── Permission list adapted per Android version ──────────────────────────
    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            when {
                // Android 13+ (API 33+): use granular media permission
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                // Android 10–12: no extra storage permission needed (scoped storage)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> { /* scoped storage — no extra */ }
                // Android 9 and below: need legacy storage
                else -> {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted   = results[Manifest.permission.CAMERA] == true
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            cameraGranted && locationGranted -> proceedToCamera()
            !cameraGranted -> showPermissionRationale(
                "Camera permission is required to capture field photos."
            )
            else -> showPermissionRationale(
                "Location permission is required to embed GPS data in photos."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: camera preview fills entire screen
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installOperatorSessionBanner()
        // Only check permissions on fresh start, not after rotation
        if (savedInstanceState == null) {
            checkConsentThenPermissions()
        }
        observeSecurityWarnings()
        observeOperatorSessionBanner()
    }


    override fun onResume() {
        super.onResume()
        refreshOperatorSessionBanner()
        operatorSessionHandler.removeCallbacks(operatorSessionTick)
        operatorSessionHandler.post(operatorSessionTick)
    }

    override fun onPause() {
        operatorSessionHandler.removeCallbacks(operatorSessionTick)
        super.onPause()
    }

    private fun installOperatorSessionBanner() {
        val root = binding.root as ViewGroup
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        operatorSessionBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.argb(242, 5, 25, 45))
            elevation = dp(12).toFloat()
            visibility = View.GONE
        }

        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        operatorSessionTitle = TextView(this).apply {
            setTextColor(Color.rgb(47, 207, 229))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            maxLines = 1
        }
        operatorSessionDetails = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 2
        }
        textBox.addView(operatorSessionTitle)
        textBox.addView(operatorSessionDetails)

        val clockOutButton = Button(this).apply {
            text = "CLOCK OUT"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(178, 45, 58))
            setOnClickListener { confirmOperatorClockOut() }
        }
        operatorSessionBanner.addView(textBox)
        operatorSessionBanner.addView(
            clockOutButton,
            LinearLayout.LayoutParams(dp(94), dp(46))
        )

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = dp(88)
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        root.addView(operatorSessionBanner, params)
    }

    private fun observeOperatorSessionBanner() {
        lifecycleScope.launch {
            viewModel.uiState.collect { refreshOperatorSessionBanner() }
        }
    }

    private fun refreshOperatorSessionBanner() {
        if (!::operatorSessionBanner.isInitialized) return
        val mode = getSharedPreferences("geostamp_prefs", MODE_PRIVATE)
            .getString("workspace_mode", "organization") ?: "organization"
        val session = viewModel.activeOperatorSession()
        if (mode != "organization" || session == null) {
            operatorSessionBanner.visibility = View.GONE
            return
        }

        val match = viewModel.uiState.value.siteMatch
        val site = match?.site?.siteId ?: "NO SITE LOCKED"
        val distance = match?.distanceM?.let {
            if (it >= 1000.0) String.format(Locale.ENGLISH, "%.1f km", it / 1000.0)
            else String.format(Locale.ENGLISH, "%.0f m", it)
        } ?: "GPS SEARCHING"

        val expiryAt = session.lastActivityAt + session.inactivityTimeoutMinutes * 60_000L
        val remaining = (expiryAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val hours = remaining / 3_600_000L
        val minutes = (remaining % 3_600_000L) / 60_000L

        operatorSessionTitle.text = "${session.operatorName.uppercase()}  •  OPERATOR CLOCKED IN"
        operatorSessionDetails.text =
            "$site  •  $distance  •  ${session.photoCount} photos\n" +
            "Auto clock-out in ${hours}h ${minutes}m  •  Test radius 1000m"
        operatorSessionBanner.visibility = View.VISIBLE
        operatorSessionBanner.bringToFront()
    }

    private fun confirmOperatorClockOut() {
        val session = viewModel.activeOperatorSession() ?: return
        AlertDialog.Builder(this)
            .setTitle("Clock out from ${session.operatorName}?")
            .setMessage("The operator session and its evidence activity will be finalized.")
            .setPositiveButton("CLOCK OUT") { _, _ ->
                viewModel.endOperatorSession()
                refreshOperatorSessionBanner()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkConsentThenPermissions() {
        val prefs = getSharedPreferences("geostamp_consent", MODE_PRIVATE)
        val acceptedVersion = prefs.getInt("device_evidence_consent_version", 0)
        if (acceptedVersion >= 1) {
            checkSecurityAndPermissions()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Device & Evidence Integrity Consent")
            .setMessage(
                "GeoStamp records the phone brand and model, an app-scoped trusted device identity, " +
                    "capture time, location, image hash and technical integrity signals to seal and verify evidence.\n\n" +
                    "GeoStamp also creates a device-protected cryptographic signing key and silently compares " +
                    "device-continuity signals for fraud detection and forensic reporting.\n\n" +
                    "Routine matching and internal identity-history updates do not generate notifications. " +
                    "Relevant integrity findings may appear in verification or forensic reports.\n\n" +
                    "GeoStamp does not access or fabricate IMEI, IMSI or SIM serial numbers."
            )
            .setPositiveButton("I AGREE & CONTINUE") { _, _ ->
                prefs.edit()
                    .putInt("device_evidence_consent_version", 1)
                    .putLong("device_evidence_consent_accepted_at", System.currentTimeMillis())
                    .apply()
                checkSecurityAndPermissions()
            }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }

    private fun checkSecurityAndPermissions() {
        if (hasAllRequiredPermissions()) {
            proceedToCamera()
        } else {
            val missingCamera   = !hasPermission(Manifest.permission.CAMERA)
            val missingLocation = !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                                  !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (shouldShowRationale(missingCamera, missingLocation)) {
                showPermissionExplanationDialog()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        val cameraOk   = hasPermission(Manifest.permission.CAMERA)
        val locationOk = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                         hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        return cameraOk && locationOk
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun shouldShowRationale(missingCamera: Boolean, missingLocation: Boolean): Boolean {
        if (missingCamera   && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) return true
        if (missingLocation && shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) return true
        return false
    }

    private fun proceedToCamera() {
        // Guard: only replace fragment if it's not already showing
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CameraFragment())
                .commit()
        }
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "GeoStamp needs the following to work:\n\n" +
                "📷  Camera — to capture field photos\n" +
                "📍  Location — to embed verified GPS coordinates\n" +
                "📁  Storage — to save stamped photos to gallery\n\n" +
                "These are required for the app to function."
            )
            .setPositiveButton("Grant Permissions") { _, _ ->
                permissionLauncher.launch(requiredPermissions)
            }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }

    private fun observeSecurityWarnings() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                state.securityWarnings.firstOrNull {
                    it.level == AntiSpoofManager.ThreatLevel.CRITICAL
                }?.let { showCriticalSecurityDialog(it) }
            }
        }
    }

    private fun showCriticalSecurityDialog(warning: AntiSpoofManager.SecurityWarning) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Security Warning: ${warning.title}")
            .setMessage(warning.message)
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showPermissionRationale(reason: String) {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("$reason\n\nPlease grant permissions in App Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Exit") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }
}
