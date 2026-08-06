package com.axiominfratech.geostamp.core

/**
 * Industry-neutral workspace model. Telecom is only one template; personal and
 * other business uses do not require an operator or tower site.
 */
enum class WorkspaceType { PERSONAL, ORGANIZATION }

enum class IndustryTemplate {
    PERSONAL, TELECOM, CONSTRUCTION, SOLAR, SECURITY, LOGISTICS,
    INSURANCE, REAL_ESTATE, UTILITIES, CUSTOM
}

data class Workspace(
    val id: String,
    val type: WorkspaceType,
    val displayName: String,
    val industry: IndustryTemplate,
    val organizationId: String? = null,
    val logoUrl: String? = null,
    val primaryEntityLabel: String = "Organization",
    val secondaryEntityLabel: String = "Location",
    val enabled: Boolean = true
)

data class OrganizationConfig(
    val id: String,
    val name: String,
    val code: String,
    val industry: IndustryTemplate,
    val logoUrl: String? = null,
    val primaryColor: String? = null,
    val projectLabel: String = "Project",
    val locationLabel: String = "Location",
    val updatedAtEpochMs: Long = 0L
)

data class ProjectConfig(
    val id: String,
    val organizationId: String,
    val name: String,
    val reference: String = "",
    val active: Boolean = true
)

data class LocationConfig(
    val id: String,
    val projectId: String,
    val code: String,
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String = "",
    val region: String = "",
    val metadata: Map<String, String> = emptyMap()
)
