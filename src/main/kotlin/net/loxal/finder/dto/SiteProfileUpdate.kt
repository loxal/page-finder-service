// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.*

/**
 * DTO for updating a site profile.
 *
 * Uses @JsonIgnoreProperties to handle extra fields (like 'id') that may be sent
 * when clients use SiteProfile objects for updates.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SiteProfileUpdate(
        var secret: UUID = UUID.fromString("0-0-0-3-0"),
        var email: String = "",
        var configs: List<SiteProfile.Config> = emptyList()
)
