package com.axiominfratech.geostamp.admin

import com.axiominfratech.geostamp.core.LocationConfig
import com.axiominfratech.geostamp.core.OrganizationConfig
import com.axiominfratech.geostamp.core.ProjectConfig

/**
 * Zero-cost admin distribution format. Axiom can generate this JSON locally and
 * publish it through GitHub Pages/Releases. The Android app will later verify a
 * signature before accepting an update.
 */
data class AdminConfigBundle(
    val schemaVersion: Int = 1,
    val generatedAtEpochMs: Long,
    val organizations: List<OrganizationConfig>,
    val projects: List<ProjectConfig>,
    val locations: List<LocationConfig>,
    val signatureBase64: String = ""
)
