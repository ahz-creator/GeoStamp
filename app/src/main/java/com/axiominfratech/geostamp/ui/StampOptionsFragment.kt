package com.axiominfratech.geostamp.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.axiominfratech.geostamp.R
import com.axiominfratech.geostamp.database.Operator
import com.axiominfratech.geostamp.databinding.FragmentStampOptionsBinding
import com.axiominfratech.geostamp.overlay.OverlayColorScheme
import com.axiominfratech.geostamp.overlay.LiveInfoMode
import kotlinx.coroutines.launch

class StampOptionsFragment : Fragment() {

    private var _binding: FragmentStampOptionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStampOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        setupOperatorSpinner(emptyList())

        // Site radius is controlled remotely by the administrator.
        binding.sliderRadius.isEnabled = false
        binding.sliderRadius.alpha = 0.45f

        // ── Overlay alpha slider ───────────────────────────────────────
        binding.sliderOverlayAlpha.addOnChangeListener { _, value, _ ->
            val alpha = value / 100f
            viewModel.updateOverlayAlpha(alpha)
            binding.tvAlphaValue.text = "${value.toInt()}%"
            refreshPreviewCard()
        }

        // ── Overlay scale slider ───────────────────────────────────────
        binding.sliderOverlayScale.addOnChangeListener { _, value, _ ->
            viewModel.updateSavedOverlayHeight(value)
            binding.tvScaleValue.text = "${value.toInt()}%"
        }

        // ── Security toggle ────────────────────────────────────────────
        binding.switchBlockSpoof.setOnCheckedChangeListener { _, checked ->
            viewModel.updateStampConfig(viewModel.stampConfig.value.copy(blockIfSpoofDetected = checked))
        }


        // ══════════════════════════════════════════════════════════════
        //  OVERLAY VISIBILITY SWITCHES
        // ══════════════════════════════════════════════════════════════

        binding.radioLiveFloating.setOnClickListener { viewModel.updateLiveInfoMode(LiveInfoMode.FLOATING) }
        binding.radioLiveBottom.setOnClickListener { viewModel.updateLiveInfoMode(LiveInfoMode.BOTTOM) }
        binding.radioLiveOff.setOnClickListener { viewModel.updateLiveInfoMode(LiveInfoMode.OFF) }

        // ══════════════════════════════════════════════════════════════
        //  COLOR SCHEME SWATCHES
        // ══════════════════════════════════════════════════════════════

        binding.containerSwatchBlack.setOnClickListener  { selectScheme(OverlayColorScheme.BLACK) }
        binding.containerSwatchNavy.setOnClickListener   { selectScheme(OverlayColorScheme.NAVY) }
        binding.containerSwatchForest.setOnClickListener { selectScheme(OverlayColorScheme.FOREST) }
        binding.containerSwatchMaroon.setOnClickListener { selectScheme(OverlayColorScheme.MAROON) }

        // The entire FrameLayout acts as the Custom swatch tap target
        binding.btnCustomColor.setOnClickListener { selectScheme(OverlayColorScheme.CUSTOM) }

        // ── Custom color hex input ─────────────────────────────────────
        binding.btnApplyCustomColor.setOnClickListener {
            val hex = binding.etCustomColorHex.text.toString().trim()
            applyCustomHexColor(hex)
        }
        binding.etCustomColorHex.setOnEditorActionListener { tv, _, _ ->
            applyCustomHexColor(tv.text.toString().trim())
            tv.clearFocus(); false
        }

        // ══════════════════════════════════════════════════════════════
        //  OBSERVE CONFIG — keep all UI in sync
        // ══════════════════════════════════════════════════════════════

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stampConfig.collect { config ->

                // Radius
                val adminRadius = viewModel.remoteAppConfig.value.policy.siteDetectionRadiusM.toFloat().coerceIn(0f, 1000f)
                binding.sliderRadius.value = adminRadius
                binding.tvRadiusValue.text = "Admin: ${adminRadius.toInt()} m"


                // Security
                binding.switchBlockSpoof.isChecked = config.blockIfSpoofDetected

                // Alpha
                val alphaPercent = (config.overlayAlpha * 100).toInt().toFloat().coerceIn(20f, 90f)
                if (binding.sliderOverlayAlpha.value != alphaPercent)
                    binding.sliderOverlayAlpha.value = alphaPercent
                binding.tvAlphaValue.text = "${alphaPercent.toInt()}%"

                // Saved-photo overlay height (20–30%)
                val heightPercent = (config.savedOverlayHeightFraction * 100f).coerceIn(20f, 30f)
                if (binding.sliderOverlayScale.value != heightPercent)
                    binding.sliderOverlayScale.value = heightPercent
                binding.tvScaleValue.text = "${heightPercent.toInt()}%"

                // Exactly one live camera information mode
                binding.radioLiveFloating.isChecked = config.liveInfoMode == LiveInfoMode.FLOATING
                binding.radioLiveBottom.isChecked = config.liveInfoMode == LiveInfoMode.BOTTOM
                binding.radioLiveOff.isChecked = config.liveInfoMode == LiveInfoMode.OFF

                // Color scheme — update check marks + custom row
                updateSchemeCheckmarks(config.colorScheme)
                binding.rowCustomColor.visibility =
                    if (config.colorScheme == OverlayColorScheme.CUSTOM) View.VISIBLE else View.GONE

                // Update custom swatch preview if RGB is stored
                if (config.colorScheme == OverlayColorScheme.CUSTOM && config.customColorRgb != 0) {
                    applySwatchColor(binding.ivCustomColorPreview, config.customColorRgb)
                }

                // Live preview card
                refreshPreviewCard()
            }
        }
    }

    // ── Scheme selection ───────────────────────────────────────────────────────

    private fun selectScheme(scheme: OverlayColorScheme) {
        viewModel.updateColorScheme(scheme)
        // If switching away from custom, hide row immediately
        if (scheme != OverlayColorScheme.CUSTOM) {
            binding.rowCustomColor.visibility = View.GONE
        } else {
            binding.rowCustomColor.visibility = View.VISIBLE
        }
    }

    private fun applyCustomHexColor(hex: String) {
        // Accept with or without leading #
        val clean = if (hex.startsWith("#")) hex else "#$hex"
        try {
            val parsed = Color.parseColor(clean)
            val rgb = parsed and 0x00FFFFFF  // strip alpha — we control it
            viewModel.updateCustomColor(rgb)
            applySwatchColor(binding.ivCustomColorPreview, rgb)
            refreshPreviewCard()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Invalid color — use #RRGGBB format", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySwatchColor(view: View, rgb: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(rgb or 0xFF000000.toInt())   // solid for the preview dot
            setStroke((1.5f * resources.displayMetrics.density).toInt(),
                Color.argb(100, 255, 255, 255))
        }
    }

    // ── Check mark management ──────────────────────────────────────────────────

    private fun updateSchemeCheckmarks(active: OverlayColorScheme) {
        binding.ivCheckBlack.visibility  = if (active == OverlayColorScheme.BLACK)   View.VISIBLE else View.GONE
        binding.ivCheckNavy.visibility   = if (active == OverlayColorScheme.NAVY)    View.VISIBLE else View.GONE
        binding.ivCheckForest.visibility = if (active == OverlayColorScheme.FOREST)  View.VISIBLE else View.GONE
        binding.ivCheckMaroon.visibility = if (active == OverlayColorScheme.MAROON)  View.VISIBLE else View.GONE
        binding.ivCheckCustom.visibility = if (active == OverlayColorScheme.CUSTOM)  View.VISIBLE else View.GONE
    }

    // ── Live preview card ──────────────────────────────────────────────────────

    private fun refreshPreviewCard() {
        val config  = viewModel.stampConfig.value
        val alpha   = config.overlayAlpha.coerceIn(0.2f, 0.9f)
        val alphaInt = (alpha * 255).toInt()

        val baseRgb: Int = when (config.colorScheme) {
            OverlayColorScheme.BLACK  -> 0x000000
            OverlayColorScheme.NAVY  -> 0x1A237E
            OverlayColorScheme.FOREST -> 0x1B5E20
            OverlayColorScheme.MAROON -> 0x621B2F
            OverlayColorScheme.CUSTOM -> config.customColorRgb.takeIf { it != 0 } ?: 0x000000
        }

        val r = (baseRgb shr 16) and 0xFF
        val g = (baseRgb shr 8)  and 0xFF
        val b =  baseRgb         and 0xFF

        val bgColor     = Color.argb(alphaInt, r, g, b)
        val cornerPx    = 20f * resources.displayMetrics.density
        val strokeColor = Color.argb(51, 255, 255, 255)
        val strokePx    = (1f * resources.displayMetrics.density).toInt()

        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = cornerPx
            setStroke(strokePx, strokeColor)
        }
        binding.overlayPreviewCard.background = drawable
        // Also update the alpha-section preview card (renamed to avoid duplicate ID)
        binding.overlayPreviewCardAlpha.background = drawable.constantState?.newDrawable()?.mutate()
    }

    // ── Operator spinner ───────────────────────────────────────────────────────

    private fun setupOperatorSpinner(datasetOps: List<String>) {
        // Organization/operator selection will come from the verified sign-in profile.
        // Until sign-in is connected, ALL keeps automatic GPS site matching available
        // without allowing field users to rename or manually switch organizations.
        val current = viewModel.stampConfig.value.selectedOperator
        val assignedLabel = if (current == Operator.ALL) "Auto from site data" else current.displayName
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf(assignedLabel))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spOperator.adapter = adapter
        binding.spOperator.isEnabled = false
        binding.spOperator.alpha = 0.72f
    }

    override fun onStart() {
        super.onStart()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.datasetOperators.collect { ops -> setupOperatorSpinner(ops) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
