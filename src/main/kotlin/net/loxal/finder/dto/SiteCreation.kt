// Copyright 2026 Alexander Orlov <alexander.orlov@loxal.net>

package net.loxal.finder.dto

import java.util.*

data class SiteCreation(
        val siteId: UUID, // siteId used to be null-safe
        val siteSecret: UUID // siteSecret used to be null-safe
)
