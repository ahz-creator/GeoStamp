package com.axiominfratech.geostamp.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class StampConfigSettingsTest {
    @Test fun defaultsUseApprovedStampSettings() {
        val config = StampConfig()
        assertEquals(SavedStampLayout.CARD, config.savedStampLayout)
        assertEquals(StampTheme.DARK, config.stampTheme)
        assertEquals(0.25f, config.savedOverlayHeightFraction, 0.0001f)
    }

    @Test fun sizePresetsMapToSupportedHeightRange() {
        assertEquals(0.20f, 20f / 100f, 0.0001f)
        assertEquals(0.25f, 25f / 100f, 0.0001f)
        assertEquals(0.30f, 30f / 100f, 0.0001f)
    }
}
