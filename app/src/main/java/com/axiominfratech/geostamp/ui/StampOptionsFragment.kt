package com.axiominfratech.geostamp.ui

import android.animation.LayoutTransition
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.axiominfratech.geostamp.R
import com.axiominfratech.geostamp.databinding.FragmentStampOptionsBinding
import com.axiominfratech.geostamp.overlay.LiveInfoMode
import com.axiominfratech.geostamp.overlay.SavedStampLayout
import com.axiominfratech.geostamp.overlay.StampTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

class StampOptionsFragment : Fragment(R.layout.fragment_stamp_options) {
    private var _binding: FragmentStampOptionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private enum class Section { SITE, SAVED, INFO, CAMERA, PROTECTION }
    private var openSection = Section.SAVED
    private var applyingState = false

    private data class Tokens(
        val page: Int, val card: Int, val border: Int, val primary: Int,
        val secondary: Int, val accent: Int, val preview: Int
    )

    private fun tokens(theme: StampTheme) = if (theme == StampTheme.LIGHT) Tokens(
        Color.parseColor("#F5F8FC"), Color.WHITE, Color.parseColor("#D8E2EE"),
        Color.parseColor("#0B1830"), Color.parseColor("#64748B"), Color.parseColor("#0AAFE6"), Color.WHITE
    ) else Tokens(
        Color.parseColor("#07101F"), Color.parseColor("#111D2D"), Color.parseColor("#233651"),
        Color.parseColor("#F5F7FA"), Color.parseColor("#A9B6C8"), Color.parseColor("#25C7DF"), Color.BLACK
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentStampOptionsBinding.bind(view)

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        listOf(
            binding.sectionSiteHeader to Section.SITE,
            binding.sectionSavedHeader to Section.SAVED,
            binding.sectionInfoHeader to Section.INFO,
            binding.sectionCameraHeader to Section.CAMERA,
            binding.sectionProtectionHeader to Section.PROTECTION
        ).forEach { (header, section) -> header.setOnClickListener { setOpenSection(section, true) } }
        binding.sectionSavedContent.post { setOpenSection(Section.SAVED, false) }

        binding.groupLayout.setOnCheckedChangeListener { _, id -> if (!applyingState) when (id) {
            R.id.radio_layout_card -> viewModel.updateSavedStampLayout(SavedStampLayout.CARD)
            R.id.radio_layout_strip -> viewModel.updateSavedStampLayout(SavedStampLayout.STRIP)
            R.id.radio_layout_footer -> viewModel.updateSavedStampLayout(SavedStampLayout.FOOTER)
        } }
        binding.groupSize.setOnCheckedChangeListener { _, id -> if (!applyingState) when (id) {
            R.id.radio_size_compact -> viewModel.updateSavedOverlayHeight(20f)
            R.id.radio_size_standard -> viewModel.updateSavedOverlayHeight(25f)
            R.id.radio_size_large -> viewModel.updateSavedOverlayHeight(30f)
        } }
        binding.sliderOverlayAlpha.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingState) viewModel.updateOverlayAlpha(value / 100f)
        }
        binding.groupTheme.setOnCheckedChangeListener { _, id -> if (!applyingState) when (id) {
            R.id.radio_theme_dark -> viewModel.updateStampTheme(StampTheme.DARK)
            R.id.radio_theme_light -> viewModel.updateStampTheme(StampTheme.LIGHT)
        } }
        binding.groupLiveInfo.setOnCheckedChangeListener { _, id -> if (!applyingState) when (id) {
            R.id.radio_live_floating -> viewModel.updateLiveInfoMode(LiveInfoMode.FLOATING)
            R.id.radio_live_bottom -> viewModel.updateLiveInfoMode(LiveInfoMode.BOTTOM)
            R.id.radio_live_off -> viewModel.updateLiveInfoMode(LiveInfoMode.OFF)
        } }
        binding.switchBlockSpoof.setOnCheckedChangeListener { _, checked ->
            if (!applyingState) viewModel.updateStampConfig(viewModel.stampConfig.value.copy(blockIfSpoofDetected = checked))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stampConfig.collect { applyState(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                val match = state.siteMatch
                binding.tvSiteStatus.text = when {
                    state.gpsStatus == MainViewModel.GpsStatus.SPOOFED -> "Location trust warning detected"
                    match?.site != null -> "Matched: ${match.site.siteId} · ${match.distanceM.toInt()} m"
                    else -> "Waiting for a verified site match"
                }
            }
        }
    }

    private fun setOpenSection(section: Section, animate: Boolean) {
        openSection = section
        val pairs = listOf(
            Section.SITE to binding.sectionSiteContent,
            Section.SAVED to binding.sectionSavedContent,
            Section.INFO to binding.sectionInfoContent,
            Section.CAMERA to binding.sectionCameraContent,
            Section.PROTECTION to binding.sectionProtectionContent
        )
        if (animate) binding.sectionSavedContent.parent?.let { (it as? ViewGroup)?.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
        } }
        pairs.forEach { (key, content) ->
            val show = key == section
            if (show && content.visibility != View.VISIBLE) {
                content.alpha = 0f
                content.visibility = View.VISIBLE
                content.animate().alpha(1f).setDuration(180).start()
            } else if (!show) {
                content.animate().alpha(0f).setDuration(120).withEndAction { content.visibility = View.GONE }.start()
            }
        }
        binding.tvSiteChevron.text = if (section == Section.SITE) "⌃" else "⌄"
        binding.tvSavedChevron.text = if (section == Section.SAVED) "⌃" else "⌄"
        binding.tvInfoChevron.text = if (section == Section.INFO) "⌃" else "⌄"
        binding.tvCameraChevron.text = if (section == Section.CAMERA) "⌃" else "⌄"
        binding.tvProtectionChevron.text = if (section == Section.PROTECTION) "⌃" else "⌄"
    }

    private fun applyState(config: com.axiominfratech.geostamp.overlay.StampConfig) {
        applyingState = true
        try {
            val t = tokens(config.stampTheme)
            applyTokens(t)

            val radius = viewModel.remoteAppConfig.value.policy.siteDetectionRadiusM
            val radiusLabel = if (radius >= 1000) "${(radius / 1000).let { if (it % 1.0 == 0.0) it.toInt() else String.format("%.1f", it) }} km" else "${radius.toInt()} m"
            binding.tvRadiusValue.text = radiusLabel
            binding.tvSiteSummary.text = "Axiom Infratech · Site match $radiusLabel"
            binding.tvOrganization.text = viewModel.remoteAppConfig.value.organization.name.ifBlank { "Axiom Infratech" }
            binding.tvOrgLogic.text = "Automatic from verified site/session"

            binding.radioLayoutCard.isChecked = config.savedStampLayout == SavedStampLayout.CARD
            binding.radioLayoutStrip.isChecked = config.savedStampLayout == SavedStampLayout.STRIP
            binding.radioLayoutFooter.isChecked = config.savedStampLayout == SavedStampLayout.FOOTER

            val nearest = nearestHeight(config.savedOverlayHeightFraction)
            binding.radioSizeCompact.isChecked = nearest == 20
            binding.radioSizeStandard.isChecked = nearest == 25
            binding.radioSizeLarge.isChecked = nearest == 30

            val alphaPercent = (config.overlayAlpha * 100f).coerceIn(20f, 90f)
            binding.sliderOverlayAlpha.value = alphaPercent
            binding.tvAlphaValue.text = "${alphaPercent.toInt()}%"

            binding.radioThemeDark.isChecked = config.stampTheme == StampTheme.DARK
            binding.radioThemeLight.isChecked = config.stampTheme == StampTheme.LIGHT

            binding.radioLiveFloating.isChecked = config.liveInfoMode == LiveInfoMode.FLOATING
            binding.radioLiveBottom.isChecked = config.liveInfoMode == LiveInfoMode.BOTTOM
            binding.radioLiveOff.isChecked = config.liveInfoMode == LiveInfoMode.OFF
            binding.tvCameraSummary.text = when (config.liveInfoMode) {
                LiveInfoMode.FLOATING -> "Floating Info Card"
                LiveInfoMode.BOTTOM -> "Bottom Info Strip"
                LiveInfoMode.OFF -> "Off"
            }

            binding.switchBlockSpoof.isChecked = config.blockIfSpoofDetected
            binding.tvProtectionSummary.text = if (config.blockIfSpoofDetected) "Capture blocked on untrusted location" else "Location trust protection"
            binding.tvProtectionManaged.text = "Available for this device/session"

            binding.tvSavedSummary.text = "${layoutLabel(config.savedStampLayout)} · ${sizeLabel(nearest)} · ${alphaPercent.toInt()}% · ${if (config.stampTheme == StampTheme.DARK) "Dark" else "Light"}"
            refreshPreview(config, t)
        } finally { applyingState = false }
    }

    private fun nearestHeight(fraction: Float): Int = listOf(20,25,30).minByOrNull { abs(it/100f-fraction) } ?: 25
    private fun sizeLabel(v: Int) = when(v) {20->"Compact";30->"Large";else->"Standard"}
    private fun layoutLabel(v: SavedStampLayout) = when(v) { SavedStampLayout.CARD->"Card"; SavedStampLayout.STRIP->"Strip"; SavedStampLayout.FOOTER->"Footer" }

    private fun refreshPreview(config: com.axiominfratech.geostamp.overlay.StampConfig, t: Tokens) {
        binding.previewFrame.setBackgroundColor(t.preview)
        val lp = binding.overlayPreviewCard.layoutParams
        when (config.savedStampLayout) {
            SavedStampLayout.CARD -> { lp.height = (150 * resources.displayMetrics.density).toInt().coerceAtLeast(1); binding.overlayPreviewCard.layoutParams = lp; binding.overlayPreviewCard.setPadding(12,12,12,12); binding.overlayPreviewCard.gravity = android.view.Gravity.NO_GRAVITY }
            SavedStampLayout.STRIP -> { lp.height = (92 * resources.displayMetrics.density).toInt(); binding.overlayPreviewCard.layoutParams = lp; binding.overlayPreviewCard.setPadding(12,8,12,8); binding.overlayPreviewCard.gravity = android.view.Gravity.CENTER_VERTICAL }
            SavedStampLayout.FOOTER -> { lp.height = (58 * resources.displayMetrics.density).toInt(); binding.overlayPreviewCard.layoutParams = lp; binding.overlayPreviewCard.setPadding(12,6,12,6); binding.overlayPreviewCard.gravity = android.view.Gravity.CENTER_VERTICAL }
        }
        val bg = GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            val a = (config.overlayAlpha * 255).toInt().coerceIn(50,230)
            setColor(Color.argb(a, Color.red(t.card), Color.green(t.card), Color.blue(t.card)))
            setStroke((1f * resources.displayMetrics.density).toInt(), t.border)
        }
        binding.overlayPreviewCard.background = bg
        val text = if (config.stampTheme == StampTheme.LIGHT) Color.parseColor("#0B1830") else Color.WHITE
        val secondary = if (config.stampTheme == StampTheme.LIGHT) Color.parseColor("#64748B") else Color.parseColor("#A9B6C8")
        binding.previewStatus.setTextColor(Color.rgb(34,197,94))
        binding.previewCoords.setTextColor(text)
        binding.previewTime.setTextColor(secondary)
        binding.previewSite.setTextColor(secondary)
        binding.previewStatus.text = "✓ Location verified"
        binding.previewCoords.text = "24.8610° N, 67.0101° E"
        binding.previewTime.text = "02 May 2026, 14:30"
        binding.previewSite.text = "Site ID: PKZ-KHI-001"
    }

    private fun applyTokens(t: Tokens) {
        binding.stampSettingsRoot.setBackgroundColor(t.page)
        binding.tvTitle.setTextColor(t.primary)
        binding.tvSubtitle.setTextColor(t.accent)
        binding.tvAutoSave.setTextColor(t.secondary)
        val headers = listOf(binding.sectionSiteHeader,binding.sectionSavedHeader,binding.sectionInfoHeader,binding.sectionCameraHeader,binding.sectionProtectionHeader)
        val contents = listOf(binding.sectionSiteContent,binding.sectionSavedContent,binding.sectionInfoContent,binding.sectionCameraContent,binding.sectionProtectionContent)
        headers.forEach { applyCard(it,t.card,t.border); styleTree(it,t) }
        contents.forEach { applyCard(it,t.card,t.border); styleTree(it,t) }
        binding.previewFrame.setBackgroundColor(t.preview)
        ViewCompat.getWindowInsetsController(requireView())?.isAppearanceLightStatusBars = t == tokens(StampTheme.LIGHT)
        requireActivity().window.statusBarColor = t.page
        requireActivity().window.navigationBarColor = t.page
    }

    private fun applyCard(view: View, fill: Int, stroke: Int) {
        view.background = GradientDrawable().apply { cornerRadius=16f*resources.displayMetrics.density; setColor(fill); setStroke((1f*resources.displayMetrics.density).toInt(),stroke) }
    }
    private fun styleTree(root: View, t: Tokens) {
        if (root is TextView) {
            root.setTextColor(when {
                root.id == R.id.tv_site_title || root.id == R.id.tv_saved_summary || root.id == R.id.tv_info_summary || root.id == R.id.tv_camera_summary || root.id == R.id.tv_protection_summary -> t.accent
                root.id == R.id.tv_site_status -> t.primary
                root.text.toString().contains("REQUIRED") || root.text.toString().contains("OPTIONAL") -> t.accent
                root.text.toString().contains("Managed") || root.text.toString().contains("Required") -> t.secondary
                else -> t.primary
            })
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) styleTree(root.getChildAt(i), t)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
