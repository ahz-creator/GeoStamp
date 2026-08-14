package com.axiominfratech.geostamp.overlay

import com.axiominfratech.geostamp.database.Operator

enum class LiveInfoMode { FLOATING, BOTTOM, OFF }
enum class SavedStampLayout { CARD, STRIP, FOOTER }
enum class StampTheme { DARK, LIGHT }

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
    val savedOverlayHeightFraction: Float = 0.25f,
    val savedStampLayout: SavedStampLayout = SavedStampLayout.CARD,
    val stampTheme: StampTheme = StampTheme.DARK,
    val liveInfoMode: LiveInfoMode = LiveInfoMode.FLOATING,
    @Deprecated("Use liveInfoMode")
    val showInfoCard: Boolean = true,
    @Deprecated("Use liveInfoMode")
    val showInfoStrip: Boolean = false,
    val colorScheme: OverlayColorScheme = OverlayColorScheme.BLACK,
    val customColorRgb: Int = 0x000000
)
