package com.axiominfratech.geostamp.overlay

import com.axiominfratech.geostamp.database.Operator

/**
 * Overlay color scheme.
 * BLACK   = #66000000 (default glass dark)
 * NAVY    = #661A237E (deep indigo)
 * FOREST  = #661B5E20 (dark green)
 * MAROON  = #66621B2F (dark red)
 * CUSTOM  = user-picked ARGB via color picker
 */
enum class LiveInfoMode { FLOATING, BOTTOM, OFF }

enum class OverlayColorScheme(val label: String, val baseArgb: Int) {
    BLACK  ("Black Glass",   0x66000000.toInt()),
    NAVY   ("Navy Blue",     0x661A237E.toInt()),
    FOREST ("Forest Green",  0x661B5E20.toInt()),
    MAROON ("Dark Maroon",   0x66621B2F.toInt()),
    CUSTOM ("Custom…",       0x66000000.toInt())
}

data class StampConfig(
    val username: String = "",
    val selectedOperator: Operator = Operator.ALL,
    val matchRadiusM: Double = 5.0,
    val blockIfSpoofDetected: Boolean = false,
    val overlayAlpha: Float = 0.6f,
    val overlayScale: Float = 1.0f,
    /** Saved-photo overlay height as a fraction of the image; hard-limited to 20–30%. */
    val savedOverlayHeightFraction: Float = 0.25f,
    /** Exactly one live camera information mode may be active. */
    val liveInfoMode: LiveInfoMode = LiveInfoMode.FLOATING,

    // ── New in v4 ──────────────────────────────────────────────
    /** Show/hide the floating main info card */
    @Deprecated("Use liveInfoMode")
    val showInfoCard: Boolean = true,
    /** Show/hide the bottom info strip */
    @Deprecated("Use liveInfoMode")
    val showInfoStrip: Boolean = false,
    /** Active color scheme for both overlays */
    val colorScheme: OverlayColorScheme = OverlayColorScheme.BLACK,
    /** Custom color RGB (0xRRGGBB) used when colorScheme == CUSTOM */
    val customColorRgb: Int = 0x000000
)
