package com.axiominfratech.geostamp.ui

import android.animation.LayoutTransition
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
        Color.parseColor("#07101F"), Color.parseColor("#111D2D"), Color.parseColor("#233651"),
        Color.parseColor("#F5F7FA"), Color.parseColor("#A9B6C8"), Color.parseColor("#19A9DC"), Color.BLACK
    ) else Tokens(
        Color.parseColor("#07101F"), Color.parseColor("#111D2D"), Color.parseColor("#233651"),
        Color.parseColor("#F5F7FA"), Color.parseColor("#A9B6C8"), Color.parseColor("#19A9DC"), Color.BLACK
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentStampOptionsBinding.bind(view)

        // The application shell stays dark. Stamp Theme never changes the app chrome.
        binding.btnBack.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        listOf(
            binding.sectionSiteHeader to Section.SITE,
            binding.sectionSavedHeader to Section.SAVED,
            binding.sectionInfoHeader to Section.INFO,
            binding.sectionCameraHeader to Section.CAMERA,
            binding.sectionProtectionHeader to Section.PROTECTION
        ).forEach { (header, section) -> header.setOnClickListener { setOpenSection(section, true) } }
        binding.sectionSavedContent.post { setOpenSection(Section.SAVED, false) }

        renameStampThemeLabel()
        setupConnectedSavedAccordion()
        setupAutoSaveInset()

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

    private fun renameStampThemeLabel() {
        fun walk(v: View) {
            if (v is TextView && v.text.toString().trim().equals("Theme", ignoreCase = true)) {
                v.text = "STAMP THEME"
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(binding.sectionSavedContent)
    }

    private fun setupConnectedSavedAccordion() {
        binding.sectionSavedHeader.layoutParams = binding.sectionSavedHeader.layoutParams.apply {
            if (this is ViewGroup.MarginLayoutParams) bottomMargin = 0
        }
        binding.sectionSavedContent.layoutParams = binding.sectionSavedContent.layoutParams.apply {
            if (this is ViewGroup.MarginLayoutParams) topMargin = 0
        }
        binding.sectionSavedContent.setPadding(
            binding.sectionSavedContent.paddingLeft,
            binding.sectionSavedContent.paddingTop,
            binding.sectionSavedContent.paddingRight,
            binding.sectionSavedContent.paddingBottom
        )
    }

    private fun setupAutoSaveInset() {
        val scroll = binding.settingsScroll
        val content = scroll.getChildAt(0) as? ViewGroup ?: return
        val autoSave = binding.tvAutoSave

        // Keep the save notice inside the scrollable content so it can never float over
        // the preview or the last accordion.
        if (autoSave.parent !== content) {
            (autoSave.parent as? ViewGroup)?.removeView(autoSave)
            content.addView(autoSave)
        }

        autoSave.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16.dpToPx()
            bottomMargin = 16.dpToPx()
        }

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, navBottom)
            autoSave.layoutParams = autoSave.layoutParams.apply {
                if (this is ViewGroup.MarginLayoutParams) bottomMargin = navBottom + 16.dpToPx()
            }
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
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
            val shell = tokens(StampTheme.DARK)
            applyTokens(shell)
            applySegmentTheme(config.stampTheme)

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
            refreshPreview(config, config.stampTheme)
        } finally { applyingState = false }
    }

    private fun nearestHeight(fraction: Float): Int = listOf(20,25,30).minByOrNull { abs(it/100f-fraction) } ?: 25
    private fun sizeLabel(v: Int) = when(v) {20->"Compact";30->"Large";else->"Standard"}
    private fun layoutLabel(v: SavedStampLayout) = when(v) { SavedStampLayout.CARD->"Card"; SavedStampLayout.STRIP->"Strip"; SavedStampLayout.FOOTER->"Footer" }

    private fun refreshPreview(config: com.axiominfratech.geostamp.overlay.StampConfig, theme: StampTheme) {
        val light = theme == StampTheme.LIGHT
        val primary = if (light) Color.parseColor("#0B1830") else Color.WHITE
        val secondary = if (light) Color.parseColor("#64748B") else Color.parseColor("#CBD5E1")
        val divider = if (light) Color.parseColor("#CBD5E1") else Color.parseColor("#233651")
        val border = if (light) Color.parseColor("#CBD7E5") else Color.parseColor("#233651")
        val previewBg = Color.BLACK
        val density = resources.displayMetrics.density

        val frameHeightDp = when (config.savedStampLayout) {
            SavedStampLayout.CARD -> 250
            SavedStampLayout.STRIP -> 175
            SavedStampLayout.FOOTER -> 135
        }
        val stampHeightDp = when (config.savedStampLayout) {
            SavedStampLayout.CARD -> 180
            SavedStampLayout.STRIP -> 92
            SavedStampLayout.FOOTER -> 60
        }
        binding.previewFrame.layoutParams = binding.previewFrame.layoutParams.apply {
            height = (frameHeightDp * density).toInt()
        }
        binding.previewFrame.setBackgroundColor(previewBg)

        binding.overlayPreviewCard.layoutParams = binding.overlayPreviewCard.layoutParams.apply {
            height = (stampHeightDp * density).toInt()
        }
        binding.overlayPreviewCard.setPadding(
            (12 * density).toInt(),
            (10 * density).toInt(),
            (12 * density).toInt(),
            (10 * density).toInt()
        )

        val bg = GradientDrawable().apply {
            cornerRadius = 16f * density
            val a = (config.overlayAlpha * 255).toInt().coerceIn(50, 230)
            val surface = if (light) Color.WHITE else Color.rgb(8, 12, 18)
            setColor(Color.argb(a, Color.red(surface), Color.green(surface), Color.blue(surface)))
            setStroke((1f * density).toInt().coerceAtLeast(1), border)
        }
        binding.overlayPreviewCard.background = bg

        binding.previewStatus.setTextColor(Color.rgb(34, 197, 94))
        binding.previewCoords.setTextColor(primary)
        binding.previewTime.setTextColor(secondary)
        binding.previewSite.setTextColor(Color.parseColor("#8B5CF6"))
        binding.previewStatus.text = "✓ Location verified"
        binding.previewCoords.text = "24.8610° N, 67.0101° E"
        binding.previewTime.text = "02 May 2026, 14:30"
        binding.previewSite.text = "Site ID: PKZ-KHI-001"

        // Keep the preview visually faithful to the selected stamp theme.
        binding.overlayPreviewCard.contentDescription = "${theme.name} stamp preview; divider ${String.format("#%06X", 0xFFFFFF and divider)}"
    }

    private fun applyTokens(t: Tokens) {
        // These tokens are deliberately fixed to the existing dark application shell.
        // Stamp Theme is NOT an application appearance switch.
        binding.stampSettingsRoot.setBackgroundColor(Color.parseColor("#07101F"))
        binding.tvTitle.setTextColor(Color.parseColor("#F5F7FA"))
        binding.tvSubtitle.setTextColor(Color.parseColor("#A9B6C8"))
        binding.tvAutoSave.setTextColor(Color.parseColor("#A9B6C8"))

        val headers = listOf(binding.sectionSiteHeader,binding.sectionSavedHeader,binding.sectionInfoHeader,binding.sectionCameraHeader,binding.sectionProtectionHeader)
        val contents = listOf(binding.sectionSiteContent,binding.sectionSavedContent,binding.sectionInfoContent,binding.sectionCameraContent,binding.sectionProtectionContent)
        headers.forEach { applyCard(it,t.card,t.border); styleTree(it,t) }
        contents.forEach { applyCard(it,t.card,t.border); styleTree(it,t) }

        // Saved Photo Stamp is one connected rounded accordion.
        connectAccordion(binding.sectionSavedHeader, binding.sectionSavedContent, t.card, t.border)
    }

    private fun applySegmentTheme(theme: StampTheme) {
        val light = theme == StampTheme.LIGHT
        val selectedBg = Color.parseColor("#19A9DC")
        val selectedText = Color.parseColor("#07182B")
        val unselectedBg = Color.parseColor(if (light) "#F4F7FA" else "#0B1424")
        val unselectedText = Color.parseColor(if (light) "#0B1830" else "#A9B6C8")
        val border = Color.parseColor(if (light) "#CBD7E5" else "#233651")

        val groups = listOf(binding.groupLayout, binding.groupSize, binding.groupTheme, binding.groupLiveInfo)
        groups.forEach { group ->
            for (i in 0 until group.childCount) {
                val button = group.getChildAt(i) as? RadioButton ?: continue
                val radius = 5f * resources.displayMetrics.density
                val checked = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(selectedBg)
                    setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), selectedBg)
                }
                val normal = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(unselectedBg)
                    setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), border)
                }
                button.background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), checked)
                    addState(intArrayOf(), normal)
                }
                button.setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(selectedText, unselectedText)
                ))
                button.buttonTintList = null
            }
        }
    }

    private fun connectAccordion(header: View, content: View, fill: Int, stroke: Int) {
        val density = resources.displayMetrics.density
        val r = 16f * density
        val zero = 0f
        val topCorners = floatArrayOf(r,r,r,r,zero,zero,zero,zero)
        val bottomCorners = floatArrayOf(zero,zero,zero,zero,r,r,r,r)
        header.background = GradientDrawable().apply {
            cornerRadii = topCorners
            setColor(fill)
            setStroke((1f * density).toInt().coerceAtLeast(1), stroke)
        }
        content.background = GradientDrawable().apply {
            cornerRadii = bottomCorners
            setColor(fill)
            setStroke((1f * density).toInt().coerceAtLeast(1), stroke)
        }
        header.layoutParams = header.layoutParams.apply {
            if (this is ViewGroup.MarginLayoutParams) {
                topMargin = 8.dpToPx()
                bottomMargin = 0
            }
        }
        content.layoutParams = content.layoutParams.apply {
            if (this is ViewGroup.MarginLayoutParams) {
                topMargin = 0
                bottomMargin = 0
            }
        }
    }

    private fun applyCard(view: View, fill: Int, stroke: Int) {
        view.background = GradientDrawable().apply {
            cornerRadius=16f*resources.displayMetrics.density
            setColor(fill)
            setStroke((1f*resources.displayMetrics.density).toInt().coerceAtLeast(1),stroke)
        }
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

    private fun Int.dpToPx() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
