from pathlib import Path

path = Path("app/src/main/java/com/axiominfratech/geostamp/ui/MainActivity.kt")
text = path.read_text(encoding="utf-8")

if "installOperatorSessionBanner()" in text:
    print("Visible operator-session banner is already applied.")
    raise SystemExit(0)

imports = """import android.graphics.Color
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
"""
text = text.replace("import android.net.Uri\n", "import android.net.Uri\n" + imports)

fields = """
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
"""
text = text.replace(
    "    private val viewModel: MainViewModel by viewModels()\n",
    "    private val viewModel: MainViewModel by viewModels()\n" + fields,
)

text = text.replace(
    "        setContentView(binding.root)\n",
    "        setContentView(binding.root)\n        installOperatorSessionBanner()\n",
)
text = text.replace(
    "        observeSecurityWarnings()\n",
    "        observeSecurityWarnings()\n        observeOperatorSessionBanner()\n",
    1,
)

anchor = "    private fun checkConsentThenPermissions() {"
methods = r'''
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

'''
if anchor not in text:
    raise SystemExit("MainActivity anchor not found; patch not applied.")
text = text.replace(anchor, methods + anchor, 1)
path.write_text(text, encoding="utf-8")
print("Visible operator-session banner applied to MainActivity.kt")
