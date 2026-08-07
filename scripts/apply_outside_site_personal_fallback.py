from pathlib import Path

path = Path("app/src/main/java/com/axiominfratech/geostamp/ui/CameraFragment.kt")
text = path.read_text(encoding="utf-8")

marker = "requestCaptureWithWorkspaceGuard"
if marker in text:
    print("Outside-site personal fallback already applied.")
    raise SystemExit(0)

old = '''        binding.btnCapture.setOnClickListener {
            val secs = viewModel.uiState.value.timerSeconds
            if (secs > 0) startTimerThenCapture(secs) else captureNow()
        }
'''
new = '''        binding.btnCapture.setOnClickListener {
            requestCaptureWithWorkspaceGuard()
        }
'''
if old not in text:
    raise SystemExit("Capture button anchor not found; patch not applied.")
text = text.replace(old, new, 1)

anchor = '''    private fun captureNow() {
        val dir = requireContext().filesDir.resolve("photos").also { it.mkdirs() }
        triggerCaptureWithFade(dir)
    }
'''
methods = r'''
    private fun requestCaptureWithWorkspaceGuard() {
        val mode = prefs.getString("workspace_mode", "organization") ?: "organization"
        if (mode == "personal") {
            continueCaptureAfterGuard()
            return
        }

        val session = viewModel.activeOperatorSession()
        if (session == null) {
            Toast.makeText(requireContext(), "Clock in to an operator before organization capture", Toast.LENGTH_LONG).show()
            showOperatorPicker()
            return
        }

        val match = viewModel.uiState.value.siteMatch
        val allowedRadius = viewModel.remoteAppConfig.value.policy.siteDetectionRadiusM
        val validSiteLock = match?.site != null && match.distanceM <= allowedRadius

        if (validSiteLock) {
            continueCaptureAfterGuard()
            return
        }

        val distanceText = match?.distanceM?.let {
            if (it >= 1000.0) "%.1f km".format(Locale.ENGLISH, it / 1000.0)
            else "%.0f m".format(Locale.ENGLISH, it)
        } ?: "unknown"

        AlertDialog.Builder(requireContext())
            .setTitle("Outside organization site radius")
            .setMessage(
                "No valid ${session.operatorName} site is locked within ${allowedRadius.toInt()} m. " +
                    "Nearest detected distance: $distanceText.\n\n" +
                    "You may continue as Personal Evidence. The operator clock-in will remain active, " +
                    "but this photo will not be added to any organization site folder or operator-session photo count."
            )
            .setPositiveButton("PERSONAL CAPTURE") { _, _ ->
                prefs.edit()
                    .putString("workspace_mode", "personal")
                    .putString("personal_title", "Field Evidence")
                    .putString("personal_reference", "Outside Site Radius")
                    .apply()
                applyWorkspaceUi()
                Toast.makeText(requireContext(), "Personal capture mode active", Toast.LENGTH_SHORT).show()
                continueCaptureAfterGuard()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun continueCaptureAfterGuard() {
        val secs = viewModel.uiState.value.timerSeconds
        if (secs > 0) startTimerThenCapture(secs) else captureNow()
    }

'''
if anchor not in text:
    raise SystemExit("captureNow anchor not found; patch not applied.")
text = text.replace(anchor, methods + anchor, 1)

path.write_text(text, encoding="utf-8")
print("Outside-site organization guard and personal capture fallback applied.")
