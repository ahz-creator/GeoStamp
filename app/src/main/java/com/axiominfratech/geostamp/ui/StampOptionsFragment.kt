package com.axiominfratech.geostamp.ui

import android.animation.LayoutTransition
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.axiominfratech.geostamp.R
import com.axiominfratech.geostamp.databinding.FragmentStampOptionsBinding
import com.axiominfratech.geostamp.overlay.LiveInfoMode
import com.axiominfratech.geostamp.overlay.SavedStampLayout
import com.axiominfratech.geostamp.overlay.StampConfig
import com.axiominfratech.geostamp.overlay.StampTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

class StampOptionsFragment : Fragment(R.layout.fragment_stamp_options) {
    private var _binding: FragmentStampOptionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var applyingState = false

    private enum class Section { SITE, SAVED, INFO, CAMERA, PROTECTION }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentStampOptionsBinding.bind(view)
        binding.btnBack.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.text_primary))
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        hideRemovedLayouts()
        bindAccordions()
        bindControls()
        setupAutoSaveInset()

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

    private fun hideRemovedLayouts() {
        // Card is now the only saved-photo layout exposed to users.
        binding.radioLayoutStrip.visibility = View.GONE
        binding.radioLayoutFooter.visibility = View.GONE
        binding.radioLayoutCard.layoutParams = binding.radioLayoutCard.layoutParams.apply {
            width = 0
            (this as? android.widget.LinearLayout.LayoutParams)?.weight = 1f
        }
        binding.radioLayoutCard.text = "Card"
    }

    private fun bindAccordions() {
        val sections = listOf(
            binding.sectionSiteHeader to Pair(Section.SITE, binding.sectionSiteContent),
            binding.sectionSavedHeader to Pair(Section.SAVED, binding.sectionSavedContent),
            binding.sectionInfoHeader to Pair(Section.INFO, binding.sectionInfoContent),
            binding.sectionCameraHeader to Pair(Section.CAMERA, binding.sectionCameraContent),
            binding.sectionProtectionHeader to Pair(Section.PROTECTION, binding.sectionProtectionContent)
        )
        sections.forEach { (header, pair) -> header.setOnClickListener { openSection(pair.first) } }
        binding.sectionSavedContent.post { openSection(Section.SAVED, false) }
        binding.sectionSavedHeader.layoutParams = binding.sectionSavedHeader.layoutParams.apply { if (this is ViewGroup.MarginLayoutParams) bottomMargin = 0 }
        binding.sectionSavedContent.layoutParams = binding.sectionSavedContent.layoutParams.apply { if (this is ViewGroup.MarginLayoutParams) topMargin = 0 }
    }

    private fun openSection(section: Section, animate: Boolean = true) {
        val pairs = listOf(
            Section.SITE to binding.sectionSiteContent,
            Section.SAVED to binding.sectionSavedContent,
            Section.INFO to binding.sectionInfoContent,
            Section.CAMERA to binding.sectionCameraContent,
            Section.PROTECTION to binding.sectionProtectionContent
        )
        if (animate) (binding.sectionSavedContent.parent as? ViewGroup)?.layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        pairs.forEach { (key, content) ->
            content.animate().cancel()
            content.visibility = if (key == section) View.VISIBLE else View.GONE
            content.alpha = 1f
        }
        binding.tvSiteChevron.text = if (section == Section.SITE) "⌃" else "⌄"
        binding.tvSavedChevron.text = if (section == Section.SAVED) "⌃" else "⌄"
        binding.tvInfoChevron.text = if (section == Section.INFO) "⌃" else "⌄"
        binding.tvCameraChevron.text = if (section == Section.CAMERA) "⌃" else "⌄"
        binding.tvProtectionChevron.text = if (section == Section.PROTECTION) "⌃" else "⌄"
    }

    private fun bindControls() {
        binding.groupLayout.setOnCheckedChangeListener { _, id ->
            if (!applyingState && id == R.id.radio_layout_card) viewModel.updateSavedStampLayout(SavedStampLayout.CARD)
        }
        binding.groupSize.setOnCheckedChangeListener { _, id -> if (!applyingState) when (id) {
            R.id.radio_size_compact -> viewModel.updateSavedOverlayHeight(20f)
            R.id.radio_size_standard -> viewModel.updateSavedOverlayHeight(25f)
            R.id.radio_size_large -> viewModel.updateSavedOverlayHeight(30f)
        } }
        binding.sliderOverlayAlpha.addOnChangeListener { _, value, fromUser -> if (fromUser && !applyingState) viewModel.updateOverlayAlpha(value / 100f) }
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
    }

    private fun applyState(config: StampConfig) {
        applyingState = true
        try {
            if (config.savedStampLayout != SavedStampLayout.CARD) {
                // Migrate any previously persisted Strip/Footer selection to Card.
                viewModel.updateSavedStampLayout(SavedStampLayout.CARD)
            }
            val cardConfig = if (config.savedStampLayout == SavedStampLayout.CARD) config else config.copy(savedStampLayout = SavedStampLayout.CARD)
            applyShellTheme()
            applySegmentTheme(cardConfig.stampTheme)
            val radius = viewModel.remoteAppConfig.value.policy.siteDetectionRadiusM
            binding.tvRadiusValue.text = if (radius >= 1000) "${String.format("%.1f", radius / 1000.0)} km" else "${radius.toInt()} m"
            binding.tvSiteSummary.text = "Axiom Infratech · Site match ${binding.tvRadiusValue.text}"
            binding.tvOrganization.text = viewModel.remoteAppConfig.value.organization.name.ifBlank { "Axiom Infratech" }
            binding.tvOrgLogic.text = "Automatic from verified site/session"
            binding.radioLayoutCard.isChecked = true
            binding.radioLayoutStrip.isChecked = false
            binding.radioLayoutFooter.isChecked = false
            val size = nearestSize(cardConfig.savedOverlayHeightFraction)
            binding.radioSizeCompact.isChecked = size == 20
            binding.radioSizeStandard.isChecked = size == 25
            binding.radioSizeLarge.isChecked = size == 30
            val alpha = (cardConfig.overlayAlpha * 100f).coerceIn(20f, 90f)
            binding.sliderOverlayAlpha.value = alpha
            binding.tvAlphaValue.text = "${alpha.toInt()}%"
            binding.radioThemeDark.isChecked = cardConfig.stampTheme == StampTheme.DARK
            binding.radioThemeLight.isChecked = cardConfig.stampTheme == StampTheme.LIGHT
            binding.radioLiveFloating.isChecked = cardConfig.liveInfoMode == LiveInfoMode.FLOATING
            binding.radioLiveBottom.isChecked = cardConfig.liveInfoMode == LiveInfoMode.BOTTOM
            binding.radioLiveOff.isChecked = cardConfig.liveInfoMode == LiveInfoMode.OFF
            binding.tvCameraSummary.text = when (cardConfig.liveInfoMode) {
                LiveInfoMode.FLOATING -> "Floating Info Card"
                LiveInfoMode.BOTTOM -> "Bottom Info Strip"
                LiveInfoMode.OFF -> "Off"
            }
            binding.switchBlockSpoof.isChecked = cardConfig.blockIfSpoofDetected
            binding.tvProtectionSummary.text = if (cardConfig.blockIfSpoofDetected) "Capture blocked on untrusted location" else "Location trust protection"
            binding.tvProtectionManaged.text = "Available for this device/session"
            binding.tvSavedSummary.text = "Card · ${sizeLabel(size)} · ${alpha.toInt()}% · ${if (cardConfig.stampTheme == StampTheme.DARK) "Dark" else "Light"}"
            refreshPreview(cardConfig)
        } finally { applyingState = false }
    }

    private fun nearestSize(fraction: Float): Int = listOf(20, 25, 30).minByOrNull { abs(it / 100f - fraction) } ?: 25
    private fun sizeLabel(size: Int) = when (size) { 20 -> "Compact"; 30 -> "Large"; else -> "Standard" }

    private fun refreshPreview(config: StampConfig) {
        val density = resources.displayMetrics.density
        val primary = if (config.stampTheme == StampTheme.LIGHT) Color.parseColor("#0B1830") else Color.WHITE
        val secondary = if (config.stampTheme == StampTheme.LIGHT) Color.parseColor("#64748B") else Color.parseColor("#CBD5E1")
        val border = if (config.stampTheme == StampTheme.LIGHT) Color.parseColor("#CBD7E5") else Color.parseColor("#233651")
        val surface = if (config.stampTheme == StampTheme.LIGHT) Color.WHITE else Color.rgb(8, 12, 18)
        val sizeMultiplier = config.savedOverlayHeightFraction.coerceIn(0.20f, 0.30f) / 0.25f
        binding.previewFrame.layoutParams = binding.previewFrame.layoutParams.apply { height = (250f * density).toInt() }
        binding.previewFrame.setBackgroundColor(Color.rgb(38, 43, 48))
        binding.overlayPreviewCard.layoutParams = binding.overlayPreviewCard.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = (180f * sizeMultiplier * density).toInt().coerceIn((42*density).toInt(), (210*density).toInt())
        }
        binding.overlayPreviewCard.setPadding((12*density).toInt(), (10*density).toInt(), (12*density).toInt(), (10*density).toInt())
        binding.overlayPreviewCard.background = GradientDrawable().apply {
            cornerRadius = 16f*density
            setColor(Color.argb((config.overlayAlpha * 255f).toInt().coerceIn(50,230), Color.red(surface), Color.green(surface), Color.blue(surface)))
            setStroke((1*density).toInt().coerceAtLeast(1), border)
        }
        binding.previewStatus.visibility = View.VISIBLE
        binding.previewCoords.visibility = View.VISIBLE
        binding.previewTime.visibility = View.VISIBLE
        binding.previewSite.visibility = View.VISIBLE
        binding.previewStatus.text = "✓  LOCATION VERIFIED"
        binding.previewCoords.text = "24.8610° N, 67.0101° E"
        binding.previewTime.text = "02 May 2026  ·  14:30  ·  ±5 m"
        binding.previewSite.text = "SITE ID  PKZ-KHI-001   •   Evidence ID"
        binding.previewStatus.setTextColor(Color.rgb(34,197,94))
        binding.previewCoords.setTextColor(primary)
        binding.previewTime.setTextColor(secondary)
        binding.previewSite.setTextColor(Color.parseColor("#8B5CF6"))
        binding.overlayPreviewCard.contentDescription = "Card ${sizeLabel(nearestSize(config.savedOverlayHeightFraction))} ${config.stampTheme.name} stamp preview"
        binding.previewFrame.requestLayout()
        binding.overlayPreviewCard.requestLayout()
    }

    private fun applyShellTheme() {
        binding.stampSettingsRoot.setBackgroundColor(Color.parseColor("#07101F"))
        binding.tvTitle.setTextColor(Color.parseColor("#F5F7FA"))
        binding.tvSubtitle.setTextColor(Color.parseColor("#A9B6C8"))
        val fill = Color.parseColor("#111D2D")
        val border = Color.parseColor("#233651")
        listOf(binding.sectionSiteHeader,binding.sectionSavedHeader,binding.sectionInfoHeader,binding.sectionCameraHeader,binding.sectionProtectionHeader,
            binding.sectionSiteContent,binding.sectionSavedContent,binding.sectionInfoContent,binding.sectionCameraContent,binding.sectionProtectionContent).forEach {
            it.background = GradientDrawable().apply { cornerRadius = 16f*resources.displayMetrics.density; setColor(fill); setStroke(1,border) }
            styleTree(it)
        }
    }

    private fun styleTree(root: View) {
        if (root is TextView) root.setTextColor(when {
            root.id == R.id.tv_site_title || root.id == R.id.tv_saved_summary || root.id == R.id.tv_info_summary || root.id == R.id.tv_camera_summary || root.id == R.id.tv_protection_summary -> Color.parseColor("#19A9DC")
            else -> Color.parseColor("#F5F7FA")
        })
        if (root is ViewGroup) for (i in 0 until root.childCount) styleTree(root.getChildAt(i))
    }

    private fun applySegmentTheme(theme: StampTheme) {
        val light = theme == StampTheme.LIGHT
        val selectedBg = Color.parseColor("#19A9DC")
        val selectedText = Color.parseColor("#07182B")
        val normalBg = Color.parseColor(if (light) "#F4F7FA" else "#0B1424")
        val normalText = Color.parseColor(if (light) "#0B1830" else "#A9B6C8")
        val border = Color.parseColor(if (light) "#CBD7E5" else "#233651")
        listOf(binding.groupLayout,binding.groupSize,binding.groupTheme,binding.groupLiveInfo).forEach { group ->
            for (i in 0 until group.childCount) {
                val button = group.getChildAt(i) as? RadioButton ?: continue
                val checked = GradientDrawable().apply { cornerRadius=5f*resources.displayMetrics.density; setColor(selectedBg); setStroke(1,selectedBg) }
                val normal = GradientDrawable().apply { cornerRadius=5f*resources.displayMetrics.density; setColor(normalBg); setStroke(1,border) }
                button.background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), checked)
                    addState(intArrayOf(), normal)
                }
                button.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(selectedText,normalText)))
                button.buttonTintList = null
            }
        }
    }

    private fun setupAutoSaveInset() {
        val scroll = binding.settingsScroll
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, nav + (12*resources.displayMetrics.density).toInt())
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
