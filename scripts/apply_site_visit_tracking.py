from pathlib import Path

ROOT = Path("app/src/main/java/com/axiominfratech/geostamp")
manager_path = ROOT / "core/OperatorSessionManager.kt"
vm_path = ROOT / "ui/MainViewModel.kt"
camera_path = ROOT / "ui/CameraFragment.kt"

# ── OperatorSessionManager: persist current site and ordered visit history ──
text = manager_path.read_text(encoding="utf-8")
if "fun updateSiteLock(siteId: String?)" not in text:
    text = text.replace(
        "            .putString(KEY_SITE_PHOTO_COUNTS, \"{}\")\n",
        "            .putString(KEY_SITE_PHOTO_COUNTS, \"{}\")\n"
        "            .remove(KEY_CURRENT_SITE)\n"
        "            .putString(KEY_SITE_VISIT_ORDER, \"[]\")\n"
        "            .putString(KEY_SITE_FIRST_SEEN, \"{}\")\n"
    )
    text = text.replace(
        "            .remove(KEY_SITE_PHOTO_COUNTS)\n",
        "            .remove(KEY_SITE_PHOTO_COUNTS)\n"
        "            .remove(KEY_CURRENT_SITE)\n"
        "            .remove(KEY_SITE_VISIT_ORDER)\n"
        "            .remove(KEY_SITE_FIRST_SEEN)\n"
    )
    anchor = "    /**\n     * Records a successful capture and resets the four-hour inactivity timer."
    methods = '''    /**
     * Updates the currently GPS-locked site while keeping the same operator session.
     * A site is appended only once to the ordered visit history.
     */
    fun updateSiteLock(siteId: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (readWithoutExpiry() == null) return false
        val clean = siteId?.trim()?.takeIf { it.isNotEmpty() && it != "–" }
        val previous = prefs.getString(KEY_CURRENT_SITE, null)
        if (previous == clean) return false

        val order = jsonArrayToList(prefs.getString(KEY_SITE_VISIT_ORDER, "[]") ?: "[]").toMutableList()
        val firstSeenRaw = prefs.getString(KEY_SITE_FIRST_SEEN, "{}") ?: "{}"
        val firstSeen = runCatching { JSONObject(firstSeenRaw) }.getOrElse { JSONObject() }
        if (clean != null && !order.contains(clean)) {
            order += clean
            firstSeen.put(clean, now)
        }

        prefs.edit()
            .putString(KEY_CURRENT_SITE, clean)
            .putString(KEY_SITE_VISIT_ORDER, JSONArray(order).toString())
            .putString(KEY_SITE_FIRST_SEEN, firstSeen.toString())
            .apply()
        return true
    }

    fun currentSiteId(): String? = prefs.getString(KEY_CURRENT_SITE, null)

    fun siteVisitOrder(): List<String> =
        jsonArrayToList(prefs.getString(KEY_SITE_VISIT_ORDER, "[]") ?: "[]")

    fun siteFirstSeenAt(siteId: String): Long = runCatching {
        JSONObject(prefs.getString(KEY_SITE_FIRST_SEEN, "{}") ?: "{}").optLong(siteId, 0L)
    }.getOrDefault(0L)

'''
    text = text.replace(anchor, methods + anchor)
    text = text.replace(
        "        private const val KEY_SITE_PHOTO_COUNTS = \"operator_session_site_photo_counts\"\n",
        "        private const val KEY_SITE_PHOTO_COUNTS = \"operator_session_site_photo_counts\"\n"
        "        private const val KEY_CURRENT_SITE = \"operator_session_current_site\"\n"
        "        private const val KEY_SITE_VISIT_ORDER = \"operator_session_site_visit_order\"\n"
        "        private const val KEY_SITE_FIRST_SEEN = \"operator_session_site_first_seen\"\n"
    )
    manager_path.write_text(text, encoding="utf-8")

# ── MainViewModel: update site lock whenever GPS match changes; expose history ──
text = vm_path.read_text(encoding="utf-8")
if "fun operatorSessionSiteVisitOrder()" not in text:
    text = text.replace(
        "                        _uiState.update {\n                            it.copy(\n                                currentLocation = geo,",
        "                        operatorSessions.updateSiteLock(match.site?.siteId)\n"
        "                        _uiState.update {\n                            it.copy(\n                                currentLocation = geo,"
    )
    text = text.replace(
        "            _uiState.update { it.copy(siteMatch = match, operatorSession = operatorSessions.active()) }\n",
        "            operatorSessions.updateSiteLock(match.site?.siteId)\n"
        "            _uiState.update { it.copy(siteMatch = match, operatorSession = operatorSessions.active()) }\n"
    )
    text = text.replace(
        "    fun activeOperatorSession(): OperatorSessionManager.Session? = operatorSessions.active()\n",
        "    fun activeOperatorSession(): OperatorSessionManager.Session? = operatorSessions.active()\n\n"
        "    fun operatorSessionCurrentSite(): String? = operatorSessions.currentSiteId()\n"
        "    fun operatorSessionSiteVisitOrder(): List<String> = operatorSessions.siteVisitOrder()\n"
        "    fun operatorSessionSiteFirstSeenAt(siteId: String): Long = operatorSessions.siteFirstSeenAt(siteId)\n"
    )
    vm_path.write_text(text, encoding="utf-8")

# ── Session dialog: show ordered sites instead of only a count ──
text = camera_path.read_text(encoding="utf-8")
if "Sites visited:" not in text:
    old = '''        val message = buildString {
            appendLine("Active operator: ${session.operatorName}")
            appendLine("Started: $started")
            appendLine("Photos: ${session.photoCount}")
            append("Sites: ${session.siteIds.size}")
        }
'''
    new = '''        val visits = viewModel.operatorSessionSiteVisitOrder()
        val currentSite = viewModel.operatorSessionCurrentSite()
        val message = buildString {
            appendLine("Active operator: ${session.operatorName}")
            appendLine("Started: $started")
            appendLine("Photos: ${session.photoCount}")
            appendLine("Current site: ${currentSite ?: "No site locked"}")
            append("Sites visited: ")
            append(if (visits.isEmpty()) "None" else visits.joinToString(" → "))
        }
'''
    if old not in text:
        raise SystemExit("CameraFragment session-dialog anchor not found")
    text = text.replace(old, new)
    camera_path.write_text(text, encoding="utf-8")

print("Applied operator session site-lock and ordered visit tracking.")
