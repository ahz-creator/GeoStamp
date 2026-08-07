from pathlib import Path

path = Path("app/src/main/java/com/axiominfratech/geostamp/ui/MainActivity.kt")
text = path.read_text(encoding="utf-8")
original = text

# Remove imports injected by apply_visible_session_banner.py
for line in [
    "import android.graphics.Color\n",
    "import android.graphics.Typeface\n",
    "import android.os.Handler\n",
    "import android.os.Looper\n",
    "import android.view.Gravity\n",
    "import android.view.View\n",
    "import android.view.ViewGroup\n",
    "import android.widget.Button\n",
    "import android.widget.FrameLayout\n",
    "import android.widget.LinearLayout\n",
    "import android.widget.TextView\n",
    "import java.util.Locale\n",
]:
    text = text.replace(line, "")

fields = '''
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
'''
text = text.replace(fields, "")
text = text.replace("        installOperatorSessionBanner()\n", "")
text = text.replace("        observeOperatorSessionBanner()\n", "")

start = text.find("    override fun onResume() {\n        super.onResume()\n        refreshOperatorSessionBanner()")
end_anchor = "    private fun checkConsentThenPermissions() {"
if start != -1:
    end = text.find(end_anchor, start)
    if end == -1:
        raise SystemExit("Could not locate end of injected banner block.")
    text = text[:start] + text[end:]

if text == original:
    print("No global session banner injection found. Nothing to remove.")
else:
    path.write_text(text, encoding="utf-8")
    print("Removed duplicate global operator-session banner from MainActivity.")
    print("The existing Organization/Personal pill remains the single session control.")
