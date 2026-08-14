package com.axiominfratech.geostamp.admin

/** Enterprise-ready role model used by future server/admin integration. */
enum class GeoStampRole { FIELD_OPERATOR, SUPERVISOR, VERIFIER, ADMIN }

data class AccessProfile(
    val subjectId: String,
    val organizationId: String,
    val roles: Set<GeoStampRole>,
    val siteIds: Set<String> = emptySet(),
    val active: Boolean = true
) {
    fun canCapture(): Boolean = active && roles.any { it == GeoStampRole.FIELD_OPERATOR || it == GeoStampRole.SUPERVISOR || it == GeoStampRole.ADMIN }
    fun canVerify(): Boolean = active && roles.any { it == GeoStampRole.VERIFIER || it == GeoStampRole.SUPERVISOR || it == GeoStampRole.ADMIN }
    fun canAdminister(): Boolean = active && GeoStampRole.ADMIN in roles
    fun canAccessSite(siteId: String): Boolean = canAdminister() || siteIds.isEmpty() || siteId in siteIds
}
