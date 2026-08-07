from pathlib import Path

# 1) Enforce remote inactivity timeout when operator session starts.
vm = Path("app/src/main/java/com/axiominfratech/geostamp/ui/MainViewModel.kt")
text = vm.read_text(encoding="utf-8")
text = text.replace(
    "val session = operatorSessions.start(operator)",
    "val session = operatorSessions.start(\n            operator,\n            _remoteAppConfig.value.policy.operatorInactivityTimeoutMinutes\n        )",
)
text = text.replace(
    "_remoteAppConfig.value = remoteConfig.loadCached()\n            _uiState.update",
    "_remoteAppConfig.value = remoteConfig.loadCached()\n            _stampConfig.update { current ->\n                current.copy(matchRadiusM = _remoteAppConfig.value.policy.siteDetectionRadiusM)\n            }\n            _uiState.update",
)
vm.write_text(text, encoding="utf-8")

# 2) Make site radius read-only in field settings and show admin-managed value.
settings = Path("app/src/main/java/com/axiominfratech/geostamp/ui/StampOptionsFragment.kt")
text = settings.read_text(encoding="utf-8")
old_listener = '''        // ── Radius slider ──────────────────────────────────────────────
        binding.sliderRadius.addOnChangeListener { _, value, _ ->
            binding.tvRadiusValue.text = "Radius: ${value.toInt()} m"
            viewModel.updateStampConfig(viewModel.stampConfig.value.copy(matchRadiusM = value.toDouble()))
        }
'''
text = text.replace(old_listener, '''        // Site radius is controlled remotely by the administrator.
        binding.sliderRadius.isEnabled = false
        binding.sliderRadius.alpha = 0.45f
''')
text = text.replace(
    'binding.sliderRadius.value = config.matchRadiusM.toFloat().coerceIn(5f, 3000f)\n                binding.tvRadiusValue.text = "Radius: ${config.matchRadiusM.toInt()} m"',
    'val adminRadius = viewModel.remoteAppConfig.value.policy.siteDetectionRadiusM.toFloat().coerceIn(0f, 1000f)\n                binding.sliderRadius.value = adminRadius\n                binding.tvRadiusValue.text = "Admin: ${adminRadius.toInt()} m"',
)
settings.write_text(text, encoding="utf-8")

# 3) Align the slider range and explanatory copy with admin policy.
layout = Path("app/src/main/res/layout/fragment_stamp_options.xml")
text = layout.read_text(encoding="utf-8")
text = text.replace('android:valueFrom="5" android:valueTo="3000"', 'android:valueFrom="0" android:valueTo="1000"')
text = text.replace('android:stepSize="5" android:value="10"', 'android:stepSize="10" android:value="1000"')
text = text.replace(
    'android:text="Organization is assigned through verified sign-in; Site ID is matched automatically from GPS data."',
    'android:text="This radius is managed by the GeoStamp administrator and synced automatically. Field users cannot change it."',
)
layout.write_text(text, encoding="utf-8")

print("Admin policy enforcement applied: radius locked to remote config and inactivity timeout synced.")
